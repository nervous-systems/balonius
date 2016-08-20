(ns balonius.common)

(defprotocol WampConnection
  (connect! [_]
    "Return promise resolving upon connection.")
  (disconnect! [_]
    "Asynchronous disconnect.")
  (subscribe! [_ topic chan]
    "Create asynchronous channel receiving events from `topic`.")
  (unsubscribe! [_ topics]
    "Unsubscribe and close channels for given topic/s."))
