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