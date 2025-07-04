(ns jepsen-raft.server
  "Embedded Raft server for Jepsen testing"
  (:require [fluree.raft :as raft]
            [clojure.edn :as edn]
            [clojure.tools.logging :refer [info error]])
  (:gen-class))

(defonce server-state (atom nil))

(defn state-machine
  "Simple key-value state machine for testing"
  [state-atom]
  (fn [entry _raft-state]
    (case (:f entry)
      :write (do (swap! state-atom assoc (:k entry) (:v entry))
                 true)
      :read (get @state-atom (:k entry))
      :cas (let [k (:k entry)
                 old-v (:old-v entry)
                 new-v (:new-v entry)]
             (if (= (get @state-atom k) old-v)
               (do (swap! state-atom assoc k new-v)
                   true)
               false))
      :delete (do (swap! state-atom dissoc (:k entry))
                  true))))

(defn send-rpc-fn
  "Network RPC implementation"
  [_server-config]
  (fn [server msg callback]
    ;; In a real implementation, this would send over the network
    ;; For now, we'll simulate network communication
    (info "Sending RPC to" server ":" msg)
    (when callback
      (callback nil))))

(defn snapshot-write
  [file state]
  (spit file (pr-str state)))

(defn snapshot-reify
  [state-atom]
  (fn []
    @state-atom))

(defn snapshot-install
  [state-atom]
  (fn [snapshot _index]
    (reset! state-atom snapshot)))

(defn snapshot-xfer
  [_snapshot _server]
  ;; Transfer snapshot to another server
  nil)

(defn snapshot-list-indexes
  [_dir]
  [])

(defn start-server
  "Start a Raft server with the given configuration"
  [config-file]
  (let [config (edn/read-string (slurp config-file))
        state-atom (atom {})
        raft-config (merge config
                           {:state-machine (state-machine state-atom)
                            :send-rpc-fn (send-rpc-fn config)
                            :leader-change-fn (fn [event]
                                                (info "Leader changed:" event))
                            :snapshot-write snapshot-write
                            :snapshot-reify (snapshot-reify state-atom)
                            :snapshot-install (snapshot-install state-atom)
                            :snapshot-xfer snapshot-xfer
                            :snapshot-list-indexes snapshot-list-indexes})
        raft-instance (raft/start raft-config)]
    (reset! server-state {:raft raft-instance
                          :state state-atom
                          :config config})
    (info "Raft server started with config:" config)
    raft-instance))

(defn stop-server
  "Stop the running Raft server"
  []
  (when-let [state @server-state]
    (raft/close (:raft state))
    (reset! server-state nil)
    (info "Raft server stopped")))

(defn -main
  "Main entry point for the server"
  [& args]
  (if-let [config-file (first args)]
    (do
      (info "Starting Raft server with config:" config-file)
      (start-server config-file)
      ;; Keep the server running
      (Thread/sleep Long/MAX_VALUE))
    (do
      (error "Usage: jepsen-raft.server <config-file>")
      (System/exit 1))))