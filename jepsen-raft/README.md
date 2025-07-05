# Jepsen Tests for Fluree Raft

This directory contains Jepsen tests for the Fluree Raft implementation. These
tests verify the correctness of the Raft consensus algorithm under various
failure scenarios.

## Prerequisites

### Required Dependencies
- **Java 21+** (required by Jepsen 0.3.5)
- **Clojure 1.11.1+**
- **gnuplot** (for performance graphs) - Install with `brew install gnuplot`
  on macOS

### For Distributed Testing
- **Docker** (for containerized distributed testing)
- **Docker Compose** (for orchestrating multi-node test environment)

## Quick Start

### Net.async Test (Default)

```bash
# Run the default net.async test
clojure -M:netasync test netasync --time-limit 10 --concurrency 1 --nodes n1,n2,n3

# With custom parameters
clojure -M:netasync test netasync --time-limit 30 --concurrency 3 --nodes n1,n2,n3
```

### Distributed Test (Docker-based)

```bash
# Start the distributed environment
cd docker && docker-compose up -d --build

# Run distributed test
cd .. && clojure -M:distributed test distributed --time-limit 10 --concurrency 1 --no-ssh --nodes n1,n2,n3
```

## Project Structure

```
jepsen-raft/
├── Makefile                 # Convenient test commands
├── README.md                # This documentation
├── deps.edn                 # Dependencies and aliases
├── src/jepsen_raft/
│   ├── tests/
│   │   ├── distributed/         # Docker-based distributed test
│   │   │   ├── test.clj         # Main distributed test runner
│   │   │   ├── test_main.clj    # Docker node implementation
│   │   │   ├── client.clj       # Distributed client implementation
│   │   │   └── db.clj           # Distributed database setup
│   │   ├── netasync/            # Net.async TCP test (default)
│   │   │   ├── test.clj         # Main test runner
│   │   │   ├── node.clj         # TCP node implementation
│   │   │   ├── raft_node.clj    # Raft node wrapper
│   │   │   ├── tcp.clj          # TCP communication layer
│   │   │   ├── client.clj       # Client implementation
│   │   │   └── db.clj           # Database setup
│   │   └── performance/         # Load and stress testing
│   │       └── test.clj         # Performance stress test
│   └── util.clj                 # Shared utilities and state machines
├── docker/
│   ├── node/
│   │   ├── Dockerfile       # Raft node container definition
│   │   ├── supervisord.conf # Process management config
│   │   └── start-node.sh    # Node startup script
│   └── docker-compose.yml   # 3-node distributed test environment
├── test-command.clj         # Manual test script for distributed setup
├── test-distributed.sh     # Distributed test runner script
└── store/                  # Test results and artifacts
    └── raft-*/             # Timestamped test results
```

## Available Test Runners

### Distributed Test

The distributed test runs Raft across Docker containers with HTTP communication and leader forwarding.

```bash
# Start 3-node distributed environment
cd docker && docker-compose up -d --build

# Run distributed test
cd .. && clojure -M:distributed test distributed \
  --time-limit 10 \
  --concurrency 1 \
  --no-ssh \
  --nodes n1,n2,n3

# Test manual operations
clojure -M test-command.clj

# Check individual node status
curl -s http://localhost:7001/debug | jq  # n1
curl -s http://localhost:7002/debug | jq  # n2
curl -s http://localhost:7003/debug | jq  # n3
```

### Net.async Test (Default)

```bash
# Run net.async test with various concurrency levels
clojure -M:netasync test netasync --time-limit 10 --concurrency 1 --nodes n1,n2,n3
clojure -M:netasync test netasync --time-limit 10 --concurrency 3 --nodes n1,n2,n3  
clojure -M:netasync test netasync --time-limit 10 --concurrency 5 --nodes n1,n2,n3
```

### Performance Stress Test

```bash
# Run escalating load test
clojure -M:performance escalating

# Run single load test with specific parameters
clojure -M:performance single 10 50  # 10 clients, 50 commands each
```

**Test modes:**
- **`escalating`**: Automatically increases load until success rate drops below 90%
- **`single`**: Runs a single test with specified number of clients and commands

**Performance characteristics:**
- Light load (1-10 clients): 50-100 ops/sec
- Medium load (10-30 clients): 100-150 ops/sec  
- Heavy load (30-50 clients): 150+ ops/sec before degradation
- Breaking point: Around 50-75 concurrent clients

### Development Tools
```bash
# Lint source code
clojure -M:lint

# Clean Docker environment
cd docker && docker-compose down -v

# View container logs
docker logs jepsen-n1
docker logs jepsen-n2
docker logs jepsen-n3
```

## Test Implementations

### Net.async Test (Default)

TCP-based communication using the net.async library.

**Features:**
- Leader election in 3-node cluster
- TCP-based RPC communication  
- All CRUD operations (read, write, CAS, delete)
- High throughput (50-150+ ops/sec)

### Distributed Test

Docker-based test with HTTP communication.

**Features:**
- 3-node Docker cluster 
- HTTP-based RPC with leader forwarding
- Real network communication
- All CRUD operations

**Operations tested:**
- **Write**: `{:f :write, :key :x, :value 42}`
- **Read**: `{:f :read, :key :x}`  
- **Compare-and-swap**: `{:f :cas, :key :x, :old 42, :new 43}`
- **Delete**: `{:f :delete, :key :x}`

## Test Results

### Test Status

| Test Type | Status | Performance | Notes |
|-----------|--------|-------------|-------|
| **Net.async** | ✅ Working | 50-150 ops/sec | Default implementation |
| **Distributed** | ✅ Working | 20-30ms ops | Docker-based alternative |

### Performance Characteristics

**Net.async Test:**
- Light load: 50-100 ops/sec
- Medium load: 100-150 ops/sec  
- Heavy load: 150+ ops/sec before degradation
- Breaking point: Around 50-75 concurrent clients

**Distributed Test:**
- Individual operations: 20-30ms response time
- Throughput: Varies with test framework stagger settings

### Finding Test Results

After running a test, results are stored in timestamped directories:

```bash
# View the latest net.async test results  
ls -la store/raft-netasync/

# View the latest distributed test results
ls -la store/raft-distributed/
```

### Understanding Test Output

Each test run creates a timestamped directory like `store/raft-netasync/20250704T094455.975-0400/` containing:

- **`results.edn`**: Summary of test results and checker outputs
- **`history.edn`**: Complete operation history for analysis
- **`timeline.html`**: Interactive visual timeline of all operations (open in browser)
- **`jepsen.log`**: Detailed test execution logs
- **`latency-quantiles.png`**: Latency distribution graphs (requires gnuplot)
- **`latency-raw.png`**: Raw latency data over time (requires gnuplot)
- **`rate.png`**: Operation rate graphs (requires gnuplot)

### Viewing Results

```bash
# Open the interactive timeline in your default browser
open store/latest/timeline.html

# View the test summary
cat store/latest/results.edn

# Check detailed logs for debugging
less store/latest/jepsen.log

# View performance graphs (if gnuplot is installed)
open store/latest/latency-quantiles.png
open store/latest/rate.png
```

### Key Success Indicators

#### Distributed Test (Expected Results)
1. **All operations return `:type :ok`** - 100% success rate
2. **Leader election and forwarding** - Commands forwarded from followers to leader
3. **Fast response times** - 12-27ms per operation  
4. **No timeouts or failures** - Clean operation execution

#### Net.async Test (Expected Results)
1. **All operations return `:type :ok`** - 100% success rate
2. **TCP-based communication** - Production-like network layer
3. **High throughput** - 50-150+ ops/sec sustained
4. **Linearizability verified** - Full correctness validation

### Example Results

#### Net.async Test
```bash
# 20-second test with 10 concurrent clients
1500+ operations over 20 seconds = 75+ ops/sec
Response times: 5-10ms per operation
Success rate: 100%
```

#### Distributed Test  
```bash
# 20-second test with 2 concurrent clients  
128 operations over 20 seconds = 6.4 ops/sec
Response times: 20-30ms per operation
Success rate: 100%
```

## Implementation Status

### Net.async Test (Default)
- All operations work: Read, write, CAS, delete
- TCP networking using net.async library
- High throughput: 50-150+ ops/sec
- Linearizability verification

### Distributed Test  
- All operations work: Read, write, CAS, delete
- HTTP-based communication with leader forwarding
- Response times: 20-30ms per operation
- Docker container deployment

## Architecture Details

### Net.async Test (Default)
- 3 Raft instances with TCP communication (n1, n2, n3)
- TCP-based using net.async library
- Key-value store state machine
- Binary protocol over TCP with serialization

### Distributed Test
- 3 Docker containers (n1, n2, n3) with Raft instances  
- HTTP communication via localhost:7001-7003
- Key-value store with Jepsen operation adaptation (`:f` → `:op`)
- HTTP POST with Nippy serialization
- Leader forwarding with dynamic detection
- Endpoints: `/command`, `/debug`, `/health`, `/rpc`

### Configuration
Test parameters defined in `util.clj`:
- Operation timeout: 5000ms, heartbeat: 100ms, election timeout: 300ms
- Test keys: (:x, :y, :z), value range: (0-99), RPC delay: (0-5ms)  
- 3 nodes, snapshot threshold: 100 entries
- Docker ports: n1→7001, n2→7002, n3→7003

## Development

### Adding New Tests
1. Create new operation generators in the appropriate test file
2. Update state machine in `util.clj` if needed
3. Test with net.async setup (default)
4. Verify with distributed setup if needed

### REPL Development
```clojure
;; Load the net.async test namespace (default)
(require '[jepsen-raft.tests.netasync.test :as netasync])

;; Load the distributed test namespace  
(require '[jepsen-raft.tests.distributed.test :as dist])

;; Run a quick net.async test
(netasync/-main "test" "netasync" "--time-limit" "10")

;; Run a quick distributed test
(dist/-main "test" "distributed" "--time-limit" "10")
```

## Troubleshooting and Common Issues

### Distributed Test Issues

**Container startup problems**:
```bash
# Check container status
docker ps | grep jepsen

# View container logs  
docker logs jepsen-n1

# Restart containers if needed
cd docker && docker-compose restart
```

**Leader election issues**:
```bash
# Check which node is leader
for i in 1 2 3; do
  echo "=== Node n$i ==="
  curl -s http://localhost:700$i/debug | jq
done
```

**Port conflicts**:
```bash
# Check if ports 7001-7003 are available
lsof -i :7001-7003

# Kill conflicting processes if needed
docker-compose down -v
```

### Net.async Test Issues

**TCP port conflicts**: Ensure ports 9001-9003 are available for TCP communication.

**Performance tuning**: Adjust TCP buffer sizes and thread pool configuration for optimal throughput.

## TODO List

### Completed ✅
- [x] Create net.async TCP-based test implementation
- [x] Create Docker-based distributed test setup  
- [x] Fix distributed command processing
- [x] Implement leader forwarding
- [x] Dynamic state retrieval
- [x] TCP-based RPC with net.async

### High Priority
- [ ] Add nemesis for network partitions in net.async test
- [ ] Implement membership change testing (add/remove nodes dynamically)
- [ ] Add more sophisticated failure modes (process kills, network splits)
- [ ] Optimize TCP performance for even higher throughput

### Medium Priority  
- [ ] Add performance benchmarking with specific workloads
- [ ] Implement multi-key transactions testing
- [ ] Add snapshot and log compaction stress tests  
- [ ] Scale distributed test to 5-7 nodes

### Low Priority
- [ ] Add bank account transfer workload for stronger consistency testing
- [ ] Add clock skew simulation
- [ ] Performance comparison with other Raft implementations
- [ ] Add chaos engineering scenarios (random process kills, etc.)

### Infrastructure Improvements
- [ ] Automated CI/CD pipeline for tests
- [ ] Better result analysis and reporting tools
- [ ] Integration with monitoring/alerting systems
- [ ] Test result archival and comparison tools

### Common Issues (Legacy)

**Java Version Error**: 
```
ClassNotFoundException: java.util.SequencedCollection
```
**Solution**: Ensure Java 21+ is active (`java -version`)

**Missing Performance Graphs**:
**Solution**: Install gnuplot (`brew install gnuplot` on macOS)

### Debug Mode
```bash
# Enable debug logging for distributed test
export TIMBRE_LEVEL=:debug
clojure -M:distributed test distributed --time-limit 10
```

### Cleaning Up
```bash
# Clean Docker environment  
cd docker && docker-compose down -v

# Remove test results
rm -rf store/
rm -rf /tmp/jepsen-raft/
```

## Contributing

When contributing new tests or improvements:

1. **Use distributed test for validation** - It provides the most reliable results
2. **Ensure distributed tests pass** with 100% `:ok` operation success rate  
3. **Update this README** with any new features or limitations
4. **Test both implementations** if changes affect core Raft functionality
5. **Include performance impact analysis** for significant changes
6. **Document any new endpoints** or configuration options added

### Development Workflow
1. Make changes to Raft implementation or test infrastructure
2. Test with net.async setup (default): `clojure -M:netasync test netasync --time-limit 10 --nodes n1,n2,n3`
3. Verify TCP communication and performance
4. Test distributed setup if needed: `clojure -M:distributed test distributed --time-limit 10`  
5. Update documentation and commit changes