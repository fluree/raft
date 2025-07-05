(ns jepsen-raft.tests.performance.test
  "Performance stress test for distributed Raft cluster to measure true throughput limits
   
   This test is designed to find the breaking point of the Raft cluster by systematically
   increasing concurrent load until commands start getting dropped or timing out.
   
   Usage:
   - Escalating test (automatic): clojure -M:performance escalating
   - Single test: clojure -M:performance single <clients> <commands>
   
   The escalating test increases load from 1 to 100 concurrent clients and stops when
   the success rate drops below 90%, identifying the cluster's maximum capacity.
   
   Results include:
   - Throughput (ops/sec) at various load levels
   - Response time statistics (avg, min, max, p95)
   - Success/failure rates
   - Breaking point identification"
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go >! <!! chan close!]]
            [clj-http.client :as http]
            [taoensso.nippy :as nippy]
            [jepsen-raft.util :as util])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private node-ports
  "Map of node names to their HTTP ports on localhost"
  {"n1" 7001
   "n2" 7002  
   "n3" 7003})

(defn ^:private node-url
  "Get the HTTP URL for a node"
  [node endpoint]
  (str "http://localhost:" (node-ports node) endpoint))

(defn ^:private wait-for-cluster-ready
  "Wait for the cluster to have a stable leader"
  []
  (log/info "Waiting for cluster to elect leader...")
  (loop [attempts 0]
    (if (> attempts 30)
      (throw (ex-info "Cluster failed to elect leader after 30 attempts" {}))
      (let [leader-count (count (filter some?
                                        (for [node ["n1" "n2" "n3"]]
                                          (try
                                            (let [response (http/get (node-url node "/debug")
                                                                     {:timeout 1000
                                                                      :throw-exceptions false})]
                                              (when (= 200 (:status response))
                                                (-> response :body (json/read-str :key-fn keyword) :leader)))
                                            (catch Exception _ nil)))))]
        (if (>= leader-count 1)
          (do
            (log/info "Cluster ready with leader")
            true)
          (do
            (Thread/sleep 1000)
            (recur (inc attempts))))))))

(defn ^:private send-command
  "Send a single command to a node and return timing info"
  [node command timeout-ms]
  (let [start-time (System/currentTimeMillis)
        url (node-url node "/command")]
    (try
      (let [response (http/post url
                                {:body (nippy/freeze command)
                                 :headers {"Content-Type" "application/octet-stream"}
                                 :as :byte-array
                                 :socket-timeout timeout-ms
                                 :connection-timeout 5000
                                 :throw-exceptions false})
            elapsed (- (System/currentTimeMillis) start-time)
            result (when (= 200 (:status response))
                     (nippy/thaw (:body response)))]
        {:elapsed elapsed
         :status (:status response)
         :success (= 200 (:status response))
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

(defn ^:private generate-command
  "Generate a random command for testing"
  []
  (let [op-type (rand-nth [:write :read :cas :delete])
        key (util/random-key)
        value (util/random-value)]
    (case op-type
      :write {:op :write :key key :value value}
      :read {:op :read :key key}
      :cas {:op :cas :key key :old (util/random-value) :new value}
      :delete {:op :delete :key key})))

(defn ^:private concurrent-load-test
  "Run concurrent commands against the cluster"
  [nodes concurrent-clients commands-per-client timeout-ms]
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
        (let [node (rand-nth nodes)]
          (log/debug "Client" client-id "using node" node)
          (doseq [cmd-id (range commands-per-client)]
            (let [command (generate-command)
                  result (send-command node command timeout-ms)]
              (>! results-chan (assoc result 
                                      :client-id client-id
                                      :command-id cmd-id
                                      :node node))))
          (.countDown latch))))
    
    ;; Wait for all clients to complete or timeout
    (let [completed (.await latch 60 TimeUnit/SECONDS)]
      (when-not completed
        (log/warn "Load test timed out after 60 seconds")))
    
    (close! results-chan)
    
    ;; Collect all results
    (let [results (loop [results []]
                    (if-let [result (<!! results-chan)]
                      (recur (conj results result))
                      results))
          total-time (- (System/currentTimeMillis) start-time)]
      
      {:results results
       :total-time total-time
       :total-commands (count results)
       :concurrent-clients concurrent-clients
       :commands-per-client commands-per-client})))

(defn ^:private analyze-results
  "Analyze performance test results and generate report"
  [test-results]
  (let [{:keys [results total-time total-commands concurrent-clients]} test-results
        successful (filter :success results)
        failed (filter #(not (:success %)) results)
        timeouts (filter #(= :timeout (:status %)) results)
        errors (filter #(= :error (:status %)) results)
        server-errors (filter #(and (number? (:status %)) (>= (:status %) 500)) results)
        
        success-count (count successful)
        failure-count (count failed)
        timeout-count (count timeouts)
        error-count (count errors)
        server-error-count (count server-errors)
        
        success-rate (if (> total-commands 0)
                       (* 100.0 (/ success-count total-commands))
                       0.0)
        
        response-times (map :elapsed successful)
        avg-response-time (if (seq response-times)
                            (/ (reduce + response-times) (count response-times))
                            0)
        min-response-time (if (seq response-times) (apply min response-times) 0)
        max-response-time (if (seq response-times) (apply max response-times) 0)
        
        throughput (if (> total-time 0)
                     (* 1000.0 (/ success-count total-time))
                     0.0)
        
        p95-response-time (if (seq response-times)
                            (let [sorted (sort response-times)
                                  index (int (* 0.95 (count sorted)))]
                              (nth sorted (min index (dec (count sorted)))))
                            0)]
    
    {:summary
     {:total-commands total-commands
      :concurrent-clients concurrent-clients
      :test-duration-ms total-time
      :test-duration-sec (/ total-time 1000.0)
      :success-count success-count
      :failure-count failure-count
      :timeout-count timeout-count
      :error-count error-count
      :server-error-count server-error-count
      :success-rate-percent success-rate
      :throughput-ops-per-sec throughput}
     
     :performance
     {:avg-response-time-ms avg-response-time
      :min-response-time-ms min-response-time
      :max-response-time-ms max-response-time
      :p95-response-time-ms p95-response-time}
     
     :failure-analysis
     {:timeouts timeout-count
      :connection-errors error-count
      :server-errors server-error-count
      :failure-rate-percent (- 100.0 success-rate)}
     
     :raw-results results}))

(defn ^:private print-performance-report
  "Print a formatted performance report"
  [analysis]
  (let [{:keys [summary performance failure-analysis]} analysis]
    
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
    
    (when (> (:failure-count summary) 0)
      (println "\n❌ FAILURE ANALYSIS:")
      (printf "  Total Failures:     %,d (%.1f%%)\n" 
              (:failure-count summary) 
              (:failure-rate-percent failure-analysis))
      (when (> (:timeouts failure-analysis) 0)
        (printf "  Timeouts:           %,d\n" (:timeouts failure-analysis)))
      (when (> (:connection-errors failure-analysis) 0)
        (printf "  Connection Errors:  %,d\n" (:connection-errors failure-analysis)))
      (when (> (:server-errors failure-analysis) 0)
        (printf "  Server Errors:      %,d\n" (:server-errors failure-analysis))))
    
    (println "\n" (apply str (repeat 60 "=")) "\n")))

(defn ^:private escalating-load-test
  "Run escalating load tests to find breaking point"
  []
  (log/info "Starting escalating load test to find cluster limits...")
  
  (let [test-configs [;; Start conservative
                      {:clients 1 :commands 50 :timeout 5000}
                      {:clients 2 :commands 50 :timeout 5000}
                      {:clients 3 :commands 50 :timeout 5000}
                      {:clients 5 :commands 50 :timeout 5000}
                      ;; Increase load
                      {:clients 10 :commands 50 :timeout 5000}
                      {:clients 15 :commands 50 :timeout 5000}
                      {:clients 20 :commands 50 :timeout 5000}
                      ;; Stress test
                      {:clients 30 :commands 50 :timeout 5000}
                      {:clients 50 :commands 50 :timeout 5000}
                      ;; Find breaking point
                      {:clients 75 :commands 50 :timeout 5000}
                      {:clients 100 :commands 50 :timeout 5000}]
        nodes ["n1" "n2" "n3"]
        results []]
    
    (println "\n🎯 ESCALATING LOAD TEST - Finding Cluster Limits")
    (println (apply str (repeat 50 "=")))
    
    (reduce (fn [acc {:keys [clients commands timeout]}]
              (log/info "Testing with" clients "concurrent clients...")
              (Thread/sleep 2000) ; Brief pause between tests
              
              (let [test-result (concurrent-load-test nodes clients commands timeout)
                    analysis (analyze-results test-result)
                    summary (:summary analysis)
                    success-rate (:success-rate-percent summary)
                    throughput (:throughput-ops-per-sec summary)]
                
                (printf "\n📈 %d clients: %.1f ops/sec, %.1f%% success\n" 
                        clients throughput success-rate)
                
                ;; Stop if success rate drops below 90%
                (if (< success-rate 90.0)
                  (do
                    (println "\n🔴 BREAKING POINT DETECTED!")
                    (printf "Cluster performance degrades significantly at %d concurrent clients\n" clients)
                    (print-performance-report analysis)
                    (reduced (conj acc {:config {:clients clients :commands commands :timeout timeout}
                                        :analysis analysis
                                        :breaking-point true})))
                  (conj acc {:config {:clients clients :commands commands :timeout timeout}
                             :analysis analysis
                             :breaking-point false}))))
            results
            test-configs)))

(defn run-performance-test
  "Main entry point for performance testing"
  [& {:keys [test-type concurrent-clients commands-per-client timeout-ms]
      :or {test-type :escalating
           concurrent-clients 10
           commands-per-client 100
           timeout-ms 5000}}]
  
  (try
    (wait-for-cluster-ready)
    
    (case test-type
      :single
      (let [nodes ["n1" "n2" "n3"]
            test-result (concurrent-load-test nodes concurrent-clients commands-per-client timeout-ms)
            analysis (analyze-results test-result)]
        (print-performance-report analysis)
        analysis)
      
      :escalating
      (escalating-load-test)
      
      (throw (ex-info "Unknown test type" {:test-type test-type})))
    
    (catch Exception e
      (log/error "Performance test failed:" (.getMessage e))
      (throw e))))

(defn -main
  "CLI entry point for performance testing"
  [& args]
  (let [[test-type & params] args
        test-type-kw (keyword (or test-type "escalating"))]
    
    (println "🚀 Starting Raft Cluster Performance Test")
    (println "Test type:" test-type-kw)
    
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
        (System/exit 1)))))