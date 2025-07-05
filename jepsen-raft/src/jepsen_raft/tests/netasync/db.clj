(ns jepsen-raft.tests.netasync.db
  "Database setup for net.async-based distributed Raft test."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.string :as str]
            [jepsen [control :as c]
                    [db :as db]
                    [util :as util]]
            [jepsen.control.util :as cu]
            [clojure.java.shell :refer [sh]]))

(defn node->ports
  "Map node name to TCP and HTTP ports."
  [node]
  (case node
    "n1" {:tcp 9001 :http 7001}
    "n2" {:tcp 9002 :http 7002}
    "n3" {:tcp 9003 :http 7003}
    {:tcp 9000 :http 7000}))

;; Store running processes
(def processes (atom {}))

(defn start-local-node!
  "Start a local net.async Raft node."
  [node]
  (let [{:keys [tcp http]} (node->ports node)
        servers     "localhost:9001:n1,localhost:9002:n2,localhost:9003:n3"
        this-server (str "localhost:" tcp ":" node)
        log-dir     "/tmp/jepsen-raft-netasync"
        log-file    (str log-dir "/" node ".log")
        
        ;; Ensure log directory exists
        _ (clojure.java.io/make-parents log-file)
        
        ;; Build command
        cmd         ["clojure" "-M" "-m" "jepsen-raft.tests.netasync.node"
                     node (str tcp) (str http) this-server servers]
        
        ;; Start the process
        process-builder (ProcessBuilder. cmd)
        _ (.directory process-builder (java.io.File. "."))
        _ (.redirectOutput process-builder (java.io.File. log-file))
        _ (.redirectError process-builder (java.io.File. log-file))
        process (.start process-builder)]
    
    ;; Store process for later cleanup
    (swap! processes assoc node process)
    
    ;; Wait for node to be ready
    (Thread/sleep 3000)
    
    ;; Check if node started successfully
    (try
      (let [response (slurp (str "http://localhost:" http "/health"))]
        (info "Net.async node" node "started successfully"))
      (catch Exception e
        (warn "Net.async node" node "may not have started properly:" (.getMessage e))))))

(defn stop-local-node!
  "Stop a local net.async Raft node."
  [node]
  (info "Stopping net.async node" node)
  (when-let [process (get @processes node)]
    (.destroy process)
    (Thread/sleep 500)
    (when (.isAlive process)
      (.destroyForcibly process))
    (swap! processes dissoc node)))

(defrecord NetAsyncDB []
  db/DB
  (setup! [_ test node]
    (info "Setting up net.async Raft node" node)
    (start-local-node! node))
  
  (teardown! [_ test node]
    (info "Tearing down net.async Raft node" node)
    (stop-local-node! node))
  
  db/Primary
  (primaries [_ test]
    ;; All nodes can potentially be primary in Raft
    (:nodes test))
  
  (setup-primary! [_ test node]
    ;; No special primary setup needed
    )
  
  db/LogFiles
  (log-files [_ test node]
    [(str "/tmp/jepsen-raft-netasync/" node ".log")
     (str "/tmp/jepsen-raft-netasync/" node "/raft-log")]))

(defn db
  "Create a net.async-based Raft DB."
  []
  (NetAsyncDB.))