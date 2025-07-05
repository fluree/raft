#!/bin/bash

echo "Starting net.async-based Raft nodes..."
echo

# Clean up any existing processes
echo "Cleaning up old processes..."
pkill -f "jepsen-raft.tests.netasync.raft-node" || true
rm -rf /tmp/jepsen-raft-netasync
mkdir -p /tmp/jepsen-raft-netasync

# Start the three nodes
echo "Starting Raft nodes with net.async TCP..."
echo

# Node 1
echo "Starting node n1 (TCP: 9001, HTTP: 7001)"
clojure -J-Dlogback.configurationFile=resources/logback.xml \
    -M -m jepsen-raft.tests.netasync.raft-node \
    n1 9001 7001 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n1.log 2>&1 &

sleep 2

# Node 2
echo "Starting node n2 (TCP: 9002, HTTP: 7002)"
clojure -J-Dlogback.configurationFile=resources/logback.xml \
    -M -m jepsen-raft.tests.netasync.raft-node \
    n2 9002 7002 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n2.log 2>&1 &

sleep 2

# Node 3
echo "Starting node n3 (TCP: 9003, HTTP: 7003)"
clojure -J-Dlogback.configurationFile=resources/logback.xml \
    -M -m jepsen-raft.tests.netasync.raft-node \
    n3 9003 7003 n1,n2,n3 \
    > /tmp/jepsen-raft-netasync/n3.log 2>&1 &

echo "Waiting for nodes to start and elect leader..."
sleep 15

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
echo "To test a write command:"
echo '  curl -X POST -H "Content-Type: application/json" -d '\''{"op":"write","key":"x","value":42}'\'' http://localhost:7001/command | python3 -m json.tool'
echo
echo "To test a read command:"
echo '  curl -X POST -H "Content-Type: application/json" -d '\''{"op":"read","key":"x"}'\'' http://localhost:7001/command | python3 -m json.tool'
echo
echo "To stop all nodes:"
echo "  pkill -f 'jepsen-raft.tests.netasync.raft-node'"