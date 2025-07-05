(ns jepsen-raft.tests.netasync.client
  "Client for net.async-based distributed Raft test."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [jepsen [client :as client]]
            [jepsen-raft.util :as util]
            [slingshot.slingshot :refer [throw+ try+]]))

(defn node->http-url
  "Convert node name to HTTP URL."
  [node]
  (case node
    "n1" "http://localhost:7001"
    "n2" "http://localhost:7002" 
    "n3" "http://localhost:7003"
    (str "http://localhost:7000")))

(defn send-command!
  "Send command to a node, following redirects to leader if necessary."
  [node command]
  (let [url      (str (node->http-url node) "/command")
        max-redirects 3]
    (loop [current-url url
           redirects   0]
      (if (>= redirects max-redirects)
        (throw+ {:type :too-many-redirects})
        (let [result (try+
                       (let [response (http/post current-url
                                                 {:body            (json/write-str command)
                                                  :content-type    :json
                                                  :accept          :json
                                                  :as              :json
                                                  :socket-timeout  5000
                                                  :conn-timeout    5000
                                                  :throw-exceptions false})]
                         (case (:status response)
                           200
                           (let [body (:body response)]
                             (if (= :redirect (:type body))
                               ;; Follow redirect to leader
                               (if-let [leader-url (:leader body)]
                                 {:redirect leader-url}
                                 (throw+ {:type :no-leader}))
                               ;; Return result
                               {:result body}))
                           
                           (throw+ {:type :http-error :status (:status response)})))
                       (catch java.net.ConnectException e
                         (throw+ {:type :connection-refused}))
                       (catch java.net.SocketTimeoutException e
                         (throw+ {:type :timeout})))]
          (if (:redirect result)
            (recur (:redirect result) (inc redirects))
            (:result result)))))))

(defrecord NetAsyncClient [node]
  client/Client
  (open! [this test node]
    (assoc this :node node))
  
  (setup! [this test])
  
  (invoke! [this test op]
    (try+
      (case (:f op)
        :read
        (let [result (send-command! node {:op :read :key (:key op)})]
          (assoc op :type (:type result)
                    :value (:value result)))
        
        :write
        (let [result (send-command! node {:op :write 
                                          :key (:key op)
                                          :value (:value op)})]
          (assoc op :type (:type result)))
        
        :cas
        (let [[old new] (:value op)
              result    (send-command! node {:op :cas
                                             :key (:key op)
                                             :old old
                                             :new new})]
          (if (:error result)
            (assoc op :type :fail :error (:error result))
            (assoc op :type (:type result))))
        
        :delete
        (let [result (send-command! node {:op :delete :key (:key op)})]
          (assoc op :type (:type result))))
      
      (catch [:type :timeout] e
        (assoc op :type :info :error :timeout))
      
      (catch [:type :connection-refused] e
        (assoc op :type :fail :error :connection-refused))
      
      (catch [:type :no-leader] e
        (assoc op :type :fail :error :no-leader))
      
      (catch [:type :too-many-redirects] e
        (assoc op :type :fail :error :too-many-redirects))
      
      (catch Exception e
        (error e "Unexpected error")
        (assoc op :type :info :error (str e)))))
  
  (teardown! [this test])
  
  (close! [this test]))

(defn client
  "Create a net.async-based Raft client."
  []
  (NetAsyncClient. nil))