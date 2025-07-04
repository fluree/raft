#!/bin/bash
# Simple script to run distributed test using Docker

# Build the images
echo "Building Docker images..."
docker-compose -f docker/docker-compose.yml build

# Start the containers
echo "Starting containers..."
docker-compose -f docker/docker-compose.yml up -d

# Wait for nodes to be ready
echo "Waiting for nodes to start..."
sleep 10

# Check container status
echo "Container status:"
docker ps | grep jepsen

echo "Checking logs from node n1:"
docker logs jepsen-n1 --tail 20

echo ""
echo "To run the Jepsen test:"
echo "  docker exec -it jepsen-control bash"
echo "  cd /jepsen-raft"
echo "  clojure -M:jepsen test --time-limit 30"
echo ""
echo "To stop all containers:"
echo "  docker-compose -f docker/docker-compose.yml down"