(ns jepsen-raft.db
  "Database setup for local Raft test."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn error]]
            [clojure.java.io :as io]
            [jepsen [db :as db]]
            [jepsen-raft.config :as config]
            [jepsen-raft.util :as util]
            [jepsen-raft.http-client :as http-client]
            [jepsen-raft.nodeconfig :as nodes]))

;; Use node->ports from util namespace

;; Store running node processes
(def ^:private node-processes (atom {}))

(defn- start-local-node!
  "Start a local net.async Raft node."
  [node test-nodes]
  ;; Check for port conflicts before starting
  (let [{:keys [tcp http]} (util/node->ports node)]
    (when-not (util/check-port-available tcp)
      (throw (ex-info (str "TCP port " tcp " for node " node " is already in use. "
                           "Please kill any lingering processes: pkill -f jepsen-raft.raft-node")
                      {:node node :port tcp :type :tcp})))
    (when-not (util/check-port-available http)
      (throw (ex-info (str "HTTP port " http " for node " node " is already in use. "
                           "Please kill any lingering processes: pkill -f jepsen-raft.raft-node")
                      {:node node :port http :type :http}))))
  
  ;; Stagger node startup to avoid race conditions
  (let [node-index (.indexOf test-nodes node)
        node-index (if (neg? node-index) 0 node-index)  ; Default to 0 if not found
        startup-delay (* node-index 2000)]
    (when (> startup-delay 0)
      (info "Delaying startup of" node "by" startup-delay "ms")
      (Thread/sleep startup-delay)))

  (let [{:keys [tcp http]} (util/node->ports node)
        log-dir     config/log-directory
        log-file    (str log-dir "/" node ".log")
        err-file    (str log-dir "/" node ".err")

        ;; Ensure log directory exists
        _ (io/make-parents log-file)

        ;; Build command with only the actual test nodes 
        nodes       (clojure.string/join "," test-nodes)
        ;; Use shell wrapper to ensure proper command execution
        cmd         ["sh" "-c"
                     (str "clojure -M -m jepsen-raft.raft-node "
                          node " " tcp " " http " " nodes)]

        ;; Start the process
        working-dir (java.io.File. ".")
        _ (info "Working directory for" node ":" (.getAbsolutePath working-dir))
        process-builder (ProcessBuilder. cmd)
        _ (.directory process-builder working-dir)
        ;; Inherit environment to ensure clojure can find dependencies
        env (.environment process-builder)
        _ (.putAll env (System/getenv))
        _ (.redirectOutput process-builder (java.io.File. log-file))
        _ (.redirectError process-builder (java.io.File. err-file))
        _ (util/log-node-operation "Starting" node (str "with command: " cmd))
        process (.start process-builder)]

    ;; Store process for later cleanup
    (swap! node-processes assoc node process)

    ;; Check if process started successfully
    (util/log-node-operation "Process started for" node (str "isAlive: " (.isAlive process)))

    ;; Give process time to write initial output
    (Thread/sleep 1000)

    ;; Check if process is still alive
    (when-not (.isAlive process)
      (let [exit-code (.exitValue process)]
        (throw (ex-info (str "Process for node " node " died immediately with exit code " exit-code ". Check " err-file " for errors")
                        {:node node :err-file err-file :exit-code exit-code}))))

    ;; Wait for node to be ready
    (Thread/sleep 2000)

    ;; Check if node started successfully using http-client with longer timeout
    (if (http-client/wait-for-node-ready node http 60000)
      (do
        (info "Net.async node" node "started successfully")
        :started)
      (throw (ex-info (str "Node " node " failed to start within timeout")
                      {:node node :port http})))))

(defn- stop-local-node!
  "Stop a local net.async Raft node."
  [node]
  (util/log-node-operation "Stopping net.async" node)
  (when-let [process (get @node-processes node)]
    (try
      (.destroy process)
      (Thread/sleep 500)
      (when (.isAlive process)
        (.destroyForcibly process)
        (warn "Had to forcibly kill node" node))
      (catch Exception e
        (error "Error stopping node" node ":" (.getMessage e)))
      (finally
        (swap! node-processes dissoc node)))))

(defrecord NetAsyncDB []
  db/DB
  (setup! [_ test node]
    (info "Setting up net.async Raft node" node)
    ;; Clean up any existing Raft logs before starting
    (let [log-dir (str config/log-directory node "/")]
      (when (.exists (io/file log-dir))
        (info "Cleaning up existing Raft logs for" node)
        (doseq [file (.listFiles (io/file log-dir))
                :when (.isFile file)]
          (.delete file))))
    (start-local-node! node (:nodes test)))

  (teardown! [_ _test node]
    (info "Tearing down net.async Raft node" node)
    (stop-local-node! node))

  db/Primary
  (primaries [_ test]
    ;; All nodes can potentially be primary in Raft
    (:nodes test))

  (setup-primary! [_ _test _node]
    ;; No special primary setup needed
    )

  db/LogFiles
  (log-files [_ _test node]
    [(str config/log-directory node ".log")
     (str config/log-directory node "/raft-log")]))

(defn db
  "Create a net.async-based Raft DB."
  []
  (NetAsyncDB.))