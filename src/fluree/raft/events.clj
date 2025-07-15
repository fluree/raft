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
          (if-let [msgs (get-in raft-state* [:servers server-id :stats :sent])]
            (assoc-in raft-state* [:servers server-id :stats :sent] (inc msgs))
            raft-state*))
        raft-state* msg-queue))
    raft-state))


(defn call-monitor-fn
  "Internal - calls monitor function safely and calcs time."
  [state-after op data state-before start-time-ns]
  (when-let [monitor-fn (:monitor-fn state-after)]
    (safe-callback monitor-fn {:time    (format "%.3fms" (double (/ (- (System/nanoTime) start-time-ns) 1e6)))
                               :instant (System/currentTimeMillis)
                               :event   [op data]
                               :before  (dissoc state-before :config)
                               :after   (dissoc state-after :config)}))
  state-after)


(defn become-follower
  "Transition from a leader to a follower"
  [raft-state new-term new-leader-id cause]
  (log/debug (format "Becoming follower, leader: %s and term: %s." new-leader-id new-term))
  (when (not= new-term (:term raft-state))
    (raft-log/write-current-term (:log-file raft-state) new-term))
  (let [new-timeout (new-election-timeout raft-state)
        raft-state* (-> raft-state
                        (assoc :term new-term
                               :status :follower
                               :leader new-leader-id
                               :voted-for nil
                               :msg-queue nil
                               ::config-change-committed nil
                               :pending-server nil
                               :timeout (async/timeout new-timeout)
                               :timeout-ms new-timeout
                               :timeout-at (+ new-timeout (System/currentTimeMillis)))
                        (reset-server-state))]
    (watch/call-leader-watch (assoc cause :event :become-follower
                                          :new-raft-state raft-state*
                                          :old-raft-state raft-state))
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


;; TODO - this is not actually used, need to enforce
(defn initialize-config
  "When a new leader is elected, we make sure everyone has the same config"
  [{:keys [this-server] :as raft} data]
  (let [servers data
        other-servers (filterv #(not= % this-server) servers)
        servers-map   (reduce #(assoc %1 %2 server-state-baseline) {} servers)]
    (-> raft
        (assoc-in [:config :servers] servers)
        (assoc-in [:other-servers] other-servers)
        (assoc :servers servers-map))))


(defn config-change
  [raft-state data callback]
  (let [{:keys [server op term command-id]} data]
    (if (< term (:term raft-state))
      ;; old term!
      (do (safe-callback callback {:term (:term raft-state) :success false})
          raft-state))
    (do (safe-callback callback {:term (:term raft-state) :success true})
        (assoc raft-state :pending-server (merge server-state-baseline
                                                 {:id server
                                                  :op op
                                                  :command-id command-id})))))


(defn conj-distinct
  [val add]
  (-> (conj val add) set vec))


(defn apply-config-change
  ([raft-state req]
   (apply-config-change raft-state req server-state-baseline nil))
  ([raft-state req server-state]
   (apply-config-change raft-state req server-state nil))
  ([raft-state req server-state callback]
   (let [{:keys [server command-id op]} req
         _ (log/info (str "Committing " server " " (subs (str op) 1)
                          " to the network configuration. Change command id: " command-id))
         raft-state* (assoc raft-state :pending-server nil)]
     (do (safe-callback callback {:term (:term raft-state*) :success true})
         (condp = op
           :add (if (= (:this-server raft-state) server)
                  raft-state*
                  (-> raft-state*
                      (update-in [:config :servers] conj-distinct server)
                      (update-in [:other-servers] conj-distinct server)
                      (assoc-in [:servers server] server-state)))

           :remove (let [cfg-servers    (get-in raft-state* [:config :servers])
                         cfg-servers*   (filterv #(not= server %) cfg-servers)

                         other-servers  (get-in raft-state* [:other-servers])
                         other-servers* (filterv #(not= server %) other-servers)

                         servers        (-> (:servers raft-state*)
                                            (dissoc server))]
                     (-> raft-state*
                         (assoc-in [:config :servers] cfg-servers*)
                         (assoc-in [:other-servers] other-servers*)
                         (assoc-in [:servers] servers))))))))


(defn update-commits
  "Process new commits if leader-commit is updated.
  Put commit results on callback async channel if present.
  Update local raft state :commit"
  [raft-state leader-commit]
  (if (= (:commit raft-state) leader-commit)
    raft-state                                              ;; no change
    (let [{:keys [index commit snapshot-index config snapshot-pending]} raft-state
          {:keys [state-machine snapshot-threshold snapshot-write]} config
          commit-entries    (raft-log/read-entry-range (:log-file raft-state) (inc commit) leader-commit)
          command-callbacks (:command-callbacks raft-state)
          trigger-snapshot? (and (>= commit (+ (or snapshot-index 0) snapshot-threshold))
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
      (assoc raft-state :commit (min index leader-commit)   ;; never have a commit farther than our latest index
                        :snapshot-pending snapshot-pending
                        :command-callbacks (reduce
                                             (fn [callbacks entry-map]
                                               (let [resp (try
                                                           (state-machine (:entry entry-map) raft-state)
                                                           (catch Exception e
                                                             (log/error e (format "State machine error processing entry: %s. Expected vector format [command params...] but received: %s"
                                                                                  (:entry entry-map)
                                                                                  (type (:entry entry-map))))
                                                             {:error (str "State machine error: " (.getMessage e))}))]
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
    (let [{:keys [leader-id prev-log-index prev-log-term entries leader-commit instant]} args
          proposed-term          (:term args)
          proposed-new-index     (+ prev-log-index (count entries))
          {:keys [term index latest-index snapshot-index leader]} raft-state
          term-at-prev-log-index (cond
                                   (= 0 prev-log-index) 0

                                   (= prev-log-index snapshot-index)
                                   (:snapshot-term raft-state)

                                   (<= prev-log-index index)
                                   (raft-log/index->term (:log-file raft-state) prev-log-index)

                                   (< index prev-log-index) nil) ;; we don't even have this index

          new-leader?            (or (> proposed-term term) (not= leader-id leader))
          logs-match?            (= prev-log-term term-at-prev-log-index)
          new-timeout-ms         (new-election-timeout raft-state)
          raft-state*            (cond-> raft-state

                                         ;; leader's term is newer, update leader info
                                         new-leader?
                                         (#(let [cause {:cause      :append-entries
                                                        :old-leader leader
                                                        :new-leader leader-id
                                                        :message    (if (> proposed-term term)
                                                                      (str "Append entries term " proposed-term
                                                                           " is newer than current term " term ".")
                                                                      (str "Append entries leader " leader-id
                                                                           " for current term, " term ", is different than "
                                                                           "current leader: " leader "."))
                                                        :server     (:this-server raft-state)}]
                                             (when (> index prev-log-index)
                                               ;; it is possible we have log entries after the leader's latest, remove them
                                               (raft-log/remove-entries (:log-file %) (inc prev-log-index)))
                                             (-> %
                                                 (assoc :latest-index proposed-new-index) ;; always reset latest-index with new leader
                                                 (become-follower proposed-term leader-id cause))))

                                         ;; we have a log match at prev-log-index
                                         (and logs-match? (not-empty entries))
                                         (#(do
                                             (if (or (= index prev-log-index) new-leader?)

                                               ;; new entries, so add. If new leader, possibly over-write existing entries
                                               (raft-log/append (:log-file %) entries prev-log-index index)

                                               ;; Possibly new entries and no leader change.
                                               ;; At least some of these entries duplicate ones already received.
                                               ;; Happens when round-trip response doesn't complete prior to new updates being sent
                                               (let [new-entries-n (- proposed-new-index index)
                                                     new-entries   (take-last new-entries-n entries)]
                                                 (when new-entries
                                                   (raft-log/append (:log-file %) new-entries index index))))

                                             ;; as an optimization, we will cache last entry as it will likely be requested next
                                             (raft-log/assoc-index->term-cache proposed-new-index (:term (last entries)))

                                             (assoc % :index proposed-new-index
                                                      :latest-index (max proposed-new-index latest-index))))


                                         ;; we have an entry at prev-log-index, but doesn't match term, remove offending entries
                                         (and (not logs-match?) term-at-prev-log-index)
                                         (#(do
                                             (raft-log/remove-entries (:log-file %) prev-log-index)
                                             (assoc % :index (dec prev-log-index)
                                                      :latest-index (max proposed-new-index latest-index))))

                                         ;; Check if commit is newer and process into state machine if needed
                                         logs-match?
                                         (update-commits leader-commit))
          response               (cond
                                   logs-match?
                                   {:term proposed-term :success true}

                                   :else
                                   {:term proposed-term :success false})]
      (if (empty? entries)                                  ;; only debug log entries with data
        (log/trace "Append entries event: " {:new-leader? new-leader? :logs-match? logs-match? :args args :raft-state raft-state*})
        (log/debug "Append entries event: " {:new-leader? new-leader? :logs-match? logs-match? :args args :raft-state raft-state*}))
      (callback response)
      (assoc raft-state* :timeout (async/timeout new-timeout-ms)
                         :timeout-ms new-timeout-ms
                         :timeout-at (+ new-timeout-ms (System/currentTimeMillis))))))


(defn request-vote-event
  "Grant vote to server requesting leadership if:
  - proposed term is >= current term
  - we haven't already voted for someone for this term
  - log index + term is at least as far as our log.

  If we will reject vote because the log index is not as current as ours,
  and we are the current leader, immediately kick off a request vote of our own
  as it is likely a follower was just slow receiving our append entries request
  and this can get things back on track quickly."
  [raft-state args callback]
  (let [{:keys [candidate-id last-log-index last-log-term]} args
        proposed-term     (:term args)
        {:keys [index term log-file voted-for snapshot-index snapshot-term status]} raft-state
        snapshot-index    (or snapshot-index 0)
        my-last-log-term  (cond
                            ;; initial raft state 0, so use term 0
                            (= 0 index) 0
                            ;; last log index is beyond our latest snapshot, so get term from current log file
                            (> last-log-index snapshot-index) (raft-log/index->term log-file index)
                            ;; last-log-index is same as latest snapshot, to use snapshot's term
                            (= last-log-index snapshot-index) snapshot-term
                            :else nil)

        have-newer-index? (and (= last-log-term my-last-log-term) ;; if log term is same, my index must not be longer
                               (< last-log-index index))

        reject-vote?      (or (< proposed-term term)        ;; request is for an older term
                              (and (= proposed-term term)   ;; make sure we haven't already voted for someone in this term
                                   (not (nil? voted-for)))
                              (nil? my-last-log-term)       ;; old index we don't have any longer
                              (< last-log-term my-last-log-term) ;; if log term is older, reject
                              have-newer-index?)

        newer-term?       (> proposed-term term)

        response          (if reject-vote?
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

                                                 have-newer-index?
                                                 (format "For index %s the terms are the same: %s, but our index is longer: %s." last-log-index last-log-term index))}

                            {:term proposed-term :vote-granted true})
        raft-state*       (cond
                            ;; we gave our vote, register
                            (not reject-vote?)
                            (let [new-timeout (new-election-timeout raft-state)]
                              (raft-log/write-current-term (:log-file raft-state) proposed-term)
                              (raft-log/write-voted-for (:log-file raft-state) proposed-term candidate-id)
                              (assoc raft-state :term proposed-term
                                                :voted-for candidate-id
                                                :status :follower
                                                :latest-index (max (:latest-index raft-state) last-log-index)
                                                ;; reset timeout if we voted so as to not possibly immediately start a new election
                                                :timeout (async/timeout new-timeout)
                                                :timeout-ms new-timeout
                                                :timeout-at (+ new-timeout (System/currentTimeMillis))))

                            ;; rejected, but was for newer term and we were the last leader
                            ;; likely triggered by slow/temporarily disconnected consumer
                            ;; initiate a new vote immediately to try and regain leadership
                            (and newer-term? (= :leader status))
                            (assoc raft-state :trigger-request-vote true
                                              :status :candidate
                                              :term proposed-term)

                            ;; rejected but request was for newer term than current
                            ;; we can still cast a vote for another server at this newer term
                            ;; if we were a candidate for the older term, give up
                            newer-term?
                            (assoc raft-state :term proposed-term
                                              :voted-for nil
                                              :status :follower)

                            ;; rejected, but was for an older or same term. Nothing to do.
                            :else
                            raft-state)]
    (log/debug "Request vote event, responding: " {:response response :args args :raft-state raft-state*})
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
