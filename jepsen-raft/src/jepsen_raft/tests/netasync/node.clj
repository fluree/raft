(ns jepsen-raft.tests.netasync.node
  "Individual Raft node implementation with net.async TCP and HTTP command interface."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.core.async :as async :refer [go go-loop <! >! <!! >!! chan timeout]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [fluree.raft :as raft]
            [jepsen-raft.util :as util]
            [jepsen-raft.tests.netasync.tcp :as tcp]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.util.response :as response]
            [taoensso.nippy :as nippy]))

(defn handle-command
  "Handle incoming command from HTTP interface."
  [raft-instance {:keys [op key value old new] :as cmd}]
  (let [result-promise (promise)
        ;; Format command for Jepsen (using :f field)
        raft-cmd       (case op
                         :write {:f :write :key key :value value}
                         :read  {:f :read :key key}
                         :cas   {:f :cas :key key :value [old new]}
                         :delete {:f :delete :key key}
                         {:f :unknown :error "Unknown operation"})]
    (raft/new-entry raft-instance raft-cmd
                    (fn [result]
                      (deliver result-promise result))
                    5000)
    (deref result-promise 6000 {:type :info :error :timeout})))

(defn forward-command-to-leader
  "Forward a command to the leader node via HTTP."
  [leader command]
  (try
    (let [leader-port (case leader
                        "n1" 7001
                        "n2" 7002
                        "n3" 7003
                        7000)
          url (str "http://localhost:" leader-port "/command")
          response (http/post url
                                         {:body            (json/write-str command)
                                          :content-type    :json
                                          :accept          :json
                                          :as              :json
                                          :socket-timeout  5000
                                          :conn-timeout    5000
                                          :throw-exceptions false})]
      (if (= 200 (:status response))
        (:body response)
        {:type :fail :error "Leader forward failed"}))
    (catch Exception e
      {:type :fail :error (str "Forward error: " (.getMessage e))})))

(defn make-http-handler
  "Create HTTP handler for receiving commands."
  [raft-instance node-state state-atom]
  (fn [request]
    (try
      (case (:uri request)
        "/command"
        (let [body    (:body request)
              command (update body :op keyword)]
          ;; Get current Raft state
          (let [state-promise (promise)
                _ (raft/get-raft-state raft-instance
                    (fn [current-state]
                      (deliver state-promise current-state)))
                current-state (deref state-promise 1000 nil)]
            (if (= (:status current-state) :leader)
              ;; We're the leader, process directly
              (let [result (handle-command raft-instance command)]
                (response/response result))
              ;; Forward to leader if we know who it is
              (if-let [leader (:leader current-state)]
                (let [result (forward-command-to-leader leader command)]
                  (response/response result))
                (response/response {:type :fail :error "No leader elected"})))))
        
        "/debug"
        (let [state-promise (promise)
              _ (raft/get-raft-state raft-instance
                  (fn [current-state]
                    (deliver state-promise current-state)))
              current-state (deref state-promise 1000 {:error "Timeout"})]
          (response/response {:node-id    (:node-id @node-state)
                              :leader     (:leader current-state)
                              :status     (str (:status current-state))
                              :term       (:term current-state)
                              :commit     (:commit current-state)
                              :index      (:index current-state)
                              :state-machine @state-atom}))
        
        "/health"
        (response/response {:status "ok"})
        
        (response/not-found "Not found"))
      (catch Exception e
        (error e "Error handling request")
        (response/response {:error (str e)} 500)))))

(defn start-node
  "Start a Raft node with net.async TCP communication and HTTP command interface."
  [node-id tcp-port http-port servers this-server & {:keys [log-dir join?]
                                                       :or {log-dir "/tmp/jepsen-raft-netasync"
                                                            join? false}}]
  (info "Starting net.async Raft node" node-id "TCP:" tcp-port "HTTP:" http-port)
  
  (let [;; Parse all server addresses to get IDs
        server-ids (vec servers)
        
        ;; Node state
        node-state (atom {:node-id      node-id
                          :tcp-port     tcp-port
                          :http-port    http-port})
        
        ;; State machine atom
        state-atom (atom {})
        
        ;; Create state machine function (handles Jepsen's :f field)
        base-state-machine (util/create-kv-state-machine state-atom)
        state-machine-fn (fn [entry raft-state]
                           (cond
                             ;; Handle nil or empty entries
                             (or (nil? entry) (empty? entry))
                             (do
                               (debug "State machine received nil/empty entry")
                               (util/ok-result))
                             
                             ;; Check if this is an internal Raft operation (no :f field)
                             (nil? (:f entry))
                             (do
                               (debug "State machine received non-application entry:" entry)
                               (util/ok-result))
                             
                             ;; Normal application operations - convert :f to :op
                             :else
                             (let [normalized-entry (-> entry
                                                       (assoc :op (:f entry))
                                                       (dissoc :f))]
                               (base-state-machine normalized-entry raft-state))))
        
        ;; Create RPC sender using TCP
        rpc-sender-fn (tcp/send-rpc-wrapper this-server)
        
        ;; Create Raft configuration
        raft-config (util/default-raft-config
                      node-id server-ids
                      :log-dir (str log-dir "/" node-id "/")
                      :state-machine-fn state-machine-fn
                      :rpc-sender-fn rpc-sender-fn
                      :leader-change-fn (fn [event]
                                          (info "LEADER CHANGE -" node-id "- new leader:" (:new-leader event))))
        
        ;; Add snapshot functions (stubs for now)
        full-raft-config (merge raft-config
                                {:snapshot-write      (fn [index callback]
                                                        (debug node-id "Writing snapshot at index" index)
                                                        (when callback (callback)))
                                 :snapshot-reify      (fn [snapshot-index]
                                                        (debug node-id "Reifying snapshot at index" snapshot-index)
                                                        @state-atom)
                                 :snapshot-install    (fn [snapshot-map]
                                                        (let [{:keys [snapshot-index]} snapshot-map]
                                                          (debug node-id "Installing snapshot at index" snapshot-index)
                                                          nil))
                                 :snapshot-xfer       (constantly nil)
                                 :snapshot-list-indexes (constantly [])})
        
        ;; Start Raft instance
        raft-instance (raft/start full-raft-config)
        
        ;; Start TCP networking
        tcp-shutdown (tcp/launch-network-connections this-server server-ids 
                                                     tcp-port raft-instance)
        
        ;; Start HTTP server
        http-handler (-> (make-http-handler raft-instance node-state state-atom)
                         wrap-json-body
                         wrap-json-response)
        http-server  (jetty/run-jetty http-handler
                                      {:port http-port
                                       :join? false})]
    
    (info "Net.async Raft node" node-id "started successfully")
    
    ;; Return shutdown function
    (fn []
      (info "Shutting down net.async Raft node" node-id)
      (.stop http-server)
      (tcp-shutdown)
      (raft/close raft-instance))))

(defn -main
  "Main entry point for standalone net.async Raft node."
  [& args]
  (if (< (count args) 5)
    (do
      (println "Usage: node-id tcp-port http-port this-server server1,server2,server3")
      (println "Example: n1 9001 7001 n1 n1,n2,n3")
      (System/exit 1))
    (let [[node-id tcp-port http-port this-server servers-str] args
          tcp-port  (Integer/parseInt tcp-port)
          http-port (Integer/parseInt http-port)
          servers   (str/split servers-str #",")
          shutdown  (start-node node-id tcp-port http-port servers this-server)]
      ;; Keep running until interrupted
      (.. Runtime getRuntime (addShutdownHook (Thread. shutdown)))
      (Thread/sleep Long/MAX_VALUE))))