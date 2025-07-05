#!/bin/bash

echo "Starting net.async-based Raft test..."
echo

# Clean up any existing processes
echo "Cleaning up old processes..."
pkill -f "jepsen-raft.tests.netasync.node" || true
rm -rf /tmp/jepsen-raft-netasync
mkdir -p /tmp/jepsen-raft-netasync

# Start the three nodes
echo "Starting Raft nodes with net.async TCP..."
echo

# Node 1
echo "Starting node n1 (TCP: 9001, HTTP: 7001)"
clojure -M -m jepsen-raft.tests.netasync.node \
    n1 9001 7001 n1 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n1.log 2>&1 &

sleep 2

# Node 2
echo "Starting node n2 (TCP: 9002, HTTP: 7002)"
clojure -M -m jepsen-raft.tests.netasync.node \
    n2 9002 7002 n2 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n2.log 2>&1 &

sleep 2

# Node 3
echo "Starting node n3 (TCP: 9003, HTTP: 7003)"
clojure -M -m jepsen-raft.tests.netasync.node \
    n3 9003 7003 n3 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n3.log 2>&1 &

sleep 5

echo
echo "Checking node status..."
echo

for i in 1 2 3; do
    echo "=== Node n$i (HTTP port 700$i) ==="
    curl -s http://localhost:700$i/debug | python3 -m json.tool || echo "Node not ready yet"
    echo
done

echo
echo "Nodes started. Logs available at:"
echo "  /tmp/jepsen-raft-netasync/n1.log"
echo "  /tmp/jepsen-raft-netasync/n2.log"
echo "  /tmp/jepsen-raft-netasync/n3.log"
echo
echo "Run the test with:"
echo "  clojure -M:netasync test netasync --time-limit 10 --concurrency 1 --no-ssh --nodes n1,n2,n3"
echo
echo "To stop all nodes:"
echo "  pkill -f 'jepsen-raft.tests.netasync.node'"