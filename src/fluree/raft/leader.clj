(ns fluree.raft.leader
  (:require [fluree.raft.log :as raft-log]
            [clojure.core.async :as async]))

(declare send-append-entry)

(defn is-leader?
  [raft]
  (= :leader (:status @(:raft-state raft))))

(defn remote-servers
  "Returns a list of all servers excluding this-server."
  [raft]
  (let [this-server (get-in raft [:config :this-server])]
    (-> @(:raft-state raft)
        :servers
        (dissoc this-server)
        keys)))

(defn generate-election-timeout
  "Generates a new election timeout in milliseconds."
  [raft]
  (let [election-timeout (get-in raft [:config :election-timeout])]
    (+ election-timeout (rand-int election-timeout))))

(defn reset-heartbeat
  "Resets the heartbeat by putting to a core async channel
  the amount of time in ms to next timeout.

  Heartbeat will trigger and send a blank append-entries to all followers
  to maintain leadership

  Returns time in epoch millis when the next timeout will occur."
  [raft]
  (let [timeout-reset-chan    (get-in raft [:config :timeout-reset-chan])
        broadcast-time        (get-in raft [:config :broadcast-time])
        epoch-ms-at-broadcast (+ (System/currentTimeMillis) broadcast-time)]
    (async/put! timeout-reset-chan broadcast-time)
    epoch-ms-at-broadcast))

(defn reset-election-timeout
  "Resets the election timeout. Does this by putting to a core async
  channel a 'reset' trigger containing the amount of time in ms to
  next timeout. Upon trigger, creates a new core async timeout channel
  and uses alt! to trigger a timeout or another reset.

  Returns time in epoch millis when the next timeout will occur."
  [raft]
  (let [timeout-reset-chan  (get-in raft [:config :timeout-reset-chan])
        next-timeout-ms     (generate-election-timeout raft)
        epoch-ms-at-timeout (+ (System/currentTimeMillis) next-timeout-ms)]
    (async/put! timeout-reset-chan next-timeout-ms)
    epoch-ms-at-timeout))


(defn minimum-majority
  "With a sequence of numbers, returns the highest number the majority is
  at or exceeding."
  [seq]
  (->> seq
       (sort >)
       (drop (Math/floor (/ (count seq) 2)))
       (first)))

(defn recalc-commit-index
  "Recalculates commit index and returns value given a server config map from raft. (:servers raft-state).
  Pulls all :match-index values and returns the maximum of the majority."
  [servers]
  (->> servers
       vals
       (map :match-index)
       (minimum-majority)))




(defn append-entries-callback
  [raft to-index server response]
  (let [{:keys [term success]} response]
    ;; if we lost leadership, ignore response
    (when (is-leader? raft)
      (cond
        ;; response has a newer term, go to follower status and reset election timeout
        (> term (:term @(:raft-state raft)))
        (do
          (swap! (:raft-state raft) #(assoc % :term (max term (:term %)) :status :follower))
          (reset-election-timeout raft))

        ;; update successful
        (true? success)
        (let [old-state @(:raft-state raft)
              new-state (swap! (:raft-state raft)
                               (fn [state]
                                 (let [{:keys [servers commit log index]} state
                                       ;; current next-index and match-index for the responding server
                                       {:keys [next-index match-index]} (get servers server)
                                       ;; update master server map of status
                                       servers* (update servers server assoc
                                                        :next-index (max (inc to-index) next-index)
                                                        :match-index (max to-index match-index))
                                       ;; recalculate max-commit
                                       commit*  (recalc-commit-index servers*)]

                                   ;; new commits, write to state machine
                                   (when (> commit* commit)
                                     (println "--->>> APPLY COMMIT!!! " {:new commit* :old commit})
                                     (let [commit-entries (raft-log/sublog log (inc commit) (inc commit*) index)
                                           state-machine  (get-in raft [:config :state-machine])]
                                       (doseq [entry-map commit-entries]
                                         (state-machine (:entry entry-map)))))

                                   (assoc state :servers servers*
                                                :commit commit*))))]

          ;; if we increased the commit, we should immediately send out a new update
          (when (> (:commit new-state) (:commit old-state))
            (println "--->>> commit has incresed!!! " {:new (:commit new-state) :old (:commit old-state)})
            (reset-heartbeat raft)))

        ;; update failed - decrement next-index and re-send an update
        (false? success)
        (do
          (swap! (:raft-state raft) update-in [:servers server :next-index] dec)
          (send-append-entry raft server))))))


(defn send-append-entry
  "Sends an append entry request to given server based on current state."
  [raft server]
  (let [send-rpc-fn   (get-in raft [:config :send-rpc-fn])
        this-server   (get-in raft [:config :this-server])
        {:keys [servers term index log commit]} @(:raft-state raft)
        prev-log-term (:term (peek log))
        next-index    (get-in servers [server :next-index])
        entries       (if (> next-index index)
                        []
                        (raft-log/sublog log next-index (inc index) index))
        data          {:term           term
                       :leader-id      this-server
                       :prev-log-index (max (dec next-index) 0)
                       :prev-log-term  prev-log-term
                       :entries        entries
                       :leader-commit  commit}
        callback      (partial append-entries-callback raft index server)]
    (send-rpc-fn raft server :append-entries data callback)))


(defn send-append-entries
  "Sends append entries requests to all servers."
  [raft]
  (doseq [server-id (remote-servers raft)]
    (send-append-entry raft server-id)))



(defn become-leader
  "Once majority of votes to elect us as leader happen, actually become new leader for term leader-term."
  [raft leader-term]
  (println (str (get-in raft [:config :this-server]) " ===== Become leader!" leader-term))
  (let [state*  (swap! (:raft-state raft)
                       (fn [state]
                         (if (not= leader-term (:term state))
                           ;; no-op, some other process changed state, no longer candidate for this term
                           state
                           ;; become leader
                           (let [this-server (get-in raft [:config :this-server])
                                 next-index  (inc (:index state))
                                 servers*    (reduce-kv
                                               (fn [acc server server-status]
                                                 (assoc acc server (assoc server-status :next-index next-index
                                                                                        :match-index 0)))
                                               {}
                                               (:servers state))]

                             (assoc state :status :leader
                                          :leader this-server
                                          :servers servers*)))))
        ;; make sure we got leadership
        leader? (= :leader (:status state*))]


    (when leader?
      (reset-heartbeat raft)                                ;; kick off new broadcast heartbeat
      )))


(defn request-vote-callback
  "Callback function for each rpc call to get a vote."
  [raft proposed-term server response]

  ;; update this vote in state
  (swap! (:raft-state raft) update-in [:servers server :vote]
         (fn [[term vote]]
           (if (or (nil? term) (< term proposed-term))
             [proposed-term (:vote-granted response)]
             ;; either an existing vote exists, or it is for a newer term... leave as-is
             [term vote])))

  ;; tally votes, and if we received majority become the leader
  (let [server-state (:servers @(:raft-state raft))
        votes-for    (->> server-state
                          vals
                          (map :vote)
                          ;; make sure votes in state are for this term
                          (filter #(and (= proposed-term (first %)) (true? (second %))))
                          (count))
        majority?    (> votes-for (/ (count server-state) 2))]
    ;; if we have majority, and we are not already leader, become leader
    (when (and majority? (not (is-leader? raft)))
      (become-leader raft proposed-term))
    majority?))


(defn request-votes
  "Request votes for leadership from all followers."
  [raft]
  (let [this-server    (get-in raft [:config :this-server])
        raft-state     (swap! (:raft-state raft)
                              #(-> %
                                   (assoc :term (inc (:term %))
                                          :status :candidate ;; register as candidate in state
                                          :leader nil
                                          :voted-for this-server)
                                   ;; register vote for self
                                   (assoc-in [:servers this-server :vote] [(inc (:term %)) true])))

        send-rpc-fn    (get-in raft [:config :send-rpc-fn])
        {:keys [log index term]} raft-state

        last-log-entry (first (raft-log/sublog log index (inc index) index))
        request        {:term           term
                        :candidate-id   this-server
                        :last-log-index index
                        :last-log-term  (or (:term last-log-entry) 0)}]
    (doseq [server (remote-servers raft)]
      (let [callback (partial request-vote-callback raft term server)]
        (send-rpc-fn raft server :request-vote request callback)))))



