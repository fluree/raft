(ns jepsen-raft.client
  "Client protocol for interacting with Fluree Raft via HTTP API"
  (:require [jepsen.client :as client]
            [clojure.tools.logging :refer [info debug error]]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [slingshot.slingshot :refer [try+]]))

(defn node-url
  "Get the HTTP URL for a node"
  [node]
  (str "http://" node ":8090"))

(defn api-request
  "Make an API request to Fluree"
  [node endpoint method body]
  (let [url (str (node-url node) endpoint)
        request-opts (cond-> {:method method
                              :content-type :json
                              :accept :json
                              :throw-exceptions false
                              :socket-timeout 5000
                              :connection-timeout 5000}
                       body (assoc :body (json/write-str body)))]
    (try+
      (let [response (http/request (merge request-opts {:url url}))
            status (:status response)]
        (cond
          (= status 200) {:ok true :value (when-let [body (:body response)]
                                             (json/read-str body :key-fn keyword))}
          (= status 404) {:ok true :value nil} ; Key not found
          (= status 409) {:ok false :error :conflict} ; CAS conflict
          (>= status 500) {:ok false :error :server-error}
          :else {:ok false :error (str "Unexpected status: " status)}))
      (catch [:status 503] _
        {:ok false :error :unavailable})
      (catch java.net.SocketTimeoutException _
        {:ok false :error :timeout})
      (catch Exception e
        {:ok false :error (str "Request failed: " (.getMessage e))}))))

(defn kv-key-url
  "Get the URL for a key-value operation"
  [key]
  (str "/kv/" (name key)))

(defrecord FlureeClient []
  client/Client
  (open! [this test node]
    (assoc this :node node))
  
  (setup! [_ _])
  
  (invoke! [this test op]
    (let [node (:node this)
          {:keys [f key value old new]} op]
      (case f
        :read
        (let [result (api-request node (kv-key-url key) :get nil)]
          (if (:ok result)
            (assoc op :type :ok :value (:value result))
            (assoc op :type :fail :error (:error result))))
        
        :write
        (let [result (api-request node (kv-key-url key) :put {:value value})]
          (if (:ok result)
            (assoc op :type :ok)
            (assoc op :type :fail :error (:error result))))
        
        :cas
        (let [result (api-request node (kv-key-url key) :post {:old old :new new})]
          (if (:ok result)
            (assoc op :type :ok)
            (if (= (:error result) :conflict)
              (assoc op :type :fail)
              (assoc op :type :info :error (:error result)))))
        
        :delete
        (let [result (api-request node (kv-key-url key) :delete nil)]
          (if (:ok result)
            (assoc op :type :ok)
            (assoc op :type :fail :error (:error result))))
        
        (assoc op :type :fail :error (str "Unknown operation: " f)))))
  
  (teardown! [_ _])
  
  (close! [_ _]))

(defn client
  "Create a new Fluree client"
  []
  (FlureeClient.))