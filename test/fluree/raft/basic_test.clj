(ns fluree.raft.basic-test
  "Tests for the basic Raft API as shown in the README"
  (:require [clojure.test :refer [deftest is testing]]
            [fluree.raft :as raft]
            [test-with-files.core :refer [with-tmp-dir tmp-dir]]))

(defn- create-state-machine
  "Creates a simple key-value state machine that tracks changes in an atom.
  Returns [state-machine state-atom] tuple."
  []
  (let [state-atom (atom {})]
    [(fn [entry _raft-state]
       (swap! state-atom
              (fn [state]
                (case (:op entry)
                  :set (assoc state (:key entry) (:value entry))
                  :delete (dissoc state (:key entry))
                  state)))
       true) ; Always return success
     state-atom]))

(defn- minimal-snapshot-fns
  "Returns minimal snapshot functions for a single-node test."
  [state-atom]
  {:snapshot-write   (fn [file state]
                       (spit file (pr-str state)))
   :snapshot-reify   (fn [] @state-atom)
   :snapshot-install (fn [snapshot _]
                       (reset! state-atom snapshot))
   :snapshot-xfer    (fn [_ _] nil) ; No-op for single node
   :snapshot-list-indexes (fn [_] [])})

(defn- submit-entry
  "Helper to submit an entry and wait for the callback result."
  [raft-instance entry]
  (let [result-promise (promise)]
    (raft/new-entry raft-instance entry
                    (fn [success?]
                      (deliver result-promise success?)))
    (deref result-promise 2000 false)))

(defn- wait-for-processing
  "Wait a bit for async processing to complete."
  []
  (Thread/sleep 100))

(deftest minimal-example-test
  (testing "Basic Raft usage as shown in README"
    (with-tmp-dir
      (let [[state-machine state-atom] (create-state-machine)
            leader-atom (atom nil)

            config (merge
                    {:servers          ["server1"]
                     :this-server      "server1"
                     :leader-change-fn (fn [event]
                                         (reset! leader-atom (:new-leader event)))
                     :send-rpc-fn      (fn [_ _ callback]
                                         (when callback (callback nil)))
                     :log-directory    tmp-dir
                     :state-machine    state-machine
                     :heartbeat-ms     50
                     :timeout-ms       100}
                    (minimal-snapshot-fns state-atom))

            raft-instance (raft/start config)]

        (try
          ;; Wait for leader election
          (Thread/sleep 1000)

          (testing "leader election"
            (is (= "server1" @leader-atom) "Should elect itself as leader"))

          (testing "set operation"
            (is (submit-entry raft-instance {:op :set :key "foo" :value "bar"}))
            (wait-for-processing)
            (is (= "bar" (get @state-atom "foo"))))

          (testing "multiple operations"
            (is (submit-entry raft-instance {:op :set :key "baz" :value "qux"}))
            (wait-for-processing)
            (is (= {"foo" "bar" "baz" "qux"} @state-atom)))

          (testing "delete operation"
            (is (submit-entry raft-instance {:op :delete :key "foo"}))
            (wait-for-processing)
            (is (nil? (get @state-atom "foo")))
            (is (= "qux" (get @state-atom "baz"))))

          (finally
            (raft/close raft-instance)))))))