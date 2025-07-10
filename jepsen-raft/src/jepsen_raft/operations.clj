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

(defn delete-op
  "Generate a delete operation."
  [_test _ctx]
  {:f :delete})

(def operation-generators
  "Standard mix of operation generators for testing."
  [read-op write-op cas-op delete-op])
