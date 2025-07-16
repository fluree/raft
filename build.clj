(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.fluree/raft)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :src-pom nil
                :scm {:url "https://github.com/fluree/raft"
                      :connection "scm:git:git://github.com/fluree/raft.git"
                      :developerConnection "scm:git:ssh://git@github.com/fluree/raft.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A Raft consensus algorithm implementation for Clojure"]
                          [:url "https://github.com/fluree/raft"]
                          [:licenses
                           [:license
                            [:name "Eclipse Public License 1.0"]
                            [:url "https://opensource.org/license/epl-1-0/"]
                            [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))