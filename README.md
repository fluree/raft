# fluree/raft

An implementation of the [Raft consensus algorithm](https://raft.github.io/) 
in Clojure. This library provides distributed consensus with pluggable 
components for networking, state machines, and persistence.

## Features

- **Raft Core**: Leader election, log replication, and cluster membership 
  changes
- **Persistent Storage**: Durable log storage with automatic rotation and 
  snapshot support
- **Dynamic Membership**: Add or remove servers from the cluster
- **Pluggable Architecture**: Customize networking, state machines, and 
  storage backends
- **Error Handling**: Includes log corruption recovery and error handling
- **Monitoring**: Built-in callbacks for leadership changes and other events

## Prerequisites

- Clojure 1.11.1 or higher
- Java 8 or higher
- GNU Make (for development tasks)

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/com.fluree/raft.svg)](https://clojars.org/com.fluree/raft)

### deps.edn

```clojure
{:deps {com.fluree/raft {:mvn/version "1.0.0-beta1"}}}
```

Using Git dependency (for latest development version)

```clojure
{:deps {fluree/raft {:git/url "https://github.com/fluree/raft"
                     :git/sha "904d915"}}} ; use latest commit SHA
```

## Validation & Performance

This Raft implementation has been validated with [Jepsen](https://jepsen.io/) for correctness and performance.

### Latest Results (July 2025)

**Consistency Validation:**
- ✅ **Linearizability**: Zero violations across 5-minute stress test with 2-key concurrent operations
- ✅ **High Concurrency**: [Up to 846 ops/sec (7-node cluster, 150 clients)](jepsen-raft/performance-results.md)
- ✅ **Jepsen Result**: "Valid" - [Jepsen test results](jepsen-raft/test-results/5-minute-consistency-test/results.edn)

### Test Suite

**Three Test Environments:**

[Complete Test Guide](jepsen-raft/README.md) - Setup, configuration, and test scenarios
1. **[Local Net.Async Test](jepsen-raft/README.md#1-local-netasync-test-testclj)** - Fast development iteration
2. **[Docker Network Failure Test](jepsen-raft/README.md#2-dockerized-test-with-network-failures-test_dockerclj)** - Production simulation with network partitions
3. **[Performance Stress Test](jepsen-raft/README.md#3-performance-stress-test-test_performanceclj)** - Capacity planning and breaking point analysis

## Quick Start

Here's a minimal example of setting up a single-node Raft instance:

```clojure
(ns my-app.core
  (:require [fluree.raft :as raft]))

;; Track application state
(def app-state (atom {}))

;; Define your state machine
;; Note: state machine receives entry and raft-state params
(defn state-machine [entry raft-state]
  ;; Process the entry and update application state
  (swap! app-state
         (fn [state]
           (case (:op entry)
             :set (assoc state (:key entry) (:value entry))
             :delete (dissoc state (:key entry))
             state)))
  ;; Return result for the command callback
  true)

;; Configure and start a Raft node
(def config
  {:servers          ["server1"]
   :this-server      "server1"  ; Required: identifies this node
   :send-rpc-fn      (fn [server msg callback] 
                       ;; For single node, invoke callback with nil
                       (when callback (callback nil)))
   :leader-change-fn (fn [event] 
                       (println "Leader changed to:" (:new-leader event)))
   :log-directory    "/var/raft/logs"
   :state-machine    state-machine
   
   ;; Required snapshot functions
   :snapshot-write   (fn [file state] 
                       (spit file (pr-str state)))
   :snapshot-reify   (fn [] @app-state)
   :snapshot-install (fn [snapshot index] 
                       (reset! app-state snapshot))
   :snapshot-xfer    (fn [snapshot server] 
                       ;; Transfer snapshot to another server
                       ;; For single node, this is a no-op
                       nil)
   :snapshot-list-indexes (fn [dir] 
                            ;; Return list of available snapshot indexes
                            [])})

;; Start the Raft instance
(def raft-instance (raft/start config))

;; Submit a command (only works on the leader)
;; new-entry requires a callback function
(raft/new-entry raft-instance 
                {:op :set :key "foo" :value "bar"}
                (fn [success?] 
                  (println "Entry submitted:" success?))
                5000) ; optional timeout in ms
```

### Multi-Node Cluster

For a real distributed system, you'll need to implement the networking layer:

```clojure
;; Example configuration for node 1 of a 3-node cluster
(def config
  {:servers          ["server1" "server2" "server3"]
   :this-server      "server1"
   :send-rpc-fn      my-network-send-fn  ; Your RPC implementation
   :leader-change-fn handle-leader-change
   :log-directory    "/var/raft/server1/logs"
   :state-machine    state-machine
   :heartbeat-ms     150    ; Leader heartbeat interval
   :timeout-ms       300    ; Election timeout
   
   ;; Snapshot functions (see src/fluree/raft/kv_example.clj for full implementation)
   :snapshot-write   snapshot-writer-fn
   :snapshot-reify   snapshot-reify-fn
   :snapshot-install snapshot-install-fn
   :snapshot-xfer    snapshot-transfer-fn
   :snapshot-list-indexes snapshot-list-fn})
```

## Configuration

The Raft configuration map requires the following options:

### Required Configuration

```clojure
{:servers          ["server1" "server2" "server3"]  ; List of all servers in cluster
 :this-server      "server1"                        ; This server's ID (must be in servers list)
 :send-rpc-fn      (fn [server msg callback] ...)   ; Network layer for sending messages
 :state-machine    (fn [entry raft-state] ...)      ; Your state machine function
 
 ;; Required snapshot functions
 :snapshot-write   (fn [file state] ...)            ; Write state to snapshot file
 :snapshot-reify   (fn [] ...)                      ; Create snapshot from current state
 :snapshot-install (fn [snapshot index] ...)        ; Install received snapshot
 :snapshot-xfer    (fn [snapshot server] ...)}      ; Transfer snapshot to server
```

### Optional Configuration

```clojure
{:log-directory         "raftlog/"           ; Directory for logs (default: "raftlog/")
 :leader-change-fn      (fn [event] ...)      ; Callback for leadership changes
 :close-fn              (fn [] ...)           ; Cleanup function on shutdown
 :snapshot-threshold    100                   ; Entries before creating snapshot (default: 100)
 :heartbeat-ms          100                   ; Leader heartbeat interval in ms (default: 100)
 :timeout-ms            500                   ; Election timeout in ms (default: 500)
 :log-history           10                    ; Number of old log files to keep (default: 10)
 :entries-max           50                    ; Max entries to send at once (default: 50)
 :default-command-timeout 4000               ; Default timeout for commands in ms (default: 4000)
 :catch-up-rounds       10                    ; Rounds to attempt catching up (default: 10)
 :entry-cache-size      nil                  ; Size of entry cache (optional)
 
 ;; Additional snapshot-related options
 :snapshot-list-indexes (fn [dir] ...)       ; List available snapshots
 
 ;; Advanced options (rarely needed)
 :event-chan            async/chan           ; Custom event channel
 :command-chan          async/chan}          ; Custom command channel
```

## Architecture Overview

### Raft Consensus

This implementation follows the Raft specification, providing:

- **Leader Election**: Ensures one leader per term using randomized timeouts
- **Log Replication**: Consistent replication across all nodes
- **Safety**: Guarantees linearizability and prevents split-brain scenarios
- **Membership Changes**: Safe cluster reconfiguration

### Key Components

1. **Core State Machine** (`raft.clj`): Main event loop handling all state 
   transitions
2. **Log Management** (`log.clj`): Persistent, rotating log storage with 
   corruption recovery
3. **Leader Operations** (`leader.clj`): Replication, heartbeats, and 
   commitment
4. **Event Processing** (`events.clj`): Common handlers for all server states
5. **Monitoring** (`watch.clj`): Leadership change notifications

## API Reference

### Core Functions

#### `raft/start`
Starts a new Raft instance with the given configuration.

```clojure
(raft/start config) ; => RaftInstance
```

#### `raft/new-entry`
Submits a new entry to the Raft log (leader only). Requires a callback 
function.

```clojure
;; With default timeout (5000ms)
(raft/new-entry raft-instance entry callback)

;; With custom timeout
(raft/new-entry raft-instance entry callback timeout-ms)

;; Example:
(raft/new-entry raft-instance 
                {:op :set :key "foo" :value "bar"}
                (fn [success?] 
                  (if success?
                    (println "Entry committed")
                    (println "Entry failed")))
                3000)
```

#### `raft/invoke-rpc`
General RPC invocation mechanism. Used for operations like adding/removing 
servers.

```clojure
;; Add a server to the cluster (leader only)
(raft/invoke-rpc raft-instance :add-server "server4" callback-fn)

;; Remove a server from the cluster (leader only)
(raft/invoke-rpc raft-instance :remove-server "server2" callback-fn)
```

#### `raft/get-raft-state`
Get the current Raft state asynchronously. Useful for debugging.

```clojure
(raft/get-raft-state raft-instance 
                     (fn [state] 
                       (println "Current state:" (:status state))))
```

#### `raft/add-leader-watch`
Watch for leadership changes.

```clojure
(raft/add-leader-watch raft-instance 
                       :my-watch-key
                       (fn [leader-info]
                         (println "New leader:" leader-info)))
```

#### `raft/close`
Cleanly shut down a Raft instance.

```clojure
(raft/close raft-instance)
```

#### `raft/monitor-raft`
Debugging tool to monitor all Raft events.

```clojure
;; Register a monitoring function
(raft/monitor-raft raft-instance 
                   (fn [event] 
                     (println "Raft event:" event)))

;; Remove monitoring
(raft/monitor-raft raft-instance nil)
```

#### `raft/remove-leader-watch`
Remove a previously registered leader watch.

```clojure
(raft/remove-leader-watch raft-instance :my-watch-key)
```

### State Machine Interface

Your state machine function receives the entry and the current raft state:

```clojure
(defn state-machine 
  [entry raft-state]
  ;; Process entry and return result
  ;; The result will be passed to the command callback
  ;; Typically you'd maintain state in an atom and return success/failure
  result)
```

### Snapshot Operations

Implement these functions for snapshot support:

```clojure
{:snapshot-write   (fn [file state] ...)         ; Write state to snapshot file
 :snapshot-reify   (fn [] current-state)         ; Create snapshot from current state
 :snapshot-install (fn [snapshot index] ...)     ; Install received snapshot
 :snapshot-xfer    (fn [snapshot server] ...)}   ; Transfer snapshot to server
```

## Examples

### Key-Value Store Example

See `src/fluree/raft/kv_example.clj` for a complete example implementing a 
distributed key-value store. This example demonstrates:

- Full networking implementation with RPC
- Snapshot creation and restoration
- State machine with multiple operations (read, write, delete, CAS)
- Multi-node cluster setup

### Monitoring Leadership Changes

```clojure
(def config
  {:leader-change-fn (fn [event]
                       (println "Leader changed from" (:old-leader event)
                                "to" (:new-leader event)
                                "Event:" (:event event)))
   ;; ... other config
   })
```

## Development

### Building

```bash
# Download dependencies
make deps

# Run tests
make test

# Build JAR
make jar

# Run linting
make clj-kondo

# Generate coverage report
make coverage

# Check for outdated dependencies
make ancient
```

### Running Tests

```bash
# Run all tests
make test

# Run with specific test selectors
clojure -M:test -i integration
```

### Docker Support

The project includes a Dockerfile for containerized deployment:

```bash
# Build Docker image
docker build -t fluree/raft .

# Run with volume for logs
docker run -v /var/raft:/var/raft fluree/raft
```

## Troubleshooting

### Common Issues

**Log Corruption**
- The library automatically detects and repairs corrupted logs
- Check logs for: "Corrupted log entry detected at index X"

**Leader Election Failures**
- Ensure network connectivity between nodes
- Check that election timeouts are properly configured
- Verify server IDs are unique

**Performance Tuning**
```clojure
{:snapshot-threshold 5000      ; Increase for write-heavy workloads
 :heartbeat-ms 50              ; Decrease for faster failure detection
 :timeout-ms 150               ; Decrease for quicker elections
 :entries-max 100}             ; Increase for better throughput
```

### Debug Logging

Enable debug logging by configuring your logging framework appropriately.

## Resources

- [Raft Paper](https://raft.github.io/raft.pdf)
- [Raft Website](https://raft.github.io/)
- [Fluree Documentation](https://docs.flur.ee/)

## Support

- **Issues**: [GitHub Issues](https://github.com/fluree/raft/issues)
- **Discussions**: [GitHub Discussions](https://github.com/fluree/raft/discussions)

## License

Copyright © 2018-2025 Fluree PBC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
