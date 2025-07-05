(ns jepsen-raft.tests.netasync.raft-node
  "Raft node implementation using net.async TCP for inter-node communication.
   
   This implementation follows Fluree Server's pattern where:
   - TCP connections are used for Raft RPC between nodes
   - HTTP interface is used for client commands
   - Lower-sorted node names connect to higher-sorted ones
   - Connections automatically reconnect on failure"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! >! chan]]
            [clojure.string :as str]
            [net.async.tcp :as tcp]
            [taoensso.nippy :as nippy]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.util.response :as response]))

;; =============================================================================
;; State Management
;; =============================================================================

(defonce ^:private connections (atom {}))
(defonce ^:private client-event-loops (atom {}))
(defonce ^:private pending-responses (atom {}))

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

(defn- get-or-create-event-loop
  "Get existing event loop for server or create a new one."
  [server-id]
  (or (get @client-event-loops server-id)
      (let [new-loop (tcp/event-loop)]
        (swap! client-event-loops assoc server-id new-loop)
        new-loop)))

(defn- get-write-channel-for-node
  "Get the write channel for sending messages to a specific node."
  [from-server to-server]
  (get-in @connections [from-server :conn-to to-server :write-chan]))

(defn- store-connection
  "Store a connection in the connections map."
  [server-id remote-id connection-data]
  (swap! connections assoc-in [server-id :conn-to remote-id] connection-data))

(defn- remove-connection
  "Remove a connection from the connections map."
  [server-id remote-id]
  (swap! connections update-in [server-id :conn-to] dissoc remote-id))

(defn- update-connection-status
  "Update the status of a connection."
  [server-id remote-id status]
  (swap! connections assoc-in [server-id :conn-to remote-id :status] status))

;; =============================================================================
;; Message Handling
;; =============================================================================

(defn- send-message-to-channel
  "Send a serialized message through a write channel."
  [write-chan header data]
  (async/put! write-chan (serialize-message header data)))

(defn- send-rpc-to-node
  "Send RPC message to a remote node."
  [from-server to-server header data]
  (if-let [write-chan (get-write-channel-for-node from-server to-server)]
    (do
      (log/debug from-server "→" to-server "RPC:" (:op header))
      (send-message-to-channel write-chan header data)
      true)
    (do
      (log/warn from-server "No connection to" to-server)
      false)))

(defn- invoke-raft-rpc
  "Process incoming Raft RPC and return response."
  [raft-instance {:keys [op msg-id] :as header} data]
  (let [result-promise (promise)
        event-chan (raft/event-chan raft-instance)]
    
    (log/debug "Processing Raft RPC:" op)
    
    ;; Invoke RPC asynchronously
    (raft/invoke-rpc* event-chan op data
                      (fn [result]
                        (deliver result-promise result)))
    
    ;; Wait for result with timeout
    (deref result-promise 5000 {:error :timeout})))

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
  (when-let [callback (get @pending-responses msg-id)]
    (swap! pending-responses dissoc msg-id)
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
    (case (:type header)
      :hello            (process-hello-message server-id (:from header))
      :raft-rpc         (process-raft-rpc-request server-id raft-instance conn header data)
      :raft-rpc-response (process-raft-rpc-response header data)
      (log/warn server-id "Unknown message type:" (:type header)))))

;; =============================================================================
;; Connection Monitoring
;; =============================================================================

(defn- handle-connection-established
  "Handle newly established connection."
  [server-id remote-id conn-type write-chan]
  (log/info server-id (if (= :outbound conn-type) "→ Connected to" "← Accepted from") remote-id)
  (update-connection-status server-id remote-id :connected)
  ;; Send hello on outbound connections
  (when (= :outbound conn-type)
    (send-message-to-channel write-chan
                             {:type :hello :from server-id :to remote-id}
                             nil)))

(defn- handle-connection-closed
  "Handle closed connection."
  [server-id remote-id]
  (log/info server-id "× Disconnected from" remote-id)
  (remove-connection server-id remote-id))

(defn- process-connection-message
  "Process a single message from connection."
  [server-id raft-instance conn msg]
  (cond
    ;; Connection status message
    (keyword? msg)
    (case msg
      :connected
      (handle-connection-established server-id 
                                     (:remote-id conn)
                                     (:type conn)
                                     (:write-chan conn))
      
      (:disconnected :closed)
      (handle-connection-closed server-id (:remote-id conn)))
    
    ;; Data message
    (bytes? msg)
    (when-let [parsed-msg (deserialize-message msg)]
      (process-message server-id raft-instance conn parsed-msg))))

(defn- monitor-connection
  "Monitor a connection for messages and handle disconnections."
  [server-id conn remote-id conn-type raft-instance]
  (let [{:keys [read-chan]} conn
        conn-with-id (assoc conn :remote-id remote-id)]
    (go-loop []
      (when-let [msg (<! read-chan)]
        (process-connection-message server-id raft-instance conn-with-id msg)
        (recur)))))

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
          (when-let [[header _] (deserialize-message msg)]
            (if (= :hello (:type header))
              (let [remote-id (:from header)]
                (log/info server-id "← Identified connection from" remote-id)
                (store-connection server-id remote-id 
                                  (assoc temp-conn :remote-id remote-id))
                (monitor-connection server-id temp-conn remote-id :inbound raft-instance))
              (recur))))))))

(defn- handle-incoming-tcp-connection
  "Handle a new incoming TCP connection."
  [server-id raft-instance {:keys [read-chan write-chan] :as client}]
  (let [temp-conn {:write-chan write-chan
                   :read-chan  read-chan
                   :type       :inbound
                   :status     :pending}]
    (wait-for-hello-message server-id raft-instance temp-conn)))

(defn- create-tcp-acceptor
  "Create TCP acceptor configuration."
  [port buffer-size]
  {:port port
   :write-chan-fn #(chan (async/dropping-buffer buffer-size))})

(defn- accept-tcp-connections
  "Accept incoming TCP connections."
  [server-id raft-instance accept-chan]
  (go-loop []
    (when-let [client (<! accept-chan)]
      (handle-incoming-tcp-connection server-id raft-instance client)
      (recur))))

(defn- start-tcp-server
  "Start TCP server to accept incoming Raft connections."
  [server-id port raft-instance]
  (log/info server-id "Starting TCP server on port" port)
  
  (let [event-loop (tcp/event-loop)
        buffer-size 10
        acceptor (tcp/accept event-loop (create-tcp-acceptor port buffer-size))
        accept-chan (:accept-chan acceptor)]
    
    ;; Accept connections asynchronously
    (accept-tcp-connections server-id raft-instance accept-chan)
    
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
   :write-chan (chan (async/dropping-buffer buffer-size))})

(defn- establish-tcp-connection
  "Establish TCP connection to remote server."
  [server-id remote-id host port raft-instance]
  (go
    (let [event-loop (get-or-create-event-loop server-id)
          buffer-size 10
          client-config (create-tcp-client-config host port buffer-size)
          client (tcp/connect event-loop client-config)]
      
      (if client
        (let [conn {:write-chan (:write-chan client)
                    :read-chan  (:read-chan client)
                    :type       :outbound
                    :status     :connecting
                    :remote-id  remote-id}]
          (store-connection server-id remote-id conn)
          (monitor-connection server-id conn remote-id :outbound raft-instance))
        (log/error server-id "Failed to connect to" remote-id)))))

(defn- connect-to-server
  "Establish outbound connection to a remote server."
  [server-id remote-id host port raft-instance]
  (log/info server-id "→ Connecting to" remote-id "at" (str host ":" port))
  (establish-tcp-connection server-id remote-id host port raft-instance))

(defn- get-servers-to-connect
  "Get list of servers this node should connect to (higher sorted)."
  [server-id all-servers]
  (->> all-servers
       sort
       (filter #(pos? (compare % server-id)))))

(defn- setup-outbound-connections
  "Setup outbound connections following the lower→higher connection rule."
  [server-id all-servers port-map raft-instance]
  (let [servers-to-connect (get-servers-to-connect server-id all-servers)]
    (log/info server-id "Will connect to:" servers-to-connect)
    
    (doseq [remote-id servers-to-connect]
      (when-let [remote-port (get port-map remote-id)]
        (connect-to-server server-id remote-id "localhost" remote-port raft-instance)))))

;; =============================================================================
;; Raft Integration
;; =============================================================================

(defn- store-rpc-callback
  "Store callback for RPC response handling."
  [msg-id callback]
  (when callback
    (swap! pending-responses assoc msg-id callback)))

(defn- remove-rpc-callback
  "Remove callback after handling response."
  [msg-id]
  (swap! pending-responses dissoc msg-id))

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
  "Create RPC sender function for Raft that uses our TCP connections."
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
  [{:keys [op key value old new]}]
  (case op
    :write  {:f :write :key key :value value}
    :read   {:f :read :key key}
    :cas    {:f :cas :key key :old old :new new}
    :delete {:f :delete :key key}
    ;; Default
    {:f :unknown :error (str "Unknown operation: " op)}))

(defn- submit-command-to-raft
  "Submit command to Raft and wait for result."
  [raft-instance command]
  (let [result-promise (promise)]
    (raft/new-entry raft-instance command
                    (fn [result]
                      (deliver result-promise result))
                    5000)
    (deref result-promise 6000 {:type :info :error :timeout})))

(defn- handle-command
  "Process a command request from HTTP interface."
  [raft-instance command-params]
  (-> command-params
      build-raft-command
      (->> (submit-command-to-raft raft-instance))))

(defn- get-current-raft-state
  "Get current Raft state synchronously."
  [raft-instance timeout-ms]
  (let [state-promise (promise)]
    (raft/get-raft-state raft-instance
                         (fn [state]
                           (deliver state-promise state)))
    (deref state-promise timeout-ms nil)))

(defn- prepare-command-from-request
  "Prepare command from HTTP request body."
  [request]
  (update (:body request) :op keyword))

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

(defn- handle-command-request
  "Handle /command endpoint."
  [raft-instance request]
  (let [command (prepare-command-from-request request)
        current-state (get-current-raft-state raft-instance 1000)]
    (if (= :leader (:status current-state))
      (response/response (handle-command raft-instance command))
      (response/response {:type :fail :error "Not leader"}))))

(defn- handle-debug-request
  "Handle /debug endpoint."
  [raft-instance server-id state-atom]
  (let [current-state (get-current-raft-state raft-instance 1000)]
    (response/response (build-debug-response server-id current-state state-atom))))

(defn- create-http-handler
  "Create Ring handler for HTTP interface."
  [raft-instance server-id state-atom]
  (fn [request]
    (try
      (case (:uri request)
        "/command" (handle-command-request raft-instance request)
        "/debug"   (handle-debug-request raft-instance server-id state-atom)
        "/health"  (response/response {:status "ok"})
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
    (if (and entry (:f entry))
      (-> entry
          (assoc :op (:f entry))
          (dissoc :f)
          (base-state-machine raft-state))
      (util/ok-result))))

(defn- create-snapshot-config
  "Create snapshot configuration."
  [state-atom]
  {:snapshot-write (fn [index callback]
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
        :log-dir (str "/tmp/jepsen-raft-netasync/" node-id "/")
        :state-machine-fn state-machine
        :rpc-sender-fn rpc-sender)
      (merge (create-snapshot-config (atom {})))))

(defn- create-http-app
  "Create HTTP application with middleware."
  [handler]
  (-> handler
      (wrap-json-body {:keywords? true})
      wrap-json-response))

(defn- start-http-server
  "Start HTTP server for client interface."
  [handler port]
  (jetty/run-jetty handler
                   {:port port
                    :join? false}))

(defn- cleanup-node-resources
  "Clean up all node resources during shutdown."
  [http-server tcp-shutdown raft-instance]
  (.stop http-server)
  (tcp-shutdown)
  (raft/close raft-instance)
  ;; Cleanup event loops
  (doseq [[_ event-loop] @client-event-loops]
    (tcp/shutdown! event-loop))
  ;; Reset state
  (reset! connections {})
  (reset! client-event-loops {})
  (reset! pending-responses {}))

(defn start-node
  "Start a Raft node with net.async TCP for inter-node communication."
  [node-id tcp-port http-port nodes]
  (log/info "Starting Raft node" node-id)
  
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
        tcp-shutdown (start-tcp-server node-id tcp-port raft-instance)
        
        ;; Outbound connections setup
        port-map {"n1" 9001 "n2" 9002 "n3" 9003}
        _ (setup-outbound-connections node-id nodes port-map raft-instance)
        
        ;; HTTP server setup
        http-handler (create-http-handler raft-instance node-id state-atom)
        http-app (create-http-app http-handler)
        http-server (start-http-server http-app http-port)]
    
    (log/info "Raft node" node-id "started successfully")
    
    ;; Return shutdown function
    (fn []
      (log/info "Shutting down node" node-id)
      (cleanup-node-resources http-server tcp-shutdown raft-instance))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn- parse-command-line-args
  "Parse and validate command line arguments."
  [args]
  (when (< (count args) 4)
    (println "Usage: node-id tcp-port http-port nodes")
    (println "Example: n1 9001 7001 n1,n2,n3")
    (System/exit 1))
  
  (let [[node-id tcp-port-str http-port-str nodes-str] args]
    {:node-id node-id
     :tcp-port (Integer/parseInt tcp-port-str)
     :http-port (Integer/parseInt http-port-str)
     :nodes (str/split nodes-str #",")}))

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
  (let [{:keys [node-id tcp-port http-port nodes]} (parse-command-line-args args)
        shutdown-fn (start-node node-id tcp-port http-port nodes)]
    
    ;; Register shutdown hook
    (register-shutdown-hook shutdown-fn)
    
    ;; Keep main thread alive
    @(promise)))