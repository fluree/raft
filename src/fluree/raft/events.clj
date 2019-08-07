(ns fluree.raft.events
  (:require [clojure.core.async :as async]
            [fluree.raft.log :as raft-log]
            [clojure.tools.logging :as log]
            [fluree.raft.watch :as watch]))

;; general events/functions for all raft servers (not leader-specific)

(defn safe-callback
  "Executes a callback in a way that won't throw an exception."
  [callback data]
  (when (fn? callback)
    (try (callback data)
         (catch Exception e
           (log/error e "Callback failed. Called with data: " (pr-str data))
           nil))))


(defn into-chan
  "Conjoins all available entries in channel to supplied collection."
  [coll c]
  (async/<!!
    (async/go-loop [acc coll]
      (let [[next _] (async/alts! [c] :default ::drained)]
        (if (= ::drained next)
          acc
          (recur (conj acc next)))))))


(defn event-chan
  "Returns event channel for the raft instance."
  [raft]
  (get-in raft [:config :event-chan]))


(defn new-election-timeout
  "Generates a new election timeout in milliseconds."
  [raft]
  (let [election-timeout (get-in raft [:config :timeout-ms])]
    (+ election-timeout (rand-int election-timeout))))

(def ^:const server-state-baseline {:vote           nil     ;; holds two-tuple of [term voted-for?]
                                    :next-index     0       ;; next index to send
                                    :match-index    0       ;; last known index persisted
                                    :snapshot-index nil     ;; when sending, current snapshot index currently being sent
                                    :stats          {:sent         0
                                                     :received     0
                                                     :avg-response 0}})


(defn reset-server-state
  "Called when we become a follower to clear out any pending outgoing messages."
  [raft-state]
  (let [servers (get-in raft-state [:config :servers])]
    (reduce
      (fn [raft-state* server-id]
        (update-in raft-state* [:servers server-id]
                   #(assoc server-state-baseline :stats (:stats %))))
      raft-state servers)))


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


(defn call-monitor-fn
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


(defn become-follower
  "Transition from a leader to a follower"
  [raft-state new-term new-leader-id]
  (log/debug (format "Becoming follower, leader: %s and term: %s." new-leader-id new-term))
  (when (not= new-term (:term raft-state))
    (raft-log/write-current-term (:log-file raft-state) new-term))
  (let [raft-state* (-> raft-state
                        (assoc :term new-term
                               :status :follower
                               :leader new-leader-id
                               :voted-for nil
                               :msg-queue nil
                               :timeout (async/timeout (new-election-timeout raft-state)))
                        (reset-server-state))]
    (watch/call-leader-watch :become-follower raft-state raft-state*)
    raft-state*))


(defn register-callback-event
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


(defn update-commits
  "Process new commits if leader-commit is updated.
  Put commit results on callback async channel if present.
  Update local raft state :commit"
  [raft-state leader-commit]
  (if (= (:commit raft-state) leader-commit)
    raft-state                                              ;; no change
    (let [{:keys [commit snapshot-index config snapshot-pending command-callbacks]} raft-state
          {:keys [state-machine snapshot-threshold snapshot-write]} config
          commit-entries    (raft-log/read-entry-range (:log-file raft-state) (inc commit) leader-commit)
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
              snapshot-callback (fn [& _]
                                  (log/trace "Snapshot callback called, sending message:" [:snapshot [commit term-at-commit]])
                                  (async/put! (event-chan raft-state) [:snapshot [commit term-at-commit]]))]
          (log/debug (format "Raft snapshot triggered at term %s, commit %s. Last snapshot: %s. Snapshot-threshold: %s."
                             term-at-commit commit snapshot-index snapshot-threshold))
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


(defn append-entries-event
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
          new-timeout-ms         (new-election-timeout raft-state)
          raft-state*            (cond-> (assoc raft-state :leader-commit leader-commit)

                                         ;; leader's term is newer, update leader info
                                         new-leader?
                                         (#(do
                                             (when (> index prev-log-index)
                                               ;; it is possible we have log entries after the leader's latest, remove them
                                               (raft-log/remove-entries (:log-file %) (inc prev-log-index)))
                                             (become-follower % proposed-term leader-id)))

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
      (log/trace "Append entries event: " {:new-leader? new-leader? :logs-match? logs-match? :args args :raft-state raft-state*})
      (callback response)
      (assoc raft-state* :timeout (async/timeout new-timeout-ms)))))


(defn request-vote-event
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
                                               :timeout (async/timeout (new-election-timeout raft-state)))))]
    (log/debug "Request vote event: " {:args args :response response :raft-state raft-state*})
    (callback response)
    raft-state*))


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
  (log/debug "Install snapshot called: " (pr-str (dissoc snapshot-map :snapshot-data)))
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