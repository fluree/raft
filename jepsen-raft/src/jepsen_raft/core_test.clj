(ns jepsen-raft.core-test
  "Core Jepsen tests for Fluree Raft"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]
                    [checker :as checker]
                    [nemesis :as nemesis]
                    [generator :as gen]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [jepsen.os.debian :as debian]
            [jepsen-raft.db :as rdb]
            [jepsen-raft.client :as rclient]))

(defn r [_ _] {:type :invoke, :f :read, :k (rand-nth [:x :y :z])})
(defn w [_ _] {:type :invoke, :f :write, :k (rand-nth [:x :y :z]), :v (rand-int 100)})
(defn cas [_ _] {:type :invoke, :f :cas, :k (rand-nth [:x :y :z]), 
                 :old-v (rand-int 100), :new-v (rand-int 100)})
(defn d [_ _] {:type :invoke, :f :delete, :k (rand-nth [:x :y :z])})

(defn raft-test
  "Basic Raft test"
  [opts]
  (merge tests/noop-test
         opts
         {:name "raft-basic"
          :os debian/os
          :db (rdb/db "1.0.0-beta1")
          :client (rclient/client)
          :nemesis (nemesis/partition-random-halves)
          :checker (checker/compose
                     {:perf     (checker/perf)
                      :timeline (timeline/html)
                      :linear   (checker/linearizable
                                  {:model (model/cas-register)
                                   :algorithm :linear})})
          :generator (->> (gen/mix [r w cas d])
                          (gen/stagger 1/10)
                          (gen/nemesis
                            (cycle [(gen/sleep 5)
                                    {:type :info, :f :start}
                                    (gen/sleep 5)
                                    {:type :info, :f :stop}]))
                          (gen/time-limit (:time-limit opts)))}))

(defn -main
  "Runs the test. You can specify options on the command line like:
   
   lein run -- --nodes n1,n2,n3 --time-limit 60"
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn raft-test})
            args))