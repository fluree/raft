(ns jepsen-raft.nemesis-docker
  "Network partition nemesis for dockerized Raft test."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.java.shell :refer [sh]]
            [jepsen [nemesis :as nemesis]]
            [jepsen-raft.config :as config]))

(defn run-network-script
  "Execute a network partition script command."
  [command & args]
  (let [script-path config/network-script-path
        full-args (into [script-path command] args)
        result (apply sh full-args)]
    (when (not= 0 (:exit result))
      (warn "Network script failed:" full-args "Exit:" (:exit result) "Error:" (:err result)))
    result))

(defn partition-node
  "Partition a specific node from the rest of the cluster."
  [node]
  (info "Partitioning node" node "from cluster")
  (case node
    "n1" (run-network-script "partition-n1")
    "n2" (run-network-script "partition-n2")
    "n3" (run-network-script "partition-n3")
    "n4" (run-network-script "partition-n4")
    "n5" (run-network-script "partition-n5")
    (warn "Unknown node for partitioning:" node)))

(defn create-split-brain
  "Create a split-brain scenario (2-1 partition)."
  []
  (info "Creating split-brain partition")
  (run-network-script "split-brain"))

(defn heal-partitions
  "Heal all network partitions."
  []
  (info "Healing all network partitions")
  (run-network-script "heal-all"))

(defn add-latency
  "Add network latency to all nodes."
  [latency-ms]
  (info "Adding" latency-ms "ms latency to all nodes")
  (run-network-script "add-latency" (str latency-ms)))

(defn remove-latency
  "Remove network latency from all nodes."
  []
  (info "Removing network latency from all nodes")
  (run-network-script "remove-latency"))

(defn random-partition
  "Create a random network partition that maintains majority quorum."
  []
  ;; Only do single-node partitions to ensure 4-node majority can maintain quorum
  (let [node (rand-nth ["n1" "n2" "n3" "n4" "n5"])]
    (info "Random single node partition (maintaining majority):" node)
    (partition-node node)))

(defrecord DockerNetworkNemesis []
  nemesis/Nemesis
  (setup! [nemesis _test]
    (info "Setting up Docker network nemesis")
    nemesis)

  (invoke! [_nemesis _test op]
    (let [f (:f op)]
      (case f
        :start-partition
        (let [target (:value op)]
          (case target
            :random (random-partition)
            :split-brain (create-split-brain)
            (partition-node (name target)))
          (assoc op :type :info :value target))

        :stop-partition
        (do
          (heal-partitions)
          (assoc op :type :info))

        :add-latency
        (let [latency (:value op 200)]
          (add-latency latency)
          (assoc op :type :info :value latency))

        :remove-latency
        (do
          (remove-latency)
          (assoc op :type :info))

        (do
          (warn "Unknown nemesis operation:" f)
          (assoc op :type :fail :error "Unknown operation")))))

  (teardown! [_nemesis _test]
    (info "Tearing down Docker network nemesis")
    (heal-partitions)
    (remove-latency)))

(defn partition-nemesis
  "Create a network partition nemesis for dockerized testing."
  []
  (DockerNetworkNemesis.))