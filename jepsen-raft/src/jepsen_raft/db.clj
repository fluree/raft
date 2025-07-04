(ns jepsen-raft.db
  "Database setup and teardown for Fluree Raft nodes"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.util :as util]
            [clojure.string :as str]))

(def fluree-dir "/opt/fluree-server")
(def log-dir (str fluree-dir "/logs"))
(def data-dir (str fluree-dir "/data"))

(defn node-multiaddr
  "Generate multi-address for a node"
  [node]
  ;; In Docker, nodes are named n1, n2, etc. and have IPs 10.100.0.11, 10.100.0.12, etc.
  (let [node-num (Integer/parseInt (subs node 1))]
    (str "/ip4/10.100.0." (+ 10 node-num) "/tcp/62071/alias/" node)))

(defn all-multiaddrs
  "Get all node multi-addresses for the test"
  [test]
  (mapv node-multiaddr (:nodes test)))

(defn wait-for-fluree
  "Wait for Fluree to start up and be responsive"
  [node]
  (util/await-fn
    (fn []
      (try
        (c/exec :curl :-f (str "http://localhost:8090/health"))
        true
        (catch Exception e false)))
    30 ; timeout in seconds
    1)) ; retry interval

(defn configure!
  "Update Fluree configuration for the test"
  [node test]
  (info "Configuring Fluree on" node)
  ;; Configuration is handled by environment variables in Docker
  ;; Just ensure the service is restarted if needed
  (c/su
    (c/exec :supervisorctl :restart :fluree)))

(defn start!
  "Start Fluree service"
  [node test]
  (info "Starting Fluree on" node)
  (c/su
    (c/exec :supervisorctl :start :fluree)
    (wait-for-fluree node)
    (info "Fluree started on" node)))

(defn stop!
  "Stop Fluree service"
  [node]
  (info "Stopping Fluree on" node)
  (c/su
    (c/exec :supervisorctl :stop :fluree)))

(defn wipe!
  "Wipe all Fluree data"
  [node]
  (info "Wiping Fluree data on" node)
  (c/su
    (stop! node)
    (c/exec :rm :-rf (str data-dir "/*"))
    (c/exec :rm :-rf (str log-dir "/*"))))

(defrecord FlureeDB [version]
  db/DB
  (setup! [_ test node]
    ;; In Docker setup, Fluree is already installed
    ;; Just configure and start it
    (configure! node test)
    (start! node test))
  
  (teardown! [_ test node]
    (wipe! node))
  
  db/LogFiles
  (log-files [_ test node]
    [(str log-dir "/fluree.log")
     (str log-dir "/fluree_err.log")
     (str data-dir "/raft-log/raft.log")]))

(defn db
  "Create a Fluree DB instance"
  [version]
  (FlureeDB. version))