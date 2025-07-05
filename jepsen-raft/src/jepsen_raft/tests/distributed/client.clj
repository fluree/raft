(ns jepsen-raft.tests.distributed.client
  "Client for distributed Raft testing over TCP"
  (:require [jepsen.client :as client]
            [clojure.tools.logging :refer [info debug error]]))

(defrecord DistributedClient []
  client/Client
  (open! [this test node]
    (assoc this :node-id node))
  
  (setup! [_ _])
  
  (invoke! [this test op]
    (let [node-id (:node-id this)
          {:keys [f key value old new]} op
          timeout-ms 5000]
      (case f
        :read
        ;; TODO: Implement distributed read
        (assoc op :type :info :error :not-implemented)
        
        :write
        ;; TODO: Implement distributed write
        (assoc op :type :info :error :not-implemented)
        
        :cas
        ;; TODO: Implement distributed CAS
        (assoc op :type :info :error :not-implemented)
        
        :delete
        ;; TODO: Implement distributed delete
        (assoc op :type :info :error :not-implemented)
        
        (assoc op :type :fail :error (str "Unknown operation: " f)))))
  
  (teardown! [_ _])
  
  (close! [_ _]))

(defn client
  "Create a new distributed Raft client"
  []
  (DistributedClient.))