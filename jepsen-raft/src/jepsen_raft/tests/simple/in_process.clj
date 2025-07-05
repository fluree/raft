(ns jepsen-raft.tests.simple.in-process
  "Simplified in-process Jepsen test that avoids serialization issues"
  (:require [clojure.tools.logging :refer [info debug error]]
            [jepsen [cli :as cli]
                    [db :as db]
                    [tests :as tests]
                    [checker :as checker]
                    [generator :as gen]
                    [client :as client]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [fluree.raft :as raft]
            [clojure.core.async :as async :refer [<! >! go go-loop]]
            [jepsen-raft.util :as util]))

;; Global state - kept separate from test to avoid serialization issues
(defonce ^:private nodes (atom {}))

(defn create-state-machine
  "Creates a state machine function for Jepsen operations.
  
  This adapts the standard util state machine to handle Jepsen's :f field
  instead of :op field for operation types."
  [state-atom]
  (let [standard-sm (util/create-kv-state-machine state-atom)]
    (fn [entry raft-state]
      (try
        ;; Handle nil or empty entries
        (cond
          (or (nil? entry) (empty? entry))
          (do
            (info "State machine received nil/empty entry")
            (util/ok-result))
          
          ;; Check if this is an internal Raft operation (no :f field)
          (nil? (:f entry))
          (do
            (debug "State machine received non-application entry:" entry)
            (util/ok-result))
          
          ;; Normal application operations
          :else
          (let [normalized-entry (-> entry
                                     (assoc :op (:f entry))
                                     (dissoc :f))]
            (standard-sm normalized-entry raft-state)))
        (catch Exception e
          (error "State machine error processing entry:" entry "Error:" e)
          (util/fail-result (str "State machine error: " (.getMessage e))))))))

;; Use shared RPC utilities
(def ^:private rpc-router (util/create-async-rpc-router nodes))

(defn create-send-rpc-fn
  "Creates RPC sender using shared utilities"
  [node-id]
  (util/create-rpc-sender node-id rpc-router))

(defn start-node
  [node-id all-nodes]
  (let [state-atom (atom {})
        rpc-chan (async/chan 100)
        base-config (util/default-raft-config 
                     node-id all-nodes
                     :state-machine-fn (create-state-machine state-atom)
                     :rpc-sender-fn (create-send-rpc-fn node-id)
                     :leader-change-fn (fn [event]
                                         (info node-id "leader:" (:new-leader event))))
        ;; Add snapshot functions that aren't in the base config
        raft-config (merge base-config
                           {:snapshot-write (fn [index callback]
                                              (debug node-id "Writing snapshot at index" index)
                                              ;; For testing, we don't need to actually write snapshots
                                              (when callback (callback)))
                            :snapshot-reify (fn [snapshot-index]
                                              (debug node-id "Reifying snapshot at index" snapshot-index)
                                              ;; For testing, just return current state
                                              @state-atom)
                            :snapshot-install (fn [snapshot-map]
                                                (let [{:keys [snapshot-index]} snapshot-map]
                                                  (debug node-id "Installing snapshot at index" snapshot-index)
                                                  ;; For testing, we don't actually handle snapshots
                                                  nil))
                            :snapshot-xfer (fn [_ _] nil)
                            :snapshot-list-indexes (constantly [])})
        raft-instance (raft/start raft-config)
        event-chan (raft/event-chan raft-instance)]
    
    ;; RPC processor
    (go-loop []
      (when-let [{:keys [message callback]} (<! rpc-chan)]
        (try
          (let [[op data] (if (vector? message) message [message nil])]
            (raft/invoke-rpc* event-chan op data callback))
          (catch Exception e
            (error "RPC processing error:" e "message:" message)
            (when callback
              (callback {:error (str "RPC error: " (.getMessage e))}))))
        (recur)))
    
    {:raft raft-instance
     :state state-atom
     :rpc-chan rpc-chan}))

(defn stop-node
  [node-id]
  (when-let [node (get @nodes node-id)]
    (raft/close (:raft node))
    (async/close! (:rpc-chan node))))

;; DB that manages nodes outside of test map
(defrecord SimpleDB []
  db/DB
  (setup! [_ test node]
    (let [node-instance (start-node node (:nodes test))]
      (swap! nodes assoc node node-instance)
      ;; Wait a bit for the node to initialize
      (Thread/sleep 500)
      ;; On the last node, wait for leader election
      (when (= node (last (:nodes test)))
        (info "All nodes started, waiting for leader election...")
        (Thread/sleep 2000)
        (info "Proceeding with test..."))))
  
  (teardown! [_ _ node]
    (stop-node node)
    (swap! nodes dissoc node))
  
  db/LogFiles
  (log-files [_ _ node]
    [(str "/tmp/jepsen-raft/" node "/0.raft")]))

;; Client that gets node from global registry
(defrecord SimpleClient []
  client/Client
  (open! [this _ node]
    (assoc this :node node))
  
  (setup! [_ _])
  
  (invoke! [this test op]
    ; Don't crash processes on timeout - just return the timeout error
    ; This prevents the linearizability checker from seeing duplicate process operations
    (if-let [node-data (get @nodes (:node this))]
      (let [raft-node (:raft node-data)
            timeout (:operation-timeout-ms util/default-timeouts)
            result-promise (promise)
            ; Only pass the operation data, not Jepsen metadata
            entry (select-keys op [:f :key :value :old :new])]
        (raft/new-entry raft-node entry
                        (fn [result] (deliver result-promise result))
                        timeout)
        (let [result (deref result-promise (+ timeout 1000) :timeout)]
          (if (= result :timeout)
            (assoc op :type :info :error :timeout)
            (merge op result))))
      (assoc op :type :fail :error :no-node)))
  
  (teardown! [_ _])
  
  (close! [_ _]))

;; Operation generators using shared utilities (adapted for Jepsen :f field)
(defn r [_ _] {:type :invoke :f :read :key (util/random-key)})
(defn w [_ _] {:type :invoke :f :write :key (util/random-key) :value (util/random-value)})
(defn cas [_ _] {:type :invoke :f :cas :key (util/random-key) 
                 :old (util/random-value) :new (util/random-value)})
(defn d [_ _] {:type :invoke :f :delete :key (util/random-key)})

(defn simple-test
  [opts]
  (merge tests/noop-test
         opts
         {:name "raft-simple"
          :pure-generators true
          :nodes ["n1" "n2" "n3"]
          :ssh {:dummy? true}
          :db (SimpleDB.)
          :client (SimpleClient.)
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable
                                {:model (model/cas-register)})})
          :generator (->> (gen/mix [r w cas d])
                          (gen/stagger 1/10)
                          (gen/clients)
                          (gen/time-limit (:time-limit opts 10)))}))

(defn cleanup!
  "Clean up global state before running tests"
  []
  (doseq [[node-id _] @nodes]
    (stop-node node-id))
  (reset! nodes {}))

(defn -main
  "Main entry point for running the simplified Jepsen test"
  [& args]
  (cleanup!)
  (cli/run! (cli/single-test-cmd 
             {:test-fn simple-test
              :opt-spec [[nil "--time-limit SECONDS" 
                          "Time limit for test execution"
                          :default 10
                          :parse-fn #(Long/parseLong %)]]})
            args))