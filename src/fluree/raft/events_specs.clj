(ns fluree.raft.events-specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::term nat-int?)
(s/def ::index nat-int?)

(s/def ::vote (s/nilable (s/tuple ::term boolean?)))
(s/def ::next-index ::index)
(s/def ::match-index ::index)
(s/def ::snapshot-index (s/nilable ::index))
(s/def ::sent nat-int?)
(s/def ::received nat-int?)
(s/def ::avg-response (s/or :positive pos? :zero zero?))
(s/def ::stats (s/keys :req-un [::sent ::received ::avg-response]))
(s/def ::server-state (s/keys :req-un [::vote ::next-index ::match-index ::snapshot-index ::stats]))
