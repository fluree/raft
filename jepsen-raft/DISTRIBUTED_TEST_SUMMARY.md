# Distributed Jepsen Raft Test - Summary and Status

## Overview
Working on debugging and fixing a distributed Jepsen test for a Fluree Raft implementation. The test runs Raft nodes in Docker containers and uses HTTP-based RPC communication.

## Current Status

### ✅ Completed
1. **Fixed network connectivity** - Docker containers can communicate via mapped ports (localhost:7001-7003)
2. **Implemented HTTP-based RPC** - Nodes can send/receive Raft RPC messages
3. **Added Nippy serialization** - Preserves Clojure data types in HTTP communication
4. **Created state machine adapter** - Converts Jepsen's `:f` field to Raft's expected `:op` field
5. **Verified Raft cluster formation** - Nodes elect a leader and exchange heartbeats

### ❌ Current Issue
**Raft commands timeout** - Commands reach the HTTP endpoint but Raft doesn't process them because:
- The `raft-instance` stored in `node-state` atom is a static snapshot from initialization
- When Raft elects a leader internally, this snapshot doesn't update
- Non-leader nodes don't know the current leader and try to process commands locally
- Raft rejects commands from non-leaders, causing timeouts

## Key Files and Locations

### Project Structure
```
/Users/bplatz/fluree/raft/
├── src/fluree/raft.clj                    # Core Raft implementation
├── src/fluree/raft/events.clj             # Raft event handling
└── jepsen-raft/
    ├── docker/
    │   ├── docker-compose.yml              # Docker orchestration
    │   └── node/
    │       ├── Dockerfile                  # Node container definition
    │       └── start-node.sh               # Node startup script
    ├── src/jepsen_raft/
    │   ├── distributed_test.clj            # Jepsen test definition
    │   └── distributed_test_main.clj       # Distributed node implementation
    ├── test-distributed.sh                 # Test runner script
    └── test-command.clj                    # Manual command test script
```

## Important Commands

### Docker Operations
```bash
# Must be run from /Users/bplatz/fluree/raft/jepsen-raft/docker directory
cd /Users/bplatz/fluree/raft/jepsen-raft/docker

# Start containers
docker-compose up -d

# Rebuild and start (after code changes)
docker-compose down && docker-compose up -d --build

# Check container status
docker ps | grep jepsen

# View logs
docker logs jepsen-n1  # or jepsen-n2, jepsen-n3
docker exec jepsen-n1 tail -100 /opt/fluree-server/logs/fluree.log
```

### Running Tests
```bash
# Must be run from /Users/bplatz/fluree/raft/jepsen-raft directory
cd /Users/bplatz/fluree/raft/jepsen-raft

# Run distributed test
bash test-distributed.sh

# Or run directly with Clojure
clojure -M:distributed test distributed \
  --time-limit 5 \
  --concurrency 1 \
  --no-ssh \
  --nodes n1,n2,n3

# Send manual test command
clojure -M test-command.clj
```

### Health Checks and Debugging
```bash
# Check node health (from any directory)
curl -s http://localhost:7001/health  # n1
curl -s http://localhost:7002/health  # n2
curl -s http://localhost:7003/health  # n3

# Debug endpoint (shows Raft state)
curl -s http://localhost:7001/debug | jq

# Check all nodes
for i in 1 2 3; do 
  echo "=== Node n$i ==="
  curl -s http://localhost:700$i/debug | jq
done

# Search logs for specific messages
docker exec jepsen-n1 grep -E "(COMMAND:|LEADER|Starting distributed)" /opt/fluree-server/logs/fluree.log | tail -20
```

## Key Code Insights

### 1. Raft Event Channel Access
```clojure
;; The event channel is stored in the raft config
(defn event-chan [raft]
  (get-in raft [:config :event-chan]))

;; To get current Raft state
(raft/get-raft-state raft-instance callback)
```

### 2. Current HTTP Endpoints
- `/health` - Returns node ready status
- `/command` - Accepts Jepsen commands (Nippy serialized)
- `/rpc` - Internal Raft RPC communication
- `/debug` - Shows current Raft state (needs fixing)

### 3. Log Locations Inside Containers
- Application logs: `/opt/fluree-server/logs/fluree.log`
- Error logs: `/opt/fluree-server/logs/fluree_err.log`
- Raft data: `/tmp/jepsen-raft/n1/` (n2, n3 for other nodes)

## What's Left to Fix

### 1. Update State Management
- Modify `distributed_test_main.clj` to dynamically fetch current Raft state
- Use `raft/get-raft-state` or `raft/raft-state-async` instead of storing static snapshot
- Update the debug endpoint to show live state

### 2. Implement Leader Forwarding
- When non-leader receives command, check current leader
- Forward command to leader node via HTTP
- Return response from leader to client

### 3. Fix Command Processing Flow
```clojure
;; Current (broken) - uses static snapshot
(defn handle-command-request [request]
  (let [{:keys [raft-instance node-id]} @node-state]  ; <- Static snapshot
    (raft/new-entry raft-instance body callback)))    ; <- Fails on non-leader

;; Needed - check current state and forward if necessary
(defn handle-command-request [request]
  (let [{:keys [raft-instance node-id]} @node-state]
    (raft/get-raft-state raft-instance
      (fn [current-state]
        (if (= (:status current-state) :leader)
          (raft/new-entry raft-instance body callback)
          (forward-to-leader (:leader current-state) body))))))
```

### 4. Verify and Test
- Confirm commands are processed successfully
- Run full Jepsen test suite
- Check linearizability results

## Debugging Tips

1. **Logs are verbose** - Jetty debug logging creates huge files. Use grep to filter:
   ```bash
   docker exec jepsen-n1 grep -v "DEBUG org.eclipse.jetty" /opt/fluree-server/logs/fluree.log
   ```

2. **Check leader election** - Look for append-entries messages to identify current leader:
   ```bash
   docker exec jepsen-n1 grep "leader-id" /opt/fluree-server/logs/fluree.log | tail -5
   ```

3. **Force rebuild** - If code changes aren't reflected, ensure full rebuild:
   ```bash
   cd /Users/bplatz/fluree/raft/jepsen-raft/docker
   docker-compose down
   docker system prune -f  # Clean up
   docker-compose up -d --build
   ```

## Environment Details
- Platform: macOS (Darwin)
- Working directory: `/Users/bplatz/fluree/raft/jepsen-raft/docker`
- Git branch: `feature/jepsen2`
- Docker network: `docker_jepsen`
- Port mappings:
  - n1: localhost:7001 → container:7001
  - n2: localhost:7002 → container:7001
  - n3: localhost:7003 → container:7001