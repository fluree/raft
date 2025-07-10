(ns jepsen-raft.operations
  "Common operation generators for Jepsen tests."
  (:require [jepsen-raft.util :as util]))

(defn read-op
  "Generate a read operation."
  [_test _ctx]
  {:f :read})

(defn write-op
  "Generate a write operation with a random value."
  [_test _ctx]
  {:f :write, :value (util/random-value)})

(defn cas-op
  "Generate a compare-and-swap operation with random values."
  [_test _ctx]
  {:f :cas, :value [(util/random-value) (util/random-value)]})

;; Standard operations used in tests
(def read-op-gen read-op)
(def write-op-gen write-op)
(def cas-op-gen cas-op)
