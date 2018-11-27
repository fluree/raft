(ns fluree.raft
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [fluree.raft.log :as raft-log]
            [fluree.raft.leader :as leader]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))


(defn persist-term
  "Persists a new term entry into log."
  [raft term voted-for]
  (let [persist-fn :TODO #_(config/persist-log-fn raft)]
    (persist-fn :term {:term term :voted-for voted-for})))


(defn persist-entries
  "Persists a set of log entries"
  [raft header entries]
  (let [persist-fn :TODO #_(config/persist-log-fn raft)]
    (persist-fn :entries {:header header :entries entries})))



(defn default-handler
  [raft operation data callback]
  (let [event-chan (get-in raft [:config :timeout-reset-chan])]
    (async/put! event-chan [operation data callback])))


(def stored-snapshot (atom nil))

(defn get-snapshot
  []
  @stored-snapshot)


(defn persist-snapshot
  [snapshot]
  (reset! stored-snapshot snapshot))

(def persisted-entries (atom []))

(defn persist!
  "Persist a log entry. Because we do :append-entries, snapshot, etc.
  this is a two-tuple of first the type of log entry and then the entries itself.

  entry-types include
  :append-entries {:index-before i :entries [entries]}
  :remove-entries [from-i to-i] (remove i and ever entry after it)
  :snapshot {:index i :snapshot shapshot-data}"
  [entry-type data]
  ;; TODO - SNAPSHOT saving
  (swap! persisted-entries conj [entry-type data]))


(defn get-log
  []
  @persisted-entries)


(defn invoke-rpc-handler
  [raft op data callback]
  (let [handler (get-in raft [:config :rpc-handler-fn])]
    (handler raft op data callback)))


(defn close
  "Closes a raft process."
  [raft]
  (let [timeout-reset-chan (get-in raft [:config :timeout-reset-chan])
        close-fn           (get-in raft [:config :close-fn])]
    (async/close! timeout-reset-chan)
    (when (fn? close-fn)
      (close-fn))))


;; TODO - need to persist!!
(defn write-file
  [val path]
  (try
    (with-open [out (io/output-stream (io/file path))]
      (.write out val))
    (catch java.io.FileNotFoundException e
      (io/make-parents (io/file path))
      (with-open [out (io/output-stream (io/file path))]
        (.write out val)))
    (catch Exception e (throw e))))


(defn request-vote-event
  "Grant vote to server requesting leadership if:
  - proposed term is >= current term
  - we haven't already voted for someone for this term
  - log index + term is at least as far as our log
  "
  [raft-state args callback]
  (let [{:keys [candidate-id last-log-index last-log-term]} args
        proposed-term    (:term args)
        {:keys [index term log voted-for]} raft-state
        my-last-log-term (or (:term (peek log)) 0)
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
                           (assoc raft-state :term proposed-term
                                             :voted-for candidate-id
                                             :status :follower))]
    (callback response)
    raft-state*))


(defn update-commits
  [raft-state leader-commit]
  (if (= (:commit raft-state) leader-commit)
    raft-state                                              ;; no change
    (let [{:keys [log index commit]} raft-state
          state-machine-fn   (get-in raft-state [:config :state-machine])
          commit-entries     (raft-log/sublog log (inc commit) (inc leader-commit) index)
          command-callbacks  (:command-callbacks raft-state)
          command-callbacks* (reduce
                               (fn [callbacks entry-map]
                                 (let [resp (state-machine-fn (:entry entry-map))]
                                   (if-let [callback-chan (get callbacks (:id entry-map))]
                                     (do
                                       (async/put! callback-chan resp)
                                       (dissoc callbacks (:id entry-map)))
                                     callbacks)))
                               command-callbacks commit-entries)]
      (assoc raft-state :command-callbacks command-callbacks*
                        :commit leader-commit))))


(defn append-entries-event
  [raft-state args callback]
  (let [{:keys [leader-id prev-log-index prev-log-term entries leader-commit]} args
        proposed-term          (:term args)
        {:keys [term index log commit]} raft-state
        term-at-prev-log-index (cond
                                 (= 0 prev-log-index) 0

                                 (<= prev-log-index index)
                                 (:term (raft-log/modified-nth log prev-log-index index))

                                 :else nil)
        old-term?              (< proposed-term term)
        logs-match?            (= prev-log-term term-at-prev-log-index)
        raft-state*            (cond-> (assoc raft-state :timeout (async/timeout (leader/generate-election-timeout raft-state))
                                                         :leader leader-id)

                                       ;; leader's term is newer, update leader info
                                       (> proposed-term term)
                                       (assoc :term proposed-term
                                              :voted-for nil
                                              :leader leader-id
                                              :status :follower)

                                       ;; we have a log match at prev-log-index
                                       (and logs-match? (not-empty entries))
                                       (assoc :log (raft-log/append log entries prev-log-index index) ;; append entries
                                              :index (+ prev-log-index (count entries)))


                                       ;; entry at prev-log-index doesn't match local log term, remove offending entries
                                       (not logs-match?)
                                       (assoc :log (raft-log/sublog log 0 prev-log-index index) ;; remove offending entries
                                              :index (dec prev-log-index))

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
    (try (callback data) (catch Exception _ nil))))


(defn command-response
  "Generates a new command response, uses timeout for default response."
  [raft-state id resp-chan timeout-ms callback]
  (let [timeout-reset-chan (get-in raft-state [:config :timeout-reset-chan])]
    (async/go
      (try
        (let [timeout-chan (async/timeout timeout-ms)
              [resp c] (async/alts! [resp-chan timeout-chan])
              timeout?     (= timeout-chan c)]
          ;; if timeout, clear callback from raft-state
          (when timeout?
            (async/put! timeout-reset-chan [:new-command-timeout id]))
          (if timeout?
            (safe-callback callback (ex-info "Command timed out." {:operation :new-command
                                                                   :error     :raft/command-timeout
                                                                   :id        id}))
            (safe-callback callback resp)))
        (catch Exception e (log/error e) (throw e))))))


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
  (let [event-channel (get-in raft-state [:config :timeout-reset-chan])
        this-server   (:this-server raft-state)]
    (async/go-loop [raft-state (assoc raft-state
                                 :timeout (async/timeout
                                            (leader/generate-election-timeout raft-state)))]
      (let [timeout-chan (:timeout raft-state)
            [event c] (async/alts! [timeout-chan event-channel])
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
                      (safe-callback callback (ex-info "Server is not currently leader." {:operation :new-command
                                                                                          :error     :raft/not-leader}))
                      (let [id          (or (:id data) (str (UUID/randomUUID)))
                            timeout     (or (:timeout data) (get-in raft-state [:config :default-command-timeout]))
                            resp-chan   (async/promise-chan)
                            new-index   (inc (:index raft-state))
                            raft-state* (-> raft-state
                                            (update :log conj {:term (:term raft-state) :entry data :id id})
                                            (assoc :index new-index)
                                            (assoc-in [:servers this-server :match-index] new-index) ;; match-index majority used for updating leader-commit
                                            (assoc-in [:command-callbacks id] resp-chan))]
                        ;; create a go-channel to monitor for response
                        (command-response raft-state* id resp-chan timeout callback)
                        ;; kick off an append-entries call
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
                  (catch Exception e (throw (ex-info (str "Raft error processing command: " op) raft-state e))))]
            (when (not= :close op)
              (recur raft-state*))))))))


(defn default-kv-state-machine
  "Basic key-val store.

  Operations are tuples that look like:
  [operation key val compare-val]

  Operations supported are:
  - :write  - Writes a new value to specified key. Returns true on success.
              i.e. [:write 'mykey' 42]
  - :read   - Reads value of provided key. Returns nil if value doesn't exist.
              i.e. [:read 'mykey']
  - :delete - Deletes value at specified key. Returns true if successful, or
              false if key doesn't exist. i.e. [:delete 'mykey']
  - :cas    - Compare and set. Compare current value of key with compare-val and
              if equal, set val as new value of key. Returns true on success and false
              on failure. i.e. [:cas 'mykey' 100 42]"
  [state-atom]
  (fn [[op k v compare]]
    (case op
      :write (do (swap! state-atom assoc k v)
                 true)
      :read (get @state-atom k)
      :delete (if (contains? @state-atom k)
                (do (swap! state-atom dissoc k)
                    true)
                false)
      :cas (if (contains? @state-atom k)
             (let [new-state (swap! state-atom (fn [state]
                                                 (if (= compare (get state k))
                                                   (assoc state k v)
                                                   state)))]
               (= v (get new-state k)))
             false))))


(defn start
  [config]
  (let [{:keys [this-server servers
                election-timeout broadcast-time
                persist-path
                min-log snapshot-threshold

                state-machine

                send-rpc-fn rpc-handler-fn
                default-command-timeout

                close-fn]
         :or   {election-timeout        13000               ;; election-timeout, good range is 10ms->500ms
                broadcast-time          4000                ;; heartbeat broadcast-time
                persist-path            "tmp/raft/"         ;; directory to store state
                min-log                 10                  ;; keep log at minimum this size, don't snapshot more than this
                snapshot-threshold      10                  ;; number of log entries to snapshot at minumum
                rpc-handler-fn          default-handler
                state-machine           (default-kv-state-machine (atom {}))
                default-command-timeout 4000
                }} config

        raft-state {:config      (assoc config :election-timeout election-timeout
                                               :broadcast-time broadcast-time
                                               :persist-path persist-path
                                               :rpc-handler-fn rpc-handler-fn
                                               :send-rpc-fn send-rpc-fn
                                               :min-log min-log
                                               :snapshot-threshold snapshot-threshold

                                               :get-snapshot-fn get-snapshot
                                               :persist-snapshot-fn persist-snapshot
                                               :persist-log-fn persist!
                                               :get-log-fn get-log
                                               :state-machine state-machine
                                               :timeout-reset-chan (async/chan) ;; when val is put to chan, will reset timeouts
                                               :close close-fn
                                               :default-command-timeout default-command-timeout)
                    :this-server this-server
                    :status      nil                        ;; candidate, leader, follower
                    :leader      nil                        ;; current known leader

                    :log         []                         ;; latest log entries in memory
                    :term        0                          ;; latest term
                    :index       0                          ;; latest index
                    :snapshot    0                          ;; index point of last snapshot
                    :commit      0                          ;; commit point in index
                    :voted-for   nil                        ;; for the :term specified above, who we voted for

                    ;; map of servers participating in consensus. server id is key, state of server is val
                    :servers     (reduce #(assoc %1 %2 {:vote        nil
                                                        :next-index  0
                                                        :match-index 0}) {} servers)

                    }]
    (event-loop raft-state)
    raft-state))
