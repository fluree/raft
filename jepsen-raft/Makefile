.PHONY: help
help: ## Show this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help

.PHONY: test
test: ## Run robust in-process Jepsen test (recommended)
	clojure -M:jepsen test --time-limit $${TIME:-10}

.PHONY: run
run: ## Run full distributed Jepsen test (NOT YET IMPLEMENTED)
	@echo "Error: The distributed test is not yet implemented"
	@echo "Please use 'make test' for the in-process Jepsen test"
	@exit 1

.PHONY: lint
lint: ## Lint source code with clj-kondo
	clojure -M:lint

.PHONY: clean
clean: ## Remove all test results and temporary files
	rm -rf store/
	rm -rf /tmp/jepsen-raft/