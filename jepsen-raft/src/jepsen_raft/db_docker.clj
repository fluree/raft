(ns jepsen-raft.db-docker
  "Database setup for dockerized Raft test with Jepsen integration."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.java.shell :refer [sh]]
            [jepsen [db :as db]]
            [jepsen-raft.config :as config]
            [jepsen-raft.util :as util]
            [jepsen-raft.http-client :as http-client]))

;; Synchronization for Docker container lifecycle
(def ^:private docker-setup-lock (Object.))
(def ^:private docker-started? (atom false))
(def ^:private setup-counter (atom 0))
(def ^:private teardown-counter (atom 0))

(defn- docker-exec
  "Execute a command in the dockerized cluster environment."
  [& args]
  (let [result (apply sh (concat ["docker-compose" "-f" config/docker-compose-file] args))]
    (info "Docker command:" args "Exit:" (:exit result))
    (when (seq (:out result))
      (info "Docker output:" (:out result)))
    (when (not= 0 (:exit result))
      (warn "Docker command failed:" args "Exit:" (:exit result) "Error:" (:err result)))
    result))

(defn- wait-for-node-ready
  "Wait for a dockerized node to be ready."
  [node timeout-ms]
  (let [{:keys [http]} (util/node->ports node)]
    (http-client/wait-for-node-ready node http timeout-ms)))

(defn- start-dockerized-cluster!
  "Start the dockerized net.async cluster."
  []
  (util/log-node-operation "Starting" "dockerized net.async cluster")
  
  ;; First ensure any existing containers are stopped
  (info "Cleaning up any existing containers...")
  (docker-exec "down" "-v")
  
  ;; Build and start containers
  (info "Building Docker images...")
  (docker-exec "build")
  
  (info "Starting containers...")
  (docker-exec "up" "-d")
  
  ;; Wait for all nodes to be ready
  (info "Waiting for nodes to be ready...")
  (Thread/sleep config/docker-initial-wait-ms) ; Initial wait for containers to start
  
  (doseq [node ["n1" "n2" "n3"]]
    (when-not (wait-for-node-ready node config/node-ready-timeout-ms)
      (throw (ex-info (str "Node " node " failed to start") {:node node})))))

(defn- stop-dockerized-cluster!
  "Stop the dockerized net.async cluster."
  []
  (util/log-node-operation "Stopping" "dockerized net.async cluster")
  (docker-exec "down" "-v"))

(defn- check-cluster-health
  "Check if the dockerized cluster is healthy."
  []
  (try
    (every? #(let [{:keys [http]} (util/node->ports %)]
               (try
                 (slurp (str "http://localhost:" http "/debug"))
                 true
                 (catch Exception _ false)))
            ["n1" "n2" "n3"])
    (catch Exception e
      (warn "Health check failed:" (.getMessage e))
      false)))

(defrecord DockerizedNetAsyncDB []
  db/DB
  (setup! [_ test node]
    (info "DockerizedNetAsyncDB setup! called for node:" node)
    
    ;; Use locking to ensure only one thread starts Docker containers
    (locking docker-setup-lock
      (swap! setup-counter inc)
      
      ;; Start Docker containers only once
      (when-not @docker-started?
        (util/log-node-operation "Starting dockerized cluster on first setup for" node)
        (start-dockerized-cluster!)
        
        ;; Verify cluster health
        (when-not (check-cluster-health)
          (throw (ex-info "Dockerized cluster failed health check" {})))
        
        (reset! docker-started? true)
        (info "Docker cluster started successfully")))
    
    ;; Outside the lock, all nodes verify they're accessible
    (util/log-node-operation "Verifying node is accessible" node)
    (when-not (wait-for-node-ready node 30000)
      (throw (ex-info (str "Node " node " not accessible") {:node node})))
    
    (info "Setup complete for node:" node))
  
  (teardown! [_ test node]
    (info "DockerizedNetAsyncDB teardown! called for node:" node)
    
    ;; Use locking to ensure proper teardown
    (locking docker-setup-lock
      (swap! teardown-counter inc)
      
      ;; Stop Docker containers only after all nodes have been torn down
      (when (and @docker-started?
                 (= @teardown-counter (count (:nodes test))))
        (util/log-node-operation "Stopping dockerized cluster after all teardowns" node)
        (stop-dockerized-cluster!)
        (reset! docker-started? false)
        (reset! setup-counter 0)
        (reset! teardown-counter 0)
        (info "Docker cluster stopped successfully"))))
  
  db/Primary
  (primaries [_ test]
    ;; All nodes can potentially be primary in Raft
    (:nodes test))
  
  (setup-primary! [_ _test _node]
    ;; No special primary setup needed
    )
  
  db/LogFiles
  (log-files [_ _test node]
    ;; Docker container logs
    [(str "docker logs netasync-" node)]))

(defn db
  "Create a dockerized net.async-based Raft DB."
  []
  (DockerizedNetAsyncDB.))