(defproject fluree/raft "0.1.0-SNAPSHOT"

  :description "Fluree's library for fluree implementation"

  :author "Brian Platz"

  :url "https://github.com/fluree/fluree"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.3.3"

  :global-vars {*warn-on-reflection* true
                *assert*             true}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [com.taoensso/nippy "2.14.0"]]

  :profiles {:dev {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[org.clojure/tools.logging "0.4.1"]]}})
