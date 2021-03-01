.PHONY: test deps jar install deploy clean

SOURCES := $(shell find src)

target/fluree-raft.jar: pom.xml deps.edn $(SOURCES)
	clojure -M:jar

deps:
	clojure -M:test -P

pom.xml: deps.edn
	clojure -Spom

test:
	clojure -M:test

jar: target/fluree-raft.jar

install: target/fluree-raft.jar
	clojure -M:install

# You'll need to set the env vars CLOJARS_USERNAME & CLOJARS_PASSWORD
# (which must be a Clojars deploy token now) to use this.
deploy: target/fluree-raft.jar
	clojure -M:deploy

clean:
	rm -rf target