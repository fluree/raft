(ns fluree.raft.watch
  (:require [clojure.tools.logging :as log]))

;; add watch functions for specific raft changes

(defn- watch-fn-atom
  "Watch fn atom has data structure like:
  {user-key1 {:fn         function
              :event-type :become-follower
   user-key2 {:fn         function
              :event-type nil}

   where event-type is 'nil' for all events, or keyword for specific event."
  [raft]
  (:watch-fns raft))

(defn add-leader-watch
  "Registers a function to be called with each leader change. Specify any key
  which can be used to unregister function later.

  Function will be called with one four args: key, event-type, raft-state before the leader change
  and raft-state after the leader change.

  Important! Function is called synchronously, and therefore RAFT is stopped while processing.
  If function requires raft calls, it *must* be run asynchronously.
  Good to run asynchronously for anything that might be slow.

  If key is already in use, overwrites existing watch function with fn.

  Optionally register for a specific event-type. When no event-type is specified,
  triggers for all leader change events.

  event-types are:
  - :become-leader - triggered when this server becomes leader
  - :become-follower - triggered when this server becomes a follower (was leader)
  "
  ([raft key fn] (add-leader-watch raft key fn nil))
  ([raft key fn event-type]
   (assert (or (nil? event-type) (#{:become-leader :become-follower} event-type)))
   (swap! (watch-fn-atom raft) assoc key {:event-type event-type
                                          :fn         fn})))

(defn remove-leader-watch
  "Removes watch function with specified key."
  [raft key]
  (assert (not (nil? key)))
  (swap! (watch-fn-atom raft) dissoc key))

(defn call-leader-watch
  "Used internally to call registered leader watch functions."
  [change-map]
  (let [{:keys [event new-raft-state]} change-map
        watches @(watch-fn-atom new-raft-state)]
    (doseq [[k {watch-event :event-type watch-fn :fn}] watches]
      (when (or (nil? watch-event)
                (= watch-event event))
        (try (watch-fn (assoc change-map :key k))
             (catch Exception e (log/error e (str "Error executing leader watch function: " (pr-str k)))))))))