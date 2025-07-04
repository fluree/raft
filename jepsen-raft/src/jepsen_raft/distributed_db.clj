(ns jepsen-raft.distributed-db
  "Database setup for distributed Raft nodes"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen.control :as c]
            [jepsen.db :as db]))

(defrecord DistributedDB []
  db/DB
  (setup! [_ test node]
    ;; Nodes are already running in Docker containers
    ;; Just verify they're accessible
    (info "Verifying distributed node" node "is accessible")
    (c/exec :echo "Node" node "is ready"))
  
  (teardown! [_ test node]
    ;; In Docker setup, we don't tear down nodes during test
    ;; They'll be stopped when docker-compose down is run
    (info "Distributed node" node "teardown (no-op in Docker)"))
  
  db/LogFiles
  (log-files [_ test node]
    ["/opt/fluree-server/logs/fluree.log"
     "/opt/fluree-server/logs/fluree_err.log"
     (str "/tmp/jepsen-raft/" node "/raft.log")]))

(defn db
  "Create a distributed DB instance"
  []
  (DistributedDB.))