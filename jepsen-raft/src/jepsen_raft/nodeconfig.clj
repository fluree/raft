(ns jepsen-raft.nodeconfig
  "Centralized node configuration for all Jepsen Raft tests.
   This is the single source of truth for node definitions, ports, and Docker-specific settings.")

;; Define all possible nodes with their configuration
(def all-nodes
  "Complete node configuration including ports and Docker-specific settings.
   Each node has:
   - :tcp-port - Port for Raft TCP communication
   - :http-port - Port for HTTP client API
   - :docker-ip - IP address within Docker network
   - :enabled - Whether this node is available for use"
  {"n1" {:tcp-port 9001
         :http-port 7001
         :docker-ip "10.101.0.11"
         :enabled true}
   "n2" {:tcp-port 9002
         :http-port 7002
         :docker-ip "10.101.0.12"
         :enabled true}
   "n3" {:tcp-port 9003
         :http-port 7003
         :docker-ip "10.101.0.13"
         :enabled true}
   "n4" {:tcp-port 9004
         :http-port 7004
         :docker-ip "10.101.0.14"
         :enabled true}
   "n5" {:tcp-port 9005
         :http-port 7005
         :docker-ip "10.101.0.15"
         :enabled true}
   "n6" {:tcp-port 9006
         :http-port 7006
         :docker-ip "10.101.0.16"
         :enabled true}
   "n7" {:tcp-port 9007
         :http-port 7007
         :docker-ip "10.101.0.17"
         :enabled true}})

;; Test profiles defining which nodes to use for different test scenarios
(def test-profiles
  "Predefined node configurations for different test types"
  {:default ["n1" "n2" "n3" "n4" "n5"]     ; Standard 5-node cluster
   :small ["n1" "n2" "n3"]                  ; 3-node cluster for quick tests
   :large ["n1" "n2" "n3" "n4" "n5" "n6" "n7"] ; 7-node cluster for stress tests
   :docker ["n1" "n2" "n3" "n4" "n5"]       ; Docker only supports 5 nodes currently
   :performance-3 ["n1" "n2" "n3"]          ; Performance test with 3 nodes
   :performance-5 ["n1" "n2" "n3" "n4" "n5"] ; Performance test with 5 nodes
   :performance-7 ["n1" "n2" "n3" "n4" "n5" "n6" "n7"]}) ; Performance test with 7 nodes

;; Helper functions to access node configuration

(defn get-nodes
  "Get the list of nodes for a given profile. 
   Returns the default profile if none specified."
  ([] (get-nodes :default))
  ([profile]
   (get test-profiles profile (test-profiles :default))))

(defn get-node-config
  "Get the configuration for a specific node"
  [node-id]
  (get all-nodes node-id))

(defn get-tcp-port
  "Get the TCP port for a node"
  [node-id]
  (:tcp-port (get-node-config node-id)))

(defn get-http-port
  "Get the HTTP port for a node"
  [node-id]
  (:http-port (get-node-config node-id)))

(defn get-docker-ip
  "Get the Docker IP address for a node"
  [node-id]
  (:docker-ip (get-node-config node-id)))

(defn node->ports
  "Get both TCP and HTTP ports for a node.
   Maintains compatibility with existing code."
  [node-id]
  (when-let [config (get-node-config node-id)]
    {:tcp (:tcp-port config)
     :http (:http-port config)}))

(defn nodes->tcp-ports
  "Convert a list of node IDs to a map of node->tcp-port.
   Useful for maintaining compatibility with existing code."
  [nodes]
  (into {} (map (fn [node] [node (get-tcp-port node)]) nodes)))

(defn nodes->http-ports
  "Convert a list of node IDs to a map of node->http-port.
   Useful for maintaining compatibility with existing code."
  [nodes]
  (into {} (map (fn [node] [node (get-http-port node)]) nodes)))

(defn tcp-ports
  "Get all TCP ports as a map for backward compatibility"
  []
  (into {} (map (fn [[k v]] [k (:tcp-port v)]) all-nodes)))

(defn http-ports
  "Get all HTTP ports as a map for backward compatibility"
  []
  (into {} (map (fn [[k v]] [k (:http-port v)]) all-nodes)))

(defn docker-node-ips
  "Get Docker IP configuration string for a list of nodes.
   Format: 'n1:10.101.0.11:9001,n2:10.101.0.12:9002,...'"
  [nodes]
  (clojure.string/join ","
    (map (fn [node]
           (let [config (get-node-config node)]
             (str node ":" (:docker-ip config) ":" (:tcp-port config))))
         nodes)))

(defn get-node-count
  "Get the number of nodes in a profile"
  [profile]
  (count (get-nodes profile)))

;; Docker-specific configuration
(def docker-config
  "Docker-specific settings"
  {:network-subnet "10.101.0.0/24"
   :compose-file "docker/docker-compose.yml"
   :node-ready-timeout-ms 60000
   :health-check-interval "30s"
   :health-check-timeout "10s"
   :health-check-retries 3
   :health-check-start-period "30s"})

;; Performance test specific settings
(def performance-config
  "Performance test specific settings"
  {:http-port-base 8001  ; Base port for performance test HTTP
   :tcp-port-base 8101   ; Base port for performance test TCP
   :max-clients 200
   :commands-per-client 10
   :timeout-ms 2000})