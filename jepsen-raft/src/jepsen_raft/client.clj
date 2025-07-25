(ns jepsen-raft.client
  "Client for Raft test."
  (:require [clojure.tools.logging :refer [error]]
            [jepsen [client :as client]
             [independent :as independent]]
            [jepsen-raft.config :as config]
            [jepsen-raft.http-client :as http-client]
            [jepsen-raft.nodeconfig :as nodes]))

(defn- node->port
  "Get HTTP port for a node."
  [node]
  (nodes/get-http-port node))

(defn- handle-redirect
  "Handle redirect response from server."
  [response]
  (when (= :redirect (:type response))
    (if-let [leader-url (:leader response)]
      {:redirect leader-url}
      (throw (ex-info "No leader available" {:type :no-leader})))))

(defn- send-command!
  "Send command to a node, following redirects to leader if necessary."
  [node command]
  (let [port (node->port node)
        max-redirects config/max-retries]
    (loop [current-node node
           current-port port
           redirects 0]
      (if (>= redirects max-redirects)
        (throw (ex-info "Too many redirects" {:type :too-many-redirects}))
        (let [result (try
                       (if-let [response (http-client/send-command!
                                          current-node
                                          current-port
                                          command
                                          config/connection-timeout-ms)]
                         (if-let [redirect (handle-redirect response)]
                           redirect
                           {:result response})
                         (throw (ex-info "No response from server" {:type :no-response})))
                       (catch java.net.ConnectException _ex
                         (throw (ex-info "Connection refused" {:type :connection-refused})))
                       (catch java.net.SocketTimeoutException _ex
                         (throw (ex-info "Request timeout" {:type :timeout}))))]
          (if (:redirect result)
            ;; Parse redirect URL to get node and port
            (let [url (:redirect result)
                  [_ port-str] (re-find #":(\d+)" url)
                  new-port (Integer/parseInt port-str)
                  new-node (some (fn [node] (when (= (nodes/get-http-port node) new-port) node))
                                 ["n1" "n2" "n3" "n4" "n5" "n6" "n7"])]
              (recur new-node new-port (inc redirects)))
            (:result result)))))))

(defn- handle-command-result
  "Handle command result with consistent error handling."
  [cmd-result & [success-value]]
  (cond
    (nil? cmd-result) {:error :no-response}
    (or (= "fail" (:type cmd-result)) (= :fail (:type cmd-result))) {:error (:error cmd-result)}
    :else (or success-value {})))

(defrecord NetAsyncClient [node]
  client/Client
  (open! [this _test node]
    (assoc this :node node))

  (setup! [_this _test])

  (invoke! [_this _test op]
    (try
      ;; For independent checker, the Op record contains [:key operation-value] in its :value field
      (let [[k operation-value] (:value op)
            result (case (:f op)
                     :read
                     (let [cmd-result (send-command! node {:f :read :key k})]
                       (handle-command-result cmd-result {:value (:value cmd-result)}))

                     :write
                     (let [cmd-result (send-command! node {:f :write
                                                           :key k
                                                           :value operation-value})]
                       (handle-command-result cmd-result))

                     :cas
                     (let [[old new] operation-value
                           cmd-result (send-command! node {:f :cas
                                                           :key k
                                                           :old old
                                                           :new new})]
                       (handle-command-result cmd-result))

                     :delete
                     (let [cmd-result (send-command! node {:f :delete :key k})]
                       (handle-command-result cmd-result))

                     ;; Unknown operation
                     {:error :unknown-operation})]

        (if (:error result)
          (assoc op :type :fail :error (:error result))
          (case (:f op)
            :read (assoc op :type :ok :value (independent/tuple k (:value result)))
            (assoc op :type :ok))))

      (catch Exception ex
        (let [data (ex-data ex)
              error-type (:type data)
              error-mappings {:timeout            {:type :info :error :timeout}
                              :connection-refused {:type :fail :error :connection-refused}
                              :no-leader          {:type :fail :error :no-leader}
                              :too-many-redirects {:type :fail :error :too-many-redirects}
                              :no-response        {:type :fail :error :no-response}}
              error-result (get error-mappings error-type)]
          (if error-result
            (merge op error-result)
            ;; Default case for unexpected errors
            (do
              (error ex "Unexpected error")
              (assoc op :type :fail :error (str ex))))))))

  (teardown! [_this _test])

  (close! [_this _test]))

(defn client
  "Create a net.async-based Raft client."
  []
  (NetAsyncClient. nil))