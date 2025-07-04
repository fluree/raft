(ns jepsen-raft.core-test
  "Distributed Jepsen tests for Fluree Raft"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]
                    [checker :as checker]
                    [nemesis :as nemesis]
                    [generator :as gen]
                    [os :as os]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [knossos.model :as model]
            [jepsen-raft.distributed-db :as rdb]
            [jepsen-raft.distributed-client :as rclient]))

;; Use the same operation generators as in simple_in_process
(defn r [_ _] {:type :invoke :f :read :key (rand-nth [:x :y :z])})
(defn w [_ _] {:type :invoke :f :write :key (rand-nth [:x :y :z]) :value (rand-int 100)})
(defn cas [_ _] {:type :invoke :f :cas :key (rand-nth [:x :y :z]) 
                 :old (rand-int 100) :new (rand-int 100)})
(defn d [_ _] {:type :invoke :f :delete :key (rand-nth [:x :y :z])})

;; Custom OS that works with Docker containers
(def docker-os
  (reify os/OS
    (setup! [_ test node]
      (info "OS setup on" node))
    
    (teardown! [_ test node]
      (info "OS teardown on" node))))

(defn raft-test
  "Distributed Raft test for Docker environment"
  [opts]
  (merge tests/noop-test
         opts
         {:name "raft-distributed"
          :os docker-os
          :db (rdb/db)
          :client (rclient/client)
          :nemesis (nemesis/partition-random-halves)
          :checker (checker/compose
                     {:perf     (checker/perf)
                      :timeline (timeline/html)
                      :linear   (checker/linearizable
                                  {:model (model/cas-register)})})
          :generator (->> (gen/mix [r w cas d])
                          (gen/stagger 1/10)
                          (gen/clients)
                          (gen/nemesis
                            (cycle [(gen/sleep 10)
                                    {:type :info, :f :start}
                                    (gen/sleep 10)
                                    {:type :info, :f :stop}]))
                          (gen/time-limit (:time-limit opts 60)))}))

(defn -main
  "Runs the distributed test. You can specify options on the command line like:
   
     clojure -M:run test --nodes n1,n2,n3,n4,n5 --time-limit 60
   
   This expects Docker containers to be running with the names n1, n2, etc."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn raft-test
                                   :opt-spec [[nil "--time-limit SECONDS" 
                                               "How long to run the test for"
                                               :default 60
                                               :parse-fn #(Long/parseLong %)]]})
            args))