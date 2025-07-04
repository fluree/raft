(ns jepsen-raft.distributed-node
  "Distributed Raft node with TCP networking for Jepsen testing"
  (:require [clojure.core.async :as async :refer [<! >! go go-loop]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util]
            [net.async.tcp :as tcp]
            [taoensso.nippy :as nippy])

(defonce nodes (atom {}))
(defonce connections (atom {}))

;; Message serialization
(defn- serialize-message [data]
  (nippy/freeze data))

(defn- deserialize-message [data]
  (nippy/thaw data))

;; TCP client management
(defn- connect-to-node
  "Establishes TCP connection to remote node"
  [node-id remote-host remote-port]
  (let [evt-loop (tcp/event-loop)
        client (tcp/connect evt-loop {:host remote-host :port remote-port})]
    (swap! connections assoc-in [node-id remote-host] 
           {:client client
            :write-chan (:write-chan client)
            :read-chan (:read-chan client)
            :event-loop evt-loop})
    client))

(defn- handle-incoming-connection
  "Handles new incoming TCP connections"
  [node-id client]
  (let [{:keys [read-chan write-chan]} client]
    (go-loop []
      (when-let [msg (<! read-chan)]
        (when-let [data (try (deserialize-message msg)
                             (catch Exception e
                               (log/error e "Failed to deserialize message")
                               nil))]
          (when-let [node (get @nodes node-id)]
            (let [event-chan (raft/event-chan node)
                  response-chan (async/chan)
                  [op payload] (if (vector? data) data [data nil])]
              (raft/invoke-rpc* event-chan op payload response-chan)
              (when-let [response (<! response-chan)]
                (>! write-chan (serialize-message response))))))
        (recur)))))

(defn- start-tcp-server
  "Starts TCP server for a node"
  [node-id port]
  (let [evt-loop (tcp/event-loop)
        acceptor (tcp/accept evt-loop {:port port})
        accept-chan (:accept-chan acceptor)]
    (go-loop []
      (when-let [client (<! accept-chan)]
        (handle-incoming-connection node-id client)
        (recur)))
    {:event-loop evt-loop
     :acceptor acceptor
     :accept-chan accept-chan}))

;; RPC implementation for TCP
(defn- tcp-rpc
  "TCP-based RPC implementation"
  [node-id]
  (fn [raft-node member-id rpc response-chan]
    (go
      (try
        (let [[host port] (str/split member-id #":")
              conn (or (get-in @connections [node-id member-id])
                       (connect-to-node node-id host (Integer/parseInt port)))
              write-chan (:write-chan conn)]
          (>! write-chan (serialize-message rpc))
          (let [read-chan (:read-chan conn)
                response (<! read-chan)]
            (if response
              (>! response-chan (deserialize-message response))
              (async/close! response-chan))))
        (catch Exception e
          (log/error e "RPC failed to" member-id)
          (async/close! response-chan))))))

;; Public API
(defn start-node
  "Starts a distributed Raft node with TCP networking
  
  Options:
  - :node-id - unique identifier for this node (e.g. 'n1')
  - :host - hostname/IP to bind to
  - :port - TCP port to listen on
  - :cluster - vector of other nodes in format ['host:port' ...]
  - :state-machine - the state machine to use"
  [{:keys [node-id host port cluster state-machine]}]
  (let [member-id (str host ":" port)
        state-atom (atom {})
        config (merge {:this-server member-id
                       :servers (into [member-id] cluster)
                       :timeout-ms (:election-timeout-ms util/default-timeouts)
                       :heartbeat-ms (:heartbeat-ms util/default-timeouts)
                       :log-directory (str (:log-directory util/default-paths) node-id "/")
                       :snapshot-threshold (:snapshot-threshold util/default-test-params)
                       :state-machine (util/create-state-machine state-atom)
                       :send-rpc-fn (tcp-rpc node-id)
                       :snapshot-write (fn [raft-state _] 
                                         (log/debug node-id "Writing snapshot")
                                         @state-atom)
                       :snapshot-reify (fn [snapshot-index]
                                         (log/debug node-id "Reifying snapshot at index" snapshot-index)
                                         @state-atom)
                       :snapshot-install (fn [snapshot-map]
                                           (let [{:keys [snapshot-index]} snapshot-map]
                                             (log/debug node-id "Installing snapshot at index" snapshot-index)
                                             nil))
                       :snapshot-xfer (fn [_ _] nil)
                       :snapshot-list-indexes (constantly [])})
        node (raft/start config)]
    
    ;; Start TCP server
    (let [server (start-tcp-server node-id port)]
      (swap! nodes assoc node-id 
             {:node node
              :server server
              :config config}))
    
    (log/info "Started distributed node" node-id "on" member-id)
    node))

(defn stop-node
  "Stops a distributed node and cleans up connections"
  [node-id]
  (when-let [{:keys [node server]} (get @nodes node-id)]
    ;; Stop the Raft node
    (raft/close node)
    
    ;; Close TCP server
    (when-let [{:keys [accept-chan event-loop]} server]
      (async/close! accept-chan)
      (tcp/shutdown! event-loop))
    
    ;; Close all outbound connections
    (doseq [[_ conn] (get @connections node-id)]
      (when-let [write-chan (:write-chan conn)]
        (async/close! write-chan))
      (when-let [evt-loop (:event-loop conn)]
        (tcp/shutdown! evt-loop)))
    
    (swap! nodes dissoc node-id)
    (swap! connections dissoc node-id)
    (log/info "Stopped distributed node" node-id)))

;; Client operations
(defn invoke-operation
  "Invoke a Jepsen operation on the cluster"
  [node-id op timeout-ms]
  (when-let [node (get-in @nodes [node-id :node])]
    (let [result-promise (promise)
          callback (fn [result]
                     (deliver result-promise result))]
      (raft/new-command node op callback)
      (deref result-promise timeout-ms {:type :info :error :timeout}))))

(defn write-key
  "Write a key-value pair to the cluster"
  [node-id k v]
  (invoke-operation node-id {:f :write :key k :value v} 5000))

(defn read-key
  "Read a value from the cluster"
  [node-id k]
  (invoke-operation node-id {:f :read :key k} 5000))

(defn cas-key
  "Compare-and-swap operation"
  [node-id k old-val new-val]
  (invoke-operation node-id {:f :cas :key k :old old-val :new new-val} 5000))

(defn delete-key
  "Delete a key from the cluster"
  [node-id k]
  (invoke-operation node-id {:f :delete :key k} 5000))