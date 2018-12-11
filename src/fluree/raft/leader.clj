(ns fluree.raft.leader
  (:require [clojure.core.async :as async]
            [fluree.raft.log :as raft-log]))

(defn is-leader?
  [raft-state]
  (= :leader (:status raft-state)))

(defn remote-servers
  "Returns a list of all servers excluding this-server."
  [raft-state]
  (let [this-server (:this-server raft-state)]
    (-> raft-state
        :servers
        (dissoc this-server)
        keys)))

(defn generate-election-timeout
  "Generates a new election timeout in milliseconds."
  [raft]
  (let [election-timeout (get-in raft [:config :election-timeout])]
    (+ election-timeout (rand-int election-timeout))))


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


(defn become-follower
  "Transition from a leader to a follower"
  [raft-state new-term]
  (assoc raft-state :term new-term
                    :status :follower
                    :leader nil
                    :voted-for nil
                    :timeout (async/timeout (generate-election-timeout raft-state))))


(defn append-entries-callback
  "Callback function called with response from your configured send-rpc-fn."
  [event-channel server request-map response]
  (async/put! event-channel [:append-entries-response {:server   server
                                                       :request  request-map
                                                       :response response}]))

(defn send-append-entry
  "Sends an append entry request to given server based on current state."
  [raft-state server]
  (let [{:keys [send-rpc-fn timeout-reset-chan]} (:config raft-state)
        {:keys [servers term index log commit this-server]} raft-state
        next-index     (get-in servers [server :next-index])
        prev-log-index (max (dec next-index) 0)
        prev-log-term  (if (= 0 prev-log-index)
                         0
                         (:term (raft-log/modified-nth log prev-log-index index)))
        entries        (if (> next-index index)
                         []
                         (raft-log/sublog log next-index (inc index) index))
        data           {:term           term
                        :leader-id      this-server
                        :prev-log-index prev-log-index
                        :prev-log-term  prev-log-term
                        :entries        entries
                        :leader-commit  commit}
        callback       (partial append-entries-callback timeout-reset-chan server data)]
    (send-rpc-fn raft-state server :append-entries data callback)
    raft-state))


(defn append-entries-response-event
  "Updates raft state with an append-entries response.

  A few of the things that can happen:
  - If response has a newer term, we'll become a follower
  - If the response has success: true, we'll update that server's stats and possibly update the commit index
  - If the response has success: false, we'll decrement that server's next-index and resend a new append-entry
    to that server immediately with older log entries."
  [raft-state response-map]
  (let [{:keys [server request response]} response-map
        {:keys [term success]} response
        {:keys [prev-log-index entries]} request
        next-index (inc (+ prev-log-index (count entries)))]
    (if-not (is-leader? raft-state)
      ;; if we are no longer leader, ignore response
      raft-state
      (cond
        ;; response has a newer term, go to follower status and reset election timeout
        (> term (:term raft-state))
        (become-follower raft-state term)

        ;; update successful
        (true? success)
        (let [server-stats  (:servers raft-state)
              server-stats* (update server-stats server
                                    #(assoc % :next-index (max next-index (:next-index %))
                                              :match-index (max (dec next-index) (:match-index %))))]
          (assoc raft-state :servers server-stats*))

        ;; update failed - decrement next-index and re-send an update
        (false? success)
        (let [raft-state* (update-in raft-state [:servers server :next-index] dec)]
          (send-append-entry raft-state* server)
          raft-state*)))))


(defn send-append-entries
  "Sends append entries requests to all servers."
  [raft-state]
  (let [heartbeat-timeout (get-in raft-state [:config :broadcast-time])]
    (doseq [server-id (remote-servers raft-state)]
      (send-append-entry raft-state server-id))
    ;; reset timeout
    (assoc raft-state :timeout (async/timeout heartbeat-timeout))))


(defn become-leader
  "Once majority of votes to elect us as leader happen, actually become new leader for term leader-term."
  [raft-state]
  (let [this-server    (:this-server raft-state)
        broadcast-time (get-in raft-state [:config :broadcast-time])
        next-index     (inc (:index raft-state))
        servers        (reduce-kv
                         (fn [acc server server-status]
                           (assoc acc server (assoc server-status :next-index next-index
                                                                  :match-index 0)))
                         {}
                         (:servers raft-state))
        raft-state*    (assoc raft-state :status :leader
                                         :leader this-server
                                         :servers servers
                                         :timeout (async/timeout broadcast-time))]
    ;; send an initial append-entries to make sure everyone knows we are the leader
    (send-append-entries raft-state*)))


(defn request-vote-response-event
  [raft-state response-map]
  (let [{:keys [server request response]} response-map
        proposed-term (:term request)
        {:keys [vote-granted term]} response
        raft-state*   (update-in raft-state [:servers server :vote]
                                 ;; :vote holds two tuple of [term vote], only update request term newer
                                 (fn [[term vote]]
                                   (if (or (nil? term) (< term proposed-term))
                                     [proposed-term vote-granted]
                                     ;; either an existing vote exists, or it is for a newer term... leave as-is
                                     [term vote])))
        votes-for     (->> (:servers raft-state*)
                           vals
                           (map :vote)
                           ;; make sure votes in state are for this term
                           (filter #(and (= proposed-term (first %)) (true? (second %))))
                           (count))
        majority?     (> votes-for (/ (count (:servers raft-state*)) 2))]
    (cond
      ;; response has a newer term, go to follower status and reset election timeout
      (> term (:term raft-state*))
      (become-follower raft-state* term)


      (and majority? (not (is-leader? raft-state*)))
      (become-leader raft-state*)


      :else
      raft-state*)))


(defn request-vote-callback
  "Callback function for each rpc call to get a vote."
  [event-channel server request-map response]
  (async/put! event-channel [:request-vote-response {:server   server
                                                     :request  request-map
                                                     :response response}]))


(defn request-votes
  "Request votes for leadership from all followers."
  [raft-state]
  (let [{:keys [send-rpc-fn timeout-reset-chan]} (get raft-state :config)
        this-server    (:this-server raft-state)
        proposed-term  (inc (:term raft-state))
        raft-state*    (-> raft-state
                           (assoc :term proposed-term
                                  :status :candidate        ;; register as candidate in state
                                  :leader nil
                                  :voted-for this-server)
                           ;; register vote for self
                           (assoc-in [:servers this-server :vote] [proposed-term true]))

        {:keys [log index term]} raft-state*
        last-log-entry (first (raft-log/sublog log index (inc index) index))
        request        {:term           term
                        :candidate-id   this-server
                        :last-log-index index
                        :last-log-term  (or (:term last-log-entry) 0)}]
    (doseq [server (remote-servers raft-state*)]
      (let [callback (partial request-vote-callback timeout-reset-chan server request)]
        (send-rpc-fn raft-state* server :request-vote request callback)))
    (assoc raft-state* :timeout (async/timeout (generate-election-timeout raft-state)))))

