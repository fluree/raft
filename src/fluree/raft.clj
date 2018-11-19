(ns fluree.raft
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [fluree.raft.config :as config]
            [fluree.raft.log :as raft-log]
            [fluree.raft.leader :as leader]))


(defn persist-term
  "Persists a new term entry into log."
  [raft term voted-for]
  (let [persist-fn (config/persist-log-fn raft)]
    (persist-fn :term {:term term :voted-for voted-for})))


(defn persist-entries
  "Persists a set of log entries"
  [raft header entries]
  (let [persist-fn (config/persist-log-fn raft)]
    (persist-fn :entries {:header header :entries entries})))


(defmulti raft-handler (fn [_ op _] op))

(defmethod raft-handler :append-entries
  [raft _ args]
  (let [{:keys [leader-id prev-log-index prev-log-term entries leader-commit]} args
        proposed-term (:term args)
        response      (volatile! nil)]
    (swap! (:raft-state raft)
           (fn [state]
             (let [{:keys [term index log commit]} state]
               (cond
                 ;; old term, reject and no modifications
                 (< proposed-term term)
                 (do
                   (vreset! response {:term term :success false})
                   state)

                 ;; leader term is ok but don't have an entry at prev-log-index - reject
                 (> prev-log-index index)
                 (let [next-timeout (leader/reset-election-timeout raft)
                       state*       (cond-> (assoc state :next-timeout next-timeout)
                                            ;; newer term, reset some stuff
                                            (> proposed-term term)
                                            (assoc state :term proposed-term
                                                   :voted-for nil
                                                   :leader leader-id))]
                   (vreset! response {:term proposed-term :success false})
                   state*)

                 ;; check that our local log entry at prev-log-index is of the same term as prev-log-term
                 (or (= 0 prev-log-index)                   ;; initialized log, no entries - ok
                     (= prev-log-term (:term (raft-log/modified-nth log prev-log-index index))))
                 (let [next-timeout (leader/reset-election-timeout raft)
                       state*       (cond-> (assoc state :next-timeout next-timeout
                                                         :leader leader-id)

                                            ;; heartbeats will not contain any new entries, check before updating
                                            (not-empty entries)
                                            (assoc :log (raft-log/append log entries prev-log-index index) ;; append entries
                                                   :index (+ prev-log-index (count entries)))

                                            ;; update term/leader if newer than current registered term
                                            (> proposed-term term)
                                            (assoc :term proposed-term
                                                   :voted-for nil
                                                   :status :follower)

                                            ;; apply log entries to state machine if leader-commit is newer
                                            (> leader-commit commit)
                                            (#(let [{:keys [log index]} %
                                                    state-machine-fn (get-in raft [:config :state-machine])
                                                    commit-entries   (raft-log/sublog log (inc commit) (inc leader-commit) index)]
                                                (doseq [entry-map commit-entries]
                                                  (state-machine-fn (:entry entry-map)))
                                                (assoc % :commit leader-commit))))]
                   (vreset! response {:term proposed-term :success true})
                   state*)

                 ;; entry at prev-log-index does not have matching term with prev-log-term
                 :else
                 (let [next-timeout (leader/reset-election-timeout raft)
                       state*       (cond-> (assoc state :next-timeout next-timeout
                                                         :log (raft-log/sublog log 0 prev-log-index index) ;; remove offending entries
                                                         :index (dec prev-log-index))
                                            ;; newer term, reset some stuff
                                            (> proposed-term term)
                                            (assoc :term proposed-term
                                                   :voted-for nil
                                                   :leader leader-id))]
                   (vreset! response {:term proposed-term :success false})
                   state*)))))
    @response))


(defmethod raft-handler :request-vote
  [raft _ args]
  (let [{:keys [candidate-id last-log-index last-log-term]} args
        proposed-term (:term args)]
    (let [resp (volatile! {})]
      (swap! (:raft-state raft)
             (fn [state]
               (let [{:keys [index term log]} state
                     my-last-log-term (or (:term (peek log)) 0)]

                 (cond
                   ;; do nothing, false vote... we are at newer term
                   (< proposed-term term)
                   (do
                     (vreset! resp {:term term :vote-granted false})
                     state)

                   ;; Reject if has older log than ours
                   ;; older means last-log-term < ours, or if same then index < ours
                   (or (< last-log-term my-last-log-term)
                       (and (= last-log-term my-last-log-term)
                            (< last-log-index index)))
                   (do
                     (vreset! resp {:term term :vote-granted false})
                     state)

                   ;; otherwise, it is at least same term or more, and same index point or newer.. true vote
                   :else
                   (do
                     (vreset! resp {:term proposed-term :vote-granted true})
                     (persist-term raft proposed-term candidate-id)
                     (assoc state :term proposed-term
                                  :voted-for candidate-id
                                  :status :follower))))))
      @resp)))


(defmethod raft-handler :default [raft header args]
  (throw (Exception. (str "Unknown incoming RPC:" (pr-str header) (pr-str args)))))



(defn default-handler
  [raft header data]
  (let [resp-chan (:resp-chan header)
        op        (:op header)
        resp      (raft-handler raft op data)]
    (async/put! resp-chan resp)))


(def default-state (atom {}))


(defn default-state-machine
  "Default is a simple key-val store.

  Entry should be two-tuples of form:
  [[key-sequence] val]

  If val is nil, deletes entry from state."
  [entry]
  (let [[header [op k v cas]] entry
        result (volatile! true)]
    (swap! default-state (fn [state]
                           (case op
                             :write (assoc state k v)
                             :delete (if (contains? state k)
                                       (dissoc state k)
                                       (do (vreset! result false)
                                           state))
                             :cas (if (= cas (get state k))
                                    (assoc state k v)
                                    (do
                                      (vreset! result false)
                                      state)))))
    @result))


(defn read
  "attempts to read a key from state"
  [key]
  (get @default-state key))


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
  [raft header data]
  (let [handler (get-in raft [:config :rpc-handler-fn])]
    (handler raft header data)))


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


(defn initialize
  "Initializes raft at startup."
  [raft]
  ;; set initial timeout value
  (let [election-timeout   (get-in raft [:config :election-timeout])
        timeout-reset-chan (get-in raft [:config :timeout-reset-chan])]

    ;; launch go-loop that will keep timeouts current.
    (async/go-loop [timeout-ms (+ election-timeout (rand-int election-timeout))]
      (let [timeout-chan (async/timeout timeout-ms)
            ;; take first of timeout channel returning or a new timeout put on the timeout-reset channel
            [next-timeout c] (async/alts! [timeout-chan timeout-reset-chan])
            timeout?     (= c timeout-chan)]

        ;; note: do not close timeout channel even though we no longer need
        (cond
          (and (nil? next-timeout) (= timeout-reset-chan c))
          (println "Closing election timeout loop for RAFT.")

          timeout?
          (let [leader?      (leader/is-leader? raft)
                next-timeout (if leader?
                               (get-in raft [:config :broadcast-time])
                               (leader/generate-election-timeout raft))]
            (if (leader/is-leader? raft)
              (leader/send-append-entries raft)
              (leader/request-votes raft))
            (recur next-timeout))

          :else
          (do
            (when (leader/is-leader? raft)
              (println (str (get-in raft [:config :this-server]) " -***- " next-timeout))
              (leader/send-append-entries raft))
            (recur next-timeout)))))))


(defn start
  [config]
  (let [{:keys [this-server servers
                election-timeout broadcast-time
                persist-path
                min-log snapshot-threshold

                state-machine

                send-rpc-fn rpc-handler-fn

                close-fn]
         :or   {election-timeout   13000                    ;; election-timeout, good range is 10ms->500ms
                broadcast-time     4000                     ;; heartbeat broadcast-time
                persist-path       "tmp/raft/"              ;; directory to store state
                min-log            10                       ;; keep log at minimum this size, don't snapshot more than this
                snapshot-threshold 10                       ;; number of log entries to snapshot at minumum
                rpc-handler-fn     default-handler
                state-machine      default-state-machine
                }} config


        raft {:config     (assoc config :election-timeout election-timeout
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
                                        :this-server this-server
                                        :timeout-reset-chan (async/chan) ;; when val is put to chan, will reset timeouts
                                        :close close-fn)
              :raft-state (atom {:next-timeout 0            ;; will hold time of next election timeout in epoch millis

                                 :log          []           ;; latest log entries in memory
                                 :term         0            ;; latest term
                                 :index        0            ;; latest index
                                 :snapshot     0            ;; index point of last snapshot
                                 :commit       0            ;; commit point in index
                                 :voted-for    nil          ;; for the :term specified above, who we voted for


                                 :status       nil          ;; candidate, leader, follower

                                 ;; map of servers participating in consensus. server id is key, state of server is val
                                 :servers      (reduce #(assoc %1 %2 {:vote        nil
                                                                      :next-index  0
                                                                      :match-index 0}) {} servers)

                                 :leader       nil          ;; current known leader
                                 })

              }]
    (initialize raft)
    raft))

(defn whois-leader
  "Returns current leader server id."
  [raft]
  (:leader @(:raft-state raft)))

(defn add-new-entry
  [raft entry]
  (let [this-server (get-in raft [:config :this-server])
        leader      (whois-leader raft)]
    (if (not= leader this-server)
      false
      (do
        (swap! (:raft-state raft)
               (fn [state]
                 (assoc state :log (conj (:log state) {:term  (:term state)
                                                       :entry entry})
                              :index (inc (:index state)))))))))
