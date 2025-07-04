(ns jepsen-raft.distributed-test-main
  "Main entry point for distributed Raft nodes running in Docker containers"
  (:require [clojure.tools.logging :refer [info debug error]]
            [clojure.core.async :as async :refer [go]]
            [clojure.data.json :as json]
            [clojure.string]
            [clj-http.client :as http]
            [ring.adapter.jetty :as jetty]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util]
            [taoensso.nippy :as nippy])
  (:gen-class))

;; Global state for this node
(defonce ^:private node-state (atom {}))

(defn create-http-rpc-sender
  "Creates an HTTP-based RPC sender for distributed nodes"
  [node-id cluster-members]
  (fn [& args]
    (let [[target-node message callback]
          (case (count args)
            3 args  ; Direct 3-arg call
            5 [(nth args 1)                     ; Extract from 5-arg call
               [(nth args 2) (nth args 3)]     ; Combine args 2&3 as message
               (nth args 4)]                   ; Callback is last
            (throw (IllegalArgumentException.
                    (str "Invalid RPC args count: " (count args)
                         ", expected 3 or 5"))))]
      (go
        (try
          (debug "Sending RPC to" target-node "- message:" message "type:" (type message))
          (when (vector? message)
            (debug "Message vector - first:" (first message) "type:" (type (first message))))
          (if-let [target-info (get cluster-members target-node)]
            (let [{:keys [host port]} target-info
                  url (str "http://" host ":" port "/rpc")
                  payload {:from node-id :message message}
                  response (http/post url
                                      {:body (nippy/freeze payload)
                                       :headers {"Content-Type" "application/octet-stream"}
                                       :as :byte-array
                                       :socket-timeout 5000
                                       :connection-timeout 5000})]
              (when callback
                (callback (nippy/thaw (:body response)))))
            (when callback
              (callback {:error :node-not-found :target target-node})))
          (catch Exception e
            (error "RPC error sending to" target-node ":" (.getMessage e))
            ;; Don't call callback on network errors - let Raft handle the timeout
            ;; Calling callback with error response causes NullPointerException in Raft
            ))))))

(defn handle-rpc-request
  "Handles incoming HTTP RPC requests"
  [request]
  (try
    (let [body (-> request :body slurp .getBytes nippy/thaw)
          {:keys [message from]} body
          {:keys [raft-instance]} @node-state]
      (info "RPC received - raw body:" body)
      (info "Message type:" (type message) "- content:" message)
      (if raft-instance
        (let [result-promise (promise)
              [raw-op data] (if (vector? message) 
                              [(first message) (second message)]
                              [message nil])
              _ (info "Extracted raw-op:" raw-op "type:" (type raw-op))
              op (cond
                   (keyword? raw-op) raw-op
                   (string? raw-op) (keyword raw-op)
                   (symbol? raw-op) (keyword (name raw-op))
                   :else (do (error "Unknown op type:" (type raw-op) "value:" raw-op)
                            (keyword (str raw-op))))]
          (info "Final op:" op "type:" (type op) "- will invoke RPC")
          (try
            (let [event-chan (raft/event-chan raft-instance)]
              (raft/invoke-rpc* event-chan op data
                                (fn [result] 
                                  (info "RPC result for" op "-" result)
                                  (deliver result-promise result))))
            (catch Exception e
              (error "Failed to invoke RPC - op:" op "error:" e)
              (deliver result-promise {:error :rpc-invoke-failed :message (.getMessage e)})))
          (let [result (deref result-promise 5000 {:error :timeout})]
            {:status 200
             :headers {"Content-Type" "application/octet-stream"}
             :body (nippy/freeze result)}))
        {:status 503
         :headers {"Content-Type" "application/octet-stream"}
         :body (nippy/freeze {:error :node-not-ready})}))
    (catch Exception e
      (error "Error handling RPC request:" (.getMessage e) "- exception:" e)
      {:status 500
       :headers {"Content-Type" "application/octet-stream"}
       :body (nippy/freeze {:error :internal-error
                            :message (.getMessage e)})})))

(defn health-check
  "Health check endpoint"
  [_request]
  (let [{:keys [raft-instance node-id]} @node-state]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:status :ok
                            :node-ready (some? raft-instance)
                            :node-id node-id})}))

(defn forward-command-to-leader
  "Forwards a command to the leader node"
  [leader-id command]
  (let [{:keys [cluster-members]} @node-state
        leader-info (get cluster-members leader-id)]
    (if leader-info
      (let [{:keys [host port]} leader-info
            url (str "http://" host ":" port "/command")
            response (http/post url
                               {:body (nippy/freeze command)
                                :headers {"Content-Type" "application/octet-stream"}
                                :as :byte-array
                                :socket-timeout 10000
                                :connection-timeout 1000})]
        (nippy/thaw (:body response)))
      {:type :fail :error :leader-not-found})))

(defn handle-command-request
  "Handles command requests from Jepsen clients"
  [request]
  (info "COMMAND: Received command request")
  (try
    (let [body (-> request :body slurp .getBytes nippy/thaw)
          {:keys [raft-instance node-id]} @node-state]
      (info (str "COMMAND: " node-id " - Command body: " body))
      (if raft-instance
        ;; Get current raft state to check if we're the leader
        (let [state-promise (promise)
              _ (raft/get-raft-state raft-instance
                  (fn [current-state]
                    (deliver state-promise current-state)))
              current-state (deref state-promise 1000 nil)]
          (info (str "COMMAND: " node-id " - Current status: " (:status current-state) 
                     ", leader: " (:leader current-state)))
          (if (= (:status current-state) :leader)
            ;; We are the leader, process the command
            (let [result-promise (promise)
                  timeout-ms 5000]
              (info (str "COMMAND: " node-id " - We are leader, submitting entry to Raft"))
              (raft/new-entry raft-instance body
                              (fn [result] 
                                (info (str "COMMAND: " node-id " - Raft callback with result: " result))
                                (deliver result-promise result))
                              timeout-ms)
              (let [result (deref result-promise (+ timeout-ms 1000) {:type :info :error :timeout})]
                (info (str "COMMAND: " node-id " - Final result: " result))
                {:status 200
                 :headers {"Content-Type" "application/octet-stream"}
                 :body (nippy/freeze result)}))
            ;; We are not the leader, forward to leader
            (if-let [leader (:leader current-state)]
              (do
                (info (str "COMMAND: " node-id " - Forwarding to leader: " leader))
                (let [result (forward-command-to-leader leader body)]
                  {:status 200
                   :headers {"Content-Type" "application/octet-stream"}
                   :body (nippy/freeze result)}))
              (do
                (info (str "COMMAND: " node-id " - No leader elected yet"))
                {:status 503
                 :headers {"Content-Type" "application/octet-stream"}
                 :body (nippy/freeze {:type :fail :error :no-leader})}))))
        (do
          (info (str "COMMAND: " node-id " - No raft instance available"))
          {:status 503
           :headers {"Content-Type" "application/octet-stream"}
           :body (nippy/freeze {:type :fail :error :node-not-ready})})))
    (catch Exception e
      (error "COMMAND: Error handling command request:" (.getMessage e) e)
      {:status 500
       :headers {"Content-Type" "application/octet-stream"}
       :body (nippy/freeze {:type :fail :error :internal-error :message (.getMessage e)})})))

(defn create-http-handler
  "Creates HTTP request handler"
  []
  (fn [request]
    (case (:uri request)
      "/rpc"    (handle-rpc-request request)
      "/health" (health-check request)
      "/command" (handle-command-request request)
      "/test"   {:status 200
                 :headers {"Content-Type" "text/plain"}
                 :body (str "Test: " (pr-str (nippy/thaw (nippy/freeze [:request-vote {:term 1}]))))}
      "/debug"  (let [{:keys [raft-instance state-atom node-id]} @node-state]
                  (if raft-instance
                    (let [state-machine-state @state-atom
                          result-promise (promise)
                          _ (raft/get-raft-state raft-instance 
                              (fn [current-state]
                                (deliver result-promise current-state)))
                          current-state (deref result-promise 1000 {:error "Timeout getting raft state"})]
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body (json/write-str {:node-id node-id
                                              :leader (:leader current-state)
                                              :status (str (:status current-state))
                                              :term (:term current-state)
                                              :commit (:commit current-state)
                                              :index (:index current-state)
                                              :state-machine state-machine-state})})
                    {:status 200
                     :headers {"Content-Type" "application/json"}
                     :body (json/write-str {:error "No raft instance"})}))
      ;; Default 404 response
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error :not-found})})))

(defn parse-cluster-members
  "Parses cluster members string into map"
  [cluster-str]
  (into {}
        (for [member-spec (clojure.string/split cluster-str #",")]
          (let [[node-id host port] (clojure.string/split member-spec #":")]
            [node-id {:host host :port (Long/parseLong port)}]))))

(defn start-distributed-node
  "Starts a distributed Raft node"
  [node-id node-host node-port cluster-members-str]
  (info "Starting distributed Raft node" node-id "on" node-host ":" node-port)
  (info "STARTUP TEST: This should appear in logs")
  
  (let [cluster-members (parse-cluster-members cluster-members-str)
        all-nodes (vec (keys cluster-members))
        state-atom (atom {})
        
        ;; Create state machine adapter that handles Jepsen's :f field
        base-state-machine (util/create-kv-state-machine state-atom)
        state-machine-fn (fn [entry raft-state]
                          (try
                            (cond
                              ;; Handle nil or empty entries
                              (or (nil? entry) (empty? entry))
                              (do
                                (info "State machine received nil/empty entry")
                                (util/ok-result))
                              
                              ;; Check if this is an internal Raft operation (no :f field)
                              (nil? (:f entry))
                              (do
                                (debug "State machine received non-application entry:" entry)
                                (util/ok-result))
                              
                              ;; Normal application operations - convert :f to :op
                              :else
                              (let [normalized-entry (-> entry
                                                        (assoc :op (:f entry))
                                                        (dissoc :f))]
                                (base-state-machine normalized-entry raft-state)))
                            (catch Exception e
                              (error "State machine error processing entry:" entry "Error:" e)
                              (util/fail-result (str "State machine error: " (.getMessage e))))))
        
        ;; Create RPC sender
        rpc-sender-fn (create-http-rpc-sender node-id cluster-members)
        
        ;; Create Raft configuration
        raft-config (util/default-raft-config
                     node-id all-nodes
                     :state-machine-fn state-machine-fn
                     :rpc-sender-fn rpc-sender-fn
                     :leader-change-fn (fn [event]
                                         (info "LEADER CHANGE -" node-id "- new leader:" (:new-leader event) "event:" event)))
        
        ;; Add snapshot functions (test stubs)
        full-raft-config (merge raft-config
                                {:snapshot-write      (fn [index callback]
                                                        (debug node-id "Writing snapshot at index" index)
                                                        (when callback (callback)))
                                 :snapshot-reify      (fn [snapshot-index]
                                                        (debug node-id "Reifying snapshot at index" snapshot-index)
                                                        @state-atom)
                                 :snapshot-install    (fn [snapshot-map]
                                                        (let [{:keys [snapshot-index]} snapshot-map]
                                                          (debug node-id "Installing snapshot at index" snapshot-index)
                                                          nil))
                                 :snapshot-xfer       (constantly nil)
                                 :snapshot-list-indexes (constantly [])})
        
        ;; Start Raft instance
        raft-instance (raft/start full-raft-config)
        
        ;; Start HTTP server
        server (jetty/run-jetty (create-http-handler)
                                {:port node-port
                                 :host node-host
                                 :join? false})]
    
    ;; Update global state
    (swap! node-state assoc
           :node-id node-id
           :raft-instance raft-instance
           :state-atom state-atom
           :server server
           :cluster-members cluster-members)
    
    (info "Distributed node" node-id "started successfully")
    (info "Cluster members:" cluster-members)
    
    ;; Keep the main thread alive with periodic heartbeat
    (loop []
      (Thread/sleep 10000)
      (debug node-id "heartbeat - node running")
      (recur))))

(defn shutdown-node
  "Gracefully shuts down the node"
  []
  (info "Shutting down distributed node...")
  (when-let [{:keys [raft-instance server]} @node-state]
    (when raft-instance
      (raft/close raft-instance))
    (when server
      (.stop server)))
  (reset! node-state {}))

(defn -main
  "Main entry point for distributed Raft node"
  [& args]
  (when (< (count args) 4)
    (error "Usage: distributed-test-main NODE_ID NODE_HOST NODE_PORT CLUSTER_MEMBERS")
    (error "Example: distributed-test-main n1 localhost 7001 'n1:localhost:7001,n2:localhost:7002,n3:localhost:7003'")
    (System/exit 1))
  (let [[node-id node-host node-port-str cluster-members-str] args
        node-port (Long/parseLong node-port-str)]
    
    ;; Set up shutdown hook
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. shutdown-node))
    
    (try
      (start-distributed-node node-id node-host node-port cluster-members-str)
      (catch Exception e
        (error "Failed to start distributed node:" (.getMessage e))
        (shutdown-node)
        (System/exit 1)))))