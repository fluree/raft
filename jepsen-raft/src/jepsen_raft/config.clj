(ns jepsen-raft.config
  "Configuration constants and parameters for Jepsen Raft tests.")

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
  15000)

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

;; Network configuration
(def tcp-ports
  "TCP port mapping for Raft nodes."
  {"n1" 9001
   "n2" 9002
   "n3" 9003})

(def http-ports
  "HTTP port mapping for client commands."
  {"n1" 7001
   "n2" 7002
   "n3" 7003})

;; File paths
(def log-directory
  "Base directory for Raft logs."
  "/tmp/jepsen-raft-network/")

;; Docker configuration
(def docker-compose-file
  "Path to Docker Compose configuration."
  "docker/docker-compose.yml")

(def network-script-path
  "Path to network partition testing script."
  "/Users/bplatz/fluree/raft/jepsen-raft/docker/test-network-partition.sh")

;; Performance test configuration
(def perf-default-timeout-ms
  "Default timeout for performance test operations."
  2000)

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