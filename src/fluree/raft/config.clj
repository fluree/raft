(ns fluree.raft.config)


(defn- config
  [raft]
  (:config raft))

(defn election-timeout
  [raft]
  (:election-timeout (config raft)))

(defn state
  "Returns current state."
  [raft]
  @(:raft-state raft))

(defn state-get
  "Gets specified key from current state."
  [raft key]
  (get @(:raft-state raft) key))

(defn state-set
  [raft key val]
  (swap! (:raft-state raft) #(if (fn? val)
                               (update % key val)
                               (assoc % key val)))
  (state-get raft key))

(defn leader?
  [raft]
  (state-get raft :leader?))

(defn timeout
  "Returns ms count to timeout."
  [raft]
  (state-get raft :timeout))


(defn term
  "Returns current term."
  [raft]
  (state-get raft :term))

(defn last-commit
  "Return last committed index."
  [raft]
  (state-get raft :log-last-commit))

(defn persist-log-fn
  [raft]
  (:persist-log-fn (config raft)))

(defn servers
  "Returns servers config entry."
  [raft]
  (:servers (config raft)))

(defn this-server
  "Returns id for this server."
  [raft]
  (:this-server (config raft)))

(defn voted-for
  [raft]
  (state-get raft :voted-for))