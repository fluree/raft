(ns fluree.raft.leader
  (:require [clojure.core.async :as async]
            [fluree.raft.log :as raft-log]
            [clojure.tools.logging :as log]))


(defn update-server-stats
  "Updates some basic stats on servers when we receive a response.

  Server stats have the following keys:
  - sent         - number of messages sent
  - received     - number of responses received
  - avg-response - average response time in nanoseconds"
  [raft-state server response-time]
  (update-in raft-state [:servers server :stats]
             (fn [stats]
               (let [{:keys [received avg-response]} stats
                     received* (inc received)]
                 (assoc stats :received received*
                              :avg-response (float (/ (+ response-time (* received avg-response)) received*)))))))


(defn is-leader?
  [raft-state]
  (= :leader (:status raft-state)))


(defn new-election-timeout
  "Generates a new election timeout in milliseconds."
  [raft]
  (let [election-timeout (get-in raft [:config :timeout-ms])]
    (+ election-timeout (rand-int election-timeout))))


(defn- minimum-majority
  "With a sequence of numbers, returns the highest number the majority is
  at or exceeding."
  [seq]
  (->> seq
       (sort >)
       (drop (Math/floor (/ (count seq) 2)))
       (first)))


(defn recalc-commit-index
  "Recalculates commit index and returns value given a server config map from raft. (:servers raft-state).
  Pulls all :match-index values and returns the minimum match index that the majority holds."
  [servers]
  (->> servers
       vals
       (map :match-index)
       (minimum-majority)))


(defn update-commit
  [raft-state]
  (assoc raft-state :commit (->> (:servers raft-state)
                                 vals
                                 (map :match-index)
                                 (minimum-majority))))


(defn call-leader-change-fn
  "If exists, calls leader change function."
  [raft-state]
  (when-let [leader-change (get-in raft-state [:config :leader-change])]
    (when (fn? leader-change)
      (try (leader-change raft-state)
           (catch Exception e (log/error e "Exception calling leader-change function."))))))


(def ^:const server-state-baseline {:vote           nil     ;; holds two-tuple of [term voted-for?]
                                    :next-index     0       ;; next index to send
                                    :match-index    0       ;; last known index persisted
                                    :next-message   nil     ;; queue of next message waiting to send
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


(defn become-follower
  "Transition from a leader to a follower"
  [raft-state new-term new-leader-id]
  (when (not= new-term (:term raft-state))
    (raft-log/write-current-term (:log-file raft-state) new-term))
  (let [raft-state* (-> raft-state
                        (assoc :term new-term
                               :status :follower
                               :leader new-leader-id
                               :voted-for nil
                               :timeout (async/timeout (new-election-timeout raft-state)))
                        (reset-server-state))]
    (call-leader-change-fn raft-state*)
    raft-state*))


(defn- queue-install-snapshot
  ([raft-state server] (let [{:keys [snapshot-index snapshot-term]} raft-state]
                         (queue-install-snapshot raft-state server snapshot-index snapshot-term 1)))
  ([raft-state server snapshot-index snapshot-term snapshot-part]
   (let [{:keys [term servers this-server config]} raft-state
         {:keys [snapshot-xfer event-chan]} config
         snapshot-data (snapshot-xfer snapshot-index snapshot-part)
         data          {:leader-id      this-server
                        :term           term
                        :snapshot-term  snapshot-term
                        :snapshot-index snapshot-index
                        :snapshot-part  snapshot-part
                        :snapshot-parts (:parts snapshot-data)
                        :snapshot-data  (:data snapshot-data)
                        :instant        (System/currentTimeMillis)}
         callback      (fn [response] (async/put! event-chan
                                                  [:install-snapshot-response {:server   server
                                                                               :request  (dissoc data :snapshot-data)
                                                                               :response response}]))]
     (update-in raft-state [:servers server]
                #(assoc % :next-message [:install-snapshot data callback]
                          :snapshot-index snapshot-index)))))


;; TODO - rename :snapshot-index in server state to something like :sending-snapshot, so different name than what is in raft-state
(defn- queue-append-entry
  [raft-state server]
  (let [{:keys [term index commit this-server snapshot-index config]} raft-state
        server-state   (get-in raft-state [:servers server])
        next-index     (:next-index server-state)
        send-snapshot? (<= next-index snapshot-index)]
    (cond
      ;; if currently sending a snapshot, wait until done before sending more append-entries
      (:snapshot-index server-state)                        ;; indicates currently sending a snapshot
      raft-state

      ;; we need to send a snapshot, reached end of our log
      send-snapshot?
      (queue-install-snapshot raft-state server)

      ;; standard case
      :else
      (let [{:keys [event-chan entries-max]} config
            end-index      (min index (+ next-index entries-max)) ;; send at most entries-max
            prev-log-index (max (dec next-index) 0)
            prev-log-term  (cond
                             (= 0 prev-log-index)
                             0

                             (= snapshot-index prev-log-index)
                             (:snapshot-term raft-state)

                             :else
                             (raft-log/index->term (:log-file raft-state) prev-log-index))
            entries        (if (> next-index index)
                             []
                             (raft-log/read-entry-range (:log-file raft-state) next-index end-index))
            data           {:term           term
                            :leader-id      this-server
                            :prev-log-index prev-log-index
                            :prev-log-term  prev-log-term
                            :entries        entries
                            :leader-commit  commit
                            :instant        (System/currentTimeMillis)}
            callback       (fn [response] (async/put! event-chan
                                                      [:append-entries-response {:server   server
                                                                                 :request  data
                                                                                 :response response}]))
            message        [:append-entries data callback]]
        (-> raft-state
            (assoc-in [:servers server :next-message] message)
            ;; update next-index, we will send out parallel updates as needed
            (assoc-in [:servers server :next-index] (inc end-index)))))))


(defn install-snapshot-response-event
  "Response map contains two keys:
  - term - current term of server - used to determine if we lost leadership
  - next-part - next part of the snapshot we should send, or nil/0 if we should send no more."
  [raft-state {:keys [server request response]}]
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [snapshot-index snapshot-term snapshot-part snapshot-parts]} request
        {:keys [term next-part]} response
        done?       (or (not (pos-int? next-part))
                        (and (int? next-part) (> next-part snapshot-parts)))]
    (cond
      ;; lost leadership
      (not (is-leader? raft-state*))
      raft-state*

      ;; response has a newer term, go to follower status and reset election timeout
      (> term (:term raft-state*))
      (become-follower raft-state* term nil)

      done?
      (-> raft-state*
          (update-in [:servers server] #(assoc % :next-index (inc snapshot-index)
                                                 :match-index snapshot-index
                                                 :snapshot-index nil))
          (update-commit)
          (queue-append-entry server))

      ;; send next part of snapshot
      (not done?)
      (queue-install-snapshot raft-state* server snapshot-index snapshot-term (inc snapshot-part)))))


(defn queue-append-entries
  "Forces update messages for all servers to be placed in the queue.
  Called after a heartbeat timeout or when a commit index is updated.
  Resets heartbeat timeout."
  [raft-state]
  (let [{:keys [other-servers]} raft-state
        heartbeat-time (get-in raft-state [:config :heartbeat-ms])]
    (-> (reduce (fn [raft-state* server]
                  (queue-append-entry raft-state* server))
                raft-state
                other-servers)
        (assoc :timeout (async/timeout heartbeat-time)))))


(defn append-entries-response-event
  "Updates raft state with an append-entries response. Responses may come out of order.

  A few of the things that can happen:
  - If response has a newer term, we'll become a follower
  - If the response has success: true, we'll update that server's stats and possibly update the commit index
  - If the response has success: false, we'll decrement that server's next-index and resend a new append-entry
    to that server immediately with older log entries."
  [raft-state {:keys [server request response]}]
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [term success]} response
        {:keys [prev-log-index entries]} request
        next-index  (inc (+ prev-log-index (count entries)))]
    (cond
      ;; if we are no longer leader, ignore response
      (not (is-leader? raft-state*))
      raft-state*

      ;; response has a newer term, go to follower status and reset election timeout
      (> term (:term raft-state*))
      (-> raft-state*
          (become-follower term nil))

      ;; update successful
      (true? success)
      (-> raft-state*
          (update-in [:servers server] #(assoc % :next-index (max next-index (:next-index %))
                                                 :match-index (max (dec next-index) (:match-index %)))))

      ;; update failed - decrement next-index to prev-log-index of original request and re-send
      (false? success)
      (let [next-index    (get-in raft-state* [:servers server :next-index])
            ;; we already got back a response that this index point wasn't valid, don't re-trigger sending another message
            old-response? (<= next-index prev-log-index)]
        (cond-> (update-in raft-state* [:servers server]
                           (fn [server-state]
                             ;; in the case we got an out-of-order response, next-index might already be set lower than
                             ;; prev-log-index for this request, take the minimum
                             (assoc server-state :next-index (min prev-log-index (:next-index server-state)))))

                (not old-response?) (queue-append-entry server))))))


(defn- become-leader
  "Once majority of votes to elect us as leader happen, actually become new leader for term leader-term."
  [raft-state]
  (let [{:keys [this-server index servers]} raft-state
        heartbeat-time (get-in raft-state [:config :heartbeat-ms])
        next-index     (inc index)
        server-ids     (keys servers)
        servers*       (reduce (fn [servers server-id]
                                 (-> servers
                                     (assoc-in [server-id :next-index] next-index)
                                     (assoc-in [server-id :match-index] (if (= server-id this-server)
                                                                          index ;; if this-server, set initial match-index to current index
                                                                          0))))
                               servers server-ids)
        raft-state*    (assoc raft-state :status :leader
                                         :leader this-server
                                         :servers servers*
                                         :timeout (async/timeout heartbeat-time))]
    (call-leader-change-fn raft-state*)
    (queue-append-entries raft-state*)))


(defn request-vote-response-event
  [raft-state {:keys [server request response]}]
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [status term]} raft-state*
        candidate?  (= :candidate status)]
    (cond
      ;; remote server is newer term, become follower
      (> (:term response) term)
      (become-follower raft-state* (:term response) nil)

      ;; we are no longer a candidate since sending this event, ignore
      (not candidate?)
      raft-state*

      :else
      (let [proposed-term (:term request)
            {:keys [vote-granted]} response
            raft-state*   (update-in raft-state* [:servers server]
                                     ;; :vote holds two tuple of [term vote], only update request term newer
                                     (fn [server-state]
                                       (let [[term _] (:vote server-state)]
                                         (if (or (nil? term) (< term proposed-term))
                                           (assoc server-state :vote [proposed-term vote-granted])
                                           ;; either an existing vote exists, or it is for a newer term... leave as-is
                                           server-state))))
            votes-for     (->> (:servers raft-state*)
                               vals
                               (map :vote)
                               ;; make sure votes in state are for this term
                               (filter #(and (= proposed-term (first %)) (true? (second %))))
                               (count))
            majority?     (> votes-for (/ (count (:servers raft-state*)) 2))]
        (if majority?
          (become-leader raft-state*)
          raft-state*)))))


(defn request-votes
  "Request votes for leadership from all followers."
  [raft-state]
  (let [{:keys [this-server other-servers index term config leader]} raft-state
        {:keys [event-chan]} config
        proposed-term (inc term)
        _             (raft-log/write-current-term (:log-file raft-state) proposed-term)
        _             (raft-log/write-voted-for (:log-file raft-state) proposed-term this-server)
        last-log-term (raft-log/index->term (:log-file raft-state) index)
        request       {:term           proposed-term
                       :candidate-id   this-server
                       :last-log-index index
                       :last-log-term  (or last-log-term 0)
                       :instant        (System/currentTimeMillis)}
        raft-state*   (-> raft-state
                          (assoc :term proposed-term
                                 :status :candidate         ;; register as candidate in state
                                 :leader nil
                                 :voted-for this-server
                                 :timeout (async/timeout (new-election-timeout raft-state)))
                          ;; register vote for self
                          (assoc-in [:servers this-server :vote] [proposed-term true])
                          ;; queue outgoing messages
                          (#(reduce (fn [state server]
                                      (let [callback (fn [response]
                                                       (async/put! event-chan
                                                                   [:request-vote-response
                                                                    {:server server :request request :response response}]))
                                            message  [:request-vote request callback]]
                                        (assoc-in state [:servers server :next-message] message)))
                                    % other-servers)))]
    ;; if we have current leader (not nil), we have a leader state change
    (when leader (call-leader-change-fn raft-state*))
    raft-state*))
