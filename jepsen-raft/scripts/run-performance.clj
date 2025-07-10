#!/usr/bin/env clojure

(require '[jepsen-raft.test-performance :as perf])

;; Run the performance test
(perf/run-performance-test :test-type :escalating)
(System/exit 0)