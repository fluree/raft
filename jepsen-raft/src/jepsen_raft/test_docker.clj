(ns jepsen-raft.test-docker
  "Dockerized Raft test with Jepsen integration and network partition nemesis."
  (:require [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [os :as os]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [jepsen-raft.db-docker :as docker-db]
            [jepsen-raft.client :as netasync-client]
            [jepsen-raft.nemesis-docker :as docker-nemesis]
            [jepsen-raft.operations :as ops]
            [jepsen-raft.model :as raft-model]
            [jepsen-raft.config :as config]
            [knossos.model :as model]))

;; Use operations from shared namespace
(def ^:private read-op ops/read-op)
(def ^:private write-op ops/write-op)
(def ^:private cas-op ops/cas-op)

(defn dockerized-raft-test
  "Dockerized net.async Raft test with network partition nemesis.
   
   This test combines:
   - Production TCP protocol (net.async)
   - Docker container isolation 
   - Network partition/latency injection
   - Full Jepsen consistency checking"
  [opts]
  (let [db (docker-db/db)
        client (netasync-client/client)
        nemesis (docker-nemesis/partition-nemesis)]
    (merge tests/noop-test
           opts
           {:name      "raft-netasync-docker"
            :os        os/noop
            :db        db
            :client    client
            :nemesis   nemesis
            :ssh       {:dummy? true}
            :concurrency config/default-concurrency
            :checker   (checker/compose
                         {:perf     (checker/perf)
                          :timeline (timeline/html)
                          :linear   (independent/checker
                                      (checker/linearizable 
                                        {:model (model/cas-register)}))})
            :generator (->> (independent/concurrent-generator
                              3
                              config/test-keys
                              (fn [_k]
                                (->> (gen/mix [ops/read-op ops/write-op ops/cas-op])
                                     (gen/stagger config/default-stagger-rate))))
                            (gen/nemesis
                              (cycle
                                [(gen/sleep 10)
                                 {:type :info :f :start-partition :value :random}
                                 (gen/sleep 15)
                                 {:type :info :f :stop-partition}
                                 (gen/sleep 10)
                                 {:type :info :f :add-latency :value 200}
                                 (gen/sleep 10)
                                 {:type :info :f :remove-latency}]))
                            (gen/time-limit (:time-limit opts)))})))

(defn dockerized-raft-test-minimal
  "Minimal dockerized test without nemesis for quick validation."
  [opts]
  (let [db (docker-db/db)
        client (netasync-client/client)]
    (merge tests/noop-test
           opts
           {:name      "raft-netasync-docker-minimal"
            :os        os/noop
            :db        db
            :client    client
            :nemesis   nemesis/noop
            :ssh       {:dummy? true}
            :concurrency config/default-concurrency
            :checker   (checker/compose
                         {:perf     (checker/perf)
                          :timeline (timeline/html)
                          :linear   (independent/checker
                                      (checker/linearizable 
                                        {:model (model/cas-register)}))})
            :generator (->> (independent/concurrent-generator
                              3
                              config/test-keys
                              (fn [_k]
                                (->> (gen/mix [ops/read-op ops/write-op ops/cas-op])
                                     (gen/stagger config/default-stagger-rate))))
                            (gen/nemesis nil)  ; No nemesis operations
                            (gen/time-limit (:time-limit opts)))})))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
   browsing results."
  [& args]
  ;; Custom CLI parsing to handle minimal flag properly
  (let [minimal? (some #(= "--minimal" %) args)
        test-fn (if minimal? dockerized-raft-test-minimal dockerized-raft-test)]
    (cli/run! (cli/single-test-cmd 
                {:test-fn test-fn
                 :opt-spec [[nil "--minimal" "Run minimal test without nemesis"]]})
              args)))