(ns jepsen-raft.client
  "Client for Raft test."
  (:require [clojure.tools.logging :refer [error]]
            [jepsen [client :as client]
             [independent :as independent]]
            [jepsen-raft.config :as config]
            [jepsen-raft.http-client :as http-client]))

(defn- node->port
  "Get HTTP port for a node."
  [node]
  (get config/http-ports node 7000))

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
                                          1000)]  ; 1 second timeout for faster failures
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
                  new-node (some (fn [[n p]] (when (= p new-port) n)) config/http-ports)]
              (recur new-node new-port (inc redirects)))
            (:result result)))))))

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
                     (let [cmd-result (send-command! node {:op :read :key k})]
                       (cond
                         (nil? cmd-result) {:error :no-response}
                         (= "fail" (:type cmd-result)) {:error (:error cmd-result)}
                         :else {:value (:value cmd-result)}))

                     :write
                     (let [cmd-result (send-command! node {:op :write
                                                           :key k
                                                           :value operation-value})]
                       (cond
                         (nil? cmd-result) {:error :no-response}
                         (= "fail" (:type cmd-result)) {:error (:error cmd-result)}
                         :else {}))

                     :cas
                     (let [[old new] operation-value
                           cmd-result (send-command! node {:op :cas
                                                           :key k
                                                           :old old
                                                           :new new})]
                       (cond
                         (nil? cmd-result) {:error :no-response}
                         (= "fail" (:type cmd-result)) {:error (:error cmd-result)}
                         :else {}))

                     :delete
                     (let [cmd-result (send-command! node {:op :delete :key k})]
                       (cond
                         (nil? cmd-result) {:error :no-response}
                         (= "fail" (:type cmd-result)) {:error (:error cmd-result)}
                         :else {}))

                     ;; Unknown operation
                     {:error :unknown-operation})]

        (if (:error result)
          (assoc op :type :fail :error (:error result))
          (case (:f op)
            :read (assoc op :type :ok :value (independent/tuple k (:value result)))
            (assoc op :type :ok))))

      (catch Exception ex
        (let [data (ex-data ex)
              error-type (:type data)]
          (case error-type
            :timeout
            (assoc op :type :info :error :timeout)

            :connection-refused
            (assoc op :type :fail :error :connection-refused)

            :no-leader
            (assoc op :type :fail :error :no-leader)

            :too-many-redirects
            (assoc op :type :fail :error :too-many-redirects)

            :no-response
            (assoc op :type :fail :error :no-response)

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