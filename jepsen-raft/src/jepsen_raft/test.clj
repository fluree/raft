(ns jepsen-raft.test
  "Main test runner using net.async for TCP communication."
  (:require [jepsen [checker :as checker]
             [cli :as cli]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]
             [os :as os]
             [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [jepsen-raft.db :as netasync-db]
            [jepsen-raft.client :as netasync-client]
            [jepsen-raft.operations :as ops]
            [jepsen-raft.config :as config]
            [knossos.model :as model]))

;; Use operations from shared namespace
(def ^:private read-op ops/read-op)
(def ^:private write-op ops/write-op)
(def ^:private cas-op ops/cas-op)

(defn raft-test
  "Given options from the CLI, constructs a test map for net.async-based
   distributed Raft testing."
  [opts]
  (let [db (netasync-db/db)
        client (netasync-client/client)]
    (merge tests/noop-test
           opts
           {:name      "raft-netasync"
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
                             2
                             config/test-keys
                             (fn [_k]
                               (->> (gen/mix [ops/read-op ops/write-op ops/cas-op])
                                    (gen/stagger config/default-stagger-rate))))
                            (gen/nemesis nil)
                            (gen/time-limit (:time-limit opts)))})))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
   browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn raft-test})
            args))