.PHONY: help
help: ## Show this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help

# Testing Targets
# ==============

.PHONY: jepsen-test
jepsen-test: ## Run net.async test (requires manual node startup)
	@if [ "$${NODES}" = "3" ]; then \
		echo "Running 3-node test..."; \
		clojure -M:netasync test netasync --time-limit $${TIME:-10} --nodes n1,n2,n3; \
	elif [ "$${NODES}" = "7" ]; then \
		echo "Running 7-node test..."; \
		clojure -M:netasync test netasync --time-limit $${TIME:-10} --nodes n1,n2,n3,n4,n5,n6,n7; \
	else \
		echo "Running 5-node test (default)..."; \
		clojure -M:netasync test netasync --time-limit $${TIME:-10} --nodes n1,n2,n3,n4,n5; \
	fi

.PHONY: jepsen-docker-test
jepsen-docker-test: ## Run dockerized net.async test with network partition nemesis
	clojure -M:netasync-docker test --time-limit $${TIME:-60} --nodes n1,n2,n3,n4,n5

.PHONY: jepsen-docker-test-minimal
jepsen-docker-test-minimal: ## Run minimal dockerized test without nemesis
	clojure -M:netasync-docker test --minimal --time-limit $${TIME:-30} --nodes n1,n2,n3,n4,n5

.PHONY: performance-test
performance-test: ## Run performance stress test (auto-manages nodes)
	@if [ "$${NODES}" = "3" ]; then \
		echo "Running 3-node performance test..."; \
		PERF_NODE_COUNT=3 clojure -M:performance; \
	elif [ "$${NODES}" = "7" ]; then \
		echo "Running 7-node performance test..."; \
		PERF_NODE_COUNT=7 clojure -M:performance; \
	else \
		echo "Running 5-node performance test (default)..."; \
		PERF_NODE_COUNT=5 clojure -M:performance; \
	fi

# Node Management Targets
# ======================

.PHONY: start-nodes
start-nodes: ## Start net.async Raft nodes manually
	@echo "🚀 Starting net.async Raft nodes..."
	@mkdir -p logs
	@clojure -M -m jepsen-raft.raft-node n1 9001 7001 n1,n2,n3,n4,n5 > logs/n1.log 2>&1 & echo "Started n1 (PID: $$!)"
	@clojure -M -m jepsen-raft.raft-node n2 9002 7002 n1,n2,n3,n4,n5 > logs/n2.log 2>&1 & echo "Started n2 (PID: $$!)"
	@clojure -M -m jepsen-raft.raft-node n3 9003 7003 n1,n2,n3,n4,n5 > logs/n3.log 2>&1 & echo "Started n3 (PID: $$!)"
	@clojure -M -m jepsen-raft.raft-node n4 9004 7004 n1,n2,n3,n4,n5 > logs/n4.log 2>&1 & echo "Started n4 (PID: $$!)"
	@clojure -M -m jepsen-raft.raft-node n5 9005 7005 n1,n2,n3,n4,n5 > logs/n5.log 2>&1 & echo "Started n5 (PID: $$!)"
	@echo "⏳ Waiting for nodes to start..."
	@sleep 5
	@echo "🔍 Checking node health..."
	@make --silent check-nodes

.PHONY: stop-nodes
stop-nodes: ## Stop all net.async Raft nodes
	@echo "🛑 Stopping net.async Raft nodes..."
	@pkill -f "jepsen-raft.raft-node" || echo "No nodes were running"
	@echo "✅ All nodes stopped"

.PHONY: check-nodes
check-nodes: ## Check if net.async nodes are running and healthy
	@echo "📊 Node Status:"
	@for port in 7001 7002 7003 7004 7005; do \
		node=n$$(($$port - 7000)); \
		if curl -s --connect-timeout 2 http://localhost:$$port/health >/dev/null 2>&1; then \
			health=$$(curl -s http://localhost:$$port/health); \
			echo "  ✅ $$node (port $$port): $$health"; \
		else \
			echo "  ❌ $$node (port $$port): Not responding"; \
		fi; \
	done

.PHONY: check-ports
check-ports: ## Check if all required ports are available
	@echo "🔍 Checking port availability..."
	@for port in 7001 7002 7003 7004 7005 9001 9002 9003 9004 9005; do \
		if lsof -i :$$port >/dev/null 2>&1; then \
			process=$$(lsof -i :$$port | tail -n +2 | awk '{print $$1 " [PID " $$2 "]"}' | head -1); \
			echo "  ❌ Port $$port: In use by $$process"; \
		else \
			echo "  ✅ Port $$port: Available"; \
		fi; \
	done

.PHONY: logs
logs: ## Tail logs for all running nodes
	@tail -f logs/*.log 2>/dev/null || echo "No log files found. Start nodes first with 'make start-nodes'"

.PHONY: restart-nodes
restart-nodes: ## Restart all net.async nodes with fresh state
	@echo "🔄 Restarting net.async nodes with fresh state..."
	@make --silent stop-nodes
	@sleep 2
	@rm -rf /tmp/jepsen-raft-network/
	@make --silent start-nodes

# Docker Targets
# =============

.PHONY: docker-build
docker-build: ## Build Docker images for net.async testing
	cd docker && docker-compose build

.PHONY: docker-up
docker-up: ## Start net.async Docker test environment
	cd docker && docker-compose up -d

.PHONY: docker-down
docker-down: ## Stop and remove net.async Docker test environment
	cd docker && docker-compose down -v

.PHONY: docker-logs
docker-logs: ## Show logs from net.async Docker containers
	cd docker && docker-compose logs -f

.PHONY: docker-test-network
docker-test-network: ## Test network partition scenarios
	cd docker && ./test-network-partition.sh test-scenario

# Utility Targets
# ==============

.PHONY: lint
lint: ## Lint source code with clj-kondo
	clojure -M:lint

.PHONY: clean
clean: ## Remove all test results and temporary files
	@echo "🧹 Cleaning up test artifacts..."
	@rm -rf store/
	@rm -rf /tmp/jepsen-raft-network/
	@rm -rf logs/
	@pkill -f "jepsen-raft.raft-node" || true
	@docker-compose -f docker/docker-compose.yml down -v 2>/dev/null || true
	@echo "✅ Cleanup complete"