(ns jepsen-raft.model
  "Multi-register model for Jepsen tests that supports operations on multiple keys."
  (:require [knossos.model :as model]
            [clojure.tools.logging :as log])
  (:import [knossos.model Model]))

(defrecord MultiRegister [registers]
  Model
  (step [r op]
    (let [k (:key op)
          current-value (get registers k)]
      (log/debug "Model step: op=" op ", key=" k ", current-value=" current-value ", registers=" registers)
      (condp = (:f op)
        :write
        (MultiRegister. (assoc registers k (:value op)))

        :cas
        (let [[expected new-value] (:value op)]
          (if (= current-value expected)
            (MultiRegister. (assoc registers k new-value))
            (model/inconsistent (str "can't CAS " k " from " expected " to " new-value
                                     " when current value is " current-value))))

        :read
        (let [read-value (:value op)]
          (if (or (nil? read-value) (= current-value read-value))
            r
            (model/inconsistent (str "can't read " read-value " from " k
                                     " when current value is " current-value))))))))

(defn multi-register
  "A register supporting read, write, and CAS operations on multiple keys.
  Initializes with an empty map."
  []
  (MultiRegister. {}))