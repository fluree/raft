(ns jepsen-raft.raft-node
  "Raft node implementation using net.async TCP for inter-node communication.
   
   This implementation follows Fluree Server's pattern where:
   - TCP tcp-connections are used for Raft RPC between nodes
   - HTTP interface is used for client commands
   - Lower-sorted node names connect to higher-sorted ones
   - Connections automatically reconnect on failure"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! chan]]
            [clojure.string :as str]
            [net.async.tcp :as tcp]
            [taoensso.nippy :as nippy]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as response]
            [clj-http.client]
            [clojure.data.json :as json]))

;; =============================================================================
;; State Management
;; =============================================================================

(defonce ^:private tcp-connections (atom {}))
(defonce ^:private tcp-client-event-loops (atom {}))
(defonce ^:private rpc-pending-responses (atom {}))
(defonce ^:private tcp-connection-params (atom {}))

;; Configuration constants
(def ^:private rpc-timeout-ms 5000)
(def ^:private reconnect-delay-ms 5000)
(def ^:private reconnect-retry-delay-ms 10000)
(def ^:private tcp-buffer-size 10)
(def ^:private connection-retry-base-ms 1000)
(def ^:private max-connection-attempts 5)
(def ^:private connection-setup-delay-ms 1000)
(def ^:private connection-spacing-ms 100)
(def ^:private http-socket-timeout-ms 10000)
(def ^:private http-connection-timeout-ms 1000)
(def ^:private raft-state-timeout-ms 1000)

;; =============================================================================
;; Serialization
;; =============================================================================

(defn- serialize-message
  "Serialize a message header and data using Nippy."
  [header data]
  (nippy/freeze [header data]))

(defn- deserialize-message
  "Deserialize a message, returning [header data] or nil."
  [msg]
  (when (bytes? msg)
    (try
      (nippy/thaw msg)
      (catch Exception e
        (log/error e "Failed to deserialize message")
        nil))))

;; =============================================================================
;; Connection Management
;; =============================================================================

;; Forward declaration to resolve circular dependency
(declare connect-to-server)

(defn- get-or-create-event-loop
  "Get existing event loop for server or create a new one. 
   Uses a single event loop per server for all outbound tcp-connections."
  [server-id]
  (locking tcp-client-event-loops
    (if-let [existing-loop (get @tcp-client-event-loops server-id)]
      (do
        (log/debug server-id "Reusing existing event loop")
        existing-loop)
      (let [new-loop (tcp/event-loop)]
        (log/debug server-id "Creating new event loop")
        (swap! tcp-client-event-loops assoc server-id new-loop)
        new-loop))))

(defn- get-write-channel-for-node
  "Get the write channel for sending messages to a specific node.
   Returns nil if no connection exists or if the channel is closed."
  [from-server to-server]
  (get-in @tcp-connections [from-server :conn-to to-server :write-chan]))

(defn- store-connection
  "Store a connection in the tcp-connections map, closing any existing connection."
  [server-id remote-id connection-data]
  (swap! tcp-connections update-in [server-id :conn-to remote-id]
         (fn [existing-conn]
           (when existing-conn
             (log/debug server-id "Closing existing connection to" remote-id "before storing new one")
             (when-let [write-chan (:write-chan existing-conn)]
               (async/close! write-chan)))
           connection-data)))

(defn- remove-connection
  "Remove a connection from the tcp-connections map."
  [server-id remote-id]
  (log/debug server-id "Removing connection to" remote-id)
  (swap! tcp-connections update-in [server-id :conn-to] dissoc remote-id))

(defn- get-connection
  "Get connection from global state."
  [server-id remote-id]
  (get-in @tcp-connections [server-id :conn-to remote-id]))

(defn- update-connection-status
  "Update the status of a connection."
  [server-id remote-id status]
  (swap! tcp-connections assoc-in [server-id :conn-to remote-id :status] status))

;; =============================================================================
;; Message Handling
;; =============================================================================

(defn- send-message-to-channel
  "Send a serialized message through a write channel."
  [write-chan header data]
  (try
    (if (nil? write-chan)
      false
      (let [message (serialize-message header data)
            success (async/put! write-chan message)]
        ;; async/put! returns true if successful, false if channel is closed
        (boolean success)))
    (catch Exception e
      (log/debug "Failed to send message to channel:" (.getMessage e))
      false)))

(defn- send-rpc-to-node
  "Send RPC message to a remote node."
  [from-server to-server header data]
  (let [write-chan (get-write-channel-for-node from-server to-server)]
    (if write-chan
      (do
        (log/debug from-server "→" to-server "RPC:" (:op header)
                   (when (= :request-vote (:op header))
                     (str "term=" (:term data))))
        (let [send-success (send-message-to-channel write-chan header data)]
          (if send-success
            true
            (do
              (log/debug from-server "Failed to send RPC to" to-server "- cleaning up stale connection")
              (remove-connection from-server to-server)
              false))))
      (do
        (log/debug from-server "No connection to" to-server "for RPC:" (:op header)
                   "- available tcp-connections:" (keys (get-in @tcp-connections [from-server :conn-to])))
        false))))

(defn- invoke-raft-rpc
  "Process incoming Raft RPC and return response."
  [raft-instance {:keys [op] :as header} data]
  (let [result-promise (promise)
        event-chan (raft/event-chan raft-instance)]

    (log/debug "Processing incoming RPC:" op "from" (:from header)
               (when (= :request-vote op)
                 (str "term=" (:term data))))

    ;; Invoke RPC asynchronously
    (raft/invoke-rpc* event-chan op data
                      (fn [result]
                        (log/debug "RPC response for" op ":" result)
                        (deliver result-promise result)))

    ;; Wait for result with timeout
    (deref result-promise rpc-timeout-ms {:error :timeout})))

(defn- build-response-header
  "Build a response header from a request header."
  [request-header server-id]
  (-> request-header
      (assoc :type :raft-rpc-response)
      (assoc :from server-id)
      (assoc :to (:from request-header))))

(defn- deliver-rpc-response
  "Deliver RPC response to waiting callback."
  [msg-id response-data]
  (when-let [callback (get @rpc-pending-responses msg-id)]
    (swap! rpc-pending-responses dissoc msg-id)
    (callback response-data)))

(defn- process-hello-message
  "Process incoming hello message."
  [server-id from-node]
  (log/info server-id "← Connected from" from-node))

(defn- process-raft-rpc-request
  "Process incoming Raft RPC request and send response."
  [server-id raft-instance conn header data]
  (let [response (invoke-raft-rpc raft-instance header data)
        response-header (build-response-header header server-id)]
    (send-message-to-channel (:write-chan conn) response-header response)))

(defn- process-raft-rpc-response
  "Process incoming Raft RPC response."
  [header data]
  (deliver-rpc-response (:msg-id header) data))

(defn- process-message
  "Process an incoming message based on its type."
  [server-id raft-instance conn [header data :as msg]]
  (when msg
    (cond
      (= :hello header)
      (process-hello-message server-id (:server-id data))

      (map? header)
      (case (:type header)
        :raft-rpc         (process-raft-rpc-request server-id raft-instance conn header data)
        :raft-rpc-response (process-raft-rpc-response header data)
        (log/warn server-id "Unknown message type:" (:type header)))

      :else
      (log/warn server-id "Unknown message format:" header))))

;; =============================================================================
;; Connection Monitoring
;; =============================================================================

(defn- handle-connection-established
  "Handle newly established connection."
  [server-id remote-id conn-type write-chan]
  (log/info server-id (if (= :outbound conn-type) "→ Connected to" "← Accepted from") remote-id)
  (log/debug server-id "Connection details - type:" conn-type "write-chan open:" (not (nil? write-chan)))
  (update-connection-status server-id remote-id :connected)
  ;; Send hello on outbound tcp-connections (matching Fluree Server protocol)
  (when (= :outbound conn-type)
    (let [conn-id (rand-int Integer/MAX_VALUE)]
      (log/debug server-id "Sending HELLO to" remote-id "with id" conn-id)
      (send-message-to-channel write-chan
                               :hello
                               {:server-id server-id :id conn-id}))))

(defn- handle-connection-closed
  "Handle closed connection."
  [server-id remote-id reason]
  ;; Only process disconnection if there was actually a connection
  (when-let [conn (get-connection server-id remote-id)]
    (log/info server-id "× Disconnected from" remote-id "reason:" reason)
    (log/debug server-id "Connection state before removal:" (get-in @tcp-connections [server-id :conn-to remote-id]))
    (remove-connection server-id remote-id)
    ;; Attempt to reconnect if this was an outbound connection and we have the parameters
    (when (and (= :outbound (:type conn))
               (get-in @tcp-connection-params [server-id remote-id]))
      (let [{:keys [host port raft-instance]} (get-in @tcp-connection-params [server-id remote-id])]
        (log/info server-id "Scheduling reconnection to" remote-id "in" (/ reconnect-delay-ms 1000) "s")
        (go
          (<! (async/timeout reconnect-delay-ms))
          (when-not (get-connection server-id remote-id)
            (connect-to-server server-id remote-id host port raft-instance)))))))

(defn- process-connection-message
  "Process a single message from connection."
  [server-id raft-instance conn msg]
  (cond
    ;; Connection status message
    (keyword? msg)
    (do
      (log/debug server-id "Received connection status:" msg "from" (:remote-id conn))
      (case msg
        :connected
        (handle-connection-established server-id
                                       (:remote-id conn)
                                       (:type conn)
                                       (:write-chan conn))

        (:disconnected :closed)
        (handle-connection-closed server-id (:remote-id conn) msg)

        ;; Log any unexpected status messages
        (log/warn server-id "Unexpected connection status:" msg "from" (:remote-id conn))))

    ;; Data message
    (bytes? msg)
    (when-let [parsed-msg (deserialize-message msg)]
      (process-message server-id raft-instance conn parsed-msg))

    ;; Log unexpected message types
    :else
    (log/warn server-id "Unexpected message type from" (:remote-id conn) ":" (type msg) msg)))

(defn- monitor-connection!
  "Monitor a connection for messages and handle distcp-connections."
  [server-id conn remote-id conn-type raft-instance]
  (let [{:keys [read-chan]} conn
        conn-with-id (assoc conn :remote-id remote-id :type conn-type)]
    (log/debug server-id "Starting connection monitor for" remote-id "type:" conn-type)
    (go-loop []
      (let [msg (<! read-chan)]
        (if (nil? msg)
          (do
            (log/debug server-id "Read channel closed for" remote-id "- connection ended")
            (handle-connection-closed server-id remote-id :channel-closed))
          (do
            (process-connection-message server-id raft-instance conn-with-id msg)
            (recur)))))))

;; =============================================================================
;; Server Setup
;; =============================================================================

(defn- wait-for-hello-message
  "Wait for hello message to identify remote node."
  [server-id raft-instance temp-conn]
  (let [{:keys [read-chan]} temp-conn]
    (go-loop []
      (when-let [msg (<! read-chan)]
        (cond
          (keyword? msg)
          (recur) ; Skip status messages

          (bytes? msg)
          (when-let [[header data] (deserialize-message msg)]
            (if (= :hello header)
              (let [{remote-server-id :server-id, conn-id :id} data]
                (log/info server-id "← Identified connection from" remote-server-id "with id" conn-id)
                (store-connection server-id remote-server-id
                                  (assoc temp-conn :remote-id remote-server-id :id conn-id))
                (monitor-connection! server-id temp-conn remote-server-id :inbound raft-instance))
              (recur))))))))

(defn- handle-incoming-tcp-connection
  "Handle a new incoming TCP connection."
  [server-id raft-instance {:keys [read-chan write-chan]}]
  (let [temp-conn {:write-chan write-chan
                   :read-chan  read-chan
                   :type       :inbound
                   :status     :pending}]
    (wait-for-hello-message server-id raft-instance temp-conn)))

(defn- create-tcp-acceptor
  "Create TCP acceptor configuration."
  [port buffer-size & {:keys [host] :or {host "localhost"}}]
  {:port port
   :host host
   :write-chan-fn #(chan (async/dropping-buffer buffer-size))})

(defn- accept-tcp-tcp-connections!
  "Accept incoming TCP tcp-connections."
  [server-id raft-instance accept-chan]
  (go-loop []
    (when-let [client (<! accept-chan)]
      (handle-incoming-tcp-connection server-id raft-instance client)
      (recur))))

(defn- start-tcp-server
  "Start TCP server to accept incoming Raft tcp-connections."
  [server-id port raft-instance & {:keys [host] :or {host "localhost"}}]
  (log/info server-id "Starting TCP server on" host ":" port)

  (let [event-loop (tcp/event-loop)
        buffer-size 10
        acceptor (tcp/accept event-loop (create-tcp-acceptor port buffer-size :host host))
        accept-chan (:accept-chan acceptor)]

    ;; Accept tcp-connections asynchronously
    (accept-tcp-tcp-connections! server-id raft-instance accept-chan)

    ;; Return shutdown function
    (fn []
      (log/info server-id "Shutting down TCP server")
      (async/close! accept-chan)
      (tcp/shutdown! event-loop))))

;; =============================================================================
;; Client Connections
;; =============================================================================

(defn- create-tcp-client-config
  "Create TCP client configuration."
  [host port buffer-size]
  {:host host
   :port port
   :write-chan (async/chan (async/dropping-buffer buffer-size))})

(defn- establish-tcp-connection
  "Establish TCP connection to remote server with retry logic."
  [server-id remote-id host port raft-instance]
  (go-loop [attempts 0]
    (if (< attempts max-connection-attempts)
      (let [event-loop (get-or-create-event-loop server-id)
            buffer-size tcp-buffer-size
            client-config (create-tcp-client-config host port buffer-size)
            retry? (atom false)
            success? (atom false)]
        (log/debug server-id "Attempting TCP connection to" remote-id "at" (str host ":" port) "attempt" (inc attempts))
        (try
          (let [client (tcp/connect event-loop client-config)]
            (log/debug server-id "tcp/connect returned:" (type client) "value:" client)
            (if client
              (let [write-chan (:write-chan client)
                    read-chan (:read-chan client)]
                (log/debug server-id "Client channels - write-chan:" write-chan "read-chan:" read-chan)
                (if (and write-chan read-chan)
                  (let [conn {:write-chan write-chan
                              :read-chan  read-chan
                              :type       :outbound
                              :status     :connecting
                              :remote-id  remote-id}]
                    (log/debug server-id "TCP client connected to" remote-id "channels:"
                               {:write-chan (not (nil? write-chan))
                                :read-chan (not (nil? read-chan))})
                    (store-connection server-id remote-id conn)
                    (monitor-connection! server-id conn remote-id :outbound raft-instance)
                    (reset! success? true))
                  (do
                    (log/warn server-id "Client missing channels - write:" write-chan "read:" read-chan)
                    (log/warn server-id "Client object keys:" (keys client))
                    (reset! retry? true))))
              (do
                (log/debug server-id "Connection attempt" (inc attempts) "failed to" remote-id "- will retry")
                (reset! retry? true))))
          (catch Exception e
            (log/error e server-id "Exception during TCP connection to" remote-id)
            (reset! retry? true)))

        (cond
          @success? nil ; Exit loop successfully
          @retry? (do
                    (<! (async/timeout (* connection-retry-base-ms (Math/pow 2 attempts))))
                    (recur (inc attempts)))
          :else nil))
      ;; If we exhausted all attempts, schedule a reconnection attempt later
      (when-not (get-connection server-id remote-id)
        (log/warn server-id "Failed to connect to" remote-id "after" max-connection-attempts "attempts. Will retry in" (/ reconnect-retry-delay-ms 1000) "s")
        (<! (async/timeout reconnect-retry-delay-ms))
        (establish-tcp-connection server-id remote-id host port raft-instance)))))

(defn- connect-to-server
  "Establish outbound connection to a remote server."
  [server-id remote-id host port raft-instance]
  (log/info server-id "→ Connecting to" remote-id "at" (str host ":" port))
  ;; Store connection parameters for potential reconnection
  (swap! tcp-connection-params assoc-in [server-id remote-id] {:host host :port port :raft-instance raft-instance})
  (establish-tcp-connection server-id remote-id host port raft-instance))

(defn- get-servers-to-connect
  "Get list of servers this node should connect to (higher sorted)."
  [server-id all-servers]
  (->> all-servers
       sort
       (filter #(pos? (compare % server-id)))))

(defn- setup-outbound-tcp-connections!
  "Setup outbound tcp-connections following the lower→higher connection rule."
  [server-id all-servers port-map raft-instance host-map]
  (let [servers-to-connect (get-servers-to-connect server-id all-servers)]
    (log/info server-id "Will connect to:" servers-to-connect)

    ;; Add a small delay to allow other nodes to start their TCP servers
    (go
      (<! (async/timeout connection-setup-delay-ms))
      ;; Connect to servers sequentially, not concurrently
      (doseq [remote-id servers-to-connect]
        (when-let [remote-port (get port-map remote-id)]
          (let [remote-host (get host-map remote-id remote-id)] ; Use hostname from host-map, fallback to node-id
            (log/debug server-id "Connecting to" remote-id "at" remote-host ":" remote-port)
            (connect-to-server server-id remote-id remote-host remote-port raft-instance)
            ;; Add small delay between tcp-connections
            (<! (async/timeout connection-spacing-ms))))))))

;; =============================================================================
;; Raft Integration
;; =============================================================================

(defn- store-rpc-callback
  "Store callback for RPC response handling."
  [msg-id callback]
  (when callback
    (swap! rpc-pending-responses assoc msg-id callback)))

(defn- remove-rpc-callback
  "Remove callback after handling response."
  [msg-id]
  (swap! rpc-pending-responses dissoc msg-id))

(defn- invoke-no-connection-callback
  "Invoke callback when there's no connection available."
  [op callback]
  (when callback
    ;; Return a timeout response that Raft expects
    (case op
      :request-vote (callback {:term 0 :vote-granted false})
      :append-entries (callback {:term 0 :success false})
      ;; Default timeout response
      (callback {:error :timeout}))))

(defn- parse-rpc-arguments
  "Parse RPC arguments handling both 3-arg and 5-arg conventions."
  [args]
  (case (count args)
    3 args  ; (target-node message callback)
    5 [(nth args 1)         ; target-node
       [(nth args 2) (nth args 3)]  ; [op data]
       (nth args 4)]        ; callback
    (throw (IllegalArgumentException.
            (str "Invalid RPC args count: " (count args))))))

(defn- extract-message-components
  "Extract operation and data from message."
  [message]
  (if (vector? message)
    message
    [message nil]))

(defn- build-rpc-header
  "Build RPC header for outgoing message."
  [server-id target-node op msg-id]
  {:type   :raft-rpc
   :op     op
   :from   server-id
   :to     target-node
   :msg-id msg-id})

(defn- handle-rpc-send-failure
  "Handle case when RPC cannot be sent."
  [msg-id op callback]
  (remove-rpc-callback msg-id)
  (invoke-no-connection-callback op callback)
  false)

(defn- create-rpc-sender
  "Create RPC sender function for Raft that uses our TCP tcp-connections."
  [server-id]
  (fn [& args]
    (let [[target-node message callback] (parse-rpc-arguments args)
          [op data] (extract-message-components message)
          msg-id (str (java.util.UUID/randomUUID))
          header (build-rpc-header server-id target-node op msg-id)]

      ;; Store callback for response handling
      (store-rpc-callback msg-id callback)

      ;; Send the RPC
      (if (send-rpc-to-node server-id target-node header data)
        true
        (handle-rpc-send-failure msg-id op callback)))))

;; =============================================================================
;; HTTP Interface
;; =============================================================================

(defn- build-raft-command
  "Build Raft command from HTTP request."
  [command]
  ;; Pass through the command as-is, it's already in the correct format
  ;; Just validate that it has an :f field (converted from :op)
  (if (:f command)
    command
    {:f :unknown :error "Missing operation field"}))

(defn- submit-command-to-raft
  "Submit command to Raft and wait for result."
  [raft-instance command]
  (let [result-promise (promise)
        submit-timestamp (System/currentTimeMillis)]
    (log/info "RAFT_SUBMIT:" (:f command) "key=" (:key command) "value=" (:value command) 
              "timestamp=" submit-timestamp)
    (raft/new-entry raft-instance command
                    (fn [result]
                      (let [callback-timestamp (System/currentTimeMillis)]
                        (log/info "RAFT_CALLBACK:" (:f command) "key=" (:key command) 
                                  "result=" (:type result) "value=" (:value result)
                                  "submit-to-callback-ms=" (- callback-timestamp submit-timestamp)
                                  "timestamp=" callback-timestamp)
                        (deliver result-promise result)))
                    rpc-timeout-ms)
    (let [final-result (deref result-promise 6000 {:type :info :error :timeout})
          final-timestamp (System/currentTimeMillis)]
      (log/info "RAFT_RESULT:" (:f command) "key=" (:key command) 
                "final-type=" (:type final-result) "final-value=" (:value final-result)
                "total-duration-ms=" (- final-timestamp submit-timestamp)
                "timestamp=" final-timestamp)
      final-result)))

(defn- handle-command
  "Process a command request from HTTP interface."
  [raft-instance command-params]
  (let [command (build-raft-command command-params)]
    (log/info "Processing command:" command)
    (let [result (submit-command-to-raft raft-instance command)]
      (log/debug "Command result:" result)
      result)))

(defn- get-current-raft-state
  "Get current Raft state synchronously."
  [raft-instance timeout-ms]
  (let [state-promise (promise)]
    (raft/get-raft-state raft-instance
                         (fn [state]
                           (deliver state-promise state)))
    (deref state-promise timeout-ms nil)))

(defn- parse-request-body
  "Parse request body based on Content-Type header."
  [request]
  (let [content-type (get-in request [:headers "content-type"])]
    (cond
      ;; Nippy binary format
      (= content-type "application/octet-stream")
      (nippy/thaw (-> request :body slurp .getBytes))

      ;; JSON format (default)
      :else
      (:body request))))

(defn- prepare-command-from-request
  "Prepare command from HTTP request body."
  [request]
  (let [body (parse-request-body request)
        op-keyword (keyword (:op body))]
    (-> body
        (update :key keyword)
        ;; Convert :op to :f for Raft state machine compatibility
        (assoc :f op-keyword)
        (dissoc :op))))

(defn- build-debug-response
  "Build debug response with node and Raft state."
  [server-id current-state state-atom]
  {:node-id server-id
   :leader (:leader current-state)
   :status (str (:status current-state))
   :term (:term current-state)
   :commit (:commit current-state)
   :index (:index current-state)
   :state-machine @state-atom})

(defn- forward-command-to-leader
  "Forward command to the leader node via HTTP."
  [leader-id command port-map host-map docker-mode? content-type]
  (if-let [leader-port (get port-map leader-id)]
    (try
      (let [leader-host (if docker-mode?
                          ;; In Docker mode, use container hostname for inter-container communication
                          leader-id
                          ;; In non-Docker mode, use the host-map value
                          (get host-map leader-id "localhost"))
            connection-timeout (if docker-mode? 5000 http-connection-timeout-ms)]
        (log/info "Forwarding command" (:f command) "to leader" leader-id
                  "at" leader-host ":" leader-port)
        (let [url (str "http://" leader-host ":" leader-port "/command")
            ;; Forward with same content-type as original request
              response (if (= content-type "application/octet-stream")
                       ;; Nippy format
                         (clj-http.client/post url
                                               {:body (nippy/freeze command)
                                                :headers {"Content-Type" "application/octet-stream"}
                                                :as :byte-array
                                                :socket-timeout http-socket-timeout-ms
                                                :connection-timeout connection-timeout
                                                :throw-exceptions false})
                       ;; JSON format
                         (clj-http.client/post url
                                               {:body (json/write-str command)
                                                :content-type :json
                                                :accept :json
                                                :as :json
                                                :socket-timeout http-socket-timeout-ms
                                                :connection-timeout connection-timeout
                                                :throw-exceptions false}))]
          (if (= 200 (:status response))
            (let [result (if (= content-type "application/octet-stream")
                           (nippy/thaw (:body response))
                           (:body response))]
              (log/debug "Leader forward successful:" result)
              result)
            (do
              (log/warn "Leader forward failed with status" (:status response))
              {:type :fail :error "Leader forward failed"}))))
      (catch Exception e
        (log/error e "Failed to forward command to leader")
        {:type :fail :error "Leader forward error"}))
    (do
      (log/error "Leader port not found for" leader-id "in port map" port-map)
      {:type :fail :error "Leader port not found"})))

(defn- serialize-response
  "Serialize response based on request content-type."
  [response content-type]
  (if (= content-type "application/octet-stream")
    {:status 200
     :headers {"Content-Type" "application/octet-stream"}
     :body (java.io.ByteArrayInputStream. (nippy/freeze response))}
    (response/response response)))

(defn- handle-command-request
  "Handle /command endpoint."
  [raft-instance request port-map host-map docker-mode?]
  (let [content-type (get-in request [:headers "content-type"])
        command (prepare-command-from-request request)
        current-state (get-current-raft-state raft-instance raft-state-timeout-ms)
        node-id (get current-state :server-id "unknown")]
    (log/info "COMMAND_REQUEST:" node-id "op=" (:f command) "key=" (:key command) 
              "status=" (:status current-state) "term=" (:term current-state) 
              "commit=" (:commit current-state) "index=" (:index current-state)
              "timestamp=" (System/currentTimeMillis))
    (cond
      ;; We are the leader - process command
      (= :leader (:status current-state))
      (do
        (log/info "LEADER_PROCESSING:" node-id "handling" (:f command) "for key" (:key command)
                  "at commit-index" (:commit current-state) "timestamp=" (System/currentTimeMillis))
        (serialize-response (handle-command raft-instance command) content-type))

      ;; We know who the leader is - forward to them
      (:leader current-state)
      (do
        (log/info "FORWARDING_TO_LEADER:" node-id "forwarding" (:f command) "for key" (:key command) 
                  "to leader" (:leader current-state) "timestamp=" (System/currentTimeMillis))
        (serialize-response (forward-command-to-leader (:leader current-state) command port-map host-map docker-mode? content-type) content-type))

      ;; No leader elected yet
      :else
      (do
        (log/info "NO_LEADER:" node-id "rejecting" (:f command) "for key" (:key command) 
                  "no leader elected" "timestamp=" (System/currentTimeMillis))
        (serialize-response {:type :fail :error "No leader elected"} content-type)))))

(defn- handle-debug-request
  "Handle /debug endpoint."
  [raft-instance server-id state-atom]
  (let [current-state (get-current-raft-state raft-instance raft-state-timeout-ms)]
    (response/response (build-debug-response server-id current-state state-atom))))

(defn- create-http-handler
  "Create Ring handler for HTTP interface."
  [raft-instance server-id state-atom port-map host-map docker-mode?]
  (fn [request]
    (try
      (case (:uri request)
        "/command" (handle-command-request raft-instance request port-map host-map docker-mode?)
        "/debug"   (handle-debug-request raft-instance server-id state-atom)
        "/health"  (let [current-state (get-current-raft-state raft-instance raft-state-timeout-ms)]
                     (response/response {:status "ok"
                                         :node-ready (not (nil? (:leader current-state)))}))
        (response/not-found "Not found"))

      (catch Exception e
        (log/error e "Error handling HTTP request")
        (response/response {:error (.getMessage e)} 500)))))

;; =============================================================================
;; Node Lifecycle
;; =============================================================================

(defn- create-wrapped-state-machine
  "Create state machine that converts :f to :op for compatibility."
  [base-state-machine]
  (fn [entry raft-state]
    (let [apply-timestamp (System/currentTimeMillis)
          node-id (get raft-state :server-id "unknown")
          commit-index (get raft-state :commit-index "unknown")
          applied-index (get raft-state :applied-index "unknown")]
      (cond
        ;; Handle nil or empty entries
        (or (nil? entry) (empty? entry))
        (do
          (log/info "STATE_APPLY:" node-id "empty entry at applied-index" applied-index 
                    "commit-index" commit-index "timestamp=" apply-timestamp)
          (util/ok-result))

        ;; Normal application operations with :f field
        (:f entry)
        (let [converted-entry (-> entry (assoc :op (:f entry)) (dissoc :f))
              result (base-state-machine converted-entry raft-state)]
          (log/info "STATE_APPLY:" node-id "op=" (:op converted-entry) "key=" (:key converted-entry) 
                    "value=" (:value converted-entry) "result-type=" (:type result) "result-value=" (:value result)
                    "applied-index" applied-index "commit-index" commit-index "timestamp=" apply-timestamp)
          result)

        ;; Entry without :f field - likely internal Raft operation
        :else
        (let [result (base-state-machine entry raft-state)]
          (log/info "STATE_APPLY:" node-id "raw entry (no :f field)" "applied-index" applied-index 
                    "commit-index" commit-index "timestamp=" apply-timestamp)
          result)))))

(defn- create-snapshot-config
  "Create snapshot configuration."
  [state-atom]
  {:snapshot-write (fn [_index callback]
                     (when callback (callback)))
   :snapshot-reify (fn [_] @state-atom)
   :snapshot-install (constantly nil)
   :snapshot-xfer (constantly nil)
   :snapshot-list-indexes (constantly [])})

(defn- build-raft-config
  "Build complete Raft configuration."
  [node-id nodes state-machine rpc-sender]
  (-> (util/default-raft-config
       node-id nodes
       :log-dir (str "/tmp/jepsen-raft-network/" node-id "/")
       :state-machine-fn state-machine
       :rpc-sender-fn rpc-sender)
      (merge (create-snapshot-config (atom {})))))

(defn- wrap-content-type-middleware
  "Middleware to handle different content types."
  [handler]
  (fn [request]
    (let [content-type (get-in request [:headers "content-type"])]
      (if (= content-type "application/octet-stream")
        ;; For binary requests, don't use JSON middleware
        (handler request)
        ;; For JSON requests, parse the body
        (let [body-string (slurp (:body request))
              parsed-body (when-not (empty? body-string)
                            (json/read-str body-string :key-fn keyword))]
          (handler (assoc request :body parsed-body)))))))

(defn- create-http-app
  "Create HTTP application with middleware."
  [handler]
  (-> handler
      wrap-content-type-middleware
      wrap-json-response))

(defn- start-http-server
  "Start HTTP server for client interface."
  [handler port & {:keys [host] :or {host "localhost"}}]
  (jetty/run-jetty handler
                   {:port port
                    :host host
                    :join? false}))

(defn- build-http-host-map
  "Build HTTP host map for client connections.
   In Docker mode: always use localhost (ports are forwarded)
   In non-Docker mode: use node names as hostnames"
  [node-ip-map nodes]
  (if node-ip-map
    ;; Docker mode: Use localhost for all HTTP client connections
    ;; because Docker forwards container ports to host
    (into {} (for [id nodes] [id "localhost"]))
    ;; Non-Docker mode: node names are hostnames
    (into {} (for [id nodes] [id id]))))

(defn- cleanup-node-resources!
  "Clean up all node resources during shutdown."
  [http-server tcp-shutdown raft-instance]
  (.stop http-server)
  (tcp-shutdown)
  (raft/close raft-instance)
  ;; Cleanup event loops
  (doseq [[_ event-loop] @tcp-client-event-loops]
    (tcp/shutdown! event-loop))
  ;; Reset state
  (reset! tcp-connections {})
  (reset! tcp-client-event-loops {})
  (reset! rpc-pending-responses {}))

(defn start-node
  "Start a Raft node with net.async TCP for inter-node communication."
  [node-id tcp-port http-port nodes tcp-port-map http-port-map & {:keys [tcp-host http-host tcp-host-map] :or {tcp-host "localhost" http-host "localhost" tcp-host-map {}}}]
  (log/info "Starting Raft node" node-id "TCP:" tcp-host ":" tcp-port "HTTP:" http-host ":" http-port)

  (let [;; State machine setup
        state-atom (atom {})
        base-state-machine (util/create-kv-state-machine state-atom)
        wrapped-state-machine (create-wrapped-state-machine base-state-machine)

        ;; RPC setup
        rpc-sender-fn (create-rpc-sender node-id)

        ;; Raft setup
        raft-config (build-raft-config node-id nodes wrapped-state-machine rpc-sender-fn)
        raft-instance (raft/start raft-config)

        ;; TCP server setup
        tcp-shutdown (start-tcp-server node-id tcp-port raft-instance :host tcp-host)

        ;; Outbound tcp-connections setup
        _ (setup-outbound-tcp-connections! node-id nodes tcp-port-map raft-instance tcp-host-map)

        ;; HTTP server setup
        http-host-map (build-http-host-map tcp-host-map nodes)
        docker-mode? (and (seq tcp-host-map) 
                          (some #(not= "localhost" %) (vals tcp-host-map)))
        http-handler (create-http-handler raft-instance node-id state-atom http-port-map http-host-map docker-mode?)
        http-app (create-http-app http-handler)
        http-server (start-http-server http-app http-port :host http-host)]

    (log/info "Raft node" node-id "started successfully")

    ;; Return shutdown function
    (fn []
      (log/info "Shutting down node" node-id)
      (cleanup-node-resources! http-server tcp-shutdown raft-instance))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn- parse-node-ips
  "Parse NODE_IPS environment variable to create port maps.
   Format: n1:host1:port1,n2:host2:port2,n3:host3:port3"
  [node-ips-str]
  (when node-ips-str
    (let [entries (str/split node-ips-str #",")
          parsed (for [entry entries
                       :let [[node-id host port-str] (str/split entry #":")]
                       :when (and node-id host port-str)]
                   (try
                     [node-id {:host host :port (Integer/parseInt port-str)}]
                     (catch NumberFormatException _
                       (throw (ex-info (str "Invalid port number in NODE_IPS: " port-str)
                                       {:node-id node-id :host host :port-str port-str})))))]
      (into {} parsed))))

(defn- build-tcp-port-map
  "Build TCP port map from node IP mapping or fallback to sequential pattern."
  [node-ip-map tcp-port node-id nodes]
  (if node-ip-map
    (into {} (for [[nid info] node-ip-map]
               [nid (:port info)]))
    (let [base-tcp-port (- tcp-port (Integer/parseInt (subs node-id 1)) -1)]
      (into {} (map-indexed (fn [idx id]
                              [id (+ base-tcp-port idx)])
                            nodes)))))

(defn- build-http-port-map
  "Build HTTP port map from node IP mapping or fallback to sequential pattern."
  [node-ip-map http-port node-id nodes]
  (if node-ip-map
    (let [sorted-nodes (sort nodes)
          base-port 7001]
      (into {} (map-indexed (fn [idx id]
                              [id (+ base-port idx)])
                            sorted-nodes)))
    (let [base-http-port (- http-port (Integer/parseInt (subs node-id 1)) -1)]
      (into {} (map-indexed (fn [idx id]
                              [id (+ base-http-port idx)])
                            nodes)))))

(defn- build-tcp-host-map
  "Build TCP host map from node IP mapping or fallback to localhost."
  [node-ip-map nodes]
  (if node-ip-map
    (into {} (for [[nid info] node-ip-map]
               [nid (:host info)]))
    (into {} (for [id nodes] [id "localhost"]))))

(defn- validate-required-args
  "Validate that required arguments are present."
  [node-id tcp-port-str http-port-str nodes-str]
  (when (or (not node-id) (not tcp-port-str) (not http-port-str) (not nodes-str))
    (println "Usage: node-id tcp-port http-port nodes")
    (println "Example: n1 9001 7001 n1,n2,n3")
    (println "")
    (println "Or set environment variables:")
    (println "  NODE_ID=n1")
    (println "  TCP_PORT=9001")
    (println "  HTTP_PORT=7001")
    (println "  NODES=n1,n2,n3")
    (println "  NODE_IPS=n1:host1:port1,n2:host2:port2,n3:host3:port3 (optional)")
    (System/exit 1)))

(defn- parse-command-line-args
  "Parse and validate command line arguments or environment variables."
  [args]
  (let [node-id (or (first args) (System/getenv "NODE_ID"))
        tcp-port-str (or (second args) (System/getenv "TCP_PORT"))
        http-port-str (or (nth args 2 nil) (System/getenv "HTTP_PORT"))
        nodes-str (or (nth args 3 nil) (System/getenv "NODES"))
        node-ips-str (System/getenv "NODE_IPS")
        tcp-host (or (System/getenv "TCP_HOST") "0.0.0.0")
        http-host (or (System/getenv "HTTP_HOST") "0.0.0.0")]

    (validate-required-args node-id tcp-port-str http-port-str nodes-str)

    (let [tcp-port (try
                     (Integer/parseInt tcp-port-str)
                     (catch NumberFormatException _
                       (throw (ex-info (str "Invalid TCP port: " tcp-port-str)
                                       {:port tcp-port-str}))))
          http-port (try
                      (Integer/parseInt http-port-str)
                      (catch NumberFormatException _
                        (throw (ex-info (str "Invalid HTTP port: " http-port-str)
                                        {:port http-port-str}))))
          nodes (str/split nodes-str #",")
          _ (when (empty? nodes)
              (throw (ex-info "No nodes specified" {:nodes-str nodes-str})))
          node-ip-map (parse-node-ips node-ips-str)]
      {:node-id node-id
       :tcp-port tcp-port
       :http-port http-port
       :tcp-host tcp-host
       :http-host http-host
       :nodes nodes
       :tcp-port-map (build-tcp-port-map node-ip-map tcp-port node-id nodes)
       :http-port-map (build-http-port-map node-ip-map http-port node-id nodes)
       :tcp-host-map (build-tcp-host-map node-ip-map nodes)})))

(defn- register-shutdown-hook
  "Register JVM shutdown hook."
  [shutdown-fn]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable shutdown-fn)))

(defn -main
  "Command line entry point.
   Usage: node-id tcp-port http-port nodes
   Example: n1 9001 7001 n1,n2,n3"
  [& args]
  (let [{:keys [node-id tcp-port http-port tcp-host http-host nodes tcp-port-map http-port-map tcp-host-map]} (parse-command-line-args args)
        shutdown-fn (start-node node-id tcp-port http-port nodes tcp-port-map http-port-map
                                :tcp-host tcp-host :http-host http-host :tcp-host-map tcp-host-map)]

    ;; Register shutdown hook
    (register-shutdown-hook shutdown-fn)

    ;; Keep main thread alive
    @(promise)))