(ns jepsen-raft.tests.netasync.tcp
  "TCP networking implementation using net.async, similar to Fluree Server's approach."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [clojure.string :as str]
            [fluree.raft :as raft]
            [net.async.tcp :as ntcp]
            [taoensso.nippy :as nippy]))

;; Connection management atom
;; Structure: {this-server {:conn-to {remote-server connection-info}}}
(def connections (atom {}))

;; Pending RPC responses
;; Structure: {msg-id {:callback fn :timeout-at timestamp}}
(def pending-responses (atom {}))

;; Forward declaration
(declare connect-to-server)

(defn serialize
  "Serialize data using Nippy."
  [data]
  (nippy/freeze data))

(defn deserialize
  "Deserialize data using Nippy."
  [data]
  (nippy/thaw data))

(defn send-message
  "Send a message through a TCP connection."
  [conn message]
  (let [serialized (serialize message)
        write-chan (:write-chan conn)]
    (async/put! write-chan serialized)))

(defn make-rpc-message
  "Create an RPC message with proper headers."
  [from to operation data msg-id]
  {:op     operation
   :from   from
   :to     to
   :data   data
   :msg-id msg-id})

(defn send-rpc
  "Send an RPC message to a remote server."
  [this-server remote-server operation data callback]
  (let [msg-id     (str (java.util.UUID/randomUUID))
        conn-path  [this-server :conn-to remote-server]
        conn       (get-in @connections conn-path)]
    (if (and conn (= :connected (:state conn)))
      (do
        ;; Store callback for response
        (swap! pending-responses assoc msg-id 
               {:callback    callback
                :timeout-at  (+ (System/currentTimeMillis) 5000)})
        ;; Send message
        (let [message (make-rpc-message this-server remote-server operation data msg-id)]
          (send-message conn message)))
      (do
        (warn "No active connection to" remote-server "from" this-server)
        (when callback
          (callback {:error (str "No connection to " remote-server)}))))))

(defn handle-rpc-response
  "Handle an RPC response message."
  [msg-id response]
  (if-let [pending (get @pending-responses msg-id)]
    (do
      (swap! pending-responses dissoc msg-id)
      ((:callback pending) response))
    (warn "Received response for unknown message ID:" msg-id)))

(defn message-handler
  "Handle incoming messages from TCP connections."
  [raft-instance conn]
  (fn [message]
    (try
      (let [{:keys [op from to data msg-id]} message]
        (cond
          ;; Handle RPC requests
          (#{:append-entries :request-vote :install-snapshot} op)
          (let [result-promise (promise)
                event-chan (raft/event-chan raft-instance)]
            (raft/invoke-rpc* event-chan op data
                              (fn [result]
                                (deliver result-promise result)))
            (let [response (deref result-promise 5000 {:error :timeout})]
              (send-message conn (make-rpc-message to from 
                                                   (keyword (str (name op) "-response"))
                                                   response msg-id))))
          
          ;; Handle responses
          (#{:append-entries-response :request-vote-response :install-snapshot-response} op)
          (handle-rpc-response msg-id data)
          
          ;; Init message - ignore
          (= :init op)
          (debug "Received init message from" from)
          
          ;; Default
          :else
          (warn "Unknown operation:" op)))
      (catch Exception e
        (error e "Error handling message")))))

(defn monitor-connection
  "Monitor a TCP connection for messages and handle disconnections."
  [this-server remote-server conn raft-instance handler-fn]
  (go-loop []
    (let [read-chan (:read-chan conn)
          msg       (<! read-chan)]
      (cond
        ;; Connection closed
        (nil? msg)
        (do
          (info "Connection closed to" remote-server)
          (swap! connections update-in [this-server :conn-to] dissoc remote-server)
          ;; If we're the client (lower sorted), attempt reconnection
          (when (neg? (compare this-server remote-server))
            (go-loop [attempts 0]
              (<! (async/timeout (* 1000 (Math/pow 2 (min attempts 5)))))
              (info "Attempting to reconnect to" remote-server "attempt" (inc attempts))
              (if-let [new-conn (connect-to-server this-server remote-server raft-instance handler-fn (:event-loop conn))]
                (info "Reconnected to" remote-server)
                (recur (inc attempts))))))
        
        ;; Status message (keyword)
        (keyword? msg)
        (do
          (info "Connection status" msg "for" remote-server)
          (case msg
            :connected
            (do
              (info "Connection established to" remote-server)
              (swap! connections assoc-in [this-server :conn-to remote-server :state] :connected)
              ;; Send init message if client
              (when (= :outbound (:type conn))
                (send-message conn {:op :init :from this-server :to remote-server})))
            
            :disconnected
            (do
              (warn "Connection disconnected to" remote-server)
              (swap! connections assoc-in [this-server :conn-to remote-server :state] :disconnected))
            
            :closed
            (do
              (info "Connection closed to" remote-server)
              (swap! connections update-in [this-server :conn-to] dissoc remote-server)))
          (recur))
        
        ;; Binary message data
        :else
        (do
          (try
            (let [deserialized (deserialize msg)]
              (handler-fn deserialized))
            (catch Exception e
              (error e "Error deserializing message")))
          (recur))))))

(defn connect-to-server
  "Connect to a remote server (used by lower-sorted servers)."
  [this-server remote-server raft-instance handler-fn event-loop]
  (try
    (let [[host port] (str/split remote-server #":")
          port        (Integer/parseInt port)
          client      (ntcp/connect event-loop 
                                    {:host       host 
                                     :port       port
                                     :write-chan (async/chan (async/dropping-buffer 10))})]
      (if client
        (let [conn {:id         (str this-server "->" remote-server)
                    :type       :outbound
                    :from       this-server
                    :to         remote-server
                    :state      :connecting
                    :write-chan (:write-chan client)
                    :read-chan  (:read-chan client)
                    :event-loop event-loop}]
          ;; Store connection
          (swap! connections assoc-in [this-server :conn-to remote-server] conn)
          ;; Start monitoring
          (monitor-connection this-server remote-server conn raft-instance 
                              (message-handler raft-instance conn))
          conn)
        (do
          (warn "Failed to connect to" remote-server)
          nil)))
    (catch Exception e
      (error e "Error connecting to" remote-server)
      nil)))

(defn accept-connections
  "Accept incoming TCP connections (server side)."
  [this-server port raft-instance handler-fn]
  (let [event-loop (ntcp/event-loop)
        server     (ntcp/accept event-loop 
                                {:port          port
                                 :write-chan-fn (fn [] (async/chan (async/dropping-buffer 10)))})]
    (go-loop []
      (when-let [client (<! (:accept-chan server))]
        (let [temp-id (str "temp-" (System/currentTimeMillis))
              conn    {:id         temp-id
                       :type       :inbound
                       :state      :awaiting-init
                       :write-chan (:write-chan client)
                       :read-chan  (:read-chan client)
                       :event-loop event-loop}]
          ;; Monitor for init message
          (go-loop []
            (when-let [msg (<! (:read-chan client))]
              (cond
                ;; Binary message - check if init
                (and (not (keyword? msg)) (not (nil? msg)))
                (try
                  (let [{:keys [op from]} (deserialize msg)]
                    (if (= :init op)
                      (let [conn-info (assoc conn
                                             :id (str from "->" this-server)
                                             :from from
                                             :to this-server
                                             :state :connected)]
                        (swap! connections assoc-in [this-server :conn-to from] conn-info)
                        (info "Accepted connection from" from)
                        (monitor-connection this-server from conn-info raft-instance
                                            (message-handler raft-instance conn-info)))
                      (recur)))
                  (catch Exception e
                    (error e "Error processing init message")
                    (recur)))
                
                ;; Status keyword - wait for actual message
                (keyword? msg)
                (do (debug "Ignoring status message during init:" msg)
                    (recur))
                
                ;; Nil - connection closed
                :else
                (info "Connection closed before init")))))
        (recur)))
    
    ;; Return shutdown function
    (fn []
      (ntcp/shutdown! event-loop))))

(defn launch-network-connections
  "Launch TCP connections following the lower->higher connection rule."
  [this-server all-servers port raft-instance]
  (let [other-servers (remove #(= % this-server) all-servers)
        ;; Connect to servers that sort higher than us
        connect-to    (filter #(pos? (compare % this-server)) other-servers)
        ;; Shared event loop for client connections
        client-event-loop (ntcp/event-loop)]
    
    ;; Start server to accept incoming connections
    (info "Starting TCP server on port" port "for" this-server)
    (let [shutdown-server (accept-connections this-server port raft-instance identity)]
      
      ;; Connect to higher-sorted servers
      (doseq [server connect-to]
        (info this-server "connecting to" server)
        (connect-to-server this-server server raft-instance identity client-event-loop))
      
      ;; Return shutdown function
      (fn []
        (shutdown-server)
        (ntcp/shutdown! client-event-loop)
        (reset! connections {})
        (reset! pending-responses {})))))