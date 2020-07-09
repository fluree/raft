# fluree/raft

Implementation of the raft protocol in Clojure.

Pluggable components to handle message receiving and responses,
state machine implementation, and other configurations.

## Usage

### As a dependency

#### tools-deps

In `deps.edn`:

```clojure
{:deps {fluree/raft {:mvn/version "RELEASE"}}}
``` 

#### leiningen

In `project.clj`:

```clojure
:dependencies [[fluree/raft "M.m.p"]] ; see above for latest release info
```

TODO: Include some code examples here.

### Development

fluree/raft uses tools-deps (i.e. the `clojure` and `clj` shell commands), so
you'll need to install those (e.g. `brew install clojure` on macOS w/ Homebrew).
It also uses GNU Make so install that too if your system doesn't already have it
(e.g. `brew install make` on macOS).

#### Tests

You can run the tests with `make test`.

## License

Copyright © 2018-2020 Fluree PBC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
