(ns jepsen-raft.tests.netasync.test
  "Alternative distributed test using net.async for TCP communication
   similar to Fluree Server's implementation."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [os :as os]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen-raft.util :as rutil]
            [jepsen-raft.tests.netasync.db :as netasync-db]
            [jepsen-raft.tests.netasync.client :as netasync-client]
            [slingshot.slingshot :refer [throw+ try+]]
            [knossos.model :as model])
  (:import (knossos.model Model)))

(defn ^:private read-op [_ _] 
  {:type :invoke, :f :read, :key (rand-nth [:x :y :z])})

(defn ^:private write-op [_ _] 
  {:type :invoke, :f :write, :key (rand-nth [:x :y :z]), :value (rand-int 100)})

(defn ^:private cas-op [_ _] 
  {:type :invoke, :f :cas, :key (rand-nth [:x :y :z]), 
   :value [(rand-int 100) (rand-int 100)]})

(defn ^:private delete-op [_ _]
  {:type :invoke, :f :delete, :key (rand-nth [:x :y :z])})

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
            :model     (model/cas-register nil)
            :checker   (checker/compose
                         {:perf     (checker/perf)
                          :timeline (timeline/html)
                          :linear   (checker/linearizable
                                      {:model (model/cas-register nil)})})
            :generator (->> (gen/mix [read-op
                                      write-op
                                      cas-op
                                      delete-op])
                            (gen/stagger 1/50)
                            (gen/nemesis nil)
                            (gen/time-limit (:time-limit opts)))})))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
   browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn raft-test})
            args))