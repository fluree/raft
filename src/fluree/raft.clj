(ns fluree.raft
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [fluree.raft.log :as raft-log]
            [fluree.raft.leader :as leader])
  (:import (java.util UUID)))

(defrecord RaftCommand [entry id timeout callback])

(defn event-chan
  "Returns event channel for the raft instance."
  [raft]
  (get-in raft [:config :event-chan]))

(defn logfile
  "Returns log file for raft."
  [raft]
  (:log-file raft))


(defn default-handler
  [raft operation data callback]
  (async/put! (event-chan raft) [operation data callback]))


(defn invoke-rpc*
  "Like invoke-rpc, but takes just the event channel instead of
  the full raft instance."
  [event-channel operation data callback]
  (async/put! event-channel [operation data callback]))


(defn invoke-rpc
  "Call this with original raft config to invoke an incoming RPC command."
  [raft operation data callback]
  (invoke-rpc* (event-chan raft) operation data callback))


(defn close
  "Closes a raft process."
  [raft]
  (let [close-fn (get-in raft [:config :close-fn])]
    (async/close! (event-chan raft))
    (if (fn? close-fn)
      (close-fn)
      :closed)))


(defn- request-vote-event
  "Grant vote to server requesting leadership if:
  - proposed term is >= current term
  - we haven't already voted for someone for this term
  - log index + term is at least as far as our log
  "
  [raft-state args callback]
  (let [{:keys [candidate-id last-log-index last-log-term]} args
        proposed-term    (:term args)
        {:keys [index term log-file voted-for snapshot-index snapshot-term]} raft-state
        my-last-log-term (cond
                           (= 0 index) 0
                           (> last-log-index snapshot-index) (raft-log/index->term log-file index)
                           (= last-log-index snapshot-index) snapshot-term
                           :else nil)

        reject-vote?     (or (< proposed-term term)         ;; request is for an older term
                             (and (= proposed-term term)    ;; make sure we haven't already voted for someone in this term
                                  (not (nil? voted-for)))
                             (nil? my-last-log-term)        ;; old index we don't have any longer
                             (< last-log-term my-last-log-term) ;; if log term is older, reject
                             (and (= last-log-term my-last-log-term) ;; if log term is same, my index must not be longer
                                  (< last-log-index index)))

        response         (if reject-vote?
                           {:term         (max term proposed-term)
                            :vote-granted false
                            :reason       (cond (< proposed-term term)
                                                (format "Proposed term %s is less than current term %s." proposed-term term)

                                                (and (= proposed-term term) ;; make sure we haven't already voted for someone in this term
                                                     (not (nil? voted-for)))
                                                (format "Already voted for %s in term %s." voted-for proposed-term)

                                                (nil? my-last-log-term)
                                                (format "Last log index %s is older than our most recent snapshot index: %s." last-log-index snapshot-index)

                                                (< last-log-term my-last-log-term)
                                                (format "For index %s, provided log term of %s is less than our log term of %s." last-log-index last-log-term my-last-log-term)

                                                (and (= last-log-term my-last-log-term) ;; if log term is same, my index must not be longer
                                                     (< last-log-index index))
                                                (format "For index %s the terms are the same: %s, but our index is longer: %s." last-log-index last-log-term index)
                                                )}
                           {:term proposed-term :vote-granted true})
        raft-state*      (if reject-vote?
                           (assoc raft-state :term (max term proposed-term))
                           (do
                             (raft-log/write-current-term (:log-file raft-state) proposed-term)
                             (raft-log/write-voted-for (:log-file raft-state) proposed-term candidate-id)
                             (assoc raft-state :term proposed-term
                                               :voted-for candidate-id
                                               :status :follower
                                               ;; reset timeout if we voted so as to not possibly immediately start a new election
                                               :timeout (async/timeout (leader/new-election-timeout raft-state)))))]
    (callback response)
    raft-state*))


(defn- update-commits
  "Process new commits if leader-commit is updated.
  Put commit results on callback async channel if present.
  Update local raft state :commit"
  [raft-state leader-commit]
  (if (= (:commit raft-state) leader-commit)
    raft-state                                              ;; no change
    (let [{:keys [commit snapshot-index config snapshot-pending]} raft-state
          {:keys [state-machine snapshot-threshold snapshot-write]} config
          commit-entries    (raft-log/read-entry-range (:log-file raft-state) (inc commit) leader-commit)
          command-callbacks (:command-callbacks raft-state)
          trigger-snapshot? (and (>= commit (+ snapshot-index snapshot-threshold))
                                 (not snapshot-pending))
          snapshot-pending  (if trigger-snapshot?
                              commit
                              snapshot-pending)]
      ;; trigger new snapshot if necessary before applying new commits
      ;; a well behaved snapshot writer will perform this asynchronously, so
      ;; we can continue to move forward.
      (when trigger-snapshot?
        (let [term-at-commit    (raft-log/index->term (:log-file raft-state) commit)
              snapshot-callback (fn [& _] (async/put! (event-chan raft-state) [:snapshot [commit term-at-commit]]))]
          (snapshot-write commit snapshot-callback)))
      (assoc raft-state :commit leader-commit
                        :snapshot-pending snapshot-pending
                        :command-callbacks (reduce
                                             (fn [callbacks entry-map]
                                               (let [resp (state-machine (:entry entry-map) raft-state)]
                                                 (if-let [callback-chan (get callbacks (:id entry-map))]
                                                   (do
                                                     (if (nil? resp)
                                                       (async/close! callback-chan)
                                                       (async/put! callback-chan resp))
                                                     (dissoc callbacks (:id entry-map)))
                                                   callbacks)))
                                             command-callbacks commit-entries)))))


(defn- append-entries-event
  [raft-state args callback]
  (if (< (:term args) (:term raft-state))
    ;; old term!
    (do
      (callback {:term (:term raft-state) :success false})
      raft-state)
    ;; current or newer term
    (let [{:keys [leader-id prev-log-index prev-log-term entries leader-commit]} args
          proposed-term          (:term args)
          {:keys [term index snapshot-index leader]} raft-state
          term-at-prev-log-index (cond
                                   (= 0 prev-log-index) 0

                                   (= prev-log-index snapshot-index)
                                   (:snapshot-term raft-state)

                                   (<= prev-log-index index)
                                   (raft-log/index->term (:log-file raft-state) prev-log-index)

                                   (< index prev-log-index) nil ;; we don't even have this index
                                   )
          new-leader?            (or (> proposed-term term) (not= leader-id leader))
          logs-match?            (= prev-log-term term-at-prev-log-index)
          new-timeout            (async/timeout (leader/new-election-timeout raft-state))
          raft-state*            (cond-> (assoc raft-state :timeout new-timeout)

                                         ;; leader's term is newer, update leader info
                                         new-leader?
                                         (#(do
                                             (when (> index prev-log-index)
                                               ;; it is possible we have log entries after the leader's latest, remove them
                                               (raft-log/remove-entries (:log-file %) (inc prev-log-index)))
                                             (leader/become-follower % proposed-term leader-id)))

                                         ;; we have a log match at prev-log-index
                                         (and logs-match? (not-empty entries))
                                         (#(let [new-index (+ prev-log-index (count entries))]
                                             (if (or (= index prev-log-index) new-leader?)

                                               ;; new entries, so add. If new leader, possibly over-write existing entries
                                               (raft-log/append (:log-file %) entries prev-log-index index)

                                               ;; Possibly new entries and no leader change.
                                               ;; At least some of these entries duplicate ones already received.
                                               ;; Happens when round-trip response doesn't complete prior to new updates being sent
                                               (let [new-entries-n (- new-index index)
                                                     new-entries   (take-last new-entries-n entries)]
                                                 (when new-entries
                                                   (raft-log/append (:log-file %) new-entries index index))))

                                             ;; as an optimization, we will cache last entry as it will likely be requested next
                                             (raft-log/assoc-index->term-cache new-index (:term (last entries)))

                                             (assoc % :index new-index)))


                                         ;; we have an entry at prev-log-index, but doesn't match term, remove offending entries
                                         (and (not logs-match?) term-at-prev-log-index)
                                         (#(do
                                             (raft-log/remove-entries (:log-file %) prev-log-index)
                                             (assoc % :index (dec prev-log-index))))

                                         ;; Check if commit is newer and process into state machine if needed
                                         logs-match?
                                         (update-commits leader-commit))
          response               (cond
                                   logs-match?
                                   {:term proposed-term :success true}

                                   :else
                                   {:term proposed-term :success false})]

      (callback response)
      raft-state*)))


(defn- safe-callback
  "Executes a callback in a way that won't throw an exception."
  [callback data]
  (when (fn? callback)
    (try (callback data)
         (catch Exception e
           (log/error e "Callback failed. Called with data: " (pr-str data))
           nil))))


(defn- into-chan
  "Conjoins all available entries in channel to supplied collection."
  [coll c]
  (async/<!!
    (async/go-loop [acc coll]
      (let [[next _] (async/alts! [c] :default ::drained)]
        (if (= ::drained next)
          acc
          (recur (conj acc next)))))))


(defn- install-snapshot
  "Installs a new snapshot, does so in parts.
  Parts should always come serially, so can just append new part.
  Once complete, it is registered, until then it is not referenced and therefore will not be accessed by raft.

  It is possible a new leader can be elected prior to finishing the last leader's snapshot. If so, we will be
  receiving a new snapshot from the new leader, and can abandon this effort. Because of this, it cannot be guaranteed
  that historical snapshots on disk are complete - only trust the one official registered snapshot in the raft-state.

  It is a good practice when receiving part 1 to delete any existing file if exists. It is possible two leaders have same
  snapshot index and one interrupted process can exist while a new one starts, inadvertently concatenating two snapshots.
  "
  [raft-state snapshot-map callback]
  (let [{:keys [snapshot-term snapshot-index snapshot-part snapshot-parts]} snapshot-map
        {:keys [term index config]} raft-state
        {:keys [snapshot-install snapshot-reify]} config
        proposed-term (:term snapshot-map)
        old-term?     (< proposed-term term)
        old-snapshot? (>= index snapshot-index)             ;; a stale/old request, we perhaps have a new leader and newer index already
        done?         (= snapshot-part snapshot-parts)
        raft-state*   (if (and done? (not old-snapshot?))
                        (assoc raft-state :index snapshot-index
                                          :commit snapshot-index
                                          :snapshot-index snapshot-index
                                          :snapshot-term snapshot-term)
                        raft-state)
        response      (if (or old-term? done? old-snapshot?)
                        {:term term :next-part nil}
                        {:term term :next-part (inc snapshot-part)})]
    ;; wait until everything is durably stored
    (when-not (or old-term? old-snapshot?)
      (snapshot-install snapshot-map))

    (when (and done? (not old-snapshot?))
      ;; reify new snapshot - if this fails we want to crash and not write a record of this new snapshot to log
      (snapshot-reify snapshot-index)

      ;; write record of snapshot into log, reify succeeded
      (raft-log/write-snapshot (:log-file raft-state) snapshot-index snapshot-term))

    ;; send response back to leader
    (callback response)

    ;; rotate and clean up logs if done with snapshot
    (if (and done? (not old-snapshot?))
      (raft-log/rotate-log raft-state*)
      raft-state*)))


(defn- register-callback-event
  "Registers a single-arg callback to be executed once a command with specified id is committed to state
  machine with the result the state machine replies."
  [raft-state command-id timeout-ms callback]
  (let [resp-chan (async/promise-chan)
        timeout   (or timeout-ms 5000)]
    ;; launch a go channel with timeout to ensure callback is always called and cleared from state
    (async/go
      (try
        (let [timeout-chan (async/timeout timeout)
              [resp c] (async/alts! [resp-chan timeout-chan])
              timeout?     (= timeout-chan c)]
          (if timeout?
            (do
              (async/put! (event-chan raft-state) [:new-command-timeout command-id]) ;; clears callback from raft-state
              (safe-callback callback (ex-info "Command timed out." {:operation :new-command
                                                                     :error     :raft/command-timeout
                                                                     :id        command-id})))
            (safe-callback callback resp)))
        (catch Exception e (log/error e) (throw e))))
    ;; register command id to raft-state for use when processing new commits to state machine
    (assoc-in raft-state [:command-callbacks command-id] resp-chan)))


(defn- new-command-event
  "Processes new commands. Only happens if currently a raft leader."
  [raft-state command-events]
  (let [this-server (:this-server raft-state)
        raft-state* (loop [[cmd-event & r] command-events
                           raft-state raft-state]
                      (let [[_ command persist-callback] cmd-event
                            {:keys [entry id timeout callback]} command
                            new-index    (inc (:index raft-state))
                            log-entry    {:term (:term raft-state) :entry entry :id id}
                            _            (raft-log/write-new-command (:log-file raft-state) new-index log-entry)
                            raft-state*  (-> raft-state
                                             (assoc :index new-index)
                                             ;; match-index majority used for updating leader-commit
                                             (assoc-in [:servers this-server :match-index] new-index))
                            raft-state** (if (fn? callback)
                                           (register-callback-event raft-state* id timeout callback)
                                           raft-state*)]
                        (safe-callback persist-callback true)
                        (if r
                          (recur r raft-state**)
                          raft-state**)))]
    (leader/queue-append-entries raft-state*)))


(defn- call-monitor-fn
  "Internal - calls monitor function safely and calcs time."
  [event state-before state-after start-time-ns]
  (log/trace {:op      (first event)
              :time    (format "%.3fms" (double (/ (- (System/nanoTime) start-time-ns) 1e6)))
              :data    (second event)
              :before  (dissoc state-before :config)
              :after   (dissoc state-after :config)
              :instant (System/currentTimeMillis)})
  (when-let [monitor-fn (:monitor-fn state-after)]
    (safe-callback monitor-fn {:time    (format "%.3fms" (double (/ (- (System/nanoTime) start-time-ns) 1e6)))
                               :instant (System/currentTimeMillis)
                               :event   event
                               :before  (dissoc state-before :config)
                               :after   (dissoc state-after :config)})))


(defn send-queued-messages
  "Sends all queued messages if we aren't waiting for responses."
  [raft-state]
  (if-let [msg-queue (not-empty (:msg-queue raft-state))]
    (let [send-rpc-fn (get-in raft-state [:config :send-rpc-fn])
          raft-state* (dissoc raft-state :msg-queue)]
      (reduce-kv
        (fn [raft-state* server-id message]
          (apply send-rpc-fn raft-state* server-id message)
          (update-in raft-state* [:servers server-id :stats :sent] inc))
        raft-state* msg-queue))
    raft-state))


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
  (let [event-chan   (event-chan raft-state)
        command-chan (get-in raft-state [:config :command-chan])
        heartbeat-ms (get-in raft-state [:config :heartbeat-ms])]
    (async/go-loop [raft-state (assoc raft-state :timeout (async/timeout (+ heartbeat-ms (rand-int heartbeat-ms))))]
      (let [timeout-chan (:timeout raft-state)
            [event c] (async/alts! [event-chan command-chan timeout-chan] :priority true)
            [op data callback] event
            timeout?     (= c timeout-chan)
            start-time   (System/nanoTime)]
        (cond
          (and (nil? event) (not timeout?))
          :raft-closed

          timeout?
          (-> (if (leader/is-leader? raft-state)
                (leader/queue-append-entries raft-state)
                (leader/request-votes raft-state))
              (send-queued-messages)
              (recur))

          :else
          (let [raft-state*
                (try
                  (case op

                    ;; process and respond to append-entries event from leader
                    :append-entries
                    (append-entries-event raft-state data callback)

                    :request-vote
                    (request-vote-event raft-state data callback)

                    ;; registers a callback for a pending command which will be called once committed to the state machine
                    ;; this is used by followers to get a callback when a command they forward to a leader gets committed
                    ;; to local state
                    :register-callback
                    (let [[command-id timeout] data]
                      (register-callback-event raft-state command-id timeout callback))

                    ;; append a new log entry to get committed to state machine - only done by leader
                    :new-command
                    (if (leader/is-leader? raft-state)
                      ;; leader. Drain all commands and process together.
                      (let [all-commands (into-chan [event] command-chan)]
                        (new-command-event raft-state all-commands))
                      ;; not leader
                      (do
                        (safe-callback callback (ex-info "Server is not currently leader."
                                                         {:operation :new-command
                                                          :error     :raft/not-leader}))
                        raft-state))

                    ;; a command timed out, remove from state
                    :new-command-timeout
                    (update raft-state :command-callbacks dissoc data)


                    ;; response to append entry requests to external servers
                    :append-entries-response
                    (let [raft-state*     (leader/append-entries-response-event raft-state data)
                          new-commit      (leader/recalc-commit-index (:servers raft-state*))
                          updated-commit? (> new-commit (:commit raft-state))]

                      ;; if commits are updated, apply to state machine and send out new append-entries
                      (if updated-commit?
                        (-> raft-state*
                            (update-commits new-commit)
                            (leader/queue-append-entries))
                        raft-state*))

                    ;; response for request-vote requests - may become leader if enough votes received
                    :request-vote-response
                    (leader/request-vote-response-event raft-state data)

                    ;; when we kick off a snapshot process asynchronously, the callback will update
                    ;; raft state that we have a new snapshot
                    :snapshot
                    (let [[snapshot-index snapshot-term] data]
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
                    (install-snapshot raft-state data callback)

                    ;; response received by leader to an install-snapshot event
                    :install-snapshot-response
                    (leader/install-snapshot-response-event raft-state data)

                    :raft-state
                    (do (safe-callback callback raft-state)
                        raft-state)

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
                      (safe-callback callback :raft-closed)
                      raft-state))
                  (catch Exception e (throw (ex-info (str "Raft error processing command: " op)
                                                     {:data       data
                                                      :raft-state raft-state} e))))]
            (call-monitor-fn event raft-state raft-state* start-time)
            (when (not= :close op)
              (-> raft-state*
                  (send-queued-messages)
                  (recur)))))))))


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
  (let [{:keys [log-directory snapshot-reify]} (:config raft-state)
        latest-log      (raft-log/latest-log-index log-directory)
        latest-log-file (io/file log-directory (str latest-log ".raft"))
        log-entries     (try (raft-log/read-log-file latest-log-file)
                             (catch java.io.FileNotFoundException _ nil))
        raft-state*     (reduce
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
        snapshot-index  (when (pos-int? (:snapshot-index raft-state*))
                          (:snapshot-index raft-state*))]
    ;; if a snapshot exists, reify it into the state-machine
    (when snapshot-index
      (snapshot-reify snapshot-index))

    (cond-> (assoc raft-state* :log-file latest-log-file)
            snapshot-index (assoc :index (max (:index raft-state*) snapshot-index)
                                  :commit snapshot-index))))


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

        raft-state (-> {:config           config*
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
                        :servers          (reduce #(assoc %1 %2 leader/server-state-baseline) {} servers) ;; will be set up by leader/reset-server-state
                        :msg-queue        nil               ;; holds outgoing messages
                        }
                       (initialize-raft-state))]
    (event-loop raft-state)
    raft-state))
