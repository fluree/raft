(ns fluree.raft.log)


(defn modified-nth
  "Gets nth index from log, based on our current-index."
  [log index current-index]
  (let [offset (- current-index index)
        i      (dec (- (count log) offset))]
    (nth log i)))


(defn sublog
  "Like 'subvec' but works on log.

  start (inclusive) and end (exclusive) are relative to the current-index of the raft log.

  Any start that is earlier than what is in the current log will start at the beginning of the log."
  [log start end current-index]
  (let [start-offset (- current-index start)
        end-offset   (- current-index end)
        entries      (count log)
        start*       (max 0 (- (dec entries) start-offset))
        end*         (dec (- entries end-offset))]
    (subvec log start* end*)))


(defn append
  "Append entries to log starting after-index relative to current-index."
  [log entries after-index current-index]
  (if (= after-index current-index)
    ;; ideal case, just concat
    (into log entries)
    ;; we have entr(ies) that overlap, need to first remove overlapping entries and then concat
    (let [log* (sublog log 0 (inc after-index) current-index)]
      (into log* entries))))

