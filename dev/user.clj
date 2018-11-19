(ns user
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [fluree.raft :as raft]
            [fluree.raft.config :as config])
  (:import (java.util UUID)))


(defn state-machine
  [state-atom]
  (fn [[op k v compare]]
    (case op
      :write (swap! state-atom assoc k v)
      :read (get @state-atom k)
      :delete (swap! state-atom dissoc k)
      :cas (swap! state-atom (fn [state]
                               (if (= compare (get state k))
                                 (assoc state k v)
                                 state))))))


(def servers-atom (atom {:a {:chan (async/chan)}
                         :b {:chan (async/chan)}
                         :c {:chan (async/chan)}}))


(def servers (keys @servers-atom))

(defn close-fn
  [this-server]
  (fn []
    (let [c (get-in @servers-atom [this-server :chan])]
      (async/close! c))))


(defn monitor-incoming-rcp
  [raft]
  (let [this-server (config/this-server raft)
        c           (get-in @servers-atom [this-server :chan])]
    (async/go-loop []
      (let [rpc (async/<! c)]
        (if (nil? rpc)
          (log/warn "RPC channel closed for server:" this-server)
          (let [[header data] rpc]
            ;(log/info (str this-server " - incoming RPC: ") header data)
            (raft/invoke-rpc-handler raft header data)
            (recur)))))))


(defn send-rpc
  "Sends rpc call to specified server."
  [raft server operation data callback]
  (let [server-config (get @servers-atom server)
        server-chan   (:chan server-config)
        resp-chan     (async/promise-chan)
        this-server   (config/this-server raft)
        msg-id        (str (UUID/randomUUID))
        header        {:op        operation
                       :from      this-server
                       :to        server
                       :msg-id    msg-id
                       :resp-chan resp-chan}]
    (async/put! server-chan [header data])
    ;; wait for response and call back
    (async/go (let [response (async/<! resp-chan)]
                #_(log/warn (str this-server " - send rpc " {:header   (select-keys header [:op :from :to])
                                                           :data     data
                                                           :response response}))
                (callback response)))
    resp-chan))

(def state-machine-atoms {:a (atom {})
                          :b (atom {})
                          :c (atom {})})

(defn start
  [server-id]

  (let [raft (raft/start {:this-server      server-id
                          :servers          servers
                          :state-machine    (state-machine (get state-machine-atoms server-id))
                          :election-timeout 6000
                          :broadcast-time   3000
                          :send-rpc-fn      send-rpc
                          :close-fn         (close-fn server-id)})]

    (monitor-incoming-rcp raft)
    raft))

(defn new-command
  [raft [op k v compare]]
  (if (= :read op)
    (let [server-id (get-in raft [:config :this-server])]
      (-> (get state-machine-atoms server-id) deref (get k)))
    (raft/add-new-entry raft [op k v compare])))


(defn power-load
  "Load a quantity of new keys using specified prefix"
  [raft prefix quantity]
  (let [entries (map (fn [i] [:write (str prefix "_key_" i) [prefix i]]) (range quantity))]
    (doseq [entry entries]
      (new-command raft entry))))

(comment

  (def a (start :a))
  (def b (start :b))
  (def c (start :c))

  (raft/close a)
  (raft/close b)
  (raft/close c)

  (new-command a [:write "mykey" "myval"])
  (new-command c [:read "mykey"])

  (power-load a "b" 1000)
  (new-command b [:read "b_key_99"])


  )

(comment

  state-machine-atoms

  (-> a
      :raft-state
      deref)

  (-> b
      :raft-state
      deref)

  (-> c
      :raft-state
      deref)

  )