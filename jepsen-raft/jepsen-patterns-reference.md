# Jepsen Testing Patterns for Raft Implementation

This document summarizes key Jepsen testing patterns and learnings relevant to our Fluree Raft implementation, based on practical experience and Jepsen documentation.

## Overview

Jepsen is a framework for testing distributed systems by simulating network partitions, clock skew, and other real-world conditions while verifying correctness properties like linearizability.

## Key Concepts

### Models
Models define the expected behavior of a system. For key-value stores, common models include:
- **Register Model**: Single key with read/write operations
- **Multi-Register Model**: Multiple independent keys
- **CAS Register**: Compare-and-swap operations
- **Custom Models**: Implement specific business logic

### Checkers
Checkers validate operation histories against models:
- **Linearizable Checker**: Verifies linearizability using Knossos
- **Performance Checkers**: Latency, throughput analysis
- **Timeline**: Visual history representation

## Multi-Key Testing Patterns

### Pattern 1: Single Multi-Register Model (Current Implementation)

**Structure:**
```clojure
(defrecord MultiRegister [registers]
  Model
  (step [r op]
    (let [k (:key op)
          current-value (get registers k)]
      ;; Handle operations per key
      )))
```

**Pros:**
- Simple implementation
- Single model manages all keys
- Easy to reason about cross-key interactions

**Cons:**
- Linearizability checker processes entire history
- Performance degrades with long histories
- No parallelization of verification

**Configuration:**
```clojure
:checker (checker/compose
           {:linear (checker/linearizable
                      {:model (multi-register)})})
```

### Pattern 2: Independent Checker Pattern (Recommended)

**Structure:**
```clojure
:checker (checker/compose
           {:linear (independent/checker
                      (checker/linearizable 
                        {:model (model/register)}))})
```

**Pros:**
- Parallel verification per key
- Handles long test runs efficiently
- Industry standard pattern
- Scales with number of keys

**Cons:**
- More complex setup
- Cannot verify cross-key invariants
- Requires generator modifications

**Generator Setup:**
```clojure
:generator (independent/concurrent-generator
             10                    ; max keys
             (range)              ; key sequence
             (fn [_k]             ; per-key generator
               (gen/mix [read-op write-op cas-op])))
```

## Common Issues and Solutions

### Issue 1: JSON Serialization of Keywords

**Problem:** Keywords (`:fail`) converted to strings (`"fail"`) during HTTP transport.

**Symptoms:**
```clojure
;; Expected
{:type :fail, :error :cas-failed}

;; Actual after JSON
{:type "fail", :error "cas-failed"}
```

**Solution:**
```clojure
;; Client code
(= "fail" (:type cmd-result))  ; Not (= :fail ...)
```

### Issue 2: Missing Keys in Linearizability Checker

**Problem:** Operations lose `:key` field when passed to model.

**Symptoms:**
```
Model step: op= {:f :read, :value nil} , key= nil
```

**Root Cause:** Jepsen's linearizable checker may preprocess operations differently based on configuration.

**Solutions:**
1. Use independent checker pattern
2. Verify operation structure in history
3. Add debug logging to model

### Issue 3: CAS Operation Failures

**Problem:** CAS operations on non-existent keys returning success instead of failure.

**Solution Approach:**
1. Add debug logging to state machine
2. Trace operation flow from client to model
3. Verify JSON serialization handling
4. Check client response parsing

## Best Practices

### 1. Start Simple
Begin with single-key operations and basic models before adding complexity.

### 2. Add Comprehensive Logging
```clojure
(log/debug "Model step: op=" op ", key=" k ", registers=" registers)
```

### 3. Use Independent Pattern for Performance
For tests longer than ~100 seconds or with many keys, use `jepsen.independent`.

### 4. Verify JSON Serialization
Test keyword/string conversion in HTTP transport layer.

### 5. Test Both Patterns
- Non-dockerized tests for quick iteration
- Dockerized tests for realistic network conditions

### 6. Handle Timeouts Gracefully
```clojure
(catch java.net.SocketTimeoutException _ex
  (assoc op :type :info :error :timeout))
```

## Implementation Checklist

### ✅ Basic Setup
- [x] Multi-register model implementation
- [x] Read/write/CAS operations
- [x] Client with timeout handling
- [x] JSON serialization fixes

### ✅ Testing Infrastructure  
- [x] Non-dockerized test suite
- [x] Dockerized test suite with nemesis
- [x] Performance testing
- [x] Debug logging

### 🔄 Advanced Patterns (Optional)
- [ ] Independent checker pattern
- [ ] Custom nemesis implementations
- [ ] Cross-key invariant testing
- [ ] Performance benchmarking

## Test Configuration Examples

### Minimal Test (Development)
```clojure
{:time-limit 5
 :minimal true
 :nodes ["n1" "n2" "n3"]}
```

### Performance Test
```clojure
{:time-limit 60
 :concurrency 10
 :nemesis network-partition}
```

### Stress Test
```clojure
{:time-limit 300
 :concurrency 20
 :nemesis [:partition :latency :clock-skew]}
```

## Debugging Workflow

1. **Reproduce Issue**: Use minimal test configuration
2. **Add Logging**: Enable debug logging for all components
3. **Check History**: Examine operation sequences in history.edn
4. **Verify Model**: Ensure model handles edge cases correctly
5. **Test Serialization**: Verify HTTP transport layer
6. **Isolate Components**: Test state machine, client, and transport separately

## Resources

- [Jepsen Documentation](https://jepsen-io.github.io/jepsen/)
- [Independent Checker Pattern](http://jepsen-io.github.io/jepsen/jepsen.independent.html)
- [Knossos Linearizability](https://github.com/jepsen-io/knossos)
- [CockroachDB Jepsen Testing](https://www.cockroachlabs.com/blog/diy-jepsen-testing-cockroachdb/)

## Current Status

**Working:**
- ✅ CAS operations correctly failing when expected
- ✅ Non-dockerized tests with proper key tracking
- ✅ JSON serialization handling
- ✅ Basic linearizability testing
- ✅ Independent checker pattern implemented and working
- ✅ Linearizability violations fixed (Raft log cleanup)
- ✅ Performance testing with escalating load (100% success rate)
- ✅ Dockerized tests with network failure simulation

**Recently Fixed:**
- ✅ Client operation parsing bug (`:f` key location)
- ✅ Independent checker tuple wrapping for read operations
- ✅ CAS register model support for all operation types
- ✅ Raft log persistence causing linearizability violations

**Performance Results:**
- ✅ Peak throughput: 465.8 ops/sec (75 concurrent clients)
- ✅ 100% success rate across all load levels (1-100 clients)
- ✅ No breaking point detected in stress testing

**Next Steps:**
1. ✅ Independent checker pattern (completed)
2. ✅ Performance optimization and stress testing (completed)
3. 🔄 Add cross-key invariant testing if needed (optional)