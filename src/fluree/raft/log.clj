(ns fluree.raft.log
  (:require [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import (java.io FileNotFoundException DataInputStream RandomAccessFile)))


;; if an index is not a positive integer (an append-entry), it is one of these special types:
(def ^:const entry-types {:current-term -1                  ;; record of latest term we've seen
                          :voted-for    -2                  ;; record of votes
                          :snapshot     -3
                          :no-op        -4                  ;; used to clear out entries that are found to be incorrect
                          })

;; reverse map of above
(def ^:const entry-types' (into {} (map (fn [[k v]] [v k]) entry-types)))

;
;(defn modified-nth
;  "Gets nth index from log, based on our current-index."
;  [log index current-index]
;  (let [offset (- current-index index)
;        i      (dec (- (count log) offset))]
;    (nth log i)))


;(defn sublog
;  "Like 'subvec' but works on log.
;
;  start (inclusive) and end (exclusive) are relative to the current-index of the raft log.
;
;  Any start that is earlier than what is in the current log will start at the beginning of the log."
;  [log start end current-index]
;  (let [start-offset (- current-index start)
;        end-offset   (- current-index end)
;        entries      (count log)
;        start*       (max 0 (- (dec entries) start-offset))
;        end*         (dec (- entries end-offset))]
;    (subvec log start* end*)))


(defn- write-entry
  "Writes entry to specified log"
  [file index term entry]
  (try
    (let [data (nippy/freeze entry)
          len  (count data)
          raf  (RandomAccessFile. file "rw")]
      (doto raf
        (.seek (.length raf))
        (.writeInt len)
        (.writeLong index)
        (.writeLong term)
        (.write data)
        (.close)))
    (catch Exception FileNotFoundException
      (io/make-parents file)
      (write-entry file index term entry))))


(defn write-current-term
  "Record latest term we've seen to persistent log."
  [file term]
  (write-entry file (:current-term entry-types) term term))


(defn write-voted-for
  [file term voted-for]
  (write-entry file (:voted-for entry-types) term voted-for))


(defn write-new-command
  "Writes a new command as leader."
  [file index entry]
  (write-entry file index (:term entry) entry))


(defn read-log-file
  "Reads entire log file."
  [file]
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
              entry-type (if (pos? index) :append-entry (get entry-types' index))]
          (.read raf ba)
          (recur (conj log [index term entry-type (nippy/thaw ba)])))))))


(defn- read-entry
  "Reads a specific index entry from durable log.

  Entries contain:
  int - entry byte size
  long - index (or negative integer as per entry-types constant above)
  long - term
  x - entry bytes of previously specified size"
  [file index]
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
  [file start-index end-index]
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
                (recur acc)))))))))


(defn term-of-index
  "Returns term of specified index number."
  [file index]
  (-> (read-entry file index)
      :term))


(defn remove-entries
  "Removes entries from log from start-index (inclusive) to end.

  Changes index of removed entries to -1, so ignored by future reads."
  [file start-index]
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


(defn all-log-indexes
  "Returns all index file names present in provided raft log path."
  [path]
  (->> (file-seq (clojure.java.io/file path))
       (filter #(.isFile ^java.io.File %))
       (keep #(when-let [idx-str (re-find #"^[0-9]+" (.getName ^java.io.File %))]
                (Long/parseLong idx-str)))))


(defn latest-log-index
  "Returns the most recent (largest) log index point."
  [path]
  (let [all-idx-logs (all-log-indexes path)]
    (if (empty? all-idx-logs)
      0
      (apply max all-idx-logs))))





(comment

  (read-entry 11)

  (def logfile (io/file "tmp/" "log2.raft"))

  (write-entry logfile 4 2 [:a :a 4])
  (write-entry logfile 5 2 [:a :b 5])
  (write-entry logfile 6 2 [:a :c 6])
  (write-entry logfile (:voted-for entry-types) 2 :server-a)
  (write-entry logfile 7 2 [:a :d 7])

  (.length (clojure.java.io/file logfile))
  (.length (RandomAccessFile. logfile "r"))

  (read-log-file logfile)

  (read-entry logfile 6)

  (read-entry-range logfile 4 10)

  (remove-entries logfile 6)

  )


