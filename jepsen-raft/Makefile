.PHONY: help
help: ## Show this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help

.PHONY: test
test: ## Run robust in-process Jepsen test (recommended)
	clojure -M:jepsen test --time-limit $${TIME:-10}

.PHONY: docker-build
docker-build: ## Build Docker images for distributed testing
	cd docker && docker-compose build

.PHONY: docker-up
docker-up: ## Start Docker test environment
	cd docker && docker-compose up -d

.PHONY: docker-down
docker-down: ## Stop and remove Docker test environment
	cd docker && docker-compose down -v

.PHONY: docker-test
docker-test: ## Run distributed test in Docker (requires docker-up first)
	docker-compose -f docker/docker-compose.yml exec control bash -c "cd /jepsen-raft && clojure -M:run test --nodes n1,n2,n3,n4,n5 --time-limit $${TIME:-60}"

.PHONY: docker-shell
docker-shell: ## Open shell in control node
	docker-compose -f docker/docker-compose.yml exec control bash

.PHONY: lint
lint: ## Lint source code with clj-kondo
	clojure -M:lint

.PHONY: clean
clean: ## Remove all test results and temporary files
	rm -rf store/
	rm -rf /tmp/jepsen-raft/