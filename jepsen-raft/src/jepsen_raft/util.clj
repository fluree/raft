(ns jepsen-raft.util
  "Shared utilities for Jepsen Raft tests"
  (:require [clojure.tools.logging :refer [info debug]]
            [clojure.core.async :as async :refer [<! >! go go-loop]]))

;; =============================================================================
;; Configuration Constants
;; =============================================================================

(def default-timeouts
  "Default timeout configurations for Raft tests"
  {:operation-timeout-ms 5000    ; Client operation timeout
   :heartbeat-ms 100              ; Raft heartbeat interval  
   :election-timeout-ms 300       ; Raft election timeout
   :rpc-timeout-ms 6000          ; RPC operation timeout
   :leader-wait-ms 2000})        ; Time to wait for leader election

(def default-paths
  "Default file system paths for Raft tests"
  {:log-directory "/tmp/jepsen-raft/"})

(def default-test-params
  "Default parameters for test operations"
  {:test-keys [:x :y :z]         ; Keys used in operations
   :value-range 100              ; Range for random values
   :rpc-delay-max-ms 5           ; Maximum simulated network delay
   :snapshot-threshold 100       ; Entries before snapshot
   :cluster-size 3               ; Default number of nodes
   :port-base 7000})            ; Base port for services

;; =============================================================================
;; Result Helpers
;; =============================================================================

(defn make-result
  "Creates a standardized result map"
  ([type]
   {:type type})
  ([type value-or-error]
   (if (keyword? value-or-error)
     {:type type :error value-or-error}
     {:type type :value value-or-error}))
  ([type key value]
   {:type type key value}))

;; Result type helpers  
(def ok-result (partial make-result :ok))
(def fail-result (partial make-result :fail))

;; =============================================================================
;; State Machine
;; =============================================================================

(defn create-kv-state-machine
  "Creates a standard key-value state machine for Raft testing.
  
  Args:
    state-atom: Atom containing the key-value state map
    
  Returns:
    Function that processes operations and returns results"
  [state-atom]
  (fn [entry _raft-state]
    (let [{:keys [op key value old new]} entry]
      (cond
        ;; Handle nil or missing op
        (nil? op)
        (do (debug "State machine received entry with nil op:" entry)
            (ok-result))  ; Return ok for internal Raft operations
        
        ;; Standard operations
        (= op :write)
        (do
          (swap! state-atom assoc key value)
          (ok-result))
            
        (= op :read)
        (ok-result (get @state-atom key))
        
        (= op :cas)
        (if (= (get @state-atom key) old)
          (do
            (swap! state-atom assoc key new)
            (ok-result))
          (fail-result))
          
        (= op :delete)
        (do
          (swap! state-atom dissoc key)
          (ok-result))
            
        ;; Unknown operation
        :else
        (do (debug "State machine received unknown op:" op "in entry:" entry)
            (fail-result (str "Unknown operation: " op)))))))

;; =============================================================================
;; RPC Utilities
;; =============================================================================

(defn create-async-rpc-router
  "Creates an async RPC message router with simulated network delays.
  
  Args:
    nodes-registry: Atom containing map of node-id -> node-data
    max-delay-ms: Maximum network delay to simulate (default 5ms)
    
  Returns:
    Function that routes RPC messages between nodes"
  ([nodes-registry] 
   (create-async-rpc-router nodes-registry (:rpc-delay-max-ms default-test-params)))
  ([nodes-registry max-delay-ms]
   (fn [from-node to-node message callback]
     (if-let [target-node (get @nodes-registry to-node)]
       (go
         ;; Simulate network delay
         (<! (async/timeout (rand-int max-delay-ms)))
         (>! (:rpc-chan target-node) 
             {:from from-node 
              :message message 
              :callback callback}))
       ;; Node not found
       (when callback 
         (callback {:error :node-not-found :target to-node}))))))

(defn create-rpc-sender
  "Creates an RPC sender function that handles multiple argument formats.
  
  This handles the Raft library's different calling conventions for RPC.
  
  Args:
    node-id: ID of the sending node
    router-fn: Function to route messages (from create-async-rpc-router)
    
  Returns:
    Function that can handle both 3 and 5 argument RPC calls"
  [node-id router-fn]
  (fn [& args]
    (let [[target-node message callback] 
          (case (count args)
            3 args  ; Direct 3-arg call
            5 [(second args)                    ; Extract from 5-arg call
               [(nth args 2) (nth args 3)]     ; Combine args 2&3 as message
               (last args)]                    ; Callback is last
            ;; Invalid argument count
            (throw (IllegalArgumentException. 
                    (str "Invalid RPC args count: " (count args) 
                         ", expected 3 or 5"))))]
      (router-fn node-id target-node message callback))))

;; =============================================================================
;; Node Management
;; =============================================================================

(defn default-raft-config
  "Creates a default Raft configuration map.
  
  Args:
    node-id: ID of this node
    all-nodes: Vector of all node IDs in cluster
    log-dir: Directory for Raft logs (optional)
    state-machine-fn: State machine function (optional)
    rpc-sender-fn: RPC sender function (optional)
    
  Returns:
    Map of Raft configuration options"
  [node-id all-nodes & {:keys [log-dir state-machine-fn rpc-sender-fn 
                                leader-change-fn]
                         :or {log-dir (str (:log-directory default-paths) node-id "/")
                              leader-change-fn (fn [event] 
                                                 (info node-id "leader change:" event))}}]
  (cond-> {:servers all-nodes
           :this-server node-id
           :log-directory log-dir
           :heartbeat-ms (:heartbeat-ms default-timeouts)
           :timeout-ms (:election-timeout-ms default-timeouts)
           :snapshot-threshold (:snapshot-threshold default-test-params)
           :leader-change-fn leader-change-fn
           :default-command-timeout (:operation-timeout-ms default-timeouts)}
    state-machine-fn (assoc :state-machine state-machine-fn)
    rpc-sender-fn (assoc :send-rpc-fn rpc-sender-fn)))

;; =============================================================================
;; Test Operation Generators
;; =============================================================================

(defn random-key
  "Returns a random key from the standard test keys"
  []
  (rand-nth (:test-keys default-test-params)))

(defn random-value
  "Returns a random value in the standard test range"
  []
  (rand-int (:value-range default-test-params)))

