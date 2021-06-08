(ns fluree.raft-specs
  (:require [clojure.spec.alpha :as s]
            [fluree.raft.events-specs :as events])
  (:import (java.io File)))

(s/def ::id pos-int?)
(s/def ::timeout-ms pos-int?)
(s/def ::heartbeat-ms pos-int?)
(s/def ::log-directory string?)
(s/def ::snapshot-threshold pos-int?)
(s/def ::default-command-timeout pos-int?)
(s/def ::catch-up-rounds pos-int?)
(s/def ::entries-max pos-int?)
(s/def ::entry-cache-size pos-int?)
(s/def ::log-history pos-int?)

(s/def ::op #{:timeout :initialize-config :raft-state :add-server
              :remove-server :config-change :config-change-response
              :config-change-commit :config-change-commit-response
              :append-entries :append-entries-response :new-command
              :new-command-timeout :register-callback :request-vote
              :request-vote-response :snapshot :install-snapshot
              :install-snapshot-response :monitor :close})

;; TODO: Write real fn specs for these
(s/def ::unspecced-fn fn?)
(s/def ::send-rpc-fn ::unspecced-fn)
(s/def ::state-machine ::unspecced-fn)
(s/def ::snapshot-write ::unspecced-fn)
(s/def ::snapshot-xfer ::unspecced-fn)
(s/def ::snapshot-reify ::unspecced-fn)
(s/def ::snapshot-install ::unspecced-fn)
(s/def ::snapshot-list-indexes ::unspecced-fn)
(s/def ::close-fn (s/nilable ::unspecced-fn))
(s/def ::leader-change ::unspecced-fn)

;; These are all core.async channels - hard to spec as of 2021-4-19
(s/def ::event-chan any?)
(s/def ::command-chan any?)

(s/def ::config (s/keys :req-un [::log-directory ::send-rpc-fn ::state-machine
                                 ::snapshot-write ::snapshot-xfer
                                 ::snapshot-reify ::snapshot-install
                                 ::snapshot-list-indexes ::event-chan
                                 ::command-chan ::close-fn ::leader-change
                                 ::entries-max]
                        :opt-un [::timeout-ms ::heartbeat-ms ::log-history
                                 ::snapshot-threshold ::entry-cache-size
                                 ::default-command-timeout ::catch-up-rounds]))

(s/def ::server-id (s/or :keyword keyword? :string string?))
(s/def ::this-server ::server-id)
(s/def ::other-servers (s/coll-of ::server-id :distinct true))
(s/def ::status (s/nilable #{:candidate :leader :follower}))
(s/def ::leader (s/nilable ::server-id))
(s/def ::log-file #(instance? File %))
(s/def ::term ::events/term)
(s/def ::index ::events/index)
(s/def ::snapshot-index ::events/snapshot-index)
(s/def ::snapshot-term nat-int?)
(s/def ::commit nat-int?)
(s/def ::snapshot-pending (s/nilable ::commit))
(s/def ::latest-index ::index)
(s/def ::voted-for (s/nilable ::server-id))
(s/def ::server-state (s/map-of any? any?)) ; TODO: Write a real spec
(s/def ::servers (s/map-of ::server-id ::events/server-state))
(s/def ::msg-queue (s/nilable seq?))
(s/def ::pending-server ::server-id)
(s/def ::raft (s/keys :req-un [::id ::config ::this-server ::other-servers
                               ::status ::leader ::log-file ::term ::index
                               ::snapshot-index ::snapshot-term
                               ::snapshot-pending ::commit ::latest-index
                               ::voted-for ::servers ::msg-queue]
                      :opt-un [::pending-server]))


(defn valid-or-throw [spec v err-msg]
  (when-not (s/valid? spec v)
    (throw (ex-info (format "%s: %s" err-msg (pr-str v))
                    (s/explain-data spec v)))))
