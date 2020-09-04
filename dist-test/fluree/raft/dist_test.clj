(ns fluree.raft.dist-test
  (:require [jepsen.cli :as cli]
            [jepsen.tests :as tests]))

(defn test-raft [opts]
  (merge tests/noop-test opts))

(defn -main [& args]
  (let [cfg {:test-fn test-raft}
        test-cmd (cli/single-test-cmd cfg)]
    (cli/run! test-cmd args)))
