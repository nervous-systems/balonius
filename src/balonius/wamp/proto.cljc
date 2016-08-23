(ns balonius.wamp.proto)

(defprotocol WampConnection
  (on-connected [_]
    "Return promise resolving upon connection.")
  (disconnect! [_]
    "Asynchronous disconnect.")
  (-subscribe! [_ topic chan]
    "Create asynchronous channel receiving events from `topic`."))
