(defproject fluree/raft "0.8.1"

  :description "Fluree's library for fluree implementation"

  :author "Brian Platz"

  :url "https://github.com/fluree/fluree"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.3.3"

  :global-vars {*assert* true}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/tools.logging "0.4.1"]
                 [com.taoensso/nippy "2.14.0"]]

  :profiles {:dev {:repl-options {:init-ns fluree.raft.kv-example}
                   :dependencies []}})
