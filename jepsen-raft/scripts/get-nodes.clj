#!/usr/bin/env clojure
;; Helper script to get node lists from centralized configuration

(require '[jepsen-raft.nodes :as nodes])

(defn -main [& args]
  (let [profile (keyword (or (first args) "default"))
        node-list (nodes/get-nodes profile)]
    (println (clojure.string/join "," node-list))))

(-main (first *command-line-args*))