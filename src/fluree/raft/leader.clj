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
  (if (get-in raft-state [:servers server])
    (update-in raft-state [:servers server :stats]
                 (fn [stats]
                   (let [{:keys [received avg-response]} stats
                         received* (inc received)]
                     (assoc stats :received received*
                                  :avg-response (float (/ (+ response-time (* received avg-response)) received*))))))
    raft-state))


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
  ([raft-state server]
   (queue-install-snapshot raft-state server false))
  ([raft-state server pending?]
   (let [{:keys [snapshot-index snapshot-term]} raft-state]
     (queue-install-snapshot raft-state server snapshot-index snapshot-term 1 pending?)))
  ([raft-state server snapshot-index snapshot-term snapshot-part]
   (queue-install-snapshot raft-state server snapshot-index snapshot-term snapshot-part false))
  ([raft-state server snapshot-index snapshot-term snapshot-part pending?]
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
                        :instant        (System/currentTimeMillis)
                        :pending?       pending?}
         callback      (fn [response] (async/put! event-chan
                                                  [:install-snapshot-response {:server   server
                                                                               :request  (dissoc data :snapshot-data)
                                                                               :response response}]))]
     (cond-> raft-state
             true (assoc-in [:msg-queue server] [:install-snapshot data callback])
             pending? (update :pending-server #(assoc % :snapshot-index snapshot-index))
             (not pending?) (update-in [:servers server] #(assoc % :snapshot-index snapshot-index))))))


(defn queue-add-server
  [raft-state server]
  (let [{:keys [index other-servers config term pending-server this-server]} raft-state
        {:keys [event-chan]} config
        server-state  pending-server
        {:keys [next-index]} server-state

        ;; Is this sufficient?
        rs* (update-in raft-state [:pending-server :catch-up-rounds] dec)]
    ;; As long as next-index is within 1 log of index, we'll add the server
    (cond (and (> (inc next-index) index) (empty? other-servers))
          (events/apply-config-change raft-state {:server server :op :add})

          (> (inc next-index) index)
          (let [req {:server server
                     :command-id    (:command-id pending-server)
                     :op             :add
                     :term           term
                     :leader-id      this-server
                     :instant        (System/currentTimeMillis)}]
            (reduce (fn [rs recipient-server]
                      (let [callback (fn [response]
                                       (async/put! event-chan
                                                   [:config-change-response
                                                    {:server   recipient-server
                                                     :request  req
                                                     :response response}]))]
                        (assoc-in rs [:msg-queue recipient-server] [:config-change req callback])))
                    rs* other-servers))

          :else
        ;; Else remove server from raft-state and shut-down
        (let [_ (log/warn (str "Server " server " did not sync in the allotted number of rounds. Please delete this server's files, and attempt to add again. Depending on your network's connectivity, you may want to increase :fdb-group-catch-up-rounds."))
              cfg-servers (get-in raft-state [:config :servers])
              cfg-servers* (filterv #(not= server %) cfg-servers)
              other-servers (:other-servers raft-state)
              other-servers* (filterv #(not= server %) other-servers)
              servers (-> (:servers raft-state)
                          (dissoc server))
              rs (-> (assoc-in raft-state [:config :servers] cfg-servers*)
                     (assoc-in [:other-servers] other-servers*)
                     (assoc-in [:servers] servers)
                     (dissoc :pending-server))]
          (assoc-in rs [:msg-queue server] [:close :add-server-timeout nil])))))


;; TODO - rename :snapshot-index in server state to something like :sending-snapshot, so different name than what is in raft-state
(defn- queue-append-entry
  ([raft-state server]
   (queue-append-entry raft-state server false))
  ([raft-state server pending?]
   (let [{:keys [term index commit this-server snapshot-index config]} raft-state
         server-state   (if pending?
                          (:pending-server raft-state)
                          (get-in raft-state [:servers server]))
         {:keys [next-index]} server-state
         send-snapshot? (if snapshot-index (<= next-index snapshot-index) false)
         raft-state*    (if pending?
                          (update-in raft-state [:pending-server :catch-up-rounds] dec)
                          raft-state)]
     (cond
       ;; if currently sending a snapshot, wait until done before sending more append-entries
       (:snapshot-index server-state)                       ;; indicates currently sending a snapshot
       raft-state*

       ;; we need to send a snapshot, reached end of our log
       send-snapshot?
       (queue-install-snapshot raft-state* server pending?)

       ;; standard case
       :else
       (let [{:keys [event-chan entries-max]} config
             end-index      (min index (dec (+ next-index entries-max))) ;; send at most entries-max
             prev-log-index (max (dec next-index) 0)
             prev-log-term  (cond
                              (= 0 prev-log-index)
                              0

                              (= snapshot-index prev-log-index)
                              (:snapshot-term raft-state*)

                              :else
                              (raft-log/index->term (:log-file raft-state*) prev-log-index))
             entries        (if (> next-index index)
                              []
                              (raft-log/read-entry-range (:log-file raft-state*) next-index end-index))
             data           {:term           term
                             :leader-id      this-server
                             :prev-log-index prev-log-index
                             :prev-log-term  prev-log-term
                             :entries        entries
                             :leader-commit  commit
                             :pending?       pending?
                             :instant        (System/currentTimeMillis)}
             callback       (fn [response] (async/put! event-chan
                                                       [:append-entries-response {:server   server
                                                                                  :request  data
                                                                                  :response response}]))
             message        [:append-entries data callback]]
         (cond-> raft-state*
                 true (assoc-in [:msg-queue server] message)
                 pending?       (assoc-in [:pending-server :next-index] (inc end-index))
                 (not pending?) (assoc-in [:servers server :next-index] (inc end-index))))))))
             ;; update next-index, we will send out parallel updates as needed



(defn install-snapshot-response-event
  "Response map contains two keys:
  - term - current term of server - used to determine if we lost leadership
  - next-part - next part of the snapshot we should send, or nil/0 if we should send no more."
  [raft-state {:keys [server request response]}]
  (log/debug "Install snapshot response from server: " server {:response response :request request})
  (let [{:keys [snapshot-index snapshot-term snapshot-part snapshot-parts pending?]} request
        {:keys [term next-part]} response
        raft-state* (if pending?
                      raft-state
                      (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request))))
        done?       (or (not (pos-int? next-part))
                        (and (int? next-part) (> next-part snapshot-parts)))]
    (cond
      ;; lost leadership
      (not (is-leader? raft-state*))
      raft-state*

      ;; response has a newer term, go to follower status, reset pending servers, and reset election timeout
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
      (cond-> raft-state*
              pending?       (update-in [:pending-server]
                                        #(assoc % :next-index (inc snapshot-index)
                                                  :match-index snapshot-index
                                                  :snapshot-index nil))
              (not pending?) (update-in [:servers server]
                                        #(assoc % :next-index (inc snapshot-index)
                                                  :match-index snapshot-index
                                                  :snapshot-index nil))
              true (update-commit)
              true (queue-append-entry server pending?))

      ;; send next part of snapshot
      (not done?)
      (queue-install-snapshot raft-state* server snapshot-index snapshot-term (inc snapshot-part) pending?))))


(defn queue-append-entries
  "Forces update messages for all servers to be placed in the queue.
  Called after a heartbeat timeout or when a commit index is updated.
  Resets heartbeat timeout."
  [raft-state]
  (let [{:keys [other-servers pending-server]} raft-state
        heartbeat-time (get-in raft-state [:config :heartbeat-ms])]
    (log/trace "Raft leader queue sending append-entry. " {:instant        (System/currentTimeMillis)
                                                           :heartbeat-ms   heartbeat-time
                                                           :next-heartbeat (+ (System/currentTimeMillis)
                                                                              heartbeat-time)})
    (let [raft-state*    (cond ;; Attempting to add server and time to check if it's synced
                              (and pending-server
                                   (= :add (get-in raft-state [:pending-server :op]))
                                   (= 0 (get-in raft-state [:pending-server :catch-up-rounds])))
                              (queue-add-server raft-state (:id pending-server))

                              ;; Pending server * addition *, if we're pending server deletion, it'll still be in other-servers
                              (and pending-server
                                   (= :add (get-in raft-state [:pending-server :op])))
                              (let [rs* (queue-append-entry raft-state (:id pending-server) true)]
                                (reduce (fn [raft-state* server]
                                            (queue-append-entry raft-state* server)) rs* other-servers))

                              ;; No pending server, just append entries
                              :else
                              (reduce (fn [raft-state* server]
                                        (queue-append-entry raft-state* server)) raft-state other-servers))]

      (assoc raft-state* :timeout (async/timeout heartbeat-time)
                      :timeout-ms heartbeat-time
                      :timeout-at (+ heartbeat-time (System/currentTimeMillis))))))


(defn append-entries-response-event
  "Updates raft state with an append-entries response. Responses may come out of order.

  A few of the things that can happen:
  - If response has a newer term, we'll become a follower
  - If the response has success: true, we'll update that server's stats and possibly update the commit index
  - If the response has success: false, we'll decrement that server's next-index and resend a new append-entry
    to that server immediately with older log entries."
  [{:keys [other-server] :as raft-state} {:keys [server request response]}]
  (log/trace "Append entries response from server:" server {:response response :request (dissoc request :entries)})
  (let [{:keys [term success]} response
        {:keys [prev-log-index entries pending?]} request
        active?     (get-in raft-state [:servers server])
        ;; Could have become active between when this append-entry was sent and received
        pending?    (and pending? (not active?))
        raft-state* (if (not active?)
                      raft-state
                      (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request))))
        next-index  (inc (+ prev-log-index (count entries)))]
    (cond (not active?) raft-state*
      ;; if we are no longer leader, ignore response
      (not (is-leader? raft-state*)) raft-state*

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
      (and (true? success) pending?)
      (-> raft-state*
          (update :pending-server #(assoc % :next-index (max next-index (:next-index %))
                                                 :match-index (max (dec next-index) (:match-index %)))))

      (true? success)
      (-> raft-state*
          (update-in [:servers server] #(assoc % :next-index (max next-index (:next-index %))
                                                 :match-index (max (dec next-index) (:match-index %)))))

      ;; update failed - decrement next-index to prev-log-index of original request and re-send
      (false? success)
      (let [next-index    (if pending?
                            (get-in raft-state* [:pending-server :next-index])
                            (get-in raft-state* [:servers server :next-index]))
            ;; we already got back a response that this index point wasn't valid, don't re-trigger sending another message
            old-response? (<= next-index prev-log-index)]
        (cond-> raft-state*
                pending? (update  :pending-server
                                    (fn [server-state]
                                      ;; in the case we got an out-of-order response, next-index might already be set lower than
                                      ;; prev-log-index for this request, take the minimum
                                      (assoc server-state :next-index (min prev-log-index (:next-index server-state)))))

                (not pending?) (update-in [:servers server]
                                          (fn [server-state]
                                            ;; in the case we got an out-of-order response, next-index might already be set lower than
                                            ;; prev-log-index for this request, take the minimum
                                            (assoc server-state :next-index (min prev-log-index (:next-index server-state)))))

                (not old-response?) (queue-append-entry server pending?))))))


(defn- become-leader
  "Once majority of votes to elect us as leader happen, actually become new leader for term leader-term."
  [raft-state]
  (log/debug (format "Becoming leader, leader: %s, term: %s, latest index: %s."
                     (:this-server raft-state) (:term raft-state) (:index raft-state)))
  (let [{:keys [this-server index servers other-servers]} raft-state
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
                                         :pending-server nil
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
    (-> raft-state*
      ;(reduce (fn [rs server]
      ;            (assoc-in rs [:msg-queue server]  [:initialize-config servers nil]))
      ;          raft-state* other-servers)
        (queue-append-entries))))


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


(defn queue-apply-config-change
  [raft-state request]
  (let [{:keys [other-servers term]} raft-state
        {:keys [server op command-id]} request
        event-chan (-> raft-state :config :event-chan)
        req {:term           term
             :instant        (System/currentTimeMillis)
             :server server
             :op  op
             :command-id command-id}]
    (reduce (fn [rs recipient-server]
              (let [callback (fn [response]
                               (async/put! event-chan
                                           [:config-change-commit-response
                                            {:server   recipient-server
                                             :request  req
                                             :response response}]))]
                 (assoc-in rs [:msg-queue recipient-server] [:config-change-commit req callback])))
            raft-state (conj other-servers server))))


(defn config-change-response-event
  [raft-state {:keys [server request response]}]
  (log/debug "Config change response from server " server {:response response :request request})
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [status term]} raft-state*]
    (if
      ;; remote server is newer term, become follower. Lose any pending config changes.
      ;; We only get a false response if follower has newer term.
      (or (false? (:success response))
          (> (:term response) term))
      (let [cause {:cause      :config-change-response
                   :old-leader (:leader raft-state)
                   :new-leader nil
                   :message    (str "Config change response from server " server
                                    " returned term " term ", and current term is "
                                    (:term raft-state*) ".")
                   :server     (:this-server raft-state)}]
        (events/become-follower raft-state* (:term response) nil cause))

      (let [raft-state*    (update-in raft-state* [:pending-server :votes] conj server)
            pending-votes  (-> (get-in raft-state* [:pending-server :votes]) set count inc)
            majority?      (> pending-votes (/ (count (:servers raft-state*)) 2))]
        (if majority?
          (let [server-state (-> raft-state :pending-server
                                 (dissoc :votes :op :catch-up-rounds :command-id))]
            (->  raft-state
                 (events/apply-config-change request server-state)
                (queue-apply-config-change request)))
          raft-state*)))))


(defn close-leader
  [raft-state]
  (let [command-chan (get-in raft-state [:config :command-chan])]
    (do (async/put! command-chan [:close])
        raft-state)))


(defn config-change-commit-response-event
  [raft-state {:keys [server request response]}]
  (log/debug "Config change commit response from server " server {:response response :request request})
  (let [raft-state* (update-server-stats raft-state server (- (System/currentTimeMillis) (:instant request)))
        {:keys [status term this-server]} raft-state*
        voting-server server]
    (if (> (:term response) term)
      ;; remote server is newer term, become follower. Lose any pending config changes.
      (let [cause {:cause      :config-change-response
                   :old-leader (:leader raft-state)
                   :new-leader nil
                   :message    (str "Config change response from server " server
                                    " returned term " term ", and current term is "
                                    (:term raft-state*) ".")
                   :server     (:this-server raft-state)}]
        (events/become-follower raft-state* (:term response) nil cause))

      (let [{:keys [server op command-id]}     request
            config-change-status    (get-in raft-state [:config-change-committed :status])]
        (if (not= :complete config-change-status)
          (let [raft-state*   (update-in raft-state* [:config-change-committed :votes] conj voting-server)
                pending-votes (-> (get-in raft-state* [:config-change-committed :votes]) set count inc)
                majority?     (> pending-votes (/ (count (:servers raft-state*)) 2))]
            (if majority?
              ;; If add server, just remove all pending info
               (cond-> raft-state*
                       true (assoc :pending-server nil)
                       true (assoc-in [:config-change-commit-response :status] :complete)
                       (and (= :remove op)
                            (= server this-server)) (close-leader)
                       (and (= :remove op)
                            (not= server this-server)) (assoc-in [:msg-queue server] [:close :removed-server nil]))
              raft-state*))
          raft-state*)))))


(defn queue-config-change
  [{:keys [this-server other-servers] :as raft-state} data callback op]
  (let [[command-id pending-server] data]
    (cond (and (= :add op) (get-in raft-state [:servers pending-server]))
          ;; server already exists, return raft state
          (do (events/safe-callback callback
                                    (ex-info
                                      (str pending-server " already exists in our network configuration.")
                                      {:operation :new-command
                                       :error     :raft/cannot-add-server})) raft-state)

          (and (= :remove op) (not (get-in raft-state [:servers pending-server])))
          ;; server already exists, return raft state
          (do (events/safe-callback callback
                                    (ex-info
                                      (str pending-server " is not in our network configuration. Cannot remove.")
                                      {:operation :new-command
                                       :error     :raft/cannot-remove-server})) raft-state)

          (:pending-server raft-state)
          ;; In the middle of adding or removing a server, cannot make two config changes at once.
          (do (events/safe-callback callback
                                    (ex-info
                                      (str "Cannot have two configuration changes pending at once. Currently attempting to "
                                           (-> raft-state :pending-server :op str (subs 1)) " " (-> raft-state :pending-server :id))
                                      {:operation :new-command
                                       :error     :raft/cannot-add-server})) raft-state)

          ;; Add server to leader's :pending-server ONLY. This means the server has
          ;; (-> raft :config :catch-up-rounds) - default 10 - rounds to catch up.
          ;; When server is caught up, the leader will send a message to all followers
          ;; to commit the new server.config
          (= :add op)
          (let [catch-up-rounds (-> raft-state :config :catch-up-rounds)]
            (do (events/safe-callback callback {:op     :add
                                                :server pending-server
                                                :command-id command-id
                                                :message (str "The server " pending-server " has " catch-up-rounds " rounds to sync its RAFT logs with the network.")})
                (assoc raft-state :pending-server (merge events/server-state-baseline
                                                         {:id              pending-server
                                                          :op              :add
                                                          :command-id      command-id
                                                          :catch-up-rounds catch-up-rounds}))))

          (and (empty? other-servers) (= :remove op))
          (do (events/safe-callback callback
                                    (ex-info
                                      (str "Cannot remove server if there are no other servers in the configuration.")
                                      {:operation :new-command
                                       :error     :raft/cannot-remove-server})) raft-state)

          ;; Remove server can go immediately to all followers. Once a majority of the network
          ;; has this change committed, we'll change the configuration
          (= :remove op)
          (let [{:keys [other-servers term]} raft-state
                event-chan (-> raft-state :config :event-chan)
                req {:server pending-server
                     :op             :remove
                     :term           term
                     :leader-id      this-server
                     :instant        (System/currentTimeMillis)
                     :command-id     command-id}]
            (do (events/safe-callback callback {:op         :remove
                                                :server     pending-server
                                                :command-id command-id
                                                :message    (str "The server " pending-server " is slated to be removed from the network.")})
                (reduce (fn [rs recipient-server]
                          (let [callback (fn [response]
                                           (async/put! event-chan
                                                       [:config-change-response
                                                        {:server   recipient-server
                                                         :request  req
                                                         :response response}]))]
                            (assoc-in rs [:msg-queue recipient-server] [:config-change req callback])))
                        raft-state other-servers))))))


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
