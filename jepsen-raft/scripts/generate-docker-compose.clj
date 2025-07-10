#!/usr/bin/env clojure
;; Generate docker-compose.yml from centralized node configuration

(require '[jepsen-raft.nodeconfig :as nodes]
         '[clojure.string :as str])

(defn generate-service [node-id]
  (let [config (nodes/get-node-config node-id)
        tcp-port (:tcp-port config)
        http-port (:http-port config)
        docker-ip (:docker-ip config)
        docker-nodes (nodes/get-nodes :docker)
        node-ips (nodes/docker-node-ips docker-nodes)]
    (str "  " node-id ":\n"
         "    container_name: raft-" node-id "\n"
         "    hostname: " node-id "\n"
         "    build:\n"
         "      context: ../..\n"
         "      dockerfile: jepsen-raft/docker/Dockerfile.node\n"
         "    environment:\n"
         "      - NODE_ID=" node-id "\n"
         "      - TCP_PORT=" tcp-port "\n"
         "      - HTTP_PORT=" http-port "\n"
         "      - TCP_HOST=0.0.0.0\n"
         "      - HTTP_HOST=0.0.0.0\n"
         "      - NODES=" (str/join "," docker-nodes) "\n"
         "      - NODE_IPS=" node-ips "\n"
         "    ports:\n"
         "      - \"" http-port ":" http-port "\"  # HTTP client port\n"
         "      - \"" tcp-port ":" tcp-port "\"  # TCP Raft port\n"
         "    networks:\n"
         "      raft-network:\n"
         "        ipv4_address: " docker-ip "\n"
         "    volumes:\n"
         "      - " node-id "-data:/tmp/jepsen-raft-network/" node-id "\n"
         "      - " node-id "-logs:/var/log/raft\n"
         "    cap_add:\n"
         "      - NET_ADMIN  # Allow network manipulation for testing\n"
         "    healthcheck:\n"
         "      test: [\"CMD\", \"curl\", \"-f\", \"http://localhost:" http-port "/debug\"]\n"
         "      interval: " (:health-check-interval nodes/docker-config) "\n"
         "      timeout: " (:health-check-timeout nodes/docker-config) "\n"
         "      retries: " (:health-check-retries nodes/docker-config) "\n"
         "      start_period: " (:health-check-start-period nodes/docker-config) "\n")))

(defn generate-volumes [nodes]
  (str/join "\n"
    (mapcat (fn [node]
              [(str "  " node "-data:")
               (str "  " node "-logs:")])
            nodes)))

(defn generate-docker-compose []
  (let [docker-nodes (nodes/get-nodes :docker)]
    (str "version: '3.8'\n"
         "\n"
         "networks:\n"
         "  raft-network:\n"
         "    driver: bridge\n"
         "    ipam:\n"
         "      config:\n"
         "        - subnet: " (:network-subnet nodes/docker-config) "\n"
         "\n"
         "services:\n"
         (str/join "\n" (map generate-service docker-nodes))
         "\n"
         "volumes:\n"
         (generate-volumes docker-nodes)
         "\n")))

(println (generate-docker-compose))