.PHONY: test deps kv-example jar install deploy clean

SOURCES := $(shell find src)

target/fluree-raft.jar: deps.edn $(SOURCES)
	clojure -X:jar

deps:
	clojure -M:test -P

kv-example:
	clojure -X:kv-example

test:
	clojure -M:test:runner

jar: target/fluree-raft.jar

install: target/fluree-raft.jar
	clojure -X:install

# You'll need to set the env vars CLOJARS_USERNAME & CLOJARS_PASSWORD
# (which must be a Clojars deploy token now) to use this.
deploy: target/fluree-raft.jar
	clojure -X:deploy

clean:
	rm -rf target
	rm -f pom.xml pom.xml.asc