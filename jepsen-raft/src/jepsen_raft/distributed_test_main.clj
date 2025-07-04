(ns jepsen-raft.distributed-test-main
  "Main entry point for distributed Raft nodes running in Docker containers"
  (:require [clojure.tools.logging :refer [info debug error]]
            [clojure.core.async :as async :refer [go]]
            [clojure.data.json :as json]
            [clojure.string]
            [clj-http.client :as http]
            [ring.adapter.jetty :as jetty]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util])
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
          (if-let [target-info (get cluster-members target-node)]
            (let [{:keys [host port]} target-info
                  url (str "http://" host ":" port "/rpc")
                  payload {:from node-id :message message}
                  response (http/post url
                                      {:body (json/write-str payload)
                                       :headers {"Content-Type" "application/json"}
                                       :socket-timeout 5000
                                       :connection-timeout 5000})]
              (when callback
                (callback (json/read-str (:body response) :key-fn keyword))))
            (when callback
              (callback {:error :node-not-found :target target-node})))
          (catch Exception e
            (error "RPC error sending to" target-node ":" (.getMessage e))
            (when callback
              (callback {:error :rpc-failed :message (.getMessage e)}))))))))

(defn handle-rpc-request
  "Handles incoming HTTP RPC requests"
  [request]
  (try
    (let [body (slurp (:body request))
          {:keys [message]} (json/read-str body :key-fn keyword)
          {:keys [raft-instance]} @node-state]
      (if raft-instance
        (let [result-promise (promise)
              [op data] (if (vector? message) message [message nil])]
          (raft/invoke-rpc* (raft/event-chan raft-instance) op data
                            (fn [result] (deliver result-promise result)))
          (let [result (deref result-promise 5000 {:error :timeout})]
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str result)}))
        {:status 503
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error :node-not-ready})}))
    (catch Exception e
      (error "Error handling RPC request:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error :internal-error
                              :message (.getMessage e)})})))

(defn health-check
  "Health check endpoint"
  [_request]
  (let [{:keys [raft-instance]} @node-state]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:status :ok
                            :node-ready (some? raft-instance)})}))

(defn create-http-handler
  "Creates HTTP request handler"
  []
  (fn [request]
    (case (:uri request)
      "/rpc"    (handle-rpc-request request)
      "/health" (health-check request)
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
  
  (let [cluster-members (parse-cluster-members cluster-members-str)
        all-nodes (vec (keys cluster-members))
        state-atom (atom {})
        
        ;; Create state machine using util function
        state-machine-fn (util/create-kv-state-machine state-atom)
        
        ;; Create RPC sender
        rpc-sender-fn (create-http-rpc-sender node-id cluster-members)
        
        ;; Create Raft configuration
        raft-config (util/default-raft-config
                     node-id all-nodes
                     :state-machine-fn state-machine-fn
                     :rpc-sender-fn rpc-sender-fn
                     :leader-change-fn (fn [event]
                                         (info node-id "leader changed:" (:new-leader event))))
        
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