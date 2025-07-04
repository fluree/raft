#!/bin/bash
set -e

echo "Running distributed Jepsen test..."
echo "================================"

# Run test without timeout command
clojure -M:distributed test distributed \
  --time-limit 5 \
  --concurrency 1 \
  --no-ssh \
  --nodes n1,n2,n3 \
  2>&1 | tee test-output.log || true

echo ""
echo "Test Summary:"
echo "============="
grep -E "(valid|invalid|indeterminate|Wrote|perf|latency)" test-output.log | tail -20 || echo "No results found"

echo ""
echo "Checking for errors:"
grep -i "error\|exception" test-output.log | head -10 || echo "No errors found"