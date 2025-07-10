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

;; Network configuration - use nodeconfig directly
;; For backward compatibility, import these from nodeconfig
(def tcp-ports nodes/tcp-ports)
(def http-ports nodes/http-ports)

;; File paths
(def log-directory
  "Base directory for Raft logs."
  "/tmp/jepsen-raft-network/")

;; Docker configuration
(def docker-compose-file
  "Path to Docker Compose configuration."
  (:compose-file nodes/docker-config))

(def network-script-path
  "Path to network partition testing script."
  (or (System/getenv "NETWORK_SCRIPT_PATH")
      "docker/test-network-partition.sh"))

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

;; Performance test configuration is now handled by test-performance.clj