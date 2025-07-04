(ns jepsen-raft.db
  "Database setup and teardown for Fluree Raft nodes"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.util :as util]
            [clojure.string :as str]))

(def raft-dir "/opt/fluree-raft")
(def log-dir (str raft-dir "/logs"))
(def data-dir (str raft-dir "/data"))

(defn node-id
  "Generate a node ID from the node name"
  [node]
  (str "node-" (last (str/split node #"-"))))

(defn install!
  "Install Fluree Raft on a node"
  [node version]
  (info "Installing Fluree Raft on" node)
  (c/su
    (c/exec :mkdir :-p raft-dir log-dir data-dir)
    ;; In a real test, you'd download/copy the JAR here
    ;; For now, we'll assume it's available via the local Maven repo
    (c/exec :echo "Fluree Raft installed")))

(defn configure!
  "Generate Raft configuration for a node"
  [node test]
  (let [nodes (:nodes test)
        node-id (node-id node)]
    (c/su
      (c/exec :echo
              (pr-str {:servers (mapv node-id nodes)
                       :this-server node-id
                       :log-directory log-dir
                       :data-directory data-dir
                       :port 7000})
              :> (str raft-dir "/config.edn")))))

(defn start!
  "Start Raft on a node"
  [node test]
  (info "Starting Raft on" node)
  (c/su
    (c/exec :start-stop-daemon
            :--start
            :--background
            :--make-pidfile
            :--pidfile (str raft-dir "/raft.pid")
            :--chdir raft-dir
            :--exec "/usr/bin/java"
            :--
            "-Xmx1G"
            "-cp" "raft.jar"
            "jepsen_raft.server"
            raft-dir)))

(defn stop!
  "Stop Raft on a node"
  [node]
  (info "Stopping Raft on" node)
  (c/su
    (util/meh (c/exec :start-stop-daemon
                      :--stop
                      :--pidfile (str raft-dir "/raft.pid")
                      :--retry "TERM/30/KILL/5"))))

(defn wipe!
  "Remove all Raft data from a node"
  [node]
  (info "Wiping Raft data on" node)
  (c/su
    (c/exec :rm :-rf log-dir data-dir)))

(defrecord DB [version]
  db/DB
  (setup! [this test node]
    (install! node version)
    (configure! node test)
    (start! node test))
  
  (teardown! [this test node]
    (stop! node)
    (wipe! node))
  
  db/LogFiles
  (log-files [this test node]
    [(str raft-dir "/raft.log")
     (str log-dir "/*.log")]))

(defn db
  "Create a new Raft DB instance"
  [version]
  (DB. version))