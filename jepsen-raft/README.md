# Jepsen Tests for Fluree Raft

This directory contains Jepsen tests for the Fluree Raft implementation. These tests verify the correctness of the Raft consensus algorithm under various failure scenarios using a production-grade TCP-based implementation with net.async.

## Prerequisites

### Required Dependencies
- **Java 21+** (required by Jepsen 0.3.5)
- **Clojure 1.11.1+**
- **gnuplot** (for performance graphs) - Install with `brew install gnuplot` on macOS

### For Dockerized Testing
- **Docker** (for containerized testing with network failures)
- **Docker Compose** (for orchestrating multi-node test environment)

## Quick Start

### Local Testing (Non-Dockerized)
```bash
# Run the default 5-node test (120 seconds)
make jepsen-test

# Run with different node counts and time limits
NODES=3 TIME=60 make jepsen-test
NODES=7 TIME=180 make jepsen-test
```

### Dockerized Testing (Recommended for Production Validation)
```bash
# Run dockerized test with network failures (60 seconds)
make jepsen-docker-test

# Run minimal test without network failures (30 seconds)
make jepsen-docker-test-minimal

# With custom time limit
TIME=120 make jepsen-docker-test
```

### Performance Testing
```bash
# Run escalating load test (auto-starts nodes)
make performance-test

# Test with different node counts
NODES=3 make performance-test
NODES=7 make performance-test
```

## Test Suites Overview

We have **3 test suites**, each designed to validate different aspects of the Raft implementation:

### 1. Local Net.Async Test (`test.clj`)
- **Commands**: 
  - `make jepsen-test` (default 5-node, 10s duration)
  - Manual: `clojure -M:netasync test netasync --time-limit 60 --nodes n1,n2,n3,n4,n5`
- **Purpose**: Basic Raft consensus validation with TCP communication
- **Environment**: Local processes using net.async library
- **Key Features**:
  - Quick development iteration
  - No network failures (no nemesis)
  - Tests read, write, CAS operations
  - Verifies linearizability (strong consistency)
  - Best for development and debugging
- **Environment Variables**:
  - `NODES=3|5|7`: Node count (default: 5)
  - `TIME=N`: Test duration in seconds (default: 10)
- **Standard Jepsen CLI Options**:
  - `--time-limit N`: Test duration in seconds
  - `--nodes n1,n2,n3`: Comma-separated node list
  - `--concurrency N`: Concurrent operations
  - `--test-count N`: Number of test iterations
  - `--rate N`: Operation rate

### 2. Dockerized Test with Network Failures (`test_docker.clj`)
- **Commands**: 
  - Full test: `make jepsen-docker-test` (60s default)
  - Minimal test: `make jepsen-docker-test-minimal` (30s default)
  - Manual: `clojure -M:netasync-docker test --time-limit 60 --nodes n1,n2,n3,n4,n5`
- **Purpose**: Validate Raft behavior under realistic network failures
- **Environment**: Docker containers with network isolation
- **Key Features**:
  - Network partition testing (isolate nodes)
  - Network latency injection (configurable delays)
  - Split-brain scenario testing
  - Phased testing approach:
    - Phase 1: Operations without failures (baseline)
    - Phase 2: Operations with network partitions and latency
    - Phase 3: Recovery validation
  - Most realistic production-like testing
- **Environment Variables**:
  - `TIME=N`: Test duration in seconds (default: 60 for full, 30 for minimal)
- **Docker-Specific CLI Options**:
  - `--minimal`: Run without nemesis (network failures)
- **Standard Jepsen CLI Options**: Same as local test

### 3. Performance Stress Test (`test_performance.clj`)
- **Commands**: 
  - `make performance-test` (default 5-node cluster)
  - Manual escalating: `clojure -M:performance escalating`
  - Manual single load: `clojure -M:performance single 50 100`
- **Purpose**: Identify cluster throughput limits and breaking points
- **Key Features**:
  - **Automatic node management**: Starts/stops nodes automatically (default)
  - **Configurable cluster size**: 3, 5, or 7 nodes
  - **Flexible load testing**: Up to 1000+ concurrent clients
- **Test Modes**:
  - **Escalating**: Automatically increases load until failure (default)
  - **Single**: Fixed load test with specified clients/commands
- **Environment Variables**:
  - `NODES=3|5|7`: Number of nodes to test (default: 5)
  - `PERF_NODE_COUNT=3|5|7`: Same as NODES (for direct clojure calls)
  - `PERF_MAX_CLIENTS=N`: Maximum concurrent clients (default: 100)
  - `PERF_USE_EXISTING_NODES=true`: Use already-running nodes
- **Command Line Arguments**:
  - `escalating`: Run escalating load test (default)
  - `single <clients> <commands>`: Run fixed load test
    - `<clients>`: Number of concurrent clients
    - `<commands>`: Commands per client
- **Metrics Tracked**:
  - Throughput (operations per second)
  - Response times (average, min, max, 95th percentile)
  - Success/failure rates
  - Breaking point identification (when success rate < 70%)
- **Use Cases**:
  - Capacity planning
  - Performance regression testing
  - Hardware sizing recommendations
  - Comparing 3-node vs 5-node vs 7-node performance

### Common Test Characteristics
All tests share these features:
- **Operations tested**: 
  - Read: Retrieve value for a key
  - Write: Set value for a key
  - CAS: Atomic compare-and-swap
  - Delete: Remove a key
- **Keys used**: `:x`, `:y`, `:z`
- **Consistency verification**: Jepsen's linearizability checker
- **Performance metrics**: Latency graphs, rate graphs, operation timelines

## Project Structure

```
jepsen-raft/
├── Makefile                         # Convenient test commands and automation
├── README.md                        # This documentation
├── deps.edn                         # Dependencies and aliases
├── performance-results.md           # Latest performance test results
├── src/jepsen_raft/
│   ├── client.clj                   # Jepsen client implementation for HTTP API
│   ├── config.clj                   # Shared configuration and constants
│   ├── db.clj                       # Database setup for local testing
│   ├── db_docker.clj                # Docker container management
│   ├── http_client.clj              # HTTP client utilities for node communication
│   ├── nemesis_docker.clj           # Network failure injection for Docker tests
│   ├── nodeconfig.clj               # Centralized node configuration management
│   ├── operations.clj               # Shared Jepsen operation definitions
│   ├── raft_node.clj                # Complete Raft node implementation with TCP/HTTP
│   ├── test.clj                     # Local net.async test runner
│   ├── test_docker.clj              # Dockerized test runner with network failures
│   ├── test_performance.clj         # Performance stress test with auto-scaling load
│   └── util.clj                     # Shared utilities and state machines
├── scripts/
│   ├── generate-docker-compose.clj  # Generate Docker Compose configurations
│   ├── get-nodes.clj                # Node configuration helper script
│   └── run-performance.clj          # Performance test automation script
├── resources/
│   └── logback.xml                  # Logging configuration
├── docker/
│   ├── Dockerfile.node              # Container definition for Raft nodes
│   ├── docker-compose.yml           # 5-node cluster configuration
│   ├── docker-compose-generated.yml # Generated configurations for different sizes
│   └── test-network-partition.sh    # Network failure testing and automation script
├── logs/                            # Node logs from local testing
│   ├── n1.log                       # Node 1 logs
│   ├── n2.log                       # Node 2 logs
│   ├── n3.log                       # Node 3 logs
│   ├── n4.log                       # Node 4 logs
│   └── n5.log                       # Node 5 logs
├── test-results/                    # Curated test results for documentation
│   └── 5-minute-consistency-test/  # Extended 5-minute Jepsen test results
│       ├── README.md                # Detailed test analysis and interpretation
│       ├── results.edn              # Complete Jepsen test results
│       ├── timeline.html            # Interactive visual timeline
│       ├── latency-quantiles.png    # Latency distribution graphs
│       ├── latency-raw.png          # Raw latency measurements
│       └── rate.png                 # Throughput over time
└── store/                           # Test results and artifacts
    ├── current                      # Symlink to latest test run
    ├── latest                       # Symlink to latest test run
    ├── raft-netasync-*/             # Local test results (timestamped)
    ├── raft-netasync-docker-*/      # Docker test results (timestamped)
    └── performance-*/               # Performance test results (timestamped)
```

## Testing Scenarios and When to Use Each

### Development Workflow
1. **Initial development**: Use `make test` for quick iteration
2. **Pre-commit validation**: Run `make test-docker-minimal`
3. **Full validation**: Execute `make test-docker` with network failures
4. **Performance check**: Run `make performance` to ensure no regression

### CI/CD Pipeline
1. **Pull Request**: `make test-docker-minimal` (fast validation)
2. **Main branch**: `make test-docker` (comprehensive testing)
3. **Release**: Full suite including performance tests

### Production Validation
1. **Deployment testing**: Use dockerized tests to simulate production environment
2. **Capacity planning**: Run performance tests with expected load patterns
3. **Failure scenario validation**: Use network partition tests

## Network Failure Testing

The dockerized environment includes network failure simulation:

```bash
# Navigate to docker directory
cd docker

# Partition individual nodes
./test-network-partition.sh partition-n1  # Isolate node n1
./test-network-partition.sh partition-n2  # Isolate node n2
./test-network-partition.sh partition-n3  # Isolate node n3

# Create split-brain (n1,n2 vs n3)
./test-network-partition.sh split-brain

# Add network latency
./test-network-partition.sh add-latency 200  # Add 200ms latency

# Heal all partitions
./test-network-partition.sh heal-all

# Remove latency
./test-network-partition.sh remove-latency

# Check cluster status
./test-network-partition.sh status

# Run automated test scenario
./test-network-partition.sh test-scenario 30  # 30-second per phase
```

## Test Results and Analysis

### Finding Results
Test results are stored in timestamped directories:

```bash
# View latest test results
ls -la store/raft-netasync*/latest/
ls -la store/raft-netasync-docker*/latest/

# Open interactive timeline
open store/raft-netasync*/latest/timeline.html
```

### Result Files
- **`results.edn`**: Summary of test results and checker outputs
- **`history.edn`**: Complete operation history
- **`timeline.html`**: Interactive visual timeline
- **`jepsen.log`**: Detailed test execution logs
- **`latency-raw.png`**: Raw latency measurements
- **`latency-quantiles.png`**: Latency distribution
- **`rate.png`**: Throughput over time

### Performance Testing

The performance test automatically manages node lifecycle and can be configured for different scenarios:

```bash
# Run with default 5-node cluster (auto-managed)
make performance

# Run with 3-node cluster
PERF_NODE_COUNT=3 make performance

# Test up to 500 concurrent clients
PERF_MAX_CLIENTS=500 make performance

# Use existing running nodes (manual management)
make start-nodes  # Start nodes first
PERF_USE_EXISTING_NODES=true make performance

# Run specific single test (200 clients, 100 commands each)
clojure -M:performance single 200 100
```

**Environment Variables:**
- `PERF_NODE_COUNT`: Number of nodes to test (3 or 5, default: 5)
- `PERF_MAX_CLIENTS`: Maximum concurrent clients for escalating test (default: 100)
- `PERF_USE_EXISTING_NODES`: Set to "true" to use already-running nodes

**Note**: The performance test automatically starts and stops nodes by default. Node logs are written to `/tmp/jepsen-raft-network/n*-perf.log` for debugging.

### Performance Characteristics
Based on our latest testing (2025-07-13):
- **Jepsen Linearizability Tests**: ✅ 100% success rate on 5-node cluster, all consistency checks passed with 2-key concurrent operations
- **Performance Tests**: Up to 846.0 ops/sec peak (150 clients on 7-node cluster)
- **Node Startup**: Reliable staggered startup with built-in health checks
- **Cluster Configuration**: Test harness supports 3, 5, or 7 nodes with ports 7001-7005 (HTTP), 9001-9005 (TCP)
- **Breaking point**: None detected up to 200 clients - maintains reliability

## 🎯 Latest Test Results

**[📊 5-Minute Consistency Test Results](test-results/5-minute-consistency-test/results.edn)** - July 13, 2025

**Key Files:**
- [📈 Visual Timeline](test-results/5-minute-consistency-test/timeline.html) - Interactive operation timeline
- [📊 Performance Graphs](test-results/5-minute-consistency-test/) - Latency and throughput visualizations
- [📋 Detailed Results](test-results/5-minute-consistency-test/results.edn) - Complete test analysis

## Development and Debugging

### Available Make Targets
```bash
make help                     # Show all available commands

# Test Targets
make jepsen-test              # Run net.async test (requires manual node startup)
make jepsen-docker-test       # Run dockerized test with network partition nemesis
make jepsen-docker-test-minimal # Run minimal dockerized test without nemesis
make performance-test         # Run performance stress test (auto-manages nodes)

# Node Management Targets  
make start-nodes              # Start net.async Raft nodes manually
make stop-nodes               # Stop all net.async Raft nodes
make check-nodes              # Check if net.async nodes are running and healthy
make check-ports              # Check if all required ports are available
make restart-nodes            # Restart all net.async nodes with fresh state
make logs                     # Tail logs for all running nodes

# Docker Targets
make docker-build             # Build Docker images for net.async testing
make docker-up                # Start net.async Docker test environment
make docker-down              # Stop and remove net.async Docker test environment
make docker-logs              # Show logs from net.async Docker containers
make docker-test-network      # Test network partition scenarios

# Utility Targets
make lint                     # Lint source code with clj-kondo
make clean                    # Remove all test results and temporary files
```

### Environment Variables Summary

**Makefile Environment Variables:**
- `NODES=3|5|7`: Node count for tests (default varies by test)
- `TIME=N`: Test duration in seconds (default varies by test)

**Raft Node Environment Variables** (for Docker containers):
- `NODE_ID`: Node identifier (e.g., "n1", "n2", "n3")
- `TCP_PORT`: Port for Raft TCP communication between nodes
- `HTTP_PORT`: Port for HTTP client API
- `NODES`: Comma-separated list of all nodes in cluster
- `NODE_IPS`: Container IP mapping (format: n1:host1:port1,n2:host2:port2)
- `TCP_HOST`: TCP bind host (defaults to "0.0.0.0")
- `HTTP_HOST`: HTTP bind host (defaults to "0.0.0.0")

**Performance Test Environment Variables:**
- `PERF_NODE_COUNT=3|5|7`: Number of nodes for performance test (default: 5)
- `PERF_USE_EXISTING_NODES=true`: Use existing running nodes
- `PERF_MAX_CLIENTS=N`: Maximum concurrent clients for escalating test (default: 100)

### Manual Testing
```bash
# Check node status
curl -s http://localhost:7001/debug | jq  # n1
curl -s http://localhost:7002/debug | jq  # n2
curl -s http://localhost:7003/debug | jq  # n3

# Send commands
curl -X POST -H 'Content-Type: application/json' \
  -d '{"op":"write","key":"test","value":"hello"}' \
  http://localhost:7001/command

curl -X POST -H 'Content-Type: application/json' \
  -d '{"op":"read","key":"test"}' \
  http://localhost:7001/command
```

### REPL Development
```clojure
;; Load the test namespace
(require '[jepsen-raft.test :as test])

;; Run a quick test
(test/-main "test" "netasync" "--time-limit" "10")

;; Load the dockerized test
(require '[jepsen-raft.test-docker :as docker-test])

;; Run dockerized test
(docker-test/-main "test" "docker" "--minimal" "--time-limit" "10")
```

## Architecture Details

### Node Configuration
- **5 Raft nodes**: n1, n2, n3, n4, n5 (Jepsen default)
- **TCP ports**: 9001-9005 for Raft RPC communication
- **HTTP ports**: 7001-7005 for client commands
- **Staggered startup**: 2-second delays between nodes to prevent race conditions
- **Connection pattern**: Lower-ID nodes connect to higher-ID nodes
- **Automatic reconnection** with exponential backoff

### Protocol Details
- **TCP communication** using net.async library for networking
- **Binary serialization** with Nippy for efficiency
- **HTTP interface** for client commands (supports both JSON and Nippy)
- **Leader forwarding** when followers receive client commands

### Test Parameters
- **Operation timeout**: 5000ms
- **Heartbeat interval**: 100ms  
- **Election timeout**: 300ms
- **Snapshot threshold**: 100 entries
- **Test keys**: :x, :y, :z
- **Value range**: 0-99

## Troubleshooting

### Common Issues

**Port conflicts**: Ensure ports are available
```bash
lsof -i :7001-7005
lsof -i :9001-9005
```

**Container issues**: Check container status
```bash
docker ps | grep raft-
docker logs raft-n1
```

**Clean environment**: Remove all artifacts
```bash
make clean
```

### Debug Mode
```bash
# Enable debug logging
export TIMBRE_LEVEL=:debug
make test
```

## Contributing

When contributing improvements:

1. **Test locally first**: Run `make test` for quick iteration
2. **Verify with Docker**: Ensure `make test-docker-minimal` passes
3. **Full validation**: Confirm `make test-docker` passes with network failures
4. **Check performance**: Run `make performance` to ensure no regression
5. **Update documentation**: Keep this README current

## License

Copyright © 2025 Fluree PBC

Distributed under the same license as the Fluree Raft implementation.