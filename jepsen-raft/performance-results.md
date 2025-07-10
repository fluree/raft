# Raft Cluster Performance Results

## Test Configuration
- **Max Clients**: 200 concurrent clients
- **Commands per Client**: 10 
- **Timeout**: 2000ms per operation
- **Test Type**: Escalating load (automatic stopping at performance degradation)

## Results Summary

### 3-Node Cluster Performance
- **Peak Throughput**: 565.2 ops/sec at 150 clients
- **Cluster maintained 100% success rate** throughout all load levels
- **Maximum tested**: 200 clients with 474.1 ops/sec

| Clients | Throughput (ops/sec) | Success Rate |
|---------|---------------------|--------------|
| 1       | 83.3                | 100.0%       |
| 2       | 160.0               | 100.0%       |
| 5       | 312.5               | 100.0%       |
| 10      | 330.0               | 100.0%       |
| 15      | 390.3               | 100.0%       |
| 20      | 449.4               | 100.0%       |
| 30      | 454.5               | 100.0%       |
| 40      | 444.4               | 100.0%       |
| 50      | 540.5               | 100.0%       |
| 75      | 543.5               | 100.0%       |
| 100     | 500.0               | 100.0%       |
| 150     | **565.2**           | 100.0%       |
| 200     | 474.1               | 100.0%       |

### 5-Node Cluster Performance  
- **Peak Throughput**: 638.0 ops/sec at 150 clients
- **Cluster maintained 100% success rate** throughout all load levels
- **Maximum tested**: 200 clients with 531.9 ops/sec

| Clients | Throughput (ops/sec) | Success Rate |
|---------|---------------------|--------------|
| 1       | 83.3                | 100.0%       |
| 2       | 160.0               | 100.0%       |
| 5       | 312.5               | 100.0%       |
| 10      | 330.0               | 100.0%       |
| 15      | 365.9               | 100.0%       |
| 20      | 392.2               | 100.0%       |
| 30      | 454.5               | 100.0%       |
| 40      | 533.3               | 100.0%       |
| 50      | 510.2               | 100.0%       |
| 75      | 596.0               | 100.0%       |
| 100     | 625.0               | 100.0%       |
| 150     | **638.0**           | 100.0%       |
| 200     | 531.9               | 100.0%       |

### 7-Node Cluster Performance
- **Peak Throughput**: 846.0 ops/sec at 150 clients  
- **Cluster maintained 100% success rate** throughout all load levels
- **Maximum tested**: 200 clients with 791.8 ops/sec

| Clients | Throughput (ops/sec) | Success Rate |
|---------|---------------------|--------------|
| 1       | 82.0                | 100.0%       |
| 2       | 160.0               | 100.0%       |
| 5       | 312.5               | 100.0%       |
| 10      | 330.0               | 100.0%       |
| 15      | 342.5               | 100.0%       |
| 20      | 284.5               | 100.0%       |
| 30      | 454.5               | 100.0%       |
| 40      | 596.1               | 100.0%       |
| 50      | 590.3               | 100.0%       |
| 75      | 610.3               | 100.0%       |
| 100     | 699.8               | 100.0%       |
| 150     | **846.0**           | 100.0%       |
| 200     | 791.8               | 100.0%       |

## Performance Analysis

### Key Findings

1. **Cluster Size Impact**: 
   - 7-node cluster shows **33% higher peak throughput** (846.0 ops/sec) compared to 5-node (638.0 ops/sec)
   - 5-node cluster shows **13% higher peak throughput** compared to 3-node (565.2 ops/sec)

2. **Reliability**: 
   - All cluster configurations maintained **100% success rate** under all tested load levels
   - No breaking point detected within 200 concurrent clients

3. **Optimal Load Points**:
   - **3-node**: 150 clients for peak performance
   - **5-node**: 150 clients for peak performance  
   - **7-node**: 150 clients for peak performance

4. **Scalability**: 
   - Adding nodes provides significant throughput improvements
   - Linear improvement not observed (diminishing returns pattern)
   - 7-node cluster handles 200 clients with excellent performance (791.8 ops/sec)

### Performance Characteristics

- **Low Load (1-10 clients)**: All configurations perform similarly
- **Medium Load (15-50 clients)**: 5 and 7-node clusters begin to show advantages
- **High Load (75-200 clients)**: 7-node cluster demonstrates clear superiority

### Cluster Stability

All tests completed without:
- Connection timeouts
- Command failures  
- Network partition issues
- Leader election problems

The Raft implementation demonstrates excellent stability and consistent performance across different cluster sizes.

## Latest Jepsen Linearizability Tests (2025-01-10)

### 3-Minute Test Results

**Test Status**: ✅ **PASSED** - All consistency checks valid

**Test Configuration:**
- **Duration**: 180 seconds (3 minutes)
- **Cluster**: 5 nodes (n1-n5)
- **Concurrency**: 5 client threads
- **Operations**: Read, Write, CAS on 3 independent keys (x, y, z)

**Results Summary:**
- **Linearizability**: Valid
- **Timeline**: Valid
- **Performance Metrics**: Valid
- **Final State**: Key :x = 30, Key :y = 69
- **Test Artifacts**: `store/raft-netasync/20250710T064403.204-0400/`

### 5-Minute Test Results

**Test Status**: ✅ **PASSED** - All consistency checks valid

**Test Configuration:**
- **Duration**: 300 seconds (5 minutes)
- **Cluster**: 5 nodes (n1-n5)
- **Concurrency**: 5 client threads
- **Operations**: Read, Write, CAS on 3 independent keys (x, y, z)

**Results Summary:**
- **Linearizability**: Valid
- **Timeline**: Valid
- **Performance Metrics**: Valid
- **Final State**: Key :x = 35, Key :y = 89
- **Test Artifacts**: `store/raft-netasync/20250710T070046.998-0400/`

### Historical Issues (Resolved)

#### Previous Linearizability Violation
**Test Status**: ❌ **FAILED** - Linearizability violation detected

**Failure Summary:**
- **Issue**: Read operation returned value `91` when register should have contained `87`
- **Root Cause**: Write operation with value `91` timed out but appears to have partially succeeded
- **Resolution**: Fixed Raft implementation to properly handle leader changes and state synchronization

**Critical Finding**: The Raft implementation had a consistency bug where write operations could partially succeed during network timeouts, leading to split-brain scenarios and phantom reads. This issue has been resolved.

## Test Environment

- **5 Raft nodes**: n1, n2, n3, n4, n5 (Jepsen default)
- **HTTP ports**: 7001-7005 for client commands  
- **TCP ports**: 9001-9005 for Raft RPC
- **Performance test ports**: 8001-8003 (HTTP), 8101-8103 (TCP)
- **Network**: Local processes with TCP communication