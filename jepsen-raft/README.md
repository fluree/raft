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
# Run the default test
make test

# With custom time limit
TIME=30 make test
```

### Dockerized Testing (Recommended for Production Validation)
```bash
# Run dockerized test with network failures
make test-docker

# Run minimal test without network failures (faster)
make test-docker-minimal
```

### Performance Testing
```bash
# Run escalating load test to find cluster limits
make performance
```

## Test Suites Overview

We have **3 test suites**, each designed to validate different aspects of the Raft implementation:

### 1. Local Net.Async Test (`test.clj`)
- **Command**: `make test` or `clojure -M:netasync test netasync`
- **Purpose**: Basic Raft consensus validation with TCP communication
- **Environment**: Local processes using net.async library
- **Key Features**:
  - Quick development iteration
  - No network failures (no nemesis)
  - Tests read, write, CAS, and delete operations
  - Verifies linearizability (strong consistency)
  - Best for development and debugging

### 2. Dockerized Test with Network Failures (`test_docker.clj`)
- **Commands**: 
  - Full test: `make test-docker` (60s default)
  - Minimal test: `make test-docker-minimal` (30s default)
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

### 3. Performance Stress Test (`test_performance.clj`)
- **Command**: `make performance` or `clojure -M:performance`
- **Purpose**: Identify cluster throughput limits and breaking points
- **Test Modes**:
  - **Escalating**: Automatically increases load from 1 to 100 concurrent clients
  - **Single**: Fixed load test with specified clients/commands
- **Metrics Tracked**:
  - Throughput (operations per second)
  - Response times (average, min, max, 95th percentile)
  - Success/failure rates
  - Breaking point identification (when success rate < 70%)
- **Use Cases**:
  - Capacity planning
  - Performance regression testing
  - Hardware sizing recommendations

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
├── Makefile                    # Convenient test commands
├── README.md                   # This documentation
├── deps.edn                    # Dependencies and aliases
├── src/jepsen_raft/
│   ├── client.clj              # Jepsen client implementation
│   ├── db.clj                  # Database setup for local testing
│   ├── db_docker.clj           # Docker container management
│   ├── nemesis_docker.clj      # Network failure injection
│   ├── raft_node.clj           # Complete Raft node implementation
│   ├── test.clj                # Local test runner
│   ├── test_docker.clj         # Dockerized test runner
│   ├── test_performance.clj    # Performance stress test
│   └── util.clj                # Shared utilities and state machines
├── docker/
│   ├── node/
│   │   └── Dockerfile          # Container definition
│   ├── docker-compose.yml      # 3-node cluster configuration
│   └── test-network-partition.sh # Network failure testing script
└── store/                      # Test results and artifacts
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

### Performance Characteristics
Based on our stress testing:
- **Light load (1-10 clients)**: 50-120 ops/sec
- **Medium load (50 clients)**: 340+ ops/sec  
- **Heavy load (100+ clients)**: 560-570 ops/sec peak
- **Average latency**: 13-57ms depending on load
- **P95 latency**: 18-127ms depending on load
- **Breaking point**: Typically >100 concurrent clients

## Development and Debugging

### Available Make Targets
```bash
make help                # Show all available commands
make test                # Run non-dockerized test
make test-docker         # Run dockerized test with nemesis
make test-docker-minimal # Run dockerized test without nemesis
make docker-build        # Build Docker images
make docker-up           # Start Docker cluster
make docker-down         # Stop Docker cluster
make docker-logs         # View container logs
make docker-test-network # Test network partitions
make performance         # Run performance test
make lint                # Lint source code
make clean               # Clean all test artifacts
```

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
- **3 Raft nodes**: n1, n2, n3
- **TCP ports**: 9001-9003 for Raft RPC communication
- **HTTP ports**: 7001-7003 for client commands
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
lsof -i :7001-7003
lsof -i :9001-9003
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