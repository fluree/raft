(ns jepsen-raft.config
  "Configuration constants and parameters for Jepsen Raft tests."
  (:require [jepsen-raft.nodeconfig :as nodes]))

;; Timeouts (in milliseconds)
(def operation-timeout-ms
  "Default timeout for client operations."
  5000)

(def connection-timeout-ms
  "Timeout for establishing connections."
  1000)

(def socket-timeout-ms
  "Socket read timeout."
  5000)

(def node-ready-timeout-ms
  "Maximum time to wait for a node to become ready."
  60000)

(def docker-initial-wait-ms
  "Initial wait time for Docker containers to start."
  10000)

;; Retry configuration
(def max-retries
  "Maximum number of retry attempts for operations."
  3)

(def retry-delay-ms
  "Delay between retry attempts."
  100)

;; Test configuration
(def test-keys
  "Keys used in test operations."
  [:x :y :z])

(def value-range
  "Range of values for test operations (0 to value-range)."
  100)

(def default-stagger-rate
  "Default rate for staggering operations in tests."
  (/ 1 50))

(def default-concurrency
  "Default number of concurrent client threads."
  6)

(def default-time-limit
  "Default test duration in seconds."
  60)

;; Network configuration - now delegated to nodes.clj
(def tcp-ports
  "TCP port mapping for Raft nodes.
   DEPRECATED: Use jepsen-raft.nodes/tcp-ports instead."
  (nodes/tcp-ports))

(def http-ports
  "HTTP port mapping for client commands.
   DEPRECATED: Use jepsen-raft.nodes/http-ports instead."
  (nodes/http-ports))

;; File paths
(def log-directory
  "Base directory for Raft logs."
  "/tmp/jepsen-raft-network/")

;; Docker configuration - now delegated to nodes.clj
(def docker-compose-file
  "Path to Docker Compose configuration."
  (:compose-file nodes/docker-config))

(def network-script-path
  "Path to network partition testing script."
  (or (System/getenv "NETWORK_SCRIPT_PATH")
      "docker/test-network-partition.sh"))

(def node-ready-timeout-ms
  "Maximum time to wait for a node to become ready.
   Re-exported from nodes.clj for backward compatibility."
  (:node-ready-timeout-ms nodes/docker-config))

;; Performance test configuration
(def perf-default-timeout-ms
  "Default timeout for performance test operations."
  2000)

(def perf-jvm-startup-delay-ms
  "Time to wait for JVM startup during performance tests."
  10000)

(def perf-breaking-point-threshold
  "Success rate threshold (%) below which the cluster is considered broken."
  70.0)

(def perf-escalating-configs
  "Configuration for escalating load test."
  [;; Warm up
   {:clients 1 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 2 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 5 :commands 10 :timeout perf-default-timeout-ms}
   ;; Light load
   {:clients 10 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 15 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 20 :commands 10 :timeout perf-default-timeout-ms}
   ;; Medium load  
   {:clients 30 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 40 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 50 :commands 10 :timeout perf-default-timeout-ms}
   ;; Heavy load
   {:clients 75 :commands 10 :timeout perf-default-timeout-ms}
   {:clients 100 :commands 10 :timeout perf-default-timeout-ms}])