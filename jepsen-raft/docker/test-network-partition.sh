#!/bin/bash

# Network Partition Testing Script for Dockerized Raft
# This script simulates various network failure scenarios

set -e

COMPOSE_FILE="/Users/bplatz/fluree/raft/jepsen-raft/docker/docker-compose.yml"

usage() {
    echo "Usage: $0 <command> [options]"
    echo ""
    echo "Commands:"
    echo "  partition-n1     - Isolate n1 from n2 and n3"
    echo "  partition-n2     - Isolate n2 from n1 and n3"
    echo "  partition-n3     - Isolate n3 from n1 and n2"
    echo "  split-brain      - Create 2-1 partition (n1,n2 vs n3)"
    echo "  heal-all         - Remove all network partitions"
    echo "  add-latency      - Add 100ms latency between all nodes"
    echo "  remove-latency   - Remove network latency"
    echo "  status           - Show network status and Raft cluster state"
    echo "  test-scenario    - Run automated partition/heal test scenario"
    echo ""
    echo "Options:"
    echo "  --duration <sec> - Duration for temporary partitions (default: 30)"
    exit 1
}

check_docker_running() {
    if ! docker-compose -f "$COMPOSE_FILE" ps | grep -q "Up"; then
        echo "Error: Raft containers are not running."
        echo "Start them with: docker-compose -f $COMPOSE_FILE up -d"
        exit 1
    fi
}

wait_for_cluster() {
    echo "Waiting for cluster to stabilize..."
    sleep 5
}

partition_node() {
    local node=$1
    local targets=("${@:2}")
    
    echo "Partitioning $node from: ${targets[*]}"
    
    for target in "${targets[@]}"; do
        # Get IP addresses
        local target_ip=$(docker inspect "raft-$target" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
        
        # Block traffic in both directions
        docker exec "raft-$node" iptables -A OUTPUT -d "$target_ip" -j DROP
        docker exec "raft-$node" iptables -A INPUT -s "$target_ip" -j DROP
        
        echo "  Blocked: $node <-> $target ($target_ip)"
    done
}

heal_partitions() {
    echo "Healing all network partitions..."
    
    for node in n1 n2 n3; do
        docker exec "raft-$node" iptables -F 2>/dev/null || true
        echo "  Cleared iptables rules for $node"
    done
    
    wait_for_cluster
    echo "Network partitions healed"
}

add_network_latency() {
    local latency=${1:-100}
    echo "Adding ${latency}ms latency to all nodes..."
    
    for node in n1 n2 n3; do
        # Add latency to outgoing traffic
        docker exec "raft-$node" tc qdisc add dev eth0 root netem delay "${latency}ms" 2>/dev/null || \
        docker exec "raft-$node" tc qdisc change dev eth0 root netem delay "${latency}ms" 2>/dev/null || true
        echo "  Added ${latency}ms latency to $node"
    done
}

remove_network_latency() {
    echo "Removing network latency..."
    
    for node in n1 n2 n3; do
        docker exec "raft-$node" tc qdisc del dev eth0 root 2>/dev/null || true
        echo "  Removed latency from $node"
    done
}

show_status() {
    echo "=== Network Status ==="
    for node in n1 n2 n3; do
        echo "Node $node iptables rules:"
        docker exec "raft-$node" iptables -L 2>/dev/null | grep -E "(DROP|REJECT)" || echo "  No blocking rules"
        
        echo "Node $node latency:"
        docker exec "raft-$node" tc qdisc show dev eth0 2>/dev/null | grep netem || echo "  No latency configured"
        echo
    done
    
    echo "=== Raft Cluster Status ==="
    for i in 1 2 3; do
        port=$((7000 + i))
        echo "Node n$i (port $port):"
        curl -s "http://localhost:$port/debug" | python3 -m json.tool 2>/dev/null || echo "  Node not responding"
        echo
    done
}

test_scenario() {
    local duration=${1:-30}
    
    echo "=== Running Automated Network Partition Test ==="
    echo "Duration per test: ${duration}s"
    echo
    
    # Show initial state
    echo "1. Initial cluster state:"
    show_status
    
    # Test 1: Partition n1
    echo "2. Testing n1 partition..."
    partition_node n1 n2 n3
    sleep "$duration"
    show_status
    heal_partitions
    
    # Test 2: Split brain
    echo "3. Testing split-brain partition..."
    partition_node n3 n1 n2
    sleep "$duration"
    show_status
    heal_partitions
    
    # Test 3: Add latency
    echo "4. Testing network latency..."
    add_network_latency 200
    sleep "$duration"
    show_status
    remove_network_latency
    
    echo "=== Test scenario completed ==="
    show_status
}

# Main script
if [ $# -eq 0 ]; then
    usage
fi

check_docker_running

COMMAND=$1
DURATION=${2:-30}

case $COMMAND in
    "partition-n1")
        partition_node n1 n2 n3
        wait_for_cluster
        ;;
    "partition-n2")
        partition_node n2 n1 n3
        wait_for_cluster
        ;;
    "partition-n3")
        partition_node n3 n1 n2
        wait_for_cluster
        ;;
    "split-brain")
        partition_node n3 n1 n2
        wait_for_cluster
        ;;
    "heal-all")
        heal_partitions
        ;;
    "add-latency")
        add_network_latency "$DURATION"
        ;;
    "remove-latency")
        remove_network_latency
        ;;
    "status")
        show_status
        ;;
    "test-scenario")
        test_scenario "$DURATION"
        ;;
    "--duration")
        echo "Error: --duration must be used with other commands"
        usage
        ;;
    *)
        echo "Error: Unknown command '$COMMAND'"
        usage
        ;;
esac