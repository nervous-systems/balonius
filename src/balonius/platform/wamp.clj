(ns ^:no-doc balonius.platform.wamp
  (:require [clojure.core.async :as async]
            [camel-snake-kebab.core :as csk]
            [promesa.core :as p]
            [balonius.wamp.proto :as wamp.proto])
  (:import [java.util.concurrent TimeUnit]
           [rx.functions Action1]
           [ws.wamp.jawampa
            WampClient
            WampClient$ConnectedState
            WampClient$DisconnectedState
            WampClientBuilder]
           [ws.wamp.jawampa.transport.netty
            NettyWampClientConnectorProvider
            NettyWampConnectionConfig]
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.util Map List]))

(defn- observer [f]
  (reify Action1
    (call [_ v]
      (f v))))

(let [mapper (ObjectMapper.)]
  (defn- pubsub->map [o]
    (let [args    (.convertValue mapper (.arguments o)        List)
          details (.convertValue mapper (.details   o)        Map)
          kw      (.convertValue mapper (.keywordArguments o) Map)]
      (cond-> {}
        (not-empty args)    (assoc :args args)
        (not-empty details) (assoc :details details)
        (not-empty kw)      (assoc :kw kw)))))

(defn- on-change [conn cb eb]
  (-> conn .statusChanged (.subscribe (observer cb) (observer eb))))

(defn- on-status [conn status]
  (p/promise
   (fn [resolve reject]
     (on-change
      conn
      (fn [status']
        (when (instance? status status')
          (resolve nil)))
      reject))))

(defrecord WampConnectionHandle [client state]
  wamp.proto/WampConnection
  (connect! [this]
    (let [p (on-status client WampClient$ConnectedState)]
      (.open client)
      (p/then p (fn [_] this))))
  (disconnect! [_]
    (let [p (on-status client WampClient$DisconnectedState)]
      (.close client)
      p))
  (-subscribe! [_ topic chan]
    (let [topic (keyword topic)
          sub   (.makeSubscription client (name topic))]
      (.subscribe
       sub
       (observer
        (fn [o]
          (when-not (async/put! chan (pubsub->map o))
            (.unsubscribe sub)))))
      chan)))

(def ^:private retry-msecs 100)

(defn- build-client [{:keys [uri realm] :as opts}]
  (let [cp (NettyWampClientConnectorProvider.)]
    (-> (WampClientBuilder.)
        (.withConnectorProvider cp)
        (.withUri uri)
        (.withRealm realm)
        (.withInfiniteReconnects)
        (.withReconnectInterval (opts :retry-msecs retry-msecs)
                                TimeUnit/MILLISECONDS)
        .build)))

(defn connection [{:keys [uri realm] :as opts}]
  (->WampConnectionHandle (build-client opts) (atom {})))
