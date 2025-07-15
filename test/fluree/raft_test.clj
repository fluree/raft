(ns fluree.raft-test
  (:require [clojure.test :refer :all]
            [fluree.raft :refer :all]
            [fluree.raft.kv-example :as kve]
            [clojure.core.async :as async]))

(def ^:const instances 3)
(def ^:const print-debug false)
(def ^:const seed-key-count 50)
(def ^:const seed-key-prefix "seed-")

; set-up / tear-down functionality
(defn close-instances
  []
  (let [results  (map #(kve/close kve/system %) (range 1 (inc instances)))]
    (if print-debug
      (println "shutting down raft servers: " results))))

(defn setup-test-run
  []
  (let [results (kve/launch-raft-system instances)]
    (Thread/sleep 5000)                                    ; allow servers to initialize
    (let [leader (kve/get-leader)]                         ; seed data
      (dotimes [i seed-key-count]
        (kve/write-async leader (str seed-key-prefix i) (str "val-" i))))
    (if print-debug
      (println "started raft servers: " results))))

(defn teardown-test-run
  []
  (dotimes [i seed-key-count]
    (kve/delete (str seed-key-prefix i)))                  ; delete seed data
  (Thread/sleep 5000)                                      ; allow async process(es) to complete
  (close-instances))

;; wrapper for test cases in a function to perform setup
;; and teardown.  Using a fixture-type of :each wraps
;; every test case (individually); :once wraps the entire
;; test run in a single function
(defn test-fixture
  [f]
  (setup-test-run)
  (f)
  (teardown-test-run))

(use-fixtures :once test-fixture)


(deftest get-leader-test
  (testing "get-leader"
    (let [results (map kve/get-leader (range 1 (inc instances)))]
     (is (apply = results)))))

(deftest write-read-test
  (testing "write-read-local value"
    (let [k      "test-key"
          v      "test-value"
          server (kve/get-leader)]
      (is (true? (async/<!! (kve/rpc-async server [:write k v]))))
      (is (= v (async/<!! (kve/rpc-async server [:read k]))))
      (is (apply = (map #(kve/read-local (keyword (str %)) k) (range 1 (inc instances)))))
      (is (true? (kve/delete k))))))

(deftest state-sync-test
  (testing "write-read-local value"
    (is (apply = (map #(kve/dump-state (keyword (str %))) (range 1 (inc instances)))))))

(deftest compare-and-swap-test
  (testing "compare value, swap if match"
    (let [k         "some-key"
          v         "some-value"
          v-invalid "invalid!"
          v-new     "changed!"
          server (kve/get-leader)]
      (is (true? (async/<!! (kve/rpc-async server [:write k v]))))
      (is (= v (async/<!! (kve/rpc-async server [:read k]))))
      (is (false? (kve/cas k v-invalid v-new)))
      (is (= v (async/<!! (kve/rpc-async server [:read k]))))
      (is (true? (kve/cas k v v-new)))
      (is (= v-new (async/<!! (kve/rpc-async server [:read k]))))
      (is (true? (kve/delete k))))))

(deftest delete-invalid-key
  (testing "delete unknown value")
  (let [k "unknown-key"]
    (is (false? (kve/delete k)))))


;(deftest write-read-rpc-test
;  (testing "write-read value"
;    (let [k      "test-key2"
;          v      "test-value2"
;          server (kve/get-leader)
;          ]
;      (is (true? (async/<!! (kve/rpc-async server [:write k v]))))
;      (is (apply = (map #(async/<!! (kve/rpc-async (keyword (str %)) [:read k])) (range 1 (inc instances)))))
;      )))

