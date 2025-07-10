(ns jepsen-raft.test-performance
  "Performance stress test for distributed Raft cluster to measure true throughput limits
   
   This test is designed to find the breaking point of the Raft cluster by systematically
   increasing concurrent load until commands start getting dropped or timing out.
   
   Usage:
   - Escalating test (automatic): clojure -M:performance escalating
   - Single test: clojure -M:performance single <clients> <commands>
   - 3-node cluster: PERF_NODE_COUNT=3 clojure -M:performance escalating
   - Use existing nodes: PERF_USE_EXISTING_NODES=true clojure -M:performance escalating
   
   By default, the test will start and stop nodes automatically. Use PERF_USE_EXISTING_NODES=true
   to run against already-running nodes (useful for testing long-running clusters).
   
   The escalating test increases load from 1 to 100 concurrent clients and stops when
   the success rate drops below 90%, identifying the cluster's maximum capacity.
   
   Results include:
   - Throughput (ops/sec) at various load levels
   - Response time statistics (avg, min, max, p95)
   - Success/failure rates
   - Breaking point identification"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go >! <!! chan close!]]
            [clojure.java.io :as io]
            [jepsen-raft.config :as config]
            [jepsen-raft.http-client :as http-client]
            [jepsen-raft.util :as util])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; Configuration
(def ^:private all-node-ports
  "Map of all possible node names to their HTTP ports"
  {"n1" 7001
   "n2" 7002
   "n3" 7003
   "n4" 7004
   "n5" 7005})

(def ^:private node-count
  "Number of nodes to use for performance test (3 or 5)"
  (Integer/parseInt (or (System/getenv "PERF_NODE_COUNT") "5")))

(def ^:private nodes
  "Active nodes based on node-count"
  (vec (take node-count ["n1" "n2" "n3" "n4" "n5"])))

(def ^:private node-ports
  "Map of active node names to their HTTP ports"
  (select-keys all-node-ports nodes))

;; Use timeout from config
(def ^:private default-timeout-ms config/operation-timeout-ms)

;; Option to use existing nodes or start fresh
(def ^:private use-existing-nodes
  "If true, use already running nodes. If false, start/stop nodes automatically."
  (= "true" (System/getenv "PERF_USE_EXISTING_NODES")))

;; Maximum concurrent clients for escalating test
(def ^:private max-concurrent-clients
  "Maximum number of concurrent clients to test in escalating mode"
  (Integer/parseInt (or (System/getenv "PERF_MAX_CLIENTS") "100")))

;; Node management
(def ^:private node-processes (atom {}))

(defn- start-performance-node!
  "Start a single node for performance testing"
  [node]
  (let [{:keys [tcp http]} (util/node->ports node)
        log-dir     config/log-directory
        log-file    (str log-dir "/" node "-perf.log")
        err-file    (str log-dir "/" node "-perf.err")

        ;; Ensure log directory exists
        _ (io/make-parents log-file)

        ;; Build command with all nodes (raft-node expects all 5)
        all-nodes   (clojure.string/join "," ["n1" "n2" "n3" "n4" "n5"])
        cmd         ["sh" "-c"
                     (str "clojure -M -m jepsen-raft.raft-node "
                          node " " tcp " " http " " all-nodes)]

        ;; Start the process
        _ (log/info "Starting node" node "with command:" cmd)
        process-builder (ProcessBuilder. cmd)
        _ (.directory process-builder (java.io.File. "."))
        _ (.redirectOutput process-builder (java.io.File. log-file))
        _ (.redirectError process-builder (java.io.File. err-file))
        process (.start process-builder)]

    (swap! node-processes assoc node process)

    ;; Wait a moment and check if process is still alive
    (Thread/sleep 1000)
    (when-not (.isAlive process)
      (let [exit-code (.exitValue process)]
        (throw (ex-info (str "Node " node " died immediately with exit code " exit-code)
                        {:node node :err-file err-file :exit-code exit-code}))))

    ;; Process is running
    (log/info "Started node" node "process - JVM startup in progress")))

(defn- stop-performance-node!
  "Stop a single node"
  [node]
  (when-let [process (get @node-processes node)]
    (try
      (.destroy process)
      (Thread/sleep 500)
      (when (.isAlive process)
        (.destroyForcibly process))
      (swap! node-processes dissoc node)
      (catch Exception e
        (log/warn "Error stopping node" node ":" (.getMessage e))))))

(defn- start-cluster!
  "Start all nodes for performance testing with staggered startup"
  []
  (log/info "Starting" node-count "node cluster...")
  ;; Clean up any existing state
  (let [state-dir "/tmp/jepsen-raft-network/"]
    (when (.exists (io/file state-dir))
      (log/info "Cleaning up existing state...")
      ;; Delete files in reverse order (children first)
      (doseq [f (reverse (file-seq (io/file state-dir)))]
        (when (.exists f)
          (.delete f)))))

  ;; Start nodes with staggered timing
  (doseq [[idx node] (map-indexed vector nodes)]
    (when (> idx 0)
      (Thread/sleep 2000))  ; 2 second delay between nodes
    (start-performance-node! node))
  
  ;; Wait for all nodes to be reachable via HTTP
  (log/info "Waiting for all nodes to be reachable (JVM startup can take 15-30 seconds)...")
  (Thread/sleep 10000) ; Give JVMs time to start
  (doseq [node nodes]
    (let [port (node-ports node)]
      (loop [attempts 0]
        (when (> attempts 30) ; 30 attempts = 30 seconds
          (throw (ex-info (str "Node " node " HTTP port not responding after 30 attempts") 
                          {:node node :port port})))
        (if (http-client/check-health node port)
          (log/info "Node" node "HTTP port is responding")
          (do
            (when (= 0 (mod attempts 5))
              (log/info "Still waiting for" node "to start... attempt" attempts))
            (Thread/sleep 1000)
            (recur (inc attempts)))))))
  
  ;; Give cluster time to elect leader
  (Thread/sleep 5000)
  (log/info "Cluster started with" node-count "nodes"))

(defn- stop-cluster!
  "Stop all nodes"
  []
  (log/info "Stopping cluster...")
  (doseq [node nodes]
    (stop-performance-node! node))
  (reset! node-processes {})
  (log/info "Cluster stopped"))

;; Helper functions

(defn- wait-for-cluster-ready
  "Wait for the cluster to have a stable leader"
  []
  (log/info "Waiting for" node-count "node cluster to elect leader...")
  (loop [attempts 0]
    (if (> attempts 30)
      (throw (ex-info "Cluster failed to elect leader after 30 attempts" {}))
      (let [responses (for [node nodes]
                        (try
                          (when-let [debug-info (http-client/get-debug-info node (node-ports node))]
                            (:leader debug-info))
                          (catch Exception _ nil)))
            leader-count (count (filter some? responses))]
        (if (>= leader-count 1)
          (log/info "Cluster ready with leader")
          (do
            (Thread/sleep 1000)
            (recur (inc attempts))))))))

(defn- valid-response?
  "Check if a response type indicates success (including legitimate CAS failures)"
  [response-type]
  (contains? #{"ok" :ok "fail" :fail} response-type))

(defn- send-command
  "Send a single command to a node and return timing info"
  [node command timeout-ms]
  (let [start-time (System/currentTimeMillis)
        port (node-ports node)]
    (try
      (let [result (http-client/send-command! node port command timeout-ms)
            elapsed (- (System/currentTimeMillis) start-time)]
        {:elapsed elapsed
         :status (if result 200 :error)
         :success (valid-response? (:type result))
         :result result
         :command command})
      (catch java.net.SocketTimeoutException _
        {:elapsed (- (System/currentTimeMillis) start-time)
         :status :timeout
         :success false
         :result nil
         :command command})
      (catch Exception e
        {:elapsed (- (System/currentTimeMillis) start-time)
         :status :error
         :success false
         :result (.getMessage e)
         :command command}))))

(defn- generate-command
  "Generate a random command for testing"
  []
  (util/generate-test-command))

(defn- concurrent-load-test
  "Run concurrent commands against the cluster"
  [concurrent-clients commands-per-client timeout-ms]
  (log/info "Starting concurrent load test:"
            "clients:" concurrent-clients
            "commands-per-client:" commands-per-client
            "timeout:" timeout-ms "ms")

  (let [start-time (System/currentTimeMillis)
        results-chan (chan (* concurrent-clients commands-per-client))
        latch (CountDownLatch. concurrent-clients)]

    ;; Launch concurrent clients
    (doseq [client-id (range concurrent-clients)]
      (go
        (try
          (let [node (rand-nth nodes)]
            (doseq [cmd-id (range commands-per-client)]
              (let [command (generate-command)
                    result (send-command node command timeout-ms)]
                (>! results-chan (assoc result
                                        :client-id client-id
                                        :command-id cmd-id
                                        :node node)))))
          (finally
            (.countDown latch)))))

    ;; Wait for all clients to complete
    (when-not (.await latch 60 TimeUnit/SECONDS)
      (log/warn "Load test timed out after 60 seconds"))

    (close! results-chan)

    ;; Collect all results
    (let [results (loop [acc []]
                    (if-let [result (<!! results-chan)]
                      (recur (conj acc result))
                      acc))
          total-time (- (System/currentTimeMillis) start-time)]

      {:results results
       :total-time total-time
       :total-commands (count results)
       :concurrent-clients concurrent-clients
       :commands-per-client commands-per-client})))

(defn- calculate-percentile
  "Calculate the nth percentile of a sorted sequence"
  [sorted-seq percentile]
  (let [index (min (dec (count sorted-seq))
                   (int (* percentile (count sorted-seq))))]
    (nth sorted-seq index)))

(defn- analyze-results
  "Analyze performance test results and generate report"
  [{:keys [results total-time total-commands concurrent-clients]}]
  (let [successful (filter :success results)
        failed (remove :success results)
        response-times (map :elapsed successful)

        success-count (count successful)
        success-rate (if (pos? total-commands)
                       (* 100.0 (/ success-count total-commands))
                       0.0)

        throughput (if (pos? total-time)
                     (* 1000.0 (/ success-count total-time))
                     0.0)

        stats (when (seq response-times)
                (let [sorted (sort response-times)]
                  {:avg (/ (reduce + response-times) (count response-times))
                   :min (first sorted)
                   :max (last sorted)
                   :p95 (calculate-percentile sorted 0.95)}))]

    {:summary
     {:total-commands total-commands
      :concurrent-clients concurrent-clients
      :test-duration-ms total-time
      :test-duration-sec (/ total-time 1000.0)
      :success-count success-count
      :failure-count (count failed)
      :success-rate-percent success-rate
      :throughput-ops-per-sec throughput}

     :performance
     {:avg-response-time-ms (or (:avg stats) 0)
      :min-response-time-ms (or (:min stats) 0)
      :max-response-time-ms (or (:max stats) 0)
      :p95-response-time-ms (or (:p95 stats) 0)}

     :failure-analysis
     (let [failures-by-status (group-by :status failed)]
       {:timeouts (count (:timeout failures-by-status))
        :errors (count (:error failures-by-status))
        :failure-rate-percent (- 100.0 success-rate)
        :failed-samples (take 5 (map #(select-keys % [:status :elapsed :command]) failed))})}))

(defn- print-performance-report
  "Print a formatted performance report"
  [{:keys [summary performance failure-analysis]}]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "🚀 RAFT CLUSTER PERFORMANCE TEST RESULTS")
  (println (apply str (repeat 60 "=")))

  (println "\n📊 TEST SUMMARY:")
  (printf "  Total Commands:     %,d\n" (:total-commands summary))
  (printf "  Concurrent Clients: %d\n" (:concurrent-clients summary))
  (printf "  Test Duration:      %.2f seconds\n" (:test-duration-sec summary))
  (printf "  Success Rate:       %.1f%% (%,d/%,d)\n"
          (:success-rate-percent summary)
          (:success-count summary)
          (:total-commands summary))
  (printf "  Throughput:         %.1f ops/sec\n" (:throughput-ops-per-sec summary))

  (println "\n⚡ PERFORMANCE METRICS:")
  (printf "  Average Response:   %.1f ms\n" (double (:avg-response-time-ms performance)))
  (printf "  95th Percentile:    %.1f ms\n" (double (:p95-response-time-ms performance)))
  (printf "  Min Response:       %.1f ms\n" (double (:min-response-time-ms performance)))
  (printf "  Max Response:       %.1f ms\n" (double (:max-response-time-ms performance)))

  (when (pos? (:failure-count summary))
    (println "\n❌ FAILURE ANALYSIS:")
    (printf "  Total Failures:     %,d (%.1f%%)\n"
            (:failure-count summary)
            (:failure-rate-percent failure-analysis))
    (when (pos? (:timeouts failure-analysis))
      (printf "  Timeouts:           %,d\n" (:timeouts failure-analysis)))
    (when (pos? (:errors failure-analysis))
      (printf "  Connection Errors:  %,d\n" (:errors failure-analysis)))
    (when (seq (:failed-samples failure-analysis))
      (println "\n  Failed Operations (sample):")
      (doseq [op (:failed-samples failure-analysis)]
        (println "    -" (pr-str op)))))

  (println "\n" (apply str (repeat 60 "=")) "\n"))

(defn- generate-escalating-configs
  "Generate escalating test configurations up to max clients"
  [max-clients]
  (let [base-configs [1 2 5 10 15 20 30 40 50 75 100]
        extended-configs (concat base-configs
                                 (range 150 (inc max-clients) 50))]
    (for [clients (take-while #(<= % max-clients) extended-configs)]
      {:clients clients
       :commands 10
       :timeout config/perf-default-timeout-ms})))

(defn- escalating-load-test
  "Run escalating load tests to find breaking point"
  []
  (log/info "Starting escalating load test to find cluster limits...")
  (log/info "Maximum concurrent clients:" max-concurrent-clients)

  (let [test-configs (generate-escalating-configs max-concurrent-clients)]

    (println "\n🎯 ESCALATING LOAD TEST - Finding Cluster Limits")
    (println (apply str (repeat 50 "=")))

    (loop [configs test-configs
           results []]
      (if-let [{:keys [clients commands timeout]} (first configs)]
        (do
          (log/info "Testing with" clients "concurrent clients...")
          (Thread/sleep 1000) ; Brief pause between tests

          (let [test-result (concurrent-load-test clients commands timeout)
                analysis (analyze-results test-result)
                summary (:summary analysis)
                success-rate (:success-rate-percent summary)
                throughput (:throughput-ops-per-sec summary)]

            (printf "\n📈 %d clients: %.1f ops/sec, %.1f%% success\n"
                    clients throughput success-rate)
            (flush)

            ;; Stop if success rate drops below threshold
            (if (< success-rate config/perf-breaking-point-threshold)
              (do
                (println "\n🔴 BREAKING POINT DETECTED!")
                (printf "Cluster performance degrades significantly at %d concurrent clients\n" clients)
                (print-performance-report analysis)
                (conj results {:config {:clients clients :commands commands :timeout timeout}
                               :analysis analysis
                               :breaking-point true}))
              (recur (rest configs)
                     (conj results {:config {:clients clients :commands commands :timeout timeout}
                                    :analysis analysis
                                    :breaking-point false})))))
        results))))

(defn run-performance-test
  "Main entry point for performance testing"
  [& {:keys [test-type concurrent-clients commands-per-client timeout-ms manage-nodes?]
      :or {test-type :escalating
           concurrent-clients 10
           commands-per-client 100
           timeout-ms default-timeout-ms
           manage-nodes? (not use-existing-nodes)}}]

  (try
    ;; Start cluster if needed
    (when manage-nodes?
      (start-cluster!))

    (wait-for-cluster-ready)

    (let [result (case test-type
                   :single
                   (let [test-result (concurrent-load-test concurrent-clients commands-per-client timeout-ms)
                         analysis (analyze-results test-result)]
                     (print-performance-report analysis)
                     analysis)

                   :escalating
                   (escalating-load-test)

                   (throw (ex-info "Unknown test type" {:test-type test-type})))]

      ;; Stop cluster if we started it
      (when manage-nodes?
        (stop-cluster!))

      result)

    (catch Exception e
      ;; Make sure to clean up on error
      (when manage-nodes?
        (try
          (stop-cluster!)
          (catch Exception _)))
      (log/error "Performance test failed:" (.getMessage e))
      (throw e))))

(defn -main
  "CLI entry point for performance testing"
  [& args]
  (let [[test-type & params] args
        test-type-kw (keyword (or test-type "escalating"))]

    (println "🚀 Starting Raft Cluster Performance Test")
    (println "Test type:" test-type-kw)
    (println "Node configuration:" node-count "nodes -" nodes)
    (println "Node management:" (if use-existing-nodes "Using existing nodes" "Auto-managing nodes"))

    (case test-type-kw
      :single
      (let [clients (if (first params) (Integer/parseInt (first params)) 10)
            commands (if (second params) (Integer/parseInt (second params)) 100)]
        (run-performance-test :test-type :single
                              :concurrent-clients clients
                              :commands-per-client commands))

      :escalating
      (run-performance-test :test-type :escalating)

      (do
        (println "Usage: clojure -M:performance [single|escalating] [clients] [commands]")
        (println "Examples:")
        (println "  clojure -M:performance escalating")
        (println "  clojure -M:performance single 20 50")
        (println "Environment variables:")
        (println "  PERF_NODE_COUNT=3            # Use 3 nodes (default: 5)")
        (println "  PERF_USE_EXISTING_NODES=true # Use already running nodes")
        (println "  PERF_MAX_CLIENTS=500         # Test up to 500 concurrent clients (default: 100)")
        (System/exit 1)))))