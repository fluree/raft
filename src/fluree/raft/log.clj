(ns fluree.raft.log
  (:require [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [clojure.tools.logging :as log])
  (:import (java.io FileNotFoundException DataInputStream RandomAccessFile File)))


;; if an index is not a positive integer (an append-entry), it is one of these special types:
(def ^:const entry-types {:current-term -1                  ;; record of latest term we've seen
                          :voted-for    -2                  ;; record of votes
                          :snapshot     -3                  ;; record of new snapshots
                          :no-op        -4})                ;; used to clear out entries that are found to be incorrect


;; reverse map of above
(def ^:const entry-types' (into {} (map (fn [[k v]] [v k]) entry-types)))


(defn- write-entry
  "Writes entry to specified log"
  ([^File file index term entry] (write-entry file index term entry false))
  ([^File file index term entry retry?]
   (try
     (let [^bytes data (nippy/freeze entry)
           len         (count data)
           raf         (RandomAccessFile. file "rw")]
       (doto raf
         (.seek (.length raf))
         (.writeInt len)
         (.writeLong index)
         (.writeLong term)
         (.write data)
         (.close)))
     (catch FileNotFoundException _
       (if retry?
         (do (log/error "Unable to create raft log file. Does the process have permission to create: " (pr-str file))
             (log/error "Fata Error, exiting")
             (System/exit 1))
         (do
           (io/make-parents file)
           (write-entry file index term entry true))))
     (catch Exception e
       (log/error e "Unexpected Error attempting to write entry to raft log:" (pr-str file))
       (log/error e "Fatal Error, exiting.")
       (System/exit 1)))))


(defn write-current-term
  "Record latest term we've seen to persistent log."
  [file term]
  (write-entry file (:current-term entry-types) term term))


(defn write-voted-for
  [file term voted-for]
  (write-entry file (:voted-for entry-types) term voted-for))


(defn write-snapshot
  [file snapshot-index snapshot-term]
  (write-entry file (:snapshot entry-types) snapshot-term snapshot-index))


(defn write-new-command
  "Writes a new command as leader."
  [file index entry]
  (write-entry file index (:term entry) entry))


(defn read-log-file
  "Reads entire log file."
  [^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)]
    (loop [log []]
      (if (>= (.getFilePointer raf) len)
        (do
          (.close raf)
          log)
        (let [next-bytes (.readInt raf)
              ba         (byte-array next-bytes)
              index      (.readLong raf)
              term       (.readLong raf)
              _          (.read raf ba)                     ;; read entry into byte-array
              entry-type (if (pos? index) :append-entry (get entry-types' index))
              entry-data (try (nippy/thaw ba)
                              (catch Exception e (throw (ex-info
                                                          "Raft log file appears to be corrupt. If you have an existing Raft quorom, (for example, you have 3 servers running and 2 have non-corrupted Raft files), delete raft logs for this server and re-start. It will re-sync from other servers. If you do not have an existing quorom, email support@flur.ee"

                                                          {:file            (.getPath file)
                                                           :entry-number    (inc (count log))
                                                           :entry-type-code index
                                                           :entry-type      entry-type
                                                           :entry-term      term
                                                           :entry-bytes     next-bytes
                                                           :bytes           (vec ba)
                                                           :error           (.getMessage e)}))))]
          (recur (conj log [index term entry-type entry-data])))))))


(defn- read-entry
  "Reads a specific index entry from durable log.

  Entries contain:
  int - entry byte size
  long - index (or negative integer as per entry-types constant above)
  long - term
  x - entry bytes of previously specified size"
  [^File file index]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)]
    (loop []
      (let [next-bytes (.readInt raf)
            idx        (.readLong raf)]
        (cond
          (= index idx)
          (let [ba   (byte-array next-bytes)
                term (.readLong raf)]
            (.read raf ba)
            (.close raf)
            (nippy/thaw ba))

          ;; we are past requested index, return nil
          (> idx index)
          (do
            (.close raf)
            nil)

          ;; not there yet, keep seeking
          (< idx index)
          (let [next-pointer (long (+ (.getFilePointer raf) 8 next-bytes))] ;; go past term, entry-type, and entry data
            (if (>= next-pointer len)
              nil
              (do
                (.seek raf next-pointer)
                (recur)))))))))


(defn read-entry-range
  "Reads index from start-index (inclusive) to end-index (inclusive)."
  ([^File file start-index] (read-entry-range file start-index (Long/MAX_VALUE)))
  ([^File file start-index end-index]
   (let [raf (RandomAccessFile. file "r")
         len (.length raf)]
     (loop [acc []]
       (if (= (.getFilePointer raf) len)
         (do
           (.close raf)
           acc)
         (let [next-bytes (.readInt raf)
               idx        (.readLong raf)]
           (cond
             (<= start-index idx end-index)
             (let [ba   (byte-array next-bytes)
                   term (.readLong raf)]
               (.read raf ba)
               (recur (conj acc (nippy/thaw ba))))

             ;; we are past requested index, return acc
             (> idx end-index)
             (do
               (.close raf)
               acc)

             ;; not there yet, keep seeking
             (< idx start-index)
             (let [next-pointer (long (+ (.getFilePointer raf) 8 next-bytes))]
               (do
                 (.seek raf next-pointer)
                 (recur acc))))))))))


(def ^:private index->term-cache (atom {}))
(def ^{:private true :const true} cache-size 10)


(defn clear-index->term-cache
  "Clears cache"
  []
  (reset! index->term-cache {}))


(defn assoc-index->term-cache
  "Implements a simple fifo cache."
  [index term]
  (swap! index->term-cache
         (fn [x]
           (let [x' (assoc x index {:val term :instant (System/currentTimeMillis)})]
             (if (> (count x') cache-size)
               (->> x'
                    (sort-by #(-> % val :instant) >)
                    (take cache-size)
                    (into {}))
               x'))))
  term)

(defn get-index->term-cache
  [index]
  (get-in @index->term-cache [index :val]))


(defn index->term*
  "Returns term of specified index number."
  [file index]
  (-> (read-entry file index)
      :term))


(defn index->term
  "Returns term of specified index number."
  [file index]
  (or (get-index->term-cache index)
      (do
        (log/trace (format "Index->term cache miss for index: %s." index))
        (assoc-index->term-cache
          index (index->term* file index)))))


(defn remove-entries
  "Removes entries from log from start-index (inclusive) to end.

  Changes index of removed entries to -1, so ignored by future reads."
  [^File file start-index]
  (log/debug (format "Remove-entries called to remove all entries starting with: %s." start-index))
  ;; as a precaution, any time we remove entries clear the cache
  (clear-index->term-cache)
  (let [raf (RandomAccessFile. file "rw")
        len (.length raf)]
    (loop []
      (if (= (.getFilePointer raf) len)
        (do
          (.close raf)
          true)
        (let [next-bytes (.readInt raf)
              idx        (.readLong raf)]
          (cond
            (<= start-index idx)
            (do
              (doto raf
                ;; seek back to index
                (.seek (- (.getFilePointer raf) 8))
                ;; write index as -1 so ignored by future reads
                (.writeLong (:no-op entry-types))
                ;; seek to next position
                (.seek (+ (.getFilePointer raf) 8 next-bytes)))
              (recur))

            ;; not there yet, keep seeking
            (< idx start-index)
            (let [next-pointer (long (+ (.getFilePointer raf) 8 next-bytes))]
              (.seek raf next-pointer)
              (recur))))))))


(defn append
  "Append entries to log starting after-index relative to current-index."
  [file entries after-index current-index]
  (if (= after-index current-index)
    ;; ideal case, just concat
    (loop [[entry & r] entries
           index (inc after-index)]
      (if entry
        (do
          (write-entry file index (:term entry) entry)
          (recur r (inc index)))
        true))
    ;; we have entr(ies) that overlap, need to first remove overlapping entries and then concat
    (do
      (remove-entries file (inc after-index))
      ;; perform append
      (append file entries after-index after-index))))


(defn- return-log-id
  "Takes java file and returns log id (typically same as start index)
  from the file name as a long integer."
  ([^File file]
   (return-log-id file "raft"))
  ([^File file type]
   (when-let [match (re-find (re-pattern (str "^([0-9]+)\\." type "$")) (.getName file))]
     (Long/parseLong (second match)))))


(defn all-log-indexes
  "Returns all index file names present in provided raft log path."
  ([path]
   (all-log-indexes path "raft"))
  ([path type]
   (->> (file-seq (clojure.java.io/file path))
        (filter #(.isFile ^File %))
        (keep #(return-log-id % type)))))


(defn latest-log-index
  "Returns the most recent (largest) log index point."
  ([path]
   (latest-log-index path "raft"))
  ([path type]
   (let [all-idx-logs (all-log-indexes path type)]
     (if (empty? all-idx-logs)
       nil
       (apply max all-idx-logs)))))


(defn rotate-log
  "Rotates current log"
  [raft-state]
  (log/debug "Rotate log called. Raft state: " raft-state)
  (let [{:keys [config snapshot-index snapshot-term voted-for term index log-file]} raft-state
        {:keys [log-directory log-history]} config
        entries-post-snapshot (read-entry-range log-file (inc snapshot-index))
        all-logs              (all-log-indexes log-directory "raft")
        max-log-n             (when (not-empty all-logs) (apply max all-logs))
        next-log-n            (if max-log-n
                                (max snapshot-index (inc max-log-n))
                                snapshot-index)
        new-log               (io/file log-directory (str next-log-n ".raft"))
        purge-logs            (when (pos-int? log-history)
                                (drop log-history (sort > all-logs)))]
    ;; initialize base entries in log
    (write-snapshot new-log snapshot-index snapshot-term)
    (write-current-term new-log term)
    (when voted-for
      (write-voted-for new-log term voted-for))

    ;; copy over all entries after latest snapshot
    (loop [[entry & r] entries-post-snapshot
           idx (inc snapshot-index)]
      (when entry
        (write-entry new-log idx (:term entry) entry)
        (recur r (inc idx))))

    ;; purge/remove old log files, if exist
    (doseq [old-log purge-logs]
      (let [file (io/file log-directory (str old-log ".raft"))]
        (io/delete-file file true)))

    (assoc raft-state :log-file new-log)))
