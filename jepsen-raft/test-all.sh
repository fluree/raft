#!/bin/bash

echo "Testing all Jepsen test aliases after reorganization..."
echo

# Test 1: Distributed test
echo "=== Testing distributed test ==="
curl -s http://localhost:7001/debug | python3 -m json.tool
if [ $? -eq 0 ]; then
    echo "✅ Docker cluster is up and running"
    echo "Run with: clojure -M:distributed test distributed --time-limit 10 --concurrency 1 --no-ssh --nodes n1,n2,n3"
else
    echo "❌ Docker cluster not ready"
fi
echo

# Test 2: Net.async test (default)
echo "=== Testing net.async test (default) ==="
echo "Run with: clojure -M:netasync test netasync --time-limit 10 --concurrency 1 --nodes n1,n2,n3"
echo

# Test 3: Performance test
echo "=== Testing performance test ==="
echo "Requires Docker cluster to be running"
echo "Run with: clojure -M:performance single 5 20"
echo

echo "All test configurations:"
echo "- Distributed test: tests/distributed/test.clj"
echo "- Net.async test: tests/netasync/test.clj (default)"
echo "- Performance test: tests/performance/test.clj"