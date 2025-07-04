(ns jepsen-raft.distributed-test
  "Distributed Jepsen test using Docker containers with real network communication"
  (:require [clojure.set]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [jepsen [cli :as cli]
                    [db :as db]
                    [tests :as tests]
                    [checker :as checker]
                    [generator :as gen]
                    [client :as client]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [clj-http.client :as http]
            [taoensso.nippy :as nippy]
            [jepsen-raft.util :as util]))

(def ^:private node-ports
  "Map of node names to their HTTP ports on localhost"
  {"n1" 7001
   "n2" 7002  
   "n3" 7003})

(defn ^:private node-url
  "Get the HTTP URL for a node"
  [node endpoint]
  (str "http://localhost:" (node-ports node) endpoint))

(defn ^:private wait-for-leader
  "Wait for the cluster to elect a leader. Returns true if leader elected, false on timeout."
  [nodes timeout-ms]
  (let [start (System/currentTimeMillis)
        deadline (+ start timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        false
        (let [node-ready? (fn [node]
                            (try
                              (let [response (http/get (node-url node "/health")
                                                       {:timeout 1000
                                                        :throw-exceptions false})]
                                (and (= 200 (:status response))
                                     (-> response :body (json/read-str :key-fn keyword) :node-ready)))
                              (catch Exception _ false)))
              any-ready? (some node-ready? nodes)]
          (if any-ready?
            (do
              (log/info "Cluster has elected a leader")
              true)
            (do
              (Thread/sleep 500)
              (recur))))))))

(defrecord DistributedDB []
  db/DB
  (setup! [_ _test node]
    (log/info "Setting up distributed node" node)
    ;; Nodes are already running in Docker, nothing to do
    )
  
  (teardown! [_ _test node]
    (log/info "Tearing down distributed node" node)
    ;; Nodes stay running in Docker
    ))

(defrecord DistributedClient [node]
  client/Client
  
  (open! [this test node]
    (assoc this :node node))
  
  (setup! [this test]
    ;; Wait for cluster to be ready
    (when-not (wait-for-leader (:nodes test) 30000)
      (throw (ex-info "Cluster failed to elect leader" {})))
    this)
  
  (invoke! [this _test op]
    (let [timeout-ms (:operation-timeout-ms util/default-timeouts)
          command-payload (-> op
                              (select-keys [:f :key :value :old :new])
                              (clojure.set/rename-keys {:f :op}))
          start-time (System/currentTimeMillis)
          url (node-url (:node this) "/command")]
      (log/info "Sending" (:f op) "to" url)
      (try
        ;; Send operation to our assigned node
        (let [response (http/post url
                                  {:body (nippy/freeze command-payload)
                                   :headers {"Content-Type" "application/octet-stream"}
                                   :as :byte-array
                                   :socket-timeout timeout-ms
                                   :connection-timeout 5000
                                   :throw-exceptions false})
              elapsed (- (System/currentTimeMillis) start-time)]
          (log/info "Response status:" (:status response) "elapsed:" elapsed "ms")
          (if (= 200 (:status response))
            (let [result (nippy/thaw (:body response))]
              (log/info "Result:" result)
              (merge op result {:time elapsed}))
            (let [error-body (when (:body response)
                               (try (nippy/thaw (:body response))
                                    (catch Exception _ (:body response))))]
              (log/info "HTTP error:" (:status response) "body:" error-body)
              (assoc op :type :info :error :http-error :status (:status response)))))
        (catch java.net.SocketTimeoutException _
          (let [elapsed (- (System/currentTimeMillis) start-time)]
            (log/info "Operation timed out after" elapsed "ms")
            (assoc op :type :info :error :timeout)))
        (catch Exception e
          (let [elapsed (- (System/currentTimeMillis) start-time)]
            (log/error "Operation failed:" (.getMessage e) "after" elapsed "ms")
            (assoc op :type :fail :error (.getMessage e)))))))
  
  (teardown! [_this _test])
  
  (close! [_this _test]))

;; Operation generators for Jepsen test workload
(defn ^:private read-op [_ _] 
  {:type :invoke :f :read :key (util/random-key)})

(defn ^:private write-op [_ _] 
  {:type :invoke :f :write :key (util/random-key) :value (util/random-value)})

(defn ^:private cas-op [_ _] 
  {:type :invoke :f :cas :key (util/random-key) 
   :old (util/random-value) :new (util/random-value)})

(defn ^:private delete-op [_ _] 
  {:type :invoke :f :delete :key (util/random-key)})

(defn distributed-test
  "Creates a distributed Jepsen test configuration for Raft"
  [opts]
  (merge tests/noop-test
         {:name "raft-distributed"
          :pure-generators true
          :nodes ["n1" "n2" "n3"]
          :ssh {:dummy? true}  ;; No SSH needed, using HTTP
          :no-ssh true
          :db (DistributedDB.)
          :client (DistributedClient. nil)
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable
                                {:model (model/cas-register)})})
          :generator (->> (gen/mix [read-op write-op cas-op delete-op])
                          (gen/stagger 1/100)  ;; 10ms between operations
                          (gen/nemesis nil)
                          (gen/time-limit (:time-limit opts)))
          :concurrency (:concurrency opts)
          :rate (:rate opts)}
         opts))

(def ^:private cli-opts
  "Additional CLI options for distributed test"
  [])

(defn -main
  "Run the distributed Jepsen test"
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn distributed-test
                                  :opt-spec cli-opts})
            args))