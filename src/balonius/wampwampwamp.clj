(ns balonius.wampwampwamp
  (:require [clojure.core.async :as async]
            [camel-snake-kebab.core :as csk]
            [promesa.core :as p]
            [balonius.common :as common])
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

(def ^:private retry-msecs 100)

(defn build-client [{:keys [uri realm] :as opts}]
  (let [cp (NettyWampClientConnectorProvider.)]
    (-> (WampClientBuilder.)
        (.withConnectorProvider cp)
        (.withUri uri)
        (.withRealm realm)
        (.withInfiniteReconnects)
        (.withReconnectInterval (opts :retry-msecs retry-msecs)
                                TimeUnit/MILLISECONDS)
        .build)))

(defn- observer [f]
  (reify Action1
    (call [_ v]
      (f v))))

(defmacro subscribe [o slot f]
  (let [slot (csk/->camelCaseSymbol (str "." (name slot)))]
    `(.subscribe (~slot ~o) (observer ~f))))

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
  (-> conn .statusChanged (.subscribe (observer cb)
                                      (observer eb))))

(defn- on-status [conn status]
  (p/promise
   (fn [resolve reject]
     (on-change
      conn
      (fn [status']
        (when (instance? status' status)
          (resolve nil)))
      reject))))

(defrecord WampConnectionHandle [conn state]
  common/WampConnection
  (connect! [_]
    (let [p (on-status conn WampClient$ConnectedState)]
      (.open conn)
      p))
  (disconnect! [_]
    (let [p (on-status conn WampClient$DisconnectedState)]
      (.close conn)
      p))
  (subscribe! [_ topic chan]
    (let [topic (keyword topic)
          chan  (or chan (async/chan))
          sub   (.makeSubscription conn (name topic))]
      (.subscribe sub (observer (partial async/>!! chan)))
      (swap! state update topic (fnil conj []) {:chan chan :sub sub})
      chan))
  (unsubscribe! [_ topic]
    (let [topic (keyword topic)]
      (doseq [entry (-> @state :topic topic)]
        (.unsubscribe (entry :sub))
        (async/close! (entry :chan)))
      (swap! state dissoc topic))))

(defn- ->conn-handle [client]
  (->WampConnectionHandle client (atom {})))

(defn- connect! [opts]
  (-> opts build-client ->conn-handle connect!))
