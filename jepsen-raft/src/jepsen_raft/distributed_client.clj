(ns jepsen-raft.distributed-client
  "Client for distributed Raft testing over TCP"
  (:require [jepsen.client :as client]
            [clojure.tools.logging :refer [info debug error]]
            [jepsen-raft.distributed-node :as node]))

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
        (let [result (node/read-key node-id key)]
          (if (:error result)
            (assoc op :type (:type result) :error (:error result))
            (assoc op :type :ok :value (:value result))))
        
        :write
        (let [result (node/write-key node-id key value)]
          (if (:error result)
            (assoc op :type (:type result) :error (:error result))
            (assoc op :type :ok)))
        
        :cas
        (let [result (node/cas-key node-id key old new)]
          (if (:error result)
            (if (= (:type result) :info)
              (assoc op :type :info :error (:error result))
              (assoc op :type :fail :error (:error result)))
            (assoc op :type :ok)))
        
        :delete
        (let [result (node/delete-key node-id key)]
          (if (:error result)
            (assoc op :type (:type result) :error (:error result))
            (assoc op :type :ok)))
        
        (assoc op :type :fail :error (str "Unknown operation: " f)))))
  
  (teardown! [_ _])
  
  (close! [_ _]))

(defn client
  "Create a new distributed Raft client"
  []
  (DistributedClient.))