#!/bin/bash
set -e

# Create required directories
mkdir -p /opt/fluree-server/data
mkdir -p /opt/fluree-server/logs

# Start the distributed Raft node
cd /opt/fluree-server/jepsen-raft

# Node configuration from environment
NODE_ID=${NODE_ID:-n1}
NODE_HOST=${NODE_HOST:-localhost}
NODE_PORT=${NODE_PORT:-7001}
CLUSTER_MEMBERS=${CLUSTER_MEMBERS:-""}

echo "Starting Raft node $NODE_ID on $NODE_HOST:$NODE_PORT"
echo "Cluster members: $CLUSTER_MEMBERS"

# Run the distributed node
exec clojure -M -m jepsen-raft.tests.distributed.test-main \
    $NODE_ID \
    $NODE_HOST \
    $NODE_PORT \
    "$CLUSTER_MEMBERS"