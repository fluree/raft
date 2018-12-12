(ns user
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [fluree.raft :as raft]
            [fluree.raft.log :as raft-log]
            [clojure.java.io :as io])
  (:import (java.util UUID)))





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
  "Receives incoming commands from external servers"
  [raft]
  (let [this-server (:this-server raft)
        c           (get-in @servers-atom [this-server :chan])]
    (async/go-loop []
      (let [rpc (async/<! c)]
        (if (nil? rpc)
          (log/warn "RPC channel closed for server:" this-server)
          (let [[header data] rpc
                {:keys [op resp-chan]} header
                resp-header (assoc header :op (keyword (str (name op) "-response"))
                                          :to (:from header)
                                          :from (:to header))
                callback    (fn [x]
                              (log/debug "OP Callback: " {:op op :request data :response x})
                              (async/put! resp-chan [resp-header x]))]
            ;(log/info (str this-server " - incoming RPC: ") header data)
            (raft/invoke-rpc-handler raft op data callback)
            (recur)))))))


(defn send-rpc
  "Sends rpc call to specified server."
  [raft server operation data callback]
  (let [server-config (get @servers-atom server)
        server-chan   (:chan server-config)
        resp-chan     (async/promise-chan)
        this-server   (:this-server raft)
        msg-id        (str (UUID/randomUUID))
        header        {:op        operation
                       :from      this-server
                       :to        server
                       :msg-id    msg-id
                       :resp-chan resp-chan}]
    (async/put! server-chan [header data])
    ;; wait for response and call back
    ;; for now a single response channel is created for each request, so we need to match up requests/responses
    (async/go (let [response (async/<! resp-chan)
                    [header data] response]
                #_(log/warn (str this-server " - send rpc resp: "
                                 {:header   (select-keys header [:op :from :to])
                                  :data     data
                                  :response response}))
                (callback data)))
    resp-chan))


(defn start
  [server-id]

  (let [raft (raft/start {:this-server      server-id
                          :servers          servers
                          :election-timeout 100
                          :broadcast-time   10
                          :send-rpc-fn      send-rpc
                          :persist-dir      (str "log/" (name server-id) "/")
                          :close-fn         (close-fn server-id)})]

    (monitor-incoming-rcp raft)
    raft))


(defn power-load
  "Load a quantity of new keys using specified prefix"
  [raft prefix quantity callback]
  (let [entries (map (fn [i] [:write (str prefix "_key_" i) [prefix i]]) (range quantity))]
    (doseq [entry entries]
      (raft/new-command raft entry callback))))


(defn get-raft-state
  "Returns raft state via callback function."
  [raft callback]
  (let [event-chan (get-in raft [:config :timeout-reset-chan])]
    (async/put! event-chan [:raft-state nil callback])))



(comment

  (raft/close a)
  (raft/close b)
  (raft/close c)

  (def a (start :a))
  (def b (start :b))
  (def c (start :c))

  (get-raft-state a (fn [x] (clojure.pprint/pprint (dissoc x :config))))
  (get-raft-state b (fn [x] (clojure.pprint/pprint (dissoc x :config))))
  (get-raft-state c (fn [x] (clojure.pprint/pprint (dissoc x :config))))

  (raft/new-command a [:write "mykey" "myval"] (fn [x] (println "Result: " x)))
  (raft/new-command b [:read "mykey"] (fn [x] (println "Result: " x)))

  (power-load a "d" 100 nil)
  (raft/new-command b [:read "b_key_99"] (fn [x] (println "Result: " x)))


  (raft-log/read-log-file (io/file (get-in a [:config :persist-dir]) "0.raft"))
  (raft-log/read-log-file (io/file (get-in b [:config :persist-dir]) "0.raft"))
  (raft-log/read-log-file (io/file (get-in c [:config :persist-dir]) "0.raft"))

  (raft-log/all-log-indexes "log/d")
  (raft-log/latest-log-index "log/d")

  a
  b
  c




  )


