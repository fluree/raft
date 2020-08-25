.PHONY: test deps jar install deploy clean

target/fluree-ledger.jar: pom.xml deps.edn src/**/*
	clojure -A:jar

deps:
	clojure -A:test -Stree

pom.xml: deps.edn
	clojure -Spom

test:
	clojure -A:test

jar: target/fluree-ledger.jar

install: target/fluree-ledger.jar
	clojure -A:install

# You'll need to set the env vars CLOJARS_USERNAME & CLOJARS_PASSWORD
# (which must be a Clojars deploy token now) to use this.
deploy: target/fluree-ledger.jar
	clojure -A:deploy

clean:
	rm -rf target