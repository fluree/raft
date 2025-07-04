(ns jepsen-raft.distributed-test
  (:require [clojure.tools.logging :refer [info error]]
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

(def node-ports
  "Map of node names to their HTTP ports on localhost"
  {"n1" 7001
   "n2" 7002  
   "n3" 7003})

(defn node-url
  "Get the HTTP URL for a node"
  [node endpoint]
  (str "http://localhost:" (get node-ports node) endpoint))

(defn wait-for-leader
  "Wait for the cluster to elect a leader"
  [nodes timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if (>= (- (System/currentTimeMillis) start) timeout-ms)
        false
        (let [states (doall
                      (for [node nodes]
                        (try
                          (let [response (http/get (node-url node "/health")
                                                   {:timeout 1000
                                                    :throw-exceptions false})]
                            (when (= 200 (:status response))
                              (json/read-str (:body response) :key-fn keyword)))
                          (catch Exception e
                            nil))))]
          (if (some #(and % (true? (:node-ready %))) states)
            (do
              (info "Cluster has elected a leader")
              true)
            (do
              (Thread/sleep 500)
              (recur))))))))

(defrecord DistributedDB []
  db/DB
  (setup! [_ test node]
    (info "Setting up distributed node" node)
    ; Nodes are already running in Docker, nothing to do
    )
  
  (teardown! [_ test node]
    (info "Tearing down distributed node" node)
    ; Nodes stay running in Docker
    ))

(defrecord DistributedClient [node]
  client/Client
  
  (open! [this test node]
    (assoc this :node node))
  
  (setup! [this test]
    ; Wait for cluster to be ready
    (when-not (wait-for-leader (:nodes test) 30000)
      (throw (ex-info "Cluster failed to elect leader" {})))
    this)
  
  (invoke! [this test op]
    (let [timeout-ms (:operation-timeout-ms util/default-timeouts)
          entry (select-keys op [:f :key :value :old :new])
          start-time (System/currentTimeMillis)
          url (node-url (:node this) "/command")]
      (info "Sending" (:f op) "to" url)
      (try
        ; Send operation to our assigned node
        (let [response (http/post url
                                  {:body (nippy/freeze {:op (:f entry)
                                                        :key (:key entry)
                                                        :value (:value entry)
                                                        :old (:old entry)
                                                        :new (:new entry)})
                                   :headers {"Content-Type" "application/octet-stream"}
                                   :as :byte-array
                                   :socket-timeout timeout-ms
                                   :connection-timeout 5000
                                   :throw-exceptions false})
              elapsed (- (System/currentTimeMillis) start-time)]
          (info "Response status:" (:status response) "elapsed:" elapsed "ms")
          (if (= 200 (:status response))
            (let [result (nippy/thaw (:body response))]
              (info "Result:" result)
              (merge op result {:time elapsed}))
            (do
              (info "HTTP error:" (:status response) "body:" (when (:body response) 
                                                                (try (nippy/thaw (:body response))
                                                                     (catch Exception _ (:body response)))))
              (assoc op :type :info :error :http-error :status (:status response)))))
        (catch java.net.SocketTimeoutException e
          (info "Operation timed out after" (- (System/currentTimeMillis) start-time) "ms")
          (assoc op :type :info :error :timeout))
        (catch Exception e
          (error "Operation failed:" (.getMessage e) "after" (- (System/currentTimeMillis) start-time) "ms")
          (assoc op :type :fail :error (.getMessage e))))))
  
  (teardown! [this test])
  
  (close! [this test]))

;; Operation generators
(defn r [_ _] {:type :invoke :f :read :key (util/random-key)})
(defn w [_ _] {:type :invoke :f :write :key (util/random-key) :value (util/random-value)})
(defn cas [_ _] {:type :invoke :f :cas :key (util/random-key) 
                 :old (util/random-value) :new (util/random-value)})
(defn d [_ _] {:type :invoke :f :delete :key (util/random-key)})

(defn distributed-test
  [opts]
  (merge tests/noop-test
         {:name "raft-distributed"
          :pure-generators true
          :nodes ["n1" "n2" "n3"]
          :ssh {:dummy? true}  ; No SSH needed, using HTTP
          :no-ssh true
          :db (DistributedDB.)
          :client (DistributedClient. nil)
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable
                                {:model (model/cas-register)})})
          :generator (->> (gen/mix [r w cas d])
                          (gen/stagger 1/50)
                          (gen/nemesis nil)
                          (gen/time-limit (:time-limit opts)))
          :concurrency (:concurrency opts)
          :rate (:rate opts)}
         opts))

(def cli-opts
  "Additional CLI options for distributed test"
  [])

(defn -main
  "Run the distributed Jepsen test"
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn distributed-test
                                   :opt-spec cli-opts})
            args))