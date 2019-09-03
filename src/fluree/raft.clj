(ns fluree.raft
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [fluree.raft.log :as raft-log]
            [fluree.raft.leader :as leader]
            [fluree.raft.events :as events]
            [fluree.raft.watch :as watch])
  (:import (java.util UUID)))

(defrecord RaftCommand [entry id timeout callback])

(defn event-chan
  "Returns event channel for the raft instance."
  [raft]
  (events/event-chan raft))

(defn logfile
  "Returns log file name for raft."
  [raft]
  (:log-file raft))


(defn invoke-rpc*
  "Like invoke-rpc, but takes just the event channel instead of
  the full raft instance."
  [event-channel operation data callback]
  (async/put! event-channel [operation data callback]))


(defn invoke-rpc
  "Call this with original raft config to invoke an incoming RPC command."
  [raft operation data callback]
  (invoke-rpc* (events/event-chan raft) operation data callback))


(defn close
  "Closes a raft process."
  [raft]
  (let [close-fn (get-in raft [:config :close-fn])]
    (async/close! (events/event-chan raft))
    (if (fn? close-fn)
      (close-fn)
      :closed)))


(defn event-loop
  "Launches an event loop where all state changes to the raft state happen.

  This means all state changes are single-threaded.

  Maintains appropriate timeouts (hearbeat if leader, or election timeout if not leader)
  to trigger appropriate actions when no activity happens between timeouts.


  Events include:
  - append-entries        - (follower) process and respond to append-entries events sent from the leader
  - request-vote          - (follower) process a request-vote request from a leader candidate
  - new-command           - (leader) processes a new command, will return result of operation after applied to state machine
  - new-command-timeout   - (leader) a new command timed out, remove callback from state
  - append-entry-response - (leader) process response to an append-entries event
  - request-vote-response - (candidate) process response to request-vote

  - raft-state            - provides current state of raft to a callback function provided.
  - close                 - gracefully closes down raft"
  [raft-state]
  (let [event-chan      (events/event-chan raft-state)
        command-chan    (get-in raft-state [:config :command-chan])
        init-timeout    (events/new-election-timeout raft-state)
        init-raft-state (assoc raft-state :timeout (async/timeout init-timeout)
                                          :timeout-ms init-timeout
                                          :timeout-at (+ init-timeout (System/currentTimeMillis)))]
    (async/go-loop [raft-state init-raft-state
                    last-stop (System/nanoTime)]            ;; start time of last operation
      (let [timeout-chan (:timeout raft-state)
            [event c] (async/alts! [event-chan command-chan timeout-chan] :priority true)
            [op data callback] (if (= c timeout-chan)       ;; timeout!
                                 [:timeout]
                                 event)
            timeout?     (= c timeout-chan)
            start-time   (System/nanoTime)]
        (log/debug (format "%s ..... raft event: %-25s idle: %10s timeout-in: %5sms timeout at: %s (%s) event-data: %s"
                           (str (java.time.Instant/now))
                           (str op)
                           (format "%.3fms" (double (/ (- start-time last-stop) 1e6)))
                           (str (- (:timeout-at raft-state) (System/currentTimeMillis)))
                           (:timeout-at raft-state)
                           (:timeout-ms raft-state)
                           data))
        (if
          (and (nil? event) (not timeout?))
          :raft-closed
          (let [raft-state*
                (try
                  (case op

                    :timeout
                    (if (leader/is-leader? raft-state)
                      (leader/queue-append-entries raft-state)
                      (leader/request-votes raft-state))

                    ;; returns current raft state to provided callback
                    :raft-state
                    (do (events/safe-callback callback raft-state)
                        raft-state)

                    ;; process and respond to append-entries event from leader
                    :append-entries
                    (events/append-entries-event raft-state data callback)

                    ;; response to append entry requests to external servers
                    :append-entries-response
                    (let [raft-state*     (leader/append-entries-response-event raft-state data)
                          new-commit      (leader/recalc-commit-index (:servers raft-state*))
                          updated-commit? (> new-commit (:commit raft-state))]

                      ;; if commits are updated, apply to state machine and send out new append-entries
                      (if updated-commit?
                        (-> raft-state*
                            (events/update-commits new-commit)
                            (leader/queue-append-entries))
                        raft-state*))

                    ;; append a new log entry to get committed to state machine - only done by leader
                    :new-command
                    (if (leader/is-leader? raft-state)
                      ;; leader. Drain all commands and process together.
                      (let [all-commands (events/into-chan [event] command-chan)]
                        (leader/new-command-event raft-state all-commands))
                      ;; not leader
                      (do
                        (events/safe-callback callback (ex-info "Server is not currently leader."
                                                                {:operation :new-command
                                                                 :error     :raft/not-leader}))
                        raft-state))

                    ;; a command timed out, remove from state
                    :new-command-timeout
                    (update raft-state :command-callbacks dissoc data)

                    ;; registers a callback for a pending command which will be called once committed to the state machine
                    ;; this is used by followers to get a callback when a command they forward to a leader gets committed
                    ;; to local state
                    :register-callback
                    (let [[command-id timeout] data]
                      (events/register-callback-event raft-state command-id timeout callback))

                    :request-vote
                    (let [new-raft (events/request-vote-event raft-state data callback)]
                      (if (true? (:trigger-request-vote new-raft))
                        ;; special case where slow consumer triggered vote and we are the last leader,
                        ;; try to regain leadership without waiting for next timeout
                        (-> new-raft
                            (dissoc :trigger-request-vote)
                            (leader/request-votes))
                        new-raft))

                    ;; response for request-vote requests - may become leader if enough votes received
                    :request-vote-response
                    (leader/request-vote-response-event raft-state data)

                    ;; when we kick off a snapshot process asynchronously, the callback will update
                    ;; raft state that we have a new snapshot
                    :snapshot
                    (let [[snapshot-index snapshot-term] data]
                      (log/debug (format "Snapshot complete at index %s, term %s. Raft state last snapshot at %s."
                                         snapshot-index snapshot-term (:snapshot-index raft-state)))
                      (if (<= snapshot-index (:snapshot-index raft-state))
                        ;; in case callback triggered multiple times, ignore
                        raft-state
                        (-> raft-state
                            (assoc :snapshot-index snapshot-index
                                   :snapshot-term snapshot-term
                                   :snapshot-pending nil)
                            (raft-log/rotate-log))))

                    ;; received by follower once at end of log to install leader's latest snapshot
                    :install-snapshot
                    (events/install-snapshot raft-state data callback)

                    ;; response received by leader to an install-snapshot event
                    :install-snapshot-response
                    (leader/install-snapshot-response-event raft-state data)

                    ;; registers a listen function that will get called with every new command (for monitoring). Call with nil to remove.
                    :monitor
                    (cond
                      (fn? data) (assoc raft-state :monitor-fn data)
                      (nil? data) (dissoc raft-state :monitor-fn)
                      :else (do
                              (log/error "Called raft :listen with arg that was not a function: "
                                         (pr-str data) ". Ignoring call.")
                              raft-state))


                    ;; close down all pending callbacks
                    :close
                    (let [callback-chans (vals (:command-callbacks raft-state))]
                      (doseq [c callback-chans]
                        (async/put! c (ex-info "Raft server shut down." {:operation :new-command
                                                                         :error     :raft/shutdown})))
                      (events/safe-callback callback :raft-closed)
                      raft-state))
                  (catch Exception e (throw (ex-info (str "Raft error processing command: " op)
                                                     {:data       data
                                                      :raft-state raft-state} e))))]
            (log/trace {:op     op
                        :inst   (System/currentTimeMillis)
                        :time   (format "%.3fms" (double (/ (- (System/nanoTime) start-time) 1e6)))
                        :event  [op data]
                        :before (dissoc raft-state :config :log-file :watch-fns :timeout)
                        :after  (dissoc raft-state* :config :log-file :watch-fns :timeout)})
            (when (not= :close op)
              (-> raft-state*
                  (events/call-monitor-fn op data raft-state start-time)
                  (events/send-queued-messages)
                  (recur (System/nanoTime))))))))))


(defn register-callback
  "Registers a callback for a command with specified id."
  [raft command-id timeout-ms callback]
  (let [event-chan (event-chan raft)]
    (async/put! event-chan [:register-callback [command-id timeout-ms] callback])))


(defn new-command
  "Issues a new RaftCommand (leader only) to create a new log entry."
  ([raft command] (new-command raft command nil))
  ([raft command persist-callback]
   (assert (instance? RaftCommand command))
   (let [command-chan (get-in raft [:config :command-chan])]
     (async/put! command-chan [:new-command command persist-callback]))))


(defn new-entry
  "Creates a new log entry (leader only). Generates a RaftCommand and submits it for processing."
  ([raft entry callback]
   (let [timeout (or (get-in raft [:config :default-command-timeout]) 5000)]
     (new-entry raft entry callback timeout)))
  ([raft entry callback timeout-ms]
   (assert (pos-int? timeout-ms))
   (let [id      (str (UUID/randomUUID))
         command (map->RaftCommand {:entry    entry
                                    :id       id
                                    :timeout  timeout-ms
                                    :callback callback})]
     (new-command raft command nil))))


(defn view-raft-state
  "Polls raft loop and returns state to provided callback."
  [raft callback]
  (let [event-chan (event-chan raft)]
    (async/put! event-chan [:raft-state nil callback])))


(defn monitor-raft
  "Debugging tool, registers a single-argument callback fn that will be
  called with each new raft event. To remove existing listen-fn, provide
  'nil' instead of function.

  Callback argument is a map with keys:
  - event  - event data called as a three tuple [operation data callback]
  - time   - time that event took to process (locally)
  - before - raft state before command
  - after  - raft state after command"
  [raft callback]
  (let [event-chan (event-chan raft)]
    (async/put! event-chan [:monitor callback])))


(defn- initialize-raft-state
  [raft-state]
  (try
    (let [{:keys [log-directory snapshot-reify]} (:config raft-state)
          latest-log       (raft-log/latest-log-index log-directory)
          latest-log-file  (io/file log-directory (str latest-log ".raft"))
          log-entries      (try (raft-log/read-log-file latest-log-file)
                                (catch java.io.FileNotFoundException _ nil))
          raft-state*      (reduce
                             (fn [raft-state* entry]
                               (let [[index term entry-type data] entry]
                                 (cond
                                   (> index 0)
                                   (assoc raft-state* :index index :term term)

                                   (= :current-term entry-type)
                                   (assoc raft-state* :term term
                                                      :voted-for nil)

                                   (= :voted-for entry-type)
                                   (if (= term (:term raft-state*))
                                     (assoc raft-state* :voted-for data)
                                     (assoc raft-state* :voted-for nil))

                                   (= :snapshot entry-type)
                                   (assoc raft-state* :snapshot-index data
                                                      :snapshot-term term)

                                   (= :no-op entry-type)
                                   raft-state*)))
                             raft-state log-entries)
          snapshot-index   (when (pos-int? (:snapshot-index raft-state*))
                             (:snapshot-index raft-state*))
          snapshot-loaded? (when snapshot-index             ;; if a snapshot exists, reify it into the state-machine
                             (try
                               (snapshot-reify snapshot-index)
                               true
                               (catch Exception e
                                 (log/error e (str "Error reifying snapshot index: " snapshot-index))
                                 false)))]
      (if (and snapshot-index (not snapshot-loaded?))
        raft-state
        (cond-> (assoc raft-state* :log-file latest-log-file)
                snapshot-index (assoc :index (max (:index raft-state*) snapshot-index)
                                      :commit snapshot-index))))
    (catch Exception e (log/error e "Error initializing raft state from logs in: "
                                  (-> raft-state :config :log-directory)))))


(defn start
  [config]
  (let [{:keys [this-server servers timeout-ms heartbeat-ms
                log-history snapshot-threshold log-directory state-machine
                snapshot-write snapshot-xfer snapshot-install snapshot-reify
                send-rpc-fn default-command-timeout close-fn
                leader-change-fn                            ;; optional, single-arg fn called each time there is a leader change with current raft state. Current leader (or null) is in key :leader
                event-chan command-chan
                entries-max entry-cache-size]
         :or   {timeout-ms              500                 ;; election timeout, good range is 10ms->500ms
                heartbeat-ms            100                 ;; heartbeat time in milliseconds
                log-history             10                  ;; number of historical log files to retain
                snapshot-threshold      100                 ;; number of log entries since last snapshot (minimum) to generate new snapshot
                default-command-timeout 4000
                log-directory           "raftlog/"
                event-chan              (async/chan)
                command-chan            (async/chan)
                entries-max             25                  ;; maximum number of entries we will send at once to any server
                }} config
        _          (assert (fn? state-machine))
        _          (assert (fn? snapshot-write))
        _          (assert (fn? snapshot-reify))
        _          (assert (fn? snapshot-install))
        _          (assert (fn? snapshot-xfer))

        config*    (assoc config :timeout-ms timeout-ms
                                 :heartbeat-ms heartbeat-ms
                                 :log-directory log-directory
                                 :send-rpc-fn send-rpc-fn
                                 :log-history log-history
                                 :snapshot-threshold snapshot-threshold
                                 :state-machine state-machine
                                 :snapshot-write snapshot-write
                                 :snapshot-xfer snapshot-xfer
                                 :snapshot-reify snapshot-reify
                                 :snapshot-install snapshot-install
                                 :event-chan event-chan
                                 :command-chan command-chan
                                 :close close-fn
                                 :leader-change leader-change-fn
                                 :default-command-timeout default-command-timeout
                                 :entries-max entries-max
                                 :entry-cache-size (or entry-cache-size entries-max) ;; we keep a local cache of last n entries, by default size of entries-max. Performance boost as most recent entry access does not require io
                                 )
        _          (log/debug "Raft starting with config: " (pr-str config*))
        raft-state (-> {:id               (rand-int 100000) ;; opaque id, in case multiple raft processes are going
                        :watch-fns        (atom (if leader-change-fn
                                                  {::default {:fn leader-change-fn :event-type nil}}
                                                  {}))
                        :config           config*
                        :this-server      this-server
                        :other-servers    (into [] (filter #(not= this-server %) servers))
                        :status           nil               ;; candidate, leader, follower
                        :leader           nil               ;; current known leader
                        :log-file         (io/file log-directory "0.raft")
                        :term             0                 ;; latest term
                        :index            0                 ;; latest index
                        :snapshot-index   0                 ;; index point of last snapshot
                        :snapshot-term    0                 ;; term of last snapshot
                        :snapshot-pending nil               ;; holds pending commit if snapshot was requested
                        :commit           0                 ;; commit point in index
                        :voted-for        nil               ;; for the :term specified above, who we voted for

                        ;; map of servers participating in consensus. server id is key, state of server is val
                        :servers          (reduce #(assoc %1 %2 events/server-state-baseline) {} servers) ;; will be set up by leader/reset-server-state
                        :msg-queue        nil               ;; holds outgoing messages
                        }
                       (initialize-raft-state))]
    (log/debug "Raft initialized state: " (pr-str raft-state))
    (event-loop raft-state)
    raft-state))


(defn add-leader-watch
  "Registers a function to be called with each leader change. Specify any key
  which can be used to unregister function later.

  Function is called only when the leader change is of specified event-type, either
  :become-leader or :become-follower. To get called for all changes, specify an event-type
  of nil (default).

  Function is a single-argument function that is called with a map that contains information
  related to the leadership change. Keys of the map include:
  - :key            - original key the function was registered with
  - :event          - values will be either :become-follower or :become-leader
  - :cause          - Keyword for the cause of the event, namely the raft action that precipated the change
  - :message        - String with a nice message explaining the cause
  - :old-leader     - the leader before this change (will be nil or leader's name)
  - :new-leader     - leader after this change (will be nil or leader's name)
  - :old-raft-state - raft state right before the change
  - :new-raft-state - raft state after the change
  - :server         - this server's name

  Important! Function is called synchronously, and therefore RAFT is stopped while processing.
  If function requires raft calls, it *must* be run asynchronously.
  Good to run asynchronously for anything that might be slow.

  If key is already in use, overwrites existing watch function with fn."
  ([raft key fn] (watch/add-leader-watch raft key fn nil))
  ([raft key fn event-type]
   (watch/add-leader-watch raft key fn event-type)))


(defn remove-leader-watch
  "Removes watch function with specified key."
  [raft key]
  (watch/remove-leader-watch raft key))