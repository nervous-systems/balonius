(ns balonius.platform.wamp
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

(defrecord WampConnectionHandle [conn state]
  wamp.proto/WampConnection
  (on-connected [this]
    (let [p (on-status conn WampClient$ConnectedState)]
      (p/then p (constantly this))))
  (disconnect! [_]
    (let [p (on-status conn WampClient$DisconnectedState)]
      (.close conn)
      p))
  (-subscribe! [_ topic chan]
    (println topic chan "<<<<")
    (let [topic              (keyword topic)
          chan               (or chan (async/chan))
          {{sub :sub} topic} (swap! state update topic
                                    (fn [{:keys [sub chans]}]
                                      {:sub   (or sub (.makeSubscription conn (name topic)))
                                       :chans (conj chans chan)}))]
      (.subscribe sub (observer #(async/>!! chan (do
                                                   (println % (pubsub->map %))
                                                   (pubsub->map %)))))
      chan)))

(defn- ->conn-handle [client]
  (->WampConnectionHandle client (atom {})))

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

(defn connect! [{:keys [uri realm] :as opts}]
  (let [client (build-client opts)]
    (.open client)
    (wamp.proto/on-connected (->conn-handle client))))
