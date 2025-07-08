(ns jepsen-raft.operations
  "Common operation generators for Jepsen tests."
  (:require [jepsen-raft.util :as util]))

(defn read-op
  "Generate a read operation for a random key."
  [_ _] 
  {:f :read, :key (util/random-key)})

(defn write-op
  "Generate a write operation with a random key and value."
  [_ _] 
  {:f :write, :key (util/random-key), :value (util/random-value)})

(defn cas-op
  "Generate a compare-and-swap operation with random values."
  [_ _] 
  {:f :cas, :key (util/random-key), 
   :value [(util/random-value) (util/random-value)]})

(defn delete-op
  "Generate a delete operation for a random key."
  [_ _]
  {:f :delete, :key (util/random-key)})

(def operation-generators
  "Standard mix of operation generators for testing."
  [read-op write-op cas-op delete-op])
