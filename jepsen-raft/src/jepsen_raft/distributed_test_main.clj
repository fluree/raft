(ns jepsen-raft.distributed-test-main
  "Simple main function to test distributed Raft nodes"
  (:require [jepsen-raft.distributed-node :as node]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:gen-class))

(defn -main
  "Start a distributed Raft node
  
  Usage: clojure -M:node n1 localhost 7001 localhost:7002,localhost:7003"
  [& args]
  (if (< (count args) 4)
    (do
      (println "Usage: node-id host port cluster-members")
      (println "Example: n1 localhost 7001 localhost:7002,localhost:7003")
      (System/exit 1))
    (let [[node-id host port cluster-str] args
          port (Integer/parseInt port)
          cluster (when cluster-str 
                    (str/split cluster-str #","))]
      (log/info "Starting distributed node" node-id "on" host ":" port)
      (log/info "Cluster members:" cluster)
      
      ;; Start the node
      (node/start-node {:node-id node-id
                        :host host
                        :port port
                        :cluster cluster
                        :state-machine nil}) ; Will be set by start-node
      
      ;; Keep the process running
      (log/info "Node" node-id "started. Press Ctrl-C to stop.")
      @(promise))))