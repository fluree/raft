(ns jepsen-raft.client
  "Client protocol for interacting with Fluree Raft"
  (:require [jepsen.client :as client]
            [slingshot.slingshot :refer [try+]]))

(defprotocol RaftClient
  "Protocol for Raft client operations"
  (write! [client k v] "Write a key-value pair")
  (read! [client k] "Read a value by key")
  (cas! [client k old-v new-v] "Compare and swap")
  (delete! [client k] "Delete a key"))

(defn connect
  "Connect to a Raft node. Returns a client connection."
  [node]
  ;; In a real implementation, this would establish a network connection
  ;; For now, we'll return a map with connection info
  {:node node
   :port 7000
   :connected? true})

(defn disconnect!
  "Disconnect from a Raft node"
  [conn]
  (assoc conn :connected? false))

(defn invoke-operation!
  "Send an operation to the Raft cluster"
  [conn op]
  ;; This is a placeholder - in reality, you'd send the operation
  ;; over the network to the Raft node
  (try+
    (case (:f op)
      :read {:type :ok
             :value (get (:value op) (:k op))}
      :write {:type :ok}
      :cas (if (= (get (:value op) (:k op)) (:old-v op))
             {:type :ok}
             {:type :fail})
      :delete {:type :ok})
    (catch [:type :network-error] _
      {:type :fail
       :error :network-error})
    (catch Exception _
      {:type :fail
       :error :unknown})))

(defrecord Client [conn]
  client/Client
  (open! [this _test node]
    (assoc this :conn (connect node)))
  
  (setup! [_this _test])
  
  (invoke! [_this _test op]
    (case (:f op)
      :read (let [result (invoke-operation! conn op)]
              (assoc op :type (:type result)
                     :value (:value result)))
      :write (let [result (invoke-operation! conn op)]
               (assoc op :type (:type result)))
      :cas (let [result (invoke-operation! conn op)]
             (assoc op :type (:type result)))
      :delete (let [result (invoke-operation! conn op)]
                (assoc op :type (:type result)))))
  
  (teardown! [_this _test])
  
  (close! [_this _test]
    (disconnect! conn)))

(defn client
  "Create a new Raft client"
  []
  (Client. nil))