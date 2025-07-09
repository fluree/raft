(ns jepsen-raft.http-client
  "Common HTTP client utilities for Raft nodes"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]))

(defn- node->url
  "Convert node and port to URL"
  [_node port endpoint]
  (str "http://localhost:" port endpoint))

(defn- with-timeout
  "Execute HTTP request with timeout and error handling"
  [request-fn url opts]
  (try
    (request-fn url (merge {:socket-timeout 2000
                           :connection-timeout 1000
                           :throw-exceptions false
                           :headers {"Content-Type" "application/json"}}
                          opts))
    (catch java.net.SocketTimeoutException _ex
      {:status 504 :error :timeout})
    (catch Exception ex
      (log/warn ex "HTTP request failed" url)
      {:status 503 :error :connection-failed})))

(defn send-command!
  "Send a command to a Raft node via HTTP"
  [node port command timeout-ms]
  (let [url (node->url node port "/command")
        response (with-timeout 
                   http/post 
                   url
                   {:body (json/write-str command)
                    :socket-timeout timeout-ms})]
    (when (= 200 (:status response))
      (json/read-str (:body response) :key-fn keyword))))

(defn check-health
  "Check node health status"
  [node port]
  (let [url (node->url node port "/health")
        response (with-timeout http/get url {:socket-timeout 1000})]
    (when (= 200 (:status response))
      (json/read-str (:body response) :key-fn keyword))))

(defn get-debug-info
  "Get debug information from node"
  [node port]
  (let [url (node->url node port "/debug")
        response (with-timeout http/get url {:socket-timeout 1000})]
    (when (= 200 (:status response))
      (json/read-str (:body response) :key-fn keyword))))

(defn- wait-for-ready
  "Wait for a condition to be true with timeout"
  [check-fn timeout-ms poll-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (check-fn)
        true
        (if (> (System/currentTimeMillis) deadline)
          false
          (do
            (Thread/sleep poll-ms)
            (recur)))))))

(defn wait-for-node-ready
  "Wait for a node to be ready"
  [node port timeout-ms]
  (let [start-time (System/currentTimeMillis)]
    (wait-for-ready
      #(let [health-result (check-health node port)]
         (when (nil? health-result)
           (when (< (mod (- (System/currentTimeMillis) start-time) 10000) 500)
             (println "Waiting for" node "health check response...")))
         (boolean (:node-ready health-result)))
      timeout-ms
      500)))