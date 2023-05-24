(ns fluree.raft.log-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [test-with-files.tools :refer [with-tmp-dir]]
            [fluree.raft.log :as raft-log])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer)))


(defn corrupt-long
  "Corrupts a long integer returning corrupted byte array with 7 bytes instead of 8"
  [long-int]
  (let [bb (ByteBuffer/allocate 8)]
    (.putLong bb long-int)

    (->> (.array bb)
         (take 7)
         (byte-array))))

(defn corrupt-int
  "Corrupts an integer returning corrupted byte array with 3 bytes instead of 4"
  [int]
  (let [bb (ByteBuffer/allocate 4)]
    (.putInt bb int)

    (->> (.array bb)
         (take 3)
         (byte-array))))

(defn corrupt-entry
  "Corrupts a serialized byte array by chopping the bytes in half."
  [ba]
  (let [length       (alength ba)
        corrupt-size (quot length 2)]

    (->> ba
         (take corrupt-size)
         (byte-array))))

(defn write-corrupt-no-entry
  "Writes a corrupt raft log entry, by not writing any bytes
  from the log entry simulating a forced shutdown scenario."
  [^File file index term entry]
  (let [^bytes data (nippy/freeze entry)
        len         (count data)
        raf         (RandomAccessFile. file "rw")]

    (doto raf
      (.seek (.length raf))
      (.writeInt len)
      (.writeLong index)
      (.writeLong term)
      ;; purposefully don't write entry, terminate file here
      (.close))))

(defn write-corrupt-partial-entry
  "Writes a corrupt raft log entry, by chopping off the bytes
  of the serialized entry simulating an incomplete write due to
  a forced shutdown."
  [^File file index term entry]
  (let [^bytes data  (nippy/freeze entry)
        len          (count data)
        corrupt-data (corrupt-entry data)
        raf          (RandomAccessFile. file "rw")]

    (doto raf
      (.seek (.length raf))
      (.writeInt len)
      (.writeLong index)
      (.writeLong term)
      (.write corrupt-data)
      (.close))))

(defn write-corrupt-term
  "Instead of writing a full term long integer, corrupt it then end writing"
  [^File file index term entry]
  (let [^bytes data  (nippy/freeze entry)
        len          (count data)
        raf          (RandomAccessFile. file "rw")
        corrupt-term (corrupt-long term)]

    (doto raf
      (.seek (.length raf))
      (.writeInt len)
      (.writeLong index)
      (.write corrupt-term)
      (.close))))

(defn write-corrupt-index
  "Instead of writing a full index long integer, corrupt it then end writing"
  [^File file index _ entry]
  (let [^bytes data   (nippy/freeze entry)
        len           (count data)
        raf           (RandomAccessFile. file "rw")
        corrupt-index (corrupt-long index)]

    (doto raf
      (.seek (.length raf))
      (.writeInt len)
      (.write corrupt-index)
      (.close))))

(defn write-corrupt-byte-length
  "Instead of writing the byte length of the entry, corrupt and close."
  [^File file _ _ entry]
  (let [^bytes data (nippy/freeze entry)
        len         (count data)
        raf         (RandomAccessFile. file "rw")
        corrupt-len (corrupt-int len)]

    (doto raf
      (.seek (.length raf))
      (.write corrupt-len)
      (.close))))


(deftest corrupt-logs
  (with-tmp-dir temp-dir

    (let [entry1 [:test-command-a {:a 1}]
          entry2 [:test-command-b {:b 2}]
          entry3 [:test-command-c {:c 3, :this-will-be :corrupt}]]

      (testing "Corrupted command with chopped off entry at EOF successfully recovers"
        (let [file (io/file temp-dir (str 1000 ".raft"))]

          ;; write a first entry normally
          (raft-log/write-entry file 1 1 entry1)
          ;; write a second entry normally
          (raft-log/write-entry file 2 1 entry2)
          ;; corrupt the third entry
          (write-corrupt-no-entry file 3 1 entry3)

          (is (= [[1 1 :append-entry entry1] [2 1 :append-entry entry2]]
                 (raft-log/read-log-file file))
              "Read file should have only entry 1 and 2, and not entry 3 which was corrupt")))

      (testing "Corrupted command partially written entry at EOF successfully recovers"
        (let [file (io/file temp-dir (str 1001 ".raft"))]

          ;; write a first entry normally
          (raft-log/write-entry file 1 1 entry1)
          ;; write a second entry normally
          (raft-log/write-entry file 2 1 entry2)
          ;; corrupt the third entry
          (write-corrupt-partial-entry file 3 1 entry3)

          (is (= [[1 1 :append-entry entry1] [2 1 :append-entry entry2]]
                 (raft-log/read-log-file file))
              "Read file should have only entry 1 and 2, and not entry 3 which was corrupt")))

      (testing "Corruption when writing term at EOF successfully recovers"
        (let [file (io/file temp-dir (str 2000 ".raft"))]

          ;; write a first entry normally
          (raft-log/write-entry file 1 1 entry1)
          ;; write a second entry normally
          (raft-log/write-entry file 2 1 entry2)
          ;; corrupt the third entry
          (write-corrupt-term file 3 1 entry3)

          (is (= [[1 1 :append-entry entry1] [2 1 :append-entry entry2]]
                 (raft-log/read-log-file file))
              "Read file should have only entry 1 and 2, and not entry 3 which was corrupt")))

      (testing "Corruption when writing index at EOF successfully recovers"
        (let [file (io/file temp-dir (str 3000 ".raft"))]

          ;; write a first entry normally
          (raft-log/write-entry file 1 1 entry1)
          ;; write a second entry normally
          (raft-log/write-entry file 2 1 entry2)
          ;; corrupt the third entry
          (write-corrupt-index file 3 1 entry3)

          (is (= [[1 1 :append-entry entry1] [2 1 :append-entry entry2]]
                 (raft-log/read-log-file file))
              "Read file should have only entry 1 and 2, and not entry 3 which was corrupt")))

      (testing "Corruption when writing byte length at EOF successfully recovers"
        (let [file (io/file temp-dir (str 4000 ".raft"))]

          ;; write a first entry normally
          (raft-log/write-entry file 1 1 entry1)
          ;; write a second entry normally
          (raft-log/write-entry file 2 1 entry2)
          ;; corrupt the third entry
          (write-corrupt-byte-length file 3 1 entry3)

          (is (= [[1 1 :append-entry entry1] [2 1 :append-entry entry2]]
                 (raft-log/read-log-file file))
              "Read file should have only entry 1 and 2, and not entry 3 which was corrupt"))))))
