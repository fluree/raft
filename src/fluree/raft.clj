(ns fluree.raft
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [fluree.raft.log :as raft-log]
            [fluree.raft.leader :as leader])
  (:import (java.util UUID)))


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
    (when (fn? close-fn)
      (close-fn))))


(defn request-vote-event
  "Grant vote to server requesting leadership if:
  - proposed term is >= current term
  - we haven't already voted for someone for this term
  - log index + term is at least as far as our log
  "
  [raft-state args callback]
  (let [{:keys [candidate-id last-log-index last-log-term]} args
        proposed-term    (:term args)
        {:keys [index term log-file voted-for]} raft-state
        my-last-log-term (if (= 0 index)
                           0
                           (raft-log/index->term log-file index))

        reject-vote?     (or (< proposed-term term)         ;; request is for an older term
                             (and (= proposed-term term)    ;; make sure we haven't already voted for someone in this term
                                  (not (nil? voted-for)))
                             (< last-log-term my-last-log-term) ;; if log term is older, reject
                             (and (= last-log-term my-last-log-term) ;; if log term is same, my index must not be longer
                                  (< last-log-index index)))

        response         (if reject-vote?
                           {:term term :vote-granted false}
                           {:term proposed-term :vote-granted true})
        raft-state*      (if reject-vote?
                           raft-state
                           (do
                             (raft-log/write-current-term (:log-file raft-state) proposed-term)
                             (raft-log/write-voted-for (:log-file raft-state) proposed-term candidate-id)
                             (assoc raft-state :term proposed-term
                                               :voted-for candidate-id
                                               :status :follower)))]
    (callback response)
    raft-state*))


(defn update-commits
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
                                               (let [resp (state-machine (:entry entry-map))]
                                                 (if-let [callback-chan (get callbacks (:id entry-map))]
                                                   (do
                                                     (if (nil? resp)
                                                       (async/close! callback-chan)
                                                       (async/put! callback-chan resp))
                                                     (dissoc callbacks (:id entry-map)))
                                                   callbacks)))
                                             command-callbacks commit-entries)))))


(defn append-entries-event
  [raft-state args callback]
  (let [{:keys [leader-id prev-log-index prev-log-term entries leader-commit]} args
        proposed-term          (:term args)
        {:keys [term index snapshot-index]} raft-state
        term-at-prev-log-index (cond
                                 (= 0 prev-log-index) 0

                                 (= prev-log-index snapshot-index)
                                 (:snapshot-term raft-state)

                                 (<= prev-log-index index)
                                 (raft-log/index->term (:log-file raft-state) prev-log-index)

                                 :else nil)
        old-term?              (< proposed-term term)
        new-leader?            (> proposed-term term)
        logs-match?            (= prev-log-term term-at-prev-log-index)
        raft-state*            (cond-> (assoc raft-state :timeout (async/timeout (leader/generate-election-timeout raft-state))
                                                         :leader leader-id)

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


                                       ;; entry at prev-log-index doesn't match local log term, remove offending entries
                                       (not logs-match?)
                                       (#(do
                                           (raft-log/remove-entries (:log-file %) prev-log-index)
                                           (assoc % :index (dec prev-log-index))))

                                       ;; Check if commit is newer and process into state machine if needed
                                       logs-match?
                                       (update-commits leader-commit))
        response               (cond
                                 ;; older term, outright reject, send our current term back
                                 old-term?
                                 {:term term :success true}

                                 logs-match?
                                 {:term proposed-term :success true}

                                 :else
                                 {:term proposed-term :success false})]

    (callback response)

    (if old-term?
      raft-state
      raft-state*)))


(defn safe-callback
  "Executes a callback in a way that won't throw an exception."
  [callback data]
  (when (fn? callback)
    (try (callback data)
         (catch Exception e
           (log/error e "Callback failed. Called with data: " (pr-str data))
           nil))))


(defn command-response
  "Generates a new command response, uses timeout for default response."
  [raft-state id resp-chan timeout-ms callback]
  (async/go
    (try
      (let [timeout-chan (async/timeout timeout-ms)
            [resp c] (async/alts! [resp-chan timeout-chan])
            timeout?     (= timeout-chan c)]
        ;; if timeout, clear callback from raft-state
        (when timeout?
          (async/put! (event-chan raft-state) [:new-command-timeout id]))
        (if timeout?
          (safe-callback callback (ex-info "Command timed out." {:operation :new-command
                                                                 :error     :raft/command-timeout
                                                                 :id        id}))
          (safe-callback callback resp)))
      (catch Exception e (log/error e) (throw e)))))


(defn into-chan
  "Conjoins all available entries in channel to supplied collection."
  [coll c]
  (loop [acc coll]
    (let [x (async/poll! c)]
      (if (nil? x)
        acc
        (recur (conj acc x))))))


(defn install-snapshot
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


(defn event-loop
  "Launches an event loop where all state changes to the raft state happen.

  This means all state changes are single-threaded.

  Maintains appropriate timeouts (hearbeat if leader, or election-timeout if not leader)
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
  (let [event-channel   (event-chan raft-state)
        command-channel (get-in raft-state [:config :command-chan])
        this-server     (:this-server raft-state)]
    (async/go-loop [raft-state (assoc raft-state
                                 :timeout (async/timeout
                                            (leader/generate-election-timeout raft-state)))]
      (let [timeout-chan (:timeout raft-state)
            [event c] (async/alts! [event-channel command-channel timeout-chan] :priority true)
            [op data callback] event
            timeout?     (= c timeout-chan)]
        (cond
          (and (nil? event) (not timeout?))
          :raft-closed

          timeout?
          (let [leader?     (leader/is-leader? raft-state)
                raft-state* (if leader?
                              (leader/send-append-entries raft-state)
                              (leader/request-votes raft-state))]
            (recur raft-state*))

          :else
          (let [raft-state*
                (try
                  (case op

                    ;; process and respond to append-entries event from leader
                    :append-entries
                    (append-entries-event raft-state data callback)

                    :request-vote
                    (request-vote-event raft-state data callback)

                    ;; append a new log entry, an ultimately command into state machine - only done by leader
                    :new-command
                    (if-not (leader/is-leader? raft-state)
                      ;; not leader
                      (do
                        (safe-callback callback (ex-info "Server is not currently leader." {:operation :new-command
                                                                                            :error     :raft/not-leader}))
                        raft-state)

                      ;; we are leader - drain all commands so can be sent in batch
                      (let [commands    (into-chan [event] command-channel)
                            raft-state* (loop [[command & r] commands
                                               raft-state raft-state]
                                          (if command
                                            (let [[_ data callback] command
                                                  id          (or (:id data) (str (UUID/randomUUID)))
                                                  timeout     (or (:timeout data) (get-in raft-state [:config :default-command-timeout]))
                                                  resp-chan   (async/promise-chan)
                                                  new-index   (inc (:index raft-state))
                                                  entry       {:term (:term raft-state) :entry data :id id}
                                                  _           (raft-log/write-new-command (:log-file raft-state) new-index entry)
                                                  raft-state* (-> raft-state
                                                                  (assoc :index new-index)
                                                                  (assoc-in [:servers this-server :match-index] new-index) ;; match-index majority used for updating leader-commit
                                                                  (assoc-in [:command-callbacks id] resp-chan))]
                                              ;; create a go-channel to monitor for response
                                              (command-response raft-state* id resp-chan timeout callback)
                                              ;; kick off an append-entries call
                                              (recur r raft-state*))
                                            raft-state))]
                        (leader/send-append-entries raft-state*)))

                    ;; a command timed out, remove from state
                    :new-command-timeout
                    (update raft-state :command-callbacks dissoc data)

                    ;; response to append entry requests to external servers
                    :append-entries-response
                    (let [raft-state*     (leader/append-entries-response-event raft-state data)
                          new-commit      (leader/recalc-commit-index (:servers raft-state*))
                          updated-commit? (> new-commit (:commit raft-state*))]

                      ;; if commits are updated, apply to state machine and send out new append-entries
                      (if updated-commit?
                        (-> raft-state*
                            (update-commits new-commit)
                            (leader/send-append-entries))
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

                    ;; close down all pending callbacks
                    :close
                    (let [callback-chans (vals (:command-callbacks raft-state))]
                      (doseq [c callback-chans]
                        (async/put! c (ex-info "Raft server shut down." {:operation :new-command
                                                                         :error     :raft/shutdown})))
                      (safe-callback callback :raft-closed)
                      raft-state))
                  (catch Exception e (throw (ex-info (str "Raft error processing command: " op) {:data       data
                                                                                                 :raft-state raft-state} e))))]
            (when (not= :close op)
              (recur raft-state*))))))))


(defn new-command
  "Issues a new command to raft. Will return an error if we are not the current leader."
  ([raft command] (new-command raft command nil))
  ([raft command callback]
   (let [command-chan (get-in raft [:config :command-chan])]
     (async/put! command-chan [:new-command command callback]))))


(defn initialize-raft-state
  [raft-state]
  (let [{:keys [persist-dir snapshot-reify]} (:config raft-state)
        latest-log      (raft-log/latest-log-index persist-dir)
        latest-log-file (io/file persist-dir (str latest-log ".raft"))
        log-entries     (try (raft-log/read-log-file latest-log-file)
                             (catch java.io.FileNotFoundException _ nil))
        raft-state*     (reduce
                          (fn [state entry]
                            (let [[index term entry-type data] entry]
                              (cond
                                (> index 0)
                                (assoc state :index index :term term)

                                (= :current-term entry-type)
                                (assoc state :term term)

                                (= :voted-for entry-type)
                                (if (= term (:term state))
                                  (assoc state :voted-for data)
                                  state)

                                (= :snapshot entry-type)
                                (assoc state :snapshot-index data
                                             :snapshot-term term)

                                (= :no-op entry-type)
                                state)))
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
  (let [{:keys [this-server servers election-timeout broadcast-time
                retain-logs snapshot-threshold persist-dir state-machine
                snapshot-write snapshot-xfer snapshot-install snapshot-reify
                send-rpc-fn default-command-timeout close-fn
                event-chan command-chan]
         :or   {election-timeout        500                 ;; election-timeout, good range is 10ms->500ms
                broadcast-time          100                 ;; heartbeat broadcast-time
                retain-logs             10                  ;; number of historical log files to retain
                snapshot-threshold      10                  ;; number of log entries since last snapshot (minimum) to generate new snapshot
                default-command-timeout 4000
                persist-dir             "raftlog/"
                event-chan              (async/chan)
                command-chan            (async/chan)
                }} config
        _           (assert (fn? state-machine))
        _           (assert (fn? snapshot-write))
        _           (assert (fn? snapshot-reify))
        _           (assert (fn? snapshot-install))
        _           (assert (fn? snapshot-xfer))

        config*     (assoc config :election-timeout election-timeout
                                  :broadcast-time broadcast-time
                                  :persist-dir persist-dir
                                  :send-rpc-fn send-rpc-fn
                                  :retain-logs retain-logs
                                  :snapshot-threshold snapshot-threshold
                                  :state-machine state-machine
                                  :snapshot-write snapshot-write
                                  :snapshot-xfer snapshot-xfer
                                  :snapshot-reify snapshot-reify
                                  :snapshot-install snapshot-install
                                  :event-chan event-chan
                                  :command-chan command-chan
                                  :close close-fn
                                  :default-command-timeout default-command-timeout)

        raft-state  {:config           config*
                     :this-server      this-server
                     :status           nil                  ;; candidate, leader, follower
                     :leader           nil                  ;; current known leader
                     :log-file         (io/file persist-dir "0.raft")
                     :term             0                    ;; latest term
                     :index            0                    ;; latest index
                     :snapshot-index   0                    ;; index point of last snapshot
                     :snapshot-term    0                    ;; term of last snapshot
                     :snapshot-pending nil                  ;; holds pending commit if snapshot was requested
                     :commit           0                    ;; commit point in index
                     :voted-for        nil                  ;; for the :term specified above, who we voted for

                     ;; map of servers participating in consensus. server id is key, state of server is val
                     :servers          (reduce #(assoc %1 %2 {:vote        nil
                                                              :next-index  0
                                                              :match-index 0}) {} servers)

                     }
        raft-state* (initialize-raft-state raft-state)]
    (event-loop raft-state*)
    raft-state*))
