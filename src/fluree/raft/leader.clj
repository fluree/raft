(ns fluree.raft.leader
  (:require [clojure.core.async :as async]
            [fluree.raft.log :as raft-log]
            [fluree.raft.events :as events]
            [clojure.tools.logging :as log]
            [fluree.raft.watch :as watch]))


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


(defn- queue-install-snapshot
  ([raft-state server] (let [{:keys [snapshot-index snapshot-term]} raft-state]
                         (queue-install-snapshot raft-state server snapshot-index snapshot-term 1)))
  ([raft-state server snapshot-index snapshot-term snapshot-part]
   (let [{:keys [term this-server config]} raft-state
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
     (-> raft-state
         (assoc-in [:msg-queue server] [:install-snapshot data callback])
         (update-in [:servers server]
                    #(assoc % :snapshot-index snapshot-index))))))


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
            (assoc-in [:msg-queue server] message)
            ;; update next-index, we will send out parallel updates as needed
            (assoc-in [:servers server :next-index] (inc end-index)))))))


(defn install-snapshot-response-event
  "Response map contains two keys:
  - term - current term of server - used to determine if we lost leadership
  - next-part - next part of the snapshot we should send, or nil/0 if we should send no more."
  [raft-state {:keys [server request response]}]
  (log/debug "Install snapshot response from server: " server {:response response :request request})
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
      (let [cause {:cause      :install-snapshot-response
                   :old-leader (:this-server raft-state)
                   :new-leader nil
                   :message    (str "Install snapshot response from server " server
                                    " returned term " term ", and current term is "
                                    (:term raft-state*) ".")
                   :server     (:this-server raft-state)}]
        (events/become-follower raft-state* term nil cause))

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
    (log/trace "Raft leader queue sending append-entry. " {:instant        (System/currentTimeMillis)
                                                           :heartbeat-ms   heartbeat-time
                                                           :next-heartbeat (+ (System/currentTimeMillis)
                                                                              heartbeat-time)})
    (-> (reduce (fn [raft-state* server]
                  (queue-append-entry raft-state* server))
                raft-state
                other-servers)
        (assoc :timeout (async/timeout heartbeat-time)
               :timeout-ms heartbeat-time
               :timeout-at (+ heartbeat-time (System/currentTimeMillis))))))


(defn append-entries-response-event
  "Updates raft state with an append-entries response. Responses may come out of order.

  A few of the things that can happen:
  - If response has a newer term, we'll become a follower
  - If the response has success: true, we'll update that server's stats and possibly update the commit index
  - If the response has success: false, we'll decrement that server's next-index and resend a new append-entry
    to that server immediately with older log entries."
  [raft-state {:keys [server request response]}]
  (log/trace "Append entries response from server:" server {:response response :request (dissoc request :entries)})
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
      (let [cause {:cause      :append-entries-response
                   :old-leader (:this-server raft-state)
                   :new-leader nil
                   :message    (str "Append entries response from server " server
                                    " returned term " term ", and current term is "
                                    (:term raft-state*) ".")
                   :server     (:this-server raft-state)}]
        (-> raft-state*
            (events/become-follower term nil cause)))

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
  (log/debug (format "Becoming leader, leader: %s, term: %s, latest index: %s."
                     (:this-server raft-state) (:term raft-state) (:index raft-state)))
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
                                         :timeout (async/timeout heartbeat-time)
                                         :timeout-ms heartbeat-time
                                         :timeout-at (+ heartbeat-time (System/currentTimeMillis)))]
    (watch/call-leader-watch {:event          :become-leader
                              :cause          :become-leader
                              :old-leader     (:leader raft-state)
                              :new-leader     this-server
                              :message        (str "This server, " this-server
                                                   ", received the majority of the votes to become leader. "
                                                   "New term: " (:term raft-state) ", latest index: "
                                                   (:index raft-state) ".")
                              :server         this-server
                              :new-raft-state raft-state
                              :old-raft-state raft-state*})
    (queue-append-entries raft-state*)))


(defn request-vote-response-event
  [raft-state {:keys [server request response]}]
  (log/debug "Request vote response from server " server {:response response :request request})
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [status term]} raft-state*
        candidate?  (= :candidate status)]
    (cond
      ;; remote server is newer term, become follower
      (> (:term response) term)
      (let [cause {:cause      :request-vote-response
                   :old-leader (:leader raft-state)
                   :new-leader nil
                   :message    (str "Request votes response from server " server
                                    " returned term " term ", and current term is "
                                    (:term raft-state*) ".")
                   :server     (:this-server raft-state)}]
        (events/become-follower raft-state* (:term response) nil cause))

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
                                 :timeout (async/timeout (events/new-election-timeout raft-state)))
                          ;; register vote for self
                          (assoc-in [:servers this-server :vote] [proposed-term true])
                          ;; queue outgoing messages
                          (#(reduce (fn [state server]
                                      (let [callback (fn [response]
                                                       (async/put! event-chan
                                                                   [:request-vote-response
                                                                    {:server server :request request :response response}]))
                                            message  [:request-vote request callback]]
                                        (assoc-in state [:msg-queue server] message)))
                                    % other-servers)))]
    (log/debug "Requesting leader votes: " request)
    ;; if we have current leader (not nil), we have a leader state change, else this is at least our second try
    (when leader
      (watch/call-leader-watch {:event          :become-follower
                                :cause          :request-votes
                                :old-leader     (:leader raft-state)
                                :new-leader     nil
                                :message        (str "The leader '" leader "' hasn't been heard from within the timeout. "
                                                     "This server is now requesting leadership votes for a proposed term: "
                                                     proposed-term " and an index point: " index ".")
                                :server         this-server
                                :new-raft-state raft-state
                                :old-raft-state raft-state*}))
    ;; for raft of just one server, become leader
    (if (empty? other-servers)
      (-> (become-leader raft-state*)
          (events/update-commits (:index raft-state*)))
      raft-state*)))


(defn new-command-event
  "Processes new commands. Only happens if currently a raft leader."
  [raft-state command-events]
  (let [{:keys [this-server other-servers]} raft-state
        raft-state* (loop [[cmd-event & r] command-events
                           raft-state raft-state]
                      (let [[_ command persist-callback] cmd-event
                            {:keys [entry id timeout callback]} command
                            new-index    (inc (:index raft-state))
                            log-entry    {:term (:term raft-state) :entry entry :id id}
                            _            (raft-log/write-new-command (:log-file raft-state) new-index log-entry)
                            raft-state*  (-> raft-state
                                             (assoc :index new-index
                                                    :latest-index new-index)
                                             ;; match-index majority used for updating leader-commit
                                             (assoc-in [:servers this-server :match-index] new-index))
                            raft-state** (if (fn? callback)
                                           (events/register-callback-event raft-state* id timeout callback)
                                           raft-state*)]
                        (events/safe-callback persist-callback true)
                        (if r
                          (recur r raft-state**)
                          raft-state**)))]
    (if (empty? other-servers)
      ;; single-server raft, automatically update commits
      (events/update-commits raft-state* (:index raft-state*))
      (queue-append-entries raft-state*))))