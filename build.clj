(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]))

(def lib 'com.fluree/raft)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/fluree/raft"
                      :connection "scm:git:git://github.com/fluree/raft.git"
                      :developerConnection "scm:git:ssh://git@github.com/fluree/raft.git"
                      :tag (str "v" version)}})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  (try
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:installer :remote
      :artifact jar-file
      :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
    (catch Exception e
      (if-not (str/includes? (ex-message e) "Could not find artifact")
        (throw e)
        (println "Ignoring deploy exception:" (ex-message e))))))