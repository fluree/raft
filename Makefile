SOURCES := $(shell find src)

.PHONY: help
help: ## Show this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help

target/fluree-raft.jar: pom.xml deps.edn $(SOURCES)
	clojure -X:jar

.PHONY: deps
deps: ## Download dependencies
	clojure -M:test -P

pom.xml: deps.edn
	clojure -Spom

.PHONY: test
test: ## Run tests
	clojure -M:test

.PHONY: jar
jar: target/fluree-raft.jar ## Build JAR file

.PHONY: install
install: target/fluree-raft.jar ## Install JAR to local Maven repository
	clojure -X:install

# You'll need to set the env vars CLOJARS_USERNAME & CLOJARS_PASSWORD
# (which must be a Clojars deploy token now) to use this.
.PHONY: deploy
deploy: target/fluree-raft.jar ## Deploy JAR to Clojars
	clojure -X:deploy

.PHONY: clean
clean: ## Remove build artifacts
	rm -rf target
	rm -rf scanning_results

# Linting targets
.PHONY: lint
lint: ## Run clj-kondo linter
	clojure -M:clj-kondo --lint src

.PHONY: lint-ci
lint-ci: ## Run clj-kondo for CI (fail on warnings)
	clojure -M:clj-kondo --lint src --fail-level warning

# Formatting targets  
.PHONY: fmt
fmt: ## Format code with cljfmt
	clojure -M:cljfmt fix

.PHONY: fmt-check
fmt-check: ## Check code formatting
	clojure -M:cljfmt check

# Code analysis
.PHONY: eastwood
eastwood: ## Run Eastwood linter
	mkdir -p scanning_results
	clojure -M:eastwood

.PHONY: ancient
ancient: ## Check for outdated dependencies
	clojure -M:ancient

# Coverage
.PHONY: coverage
coverage: ## Run tests with code coverage
	clojure -M:coverage

# Combined targets
.PHONY: check
check: lint fmt-check test ## Run all checks (lint, format, test)

.PHONY: ci
ci: check eastwood ## Run full CI suite locally