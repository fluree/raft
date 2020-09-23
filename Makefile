.PHONY: test deps jar install deploy clean

SOURCES := $(shell find src)

target/fluree-raft.jar: pom.xml deps.edn $(SOURCES)
	clojure -A:jar

deps:
	clojure -A:test -Stree

pom.xml: deps.edn
	clojure -Spom

test:
	clojure -A:test

jar: target/fluree-raft.jar

install: target/fluree-raft.jar
	clojure -A:install

# You'll need to set the env vars CLOJARS_USERNAME & CLOJARS_PASSWORD
# (which must be a Clojars deploy token now) to use this.
deploy: target/fluree-raft.jar
	clojure -A:deploy

clean:
	rm -rf target