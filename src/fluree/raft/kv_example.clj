(ns fluree.raft.kv-example
  (:require [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [clojure.core.async :as async]
            [fluree.raft :as raft]
            [clojure.tools.logging :as log]
            [fluree.raft.log :as raft-log])
  (:refer-clojure :exclude [read])
  (:import (java.util UUID)
           (java.io File)))


(defn snapshot-xfer
  "Transfers snapshot from this server as leader, to a follower.
  Will be called with two arguments, snapshot id and part number.
  Initial call will be for part 1, and subsequent calls, if necessary,
  will be for each successive part.

  Must return a snapshot with the following fields
  :parts - how many parts total
  :data - snapshot data

  If multiple parts are returned, additional requests for each part will be
  requested. A snapshot should be broken into multiple parts if it is larger than
  the amount of data you want to push across the network at once."
  [path]
  (fn [id part]
    ;; in this example we do everything in one part, regardless of snapshot size
    (let [file (io/file path (str id ".snapshot"))
          ba   (byte-array (.length file))
          is   (io/input-stream file)]
      (.read is ba)
      (.close is)
      {:parts 1
       :data  ba})))


(defn snapshot-installer
  "Installs a new snapshot being sent from a different server.
  Blocking until write succeeds. An error will stop RAFT entirely.

  If snapshot-part = 1, should first delete any existing file if it exists (possible to have historic partial snapshot lingering).

  As soon as final part write succeeds, can safely garbage collect any old snapshots on disk except the most recent one."
  [path]
  (fn [snapshot-map]
    (let [{:keys [leader-id snapshot-term snapshot-index snapshot-part snapshot-parts snapshot-data]} snapshot-map
          file (io/file path (str snapshot-index ".snapshot"))]

      (when (= 1 snapshot-part)
        ;; delete any old file if exists
        (io/make-parents file)
        (io/delete-file file true))

      (with-open [out (io/output-stream file :append true)]
        (.write out ^bytes snapshot-data)))))


(defn snapshot-reify
  "Reifies a snapshot, should populate whatever data is needed into an initialized state machine
  that is used for raft.

  Called with snapshot-id to reify, which corresponds to the commit index the snapshot was taken.
  Should throw if snapshot not found, or unable to parse. This will stop raft."
  [path state-atom]
  (fn [snapshot-id]
    (let [file  (io/file path (str snapshot-id ".snapshot"))
          state (nippy/thaw-from-file file)]
      (reset! state-atom state))))


(defn snapshot-writer
  "Blocking until write succeeds. An error will stop RAFT entirely."
  [path state-atom]
  (fn [id callback]
    (let [state @state-atom
          file  (io/file path (str id ".snapshot"))]
      (io/make-parents file)
      (future
        (nippy/freeze-to-file file state)
        (callback)))))


(defn snapshot-list-indexes
  "Lists all stored snapshot indexes, sorted ascending. Used for bootstrapping a
  raft network from a previously made snapshot."
  [path]
  (fn []
    (-> path
        (raft-log/all-log-indexes "snapshot")
        sort
        vec)))


(defn state-machine
  "Basic key-val store.

  Operations are tuples that look like:
  [operation key val compare-val]

  Operations supported are:
  - :write  - Writes a new value to specified key. Returns true on success.
              i.e. [:write 'mykey' 42]
  - :read   - Reads value of provided key. Returns nil if value doesn't exist.
              i.e. [:read 'mykey']
  - :delete - Deletes value at specified key. Returns true if successful, or
              false if key doesn't exist. i.e. [:delete 'mykey']
  - :cas    - Compare and swap. Compare current value of key with v and if equal,
              swap val to cas-v. Returns true on success and false on failure.
              i.e. [:cas 'mykey' 100 42] - swaps 'mykey' to 42 if current val is 100."
  [state-atom]
  (fn [[op k v cas-v] raft-state]
    (case op
      :write (do (swap! state-atom assoc k v)
                 true)
      :read (get @state-atom k)
      :delete (if (contains? @state-atom k)
                (do (swap! state-atom dissoc k)
                    true)
                false)
      :cas (if (contains? @state-atom k)
             (let [new-state (swap! state-atom
                                    (fn [state]
                                      (if (= v (get state k))
                                        (assoc state k cas-v)
                                        state)))]
               (= cas-v (get new-state k)))
             false))))


;; will hold state of our started raft instances
(def system nil)


(defn send-rpc
  "Sends rpc call to specified server.
  Includes a resp-chan that will eventually contain a response."
  [raft server operation data callback]
  ;; if system was shut down, there will not be a server-chan... no-op on send-rpc call
  (when-let [server-chan (get-in system [server :rpc-chan])]
    (let [resp-chan   (async/promise-chan)
          this-server (:this-server raft)
          msg-id      (str (UUID/randomUUID))
          header      {:op        operation
                       :from      this-server
                       :to        server
                       :msg-id    msg-id
                       :resp-chan resp-chan}]
      (async/put! server-chan [header data])
      ;; wait for response and call back
      ;; for now a single response channel is created for each request, so we need to match up requests/responses
      (async/go (let [response (async/<! resp-chan)
                      [header data] response]
                  ;; tiny delay receiving message
                  (async/<! (async/timeout 5))
                  (log/trace (str this-server " - send rpc resp: "
                                  {:header   (select-keys header [:op :from :to])
                                   :data     data
                                   :response response}))
                  (callback data)))
      resp-chan)))


(defn monitor-incoming-rcp
  "Receives incoming commands from external servers.
  In this case rpc-chan is called directly, but could originate from a tcp socket, websocket, etc."
  [event-chan rpc-chan this-server]
  (async/go-loop []
    (let [rpc (async/<! rpc-chan)]
      ;; tiny delay receiving message
      (async/<! (async/timeout 5))
      (if (nil? rpc)
        (log/warn "RPC channel closed for server:" this-server)
        (let [[header data] rpc
              {:keys [op resp-chan]} header
              resp-header (assoc header :op (keyword (str (name op) "-response"))
                                        :to (:from header)
                                        :from (:to header))
              callback    (fn [x]
                            (log/trace this-server "OP Callback: " {:op op :request data :response x})
                            (async/put! resp-chan [resp-header x]))]
          (log/trace (str this-server " - incoming RPC: ") header data)
          (raft/invoke-rpc* event-chan op data callback)
          (recur))))))


(defn close-fn
  "Close function cleans up local state."
  [rpc-chan this-server]
  (fn []
    (async/close! rpc-chan)
    (alter-var-root #'system (fn [sys] (dissoc sys this-server)))
    ::closed))


(defn start-instance
  [servers server-id]
  (let [rpc-chan           (async/chan)
        state-machine-atom (atom {})
        log-directory      (str "raftlog/" (name server-id) "/")
        snapshot-dir       (str log-directory "snapshots/")
        raft               (raft/start {:this-server           server-id
                                        :leader-change-fn      (fn [x] (log/info
                                                                         (str server-id " reports leader change to: "
                                                                              (:leader x) " term: " (:term x))))
                                        :servers               servers
                                        :timeout-ms            1500
                                        :heartbeat-ms          500
                                        :send-rpc-fn           send-rpc
                                        :log-directory         log-directory
                                        :log-history           3
                                        :close-fn              (close-fn rpc-chan server-id)
                                        :state-machine         (state-machine state-machine-atom)
                                        :snapshot-write        (snapshot-writer snapshot-dir state-machine-atom)
                                        :snapshot-reify        (snapshot-reify snapshot-dir state-machine-atom)
                                        :snapshot-xfer         (snapshot-xfer snapshot-dir)
                                        :snapshot-install      (snapshot-installer snapshot-dir)
                                        :snapshot-list-indexes (snapshot-list-indexes snapshot-dir)})]
    (monitor-incoming-rcp (raft/event-chan raft) rpc-chan server-id)
    {:raft       raft
     :state-atom state-machine-atom
     :rpc-chan   rpc-chan}))


(defn launch-raft-system
  "Launches supplied number of raft instances. Stores their state and other data
  in the 'system' var that can be used to poke around, inquire about state, etc."
  [instances]
  (let [servers (mapv (comp keyword str) (range 1 (inc instances)))
        sys     (reduce (fn [acc server] (assoc acc server (start-instance servers server)))
                        {} servers)]
    (alter-var-root #'system (constantly sys))
    :started))


(defn view-raft-state
  "Displays current raft state for specified server."
  ([server] (view-raft-state server (fn [x] (clojure.pprint/pprint (dissoc x :config)))))
  ([server callback]
   (let [server     (if (keyword? server) server (keyword (str server)))
         raft       (get-in system [server :raft])
         event-chan (raft/event-chan raft)]
     (async/put! event-chan [:raft-state nil callback]))))

(defn random-server
  "Picks a random server in the raft system."
  [system]
  (rand-nth (keys system)))


(defn get-leader
  "Returns leader according to specified server."
  ([] (get-leader (random-server system)))
  ([server]
   (let [promise-chan (async/promise-chan)
         callback     (fn [resp] (if-let [leader (:leader resp)]
                                   (async/put! promise-chan leader)
                                   (async/close! promise-chan)))]
     (view-raft-state server callback)
     (async/<!! promise-chan))))


(defn rpc-async
  "Performs  rpc call to specified server, returns core async channel."
  [server entry]
  (let [raft         (get-in system [server :raft])
        promise-chan (async/promise-chan)
        callback     (fn [resp] (if (nil? resp)
                                  (async/close! promise-chan)
                                  (async/put! promise-chan resp)))]
    (raft/new-entry raft entry callback)
    promise-chan))


(defn rpc-sync
  "Performs a synchronous rpc call to specified server."
  [server entry]
  (async/<!! (rpc-async server entry)))


(defn write
  "Writes value to specified key."
  ([k v] (write (get-leader (random-server system)) k v))
  ([server k v]
   (rpc-sync server [:write k v])))


(defn write-async
  "Writes value to specified key, returns core async chan with eventual response."
  ([k v] (write (get-leader (random-server system)) k v))
  ([server k v]
   (rpc-async server [:write k v])))


(defn read
  "Reads from leader after all pending commands are committed."
  ([k] (read (get-leader (random-server system)) k))
  ([server k]
   (rpc-sync server [:read k])))


(defn dump-state
  "Dumps our full state machine state for given server"
  [server]
  (let [server     (if (keyword? server) server (keyword (str server)))
        state-atom (get-in system [server :state-atom])]
    @state-atom))


(defn read-local
  "Reads key from local state, doesn't sync across raft"
  ([k] (read-local (rand-nth (keys system)) k))
  ([server k]
   (get (dump-state server) k)))


(defn cas
  "Compare and swap"
  ([k compare swap] (cas (get-leader (random-server system)) k compare swap))
  ([server k compare swap]
   (rpc-sync server [:cas k compare swap])))


(defn delete
  "Delete key"
  ([k] (delete (get-leader (random-server system)) k))
  ([server k]
   (rpc-sync server [:delete k])))


(defn close
  "Closes specified server."
  [system server]
  (let [server (if (keyword? server) server (keyword (str server)))
        raft   (get-in system [server :raft])]
    (raft/close raft)
    :closed))


(comment

  ;; specify number of raft servers to launch
  (launch-raft-system 5)

  ;; view current raft state of a server
  (view-raft-state 1)

  ;; get the current leader as per specified server
  (get-leader 1)

  ;; same as above, but picks random server to inquire
  (let [server (random-server system)]
    (println "Using server:" server)
    (get-leader server))

  ;; assoc a key (sends to leader)
  (write "testkey" "testval")

  ;; write a bunch of commands (must use async to get them to process simultaneously)
  (let [leader (get-leader)]
    (dotimes [i 100]
      (write-async leader (str "key-" i) (str "val-" i))))


  ;; read a key, synchronized across servers (sends to leader)
  ;; will only read after all pending commands are processed
  (read "testkey")

  ;; read from local state immediately - specify any server (or omit for random server)
  (read-local 1 "testkey")

  ;; dump out entire state machine current state (local)
  (dump-state 1)

  ;; same command, but random server
  (let [server (random-server system)]
    (println "Using server:" server)
    (read-local server "testkey"))

  ;; compare and swap (sends to leader). Only changes key's val to last arg if second arg matches existing val
  (cas "testkey" "testval" "changed!")

  ;; delete a key (sends to leader)
  (delete "testkey")

  ;; shut down a server
  (close system 4))

