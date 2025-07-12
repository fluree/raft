(ns jepsen-raft.util
  "Shared utilities for Jepsen Raft tests"
  (:require [clojure.tools.logging :refer [info debug]]
            [clojure.java.shell]
            [clojure.string]
            [jepsen-raft.config :as config]
            [jepsen-raft.nodeconfig :as nodes]))

;; =============================================================================
;; Configuration Constants
;; =============================================================================

;; Default timeouts used by default-raft-config
(def ^:private default-heartbeat-ms 100)
(def ^:private default-election-timeout-ms 300)
(def ^:private default-snapshot-threshold 100)

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
    (debug "State machine received entry:" entry "key type:" (type (:key entry)))
    (let [{:keys [f key value old new]} entry]
      (debug "Processing f:" f "key:" key "key-type:" (type key) "current-state:" @state-atom)
      (cond
        ;; Handle nil or missing f
        (nil? f)
        (do (debug "State machine received entry with nil f:" entry)
            (ok-result))  ; Return ok for internal Raft operations

        ;; Standard operations
        (= f :write)
        (do
          (swap! state-atom assoc key value)
          (ok-result))

        (= f :read)
        (ok-result (get @state-atom key))

        (= f :cas)
        (let [current-value (get @state-atom key)]
          (debug "CAS operation: key=" key "old=" old "new=" new "current-value=" current-value "state=" @state-atom)
          (if (= current-value old)
            (do
              (swap! state-atom assoc key new)
              (debug "CAS succeeded: key=" key "old=" old "new=" new "new-state=" @state-atom)
              (ok-result))
            (let [failure-result (fail-result :cas-failed)]
              (debug "CAS failed: key=" key "expected=" old "actual=" current-value "equal?=" (= current-value old) "returning=" failure-result)
              failure-result)))

        ;; Unknown operation
        :else
        (do (debug "State machine received unknown f:" f "in entry:" entry)
            (fail-result (str "Unknown operation: " f)))))))

;; =============================================================================
;; Node Management
;; =============================================================================

(defn node->ports
  "Map node name to TCP and HTTP ports.
   Delegates to centralized nodes configuration."
  [node]
  (nodes/node->ports node))

(defn check-port-available
  "Check if a port is available for binding."
  [port]
  (try
    (let [socket (java.net.ServerSocket. port)]
      (.close socket)
      true)
    (catch java.net.BindException _
      false)))

(defn find-process-using-port
  "Find what process is using a specific port (macOS/Linux)."
  [port]
  (try
    (let [result (clojure.java.shell/sh "lsof" "-ti" (str ":" port))]
      (when (zero? (:exit result))
        (let [pids (clojure.string/split (clojure.string/trim (:out result)) #"\n")]
          (for [pid pids
                :when (not (clojure.string/blank? pid))]
            (let [ps-result (clojure.java.shell/sh "ps" "-p" pid "-o" "comm=")]
              (when (zero? (:exit ps-result))
                {:pid pid :command (clojure.string/trim (:out ps-result))}))))))
    (catch Exception _
      nil)))

(defn check-port-with-process-info
  "Check if port is available and provide info about what's using it if not."
  [port]
  (if (check-port-available port)
    {:available? true}
    {:available? false
     :port port
     :processes (find-process-using-port port)}))

(defn check-all-node-ports
  "Check if all ports for a list of nodes are available.
   Returns {:all-available? true} if all ports are free,
   or {:all-available? false :conflicts [...]} with details about conflicts."
  [nodes]
  (let [port-checks (for [node nodes
                          :let [{:keys [tcp http]} (node->ports node)]
                          port [tcp http]
                          :let [port-info (check-port-with-process-info port)]
                          :when (not (:available? port-info))]
                      (assoc port-info :node node))]
    (if (empty? port-checks)
      {:all-available? true}
      {:all-available? false
       :conflicts port-checks})))

(defn format-port-conflict-error
  "Format a user-friendly error message for port conflicts."
  [conflicts]
  (let [conflict-details (for [conflict conflicts
                               :let [{:keys [node port processes]} conflict]]
                           (str "  - Node " node " port " port
                                (when (seq processes)
                                  (str " (used by: "
                                       (clojure.string/join ", "
                                                            (map #(str (:command %) " [PID " (:pid %) "]")
                                                                 processes))
                                       ")"))))]
    (str "Port conflicts detected:\n"
         (clojure.string/join "\n" conflict-details)
         "\n\nTo resolve:\n"
         "  1. Stop existing processes: make stop-nodes\n"
         "  2. Or kill specific processes: pkill -f jepsen-raft.raft-node\n"
         "  3. Or use different ports in your configuration")))

(defn log-node-operation
  "Standardized logging for node operations"
  [operation node & [details]]
  (if details
    (info (str operation " node " node ": " details))
    (info (str operation " node " node))))

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
                        :or {log-dir (str config/log-directory node-id "/")
                             leader-change-fn (fn [event]
                                                (let [timestamp (System/currentTimeMillis)]
                                                  (info "LEADER_CHANGE:" node-id "event=" event "timestamp=" timestamp)))}}]
  (cond-> {:servers all-nodes
           :this-server node-id
           :log-directory log-dir
           :heartbeat-ms default-heartbeat-ms
           :timeout-ms default-election-timeout-ms
           :snapshot-threshold default-snapshot-threshold
           :leader-change-fn leader-change-fn
           :default-command-timeout config/operation-timeout-ms}
    state-machine-fn (assoc :state-machine state-machine-fn)
    rpc-sender-fn (assoc :send-rpc-fn rpc-sender-fn)))

;; =============================================================================
;; Test Operations
;; =============================================================================

(defn random-key
  "Generate a random test key from configured test keys."
  []
  (rand-nth config/test-keys))

(defn random-value
  "Generate a random test value within configured range."
  []
  (rand-int config/value-range))

(defn generate-test-command
  "Generate a random test command.
  
  Returns a map with :op and appropriate parameters for the operation."
  []
  (let [op-type (rand-nth [:write :read :cas])
        key (name (random-key))
        value (random-value)]
    (case op-type
      :write {:f :write :key key :value value}
      :read {:f :read :key key}
      :cas {:f :cas :key key :old (random-value) :new value})))
