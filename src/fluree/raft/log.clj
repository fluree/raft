(ns fluree.raft.log
  (:require [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [clojure.tools.logging :as log])
  (:import (java.io EOFException FileNotFoundException RandomAccessFile File)
           (java.nio.file CopyOption Files StandardCopyOption)))

;; if an index is not a positive integer (an append-entry), it is one of these special types:
(def ^:const entry-types {:current-term -1 ;; record of the latest term we've seen
                          :voted-for    -2 ;; record of votes
                          :snapshot     -3 ;; record of new snapshots
                          :no-op        -4}) ;; used to clear out entries that are found to be incorrect

;; reverse map of above
(def ^:const entry-types' (into {} (map (fn [[k v]] [v k]) entry-types)))

(defn write-entry
  "Writes entry to specified log"
  ([^File file index term entry] (write-entry file index term entry true))
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
         (do
           (io/make-parents file)
           (write-entry file index term entry false))
         (do (log/error "Unable to create raft log file. Does the process have permission to file: " (pr-str file) "?")
             (log/error "Fatal Error, exiting.")
             (System/exit 1))))
     (catch Exception e
       (log/error e "Unexpected Error attempting to write entry to raft log:" (pr-str file))
       (log/error e "Fatal Error, exiting.")
       (System/exit 1)))))

(defn write-log
  "Writes an entire log of entries provided as a sequence. Log entries are 4-tuples (identical format to read-log):
  [index term entry-type entry-data]"
  [^File file log]
  (doseq [[index term _ entry-data] log]
    (write-entry file index term entry-data false)))

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

(defn log-corrupt-exception
  "Given an exception thrown that is discovered in a corrupt log file, log its output in a readable format."
  [exception]
  (let [{:keys [file-name next-bytes index term]} (ex-data exception)
        msg        (str "Raft log EOF (End of File) exception reading from file: " file-name ". "
                        "Successful recovery, returning complete log entries up to the last message. "
                        "Likely a previous unexpected shutdown happened while writing the corrupted entry. ")
        error-at   (cond
                     (nil? next-bytes) "Corruption happened when writing the number of bytes contained in the next entry. "
                     (nil? index) "Corruption happened when writing the log index number. "
                     (nil? term) "Corruption happened when writing the raft term number. "
                     :else "Corruption happened when writing the log entry data. ")
        known-info (cond
                     (nil? next-bytes) "No information could be retrieved from the specific log entry."
                     (nil? index) (str "The log entry was expected to be " next-bytes " bytes.")
                     (nil? term) (str "The log entry would have been for index: " index " and " next-bytes " bytes.")
                     :else (str "The log entry would have been for index: " index
                                " and term: " term " with " next-bytes " bytes."))]
    (log/warn (str msg error-at known-info))))

(defn move-log
  "Moves the source log file to destination-file."
  [^File source-file ^File destination-file]
  (Files/move (.toPath source-file) (.toPath destination-file)
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING])))

(defn copy-corrupt-file
  "Creates a copy (for forensics) of a corrupt raft file using the '.corrupt. extension"
  [^File file]
  (let [source-path      (.getPath file)
        destination-path (str source-path ".corrupt")
        destination      (io/file destination-path)]
    (log/info (str "Copying corrupted log file for forensics from: " source-path " to: " destination-path))
    (io/copy file destination)
    (log/debug (str "Successfully created file: " destination-path))
    :done))

(defn repair-file
  "When we have an end of file exception due to a corrupt final log entry,
  we need to fix the file so that the log can continue to be used."
  [^File file log]
  (try
    (log/debug (str "About to repair log file: " (.getName file) " with " (count log) " valid entries."))
    (let [source-path (.getPath file)
          repair-path (str source-path ".repaired")
          repair-file (io/file repair-path)]
      (copy-corrupt-file file)
      (log/debug (str "Repairing log file: Writing new correct log to: " repair-path " as a temporary file."))
      (write-log repair-file log)
      (log/debug (str "Repairing log file: Done writing temporary file: " repair-path
                      ". Moving temporary file to replace original corrupt log: " source-path))
      (move-log repair-file file)
      true)
    (catch Exception e
      (throw (ex-info (str "Error creating repaired raft log file: " (.getName file)
                           " with exception: " (ex-message e))
                      {:file-name (.getName file)}
                      e)))))

(defn throw-corrupt-file-exception
  "When reading a raft log file, report out a consistent format for exceptions if they occur."
  [message exception ^RandomAccessFile raf file-name next-bytes index term entry-type entry-data]
  (let [file-length  (.length raf)
        file-pointer (.getFilePointer raf)
        EOF?         (instance? EOFException exception)]
    (throw (ex-info message
                    {:status                 500
                     :error                  :raft/corrupt-log
                     :file-name              file-name
                     :file-length            file-length
                     :file-pointer           file-pointer
                     :end-of-file-exception? EOF?
                     :next-bytes             next-bytes
                     :index                  index
                     :term                   term
                     :entry-type             entry-type
                     :entry-data             entry-data}
                    exception))))

(defn read-next-entry
  "Reads the next entry in a raft log file, returns 4-tuple of:
  [index term entry-type entry-data] or an ex-info exception if there
  was an error reading."
  [^RandomAccessFile raf file-name]
  (let [next-bytes (try (.readInt raf)
                        (catch EOFException eof-ex
                          (throw-corrupt-file-exception (str "Corrupt last entry in raft log: " file-name)
                                                        eof-ex raf file-name nil nil nil nil nil))
                        (catch Exception e (throw e)))
        index      (try (.readLong raf)
                        (catch EOFException eof-ex
                          (throw-corrupt-file-exception (str "Corrupt last entry in raft log: " file-name)
                                                        eof-ex raf file-name next-bytes nil nil nil nil))
                        (catch Exception e (throw e)))
        entry-type (if (pos? index) :append-entry (get entry-types' index))
        term       (try (.readLong raf)
                        (catch EOFException eof-ex
                          (throw-corrupt-file-exception (str "Corrupt last entry in raft log: " file-name)
                                                        eof-ex raf file-name next-bytes index nil entry-type nil))
                        (catch Exception e (throw e)))
        ba         (byte-array next-bytes)
        _          (try (.readFully raf ba)
                        (catch EOFException eof-ex
                          (throw-corrupt-file-exception (str "Corrupt last entry in raft log: " file-name)
                                                        eof-ex raf file-name next-bytes index term entry-type nil))
                        (catch Exception e (throw e)))
        entry-data (try (nippy/thaw ba)
                        (catch Exception e
                          (let [message (str "Unexpected exception when deserializing raft log entry: " file-name)]
                            (throw-corrupt-file-exception message e raf file-name next-bytes index term entry-type nil))))]
    [index term entry-type entry-data]))

(defn read-log-file
  "Reads entire log file."
  [^File file]
  (let [raf       (RandomAccessFile. file "r")
        len       (.length raf)
        file-name (.getName file)]
    (loop [log []]
      (if (>= (.getFilePointer raf) len)
        (do
          (.close raf)
          log)
        (let [next-entry (try (read-next-entry raf file-name)
                              (catch Exception e
                                (let [EOF?         (-> e ex-data :end-of-file-exception?)
                                      log-entries  (count log)
                                      first-entry? (zero? log-entries)
                                      repairable?  (and EOF? (not first-entry?))]
                                  (cond repairable? (do
                                                      (log/warn (str "Repairing corrupt log file, saving a copy to: " file-name ".corrupt"))
                                                      (log-corrupt-exception e)
                                                      (.close raf)
                                                      (repair-file file log)
                                                      (log/warn (str "Finished repairing corrupt log file: " file-name))
                                                      :repaired-log)
                                        first-entry? (do
                                                       (log/warn "First entry of raft log file is corrupt, removing file after creating a copy.")
                                                       (log-corrupt-exception e)
                                                       (.close raf)
                                                       (copy-corrupt-file file)
                                                       (.delete file)
                                                       (log/warn "Successfully removed corrupted log file, restart service.")
                                                       (log/warn "Exiting! Restart service, which should successfully load last complete log file.")
                                                       (System/exit 1))
                                        ;; not repairable, throw exception upstream
                                        :else (do (.close raf)
                                                  (throw e))))))]
          (if (= :repaired-log next-entry)
            log
            (recur (conj log next-entry))))))))

(defn read-entry
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
                _ (.readLong raf)] ;; ignore term
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
  ([^File file start-index] (read-entry-range file start-index Long/MAX_VALUE))
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
                   _ (.readLong raf)] ;; ignore term
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
               (.seek raf next-pointer)
               (recur acc)))))))))

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
   (->> (file-seq (io/file path))
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
  (let [{:keys [config snapshot-index snapshot-term voted-for term log-file]} raft-state
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
