(ns balonius.wamp.proto)

(defprotocol WampConnection
  (connect! [_]
    "Return promise resolving upon connection.")
  (disconnect! [_]
    "Return promise resolving upon disconnection.")
  (-subscribe! [_ topic chan]
    "Create asynchronous channel receiving events from `topic`."))
