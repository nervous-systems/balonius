(ns balonius.stream
  "Client implementation for the Poloniex WAMP-over-Websocket streaming API
  <https://poloniex.com/support/api/>.

  The three connection-consuming functions, [[ticker!]], [[follow!]]
  and [[trollbox!]] accept an optional final map argument, having keys:

  - `:chan` - If supplied, received items will be piped onto this core.async
  channel.

  - `:str->number` - Poloniex represents some numerical values as Number
  literals and others as Strings.  By default, Strings under numerical keys will
  be parsed with `bigdec` (JVM) or `js/parseFloat` (cljs).  This behaviour may be
  undesireable, and can be overriden by passing an alternate function,
  e.g.

```clojure
(ticker! conn {:str->number identity})
```

  In all three cases, closing the returned channel will result in teardown of
  the underlying subscription, though the connection will remain live."
  (:require [balonius.wamp :as wamp]
            [balonius.util :as util]
            [balonius.munge :as munge]
            [camel-snake-kebab.core :as csk]
            [taoensso.timbre :as log]
            [clojure.core.async :as async])
  (:gen-class))

(defn- subscription! [conn topic f
                      & [{:keys [chan str->number]
                          :or {str->number util/default-str->number}}]]
  (let [inner-ch (async/chan 1 (map #(f % str->number)))]
    (wamp/subscribe! conn topic inner-ch)
    (cond-> inner-ch chan (util/pipe* chan))))

(def ^:private ticker-cols
  [:pair :last :low-ask :high-bid :change :base-vol :quote-vol :frozen? :high-24 :low-24])

(defn- ticker-frame->map [{args :args} str->number]
  (munge/->tick (zipmap ticker-cols args)))

(defn connect!
  "Return a promise resolving to a connection record for the Poloniex WAMP/WS
  streaming API & suitable for passing into [[ticker!]], [[follow!]]
  & [[trollbox!]]

  Supported `opts` are `:uri` & `:realm`, though it's unlikely non-default
  values will be helpful."
  [& [opts]]
  (wamp/connect!
   (merge {:uri   "wss://api.poloniex.com"
           :realm "realm1"} opts)))

(defn connect!!
  "(Clojure-only) If `@(connect!)` is too much work."
  [& [opts]]
  @(connect! opts))

(defn ticker!
  "Given a connection record, return a core.async channel containing maps having
  keys:

  `:pair`, `:last`, `:low-ask`, `:high-bid`, `:change`, `:base-vol`,
  `:quote-vol`, `:frozen?`, `:high-24` & `:low-24` corresponding in order to
  the [remote API's documented fields](https://poloniex.com/support/api/).

  Currency pairs are represented as vectors of uppercase keywords,
  e.g. `[:BTC :DOGE]`."
  [conn & [{:keys [chan str->number] :as opts}]]
  (subscription! conn "ticker" ticker-frame->map opts))

(util/with-doc-examples! ticker!
  [(async/<!! (ticker! conn))
   {:quote-vol 5550916.88164119M,
    :high-bid 0.00770008M,
    :base-vol 43773.79466888M,
    :low-24 0.00650005M,
    :low-ask 0.00772999M,
    :pair [:BTC :XMR],
    :frozen? false,
    :high-24 0.00938421M,
    :change -0.13631396M,
    :last 0.00772999M}])

(defn- trollbox-frame->map [{args :args} _]
  (let [m (zipmap [:type :id :user :body :reputation] args)]
    (update m :type csk/->kebab-case-keyword)))

(defn trollbox!
  "Given a connection record, return a core.async channel containing maps having
  keys:

  `:type`, `:id`, `:user`, `:body`, `:reputation` corresponding in order
  to [Poloniex's documented fields](https://poloniex.com/support/api/)."
  [conn & [opts]]
  (subscription! conn "trollbox" trollbox-frame->map opts))

(util/with-doc-examples! trollbox!
  [(async/<!! (trollbox! conn))
   {:type       :trollbox-message,
    :id         9704929,
    :user       "nicetomeetyou",
    :body       "OnceIsNonce, punctuation?",
    :reputation 0}]
  [(async/<!! (trollbox! conn {:chan (async/chan 1 (filter (fn [{rep :reputation}] (< 0 rep))))}))
   {:type      :trollbox-message,
    :id         9704960,
    :user       "LordBeer",
    :body       "lookup, i party too much on holidays...",
    :reputation 2406}])

(defn- flatten-orders [{:keys [args kw]} str->number]
  (let [sn (get kw "seq")]
    (for [order args]
      (assoc (into {} order) :poloniex/sequence sn))))

(defn- order->map [order str->number]
  (let [out {:message          (csk/->kebab-case-keyword (order "type"))
             :poloniex/sequence (order :poloniex/sequence)}]
    (persistent!
     (reduce
      (fn [acc [k v]]
        (assoc!
         acc
         (case k "tradeID" :trade-id (keyword k))
         (case k
           "rate"   (str->number v)
           "amount" (str->number v)
           "total"  (str->number v)
           "type"   (keyword v)
           "date"   (munge/->inst v)
           v)))
      (transient out)
      (order "data")))))

(defn follow!
  "Given a connection record ([[connect!]]), subscribe to order book & trade
  events for the given currency `pair` (vector of case-insensitive keywords).

  The returned channel will contain maps having a `:message` key with one of
  three values:

  - `:order-book-modify`
  - `:order-book-remove`
  - `:new-trade`

  Which are case-adjusted names obtained from the [underlying API's
  data](https://poloniex.com/support/api/).

  Aside from `:message`, the set of possible keys is `:trade-id`, `:type`,
  `:total`, `:amount`, `:rate` & `:poloniex/sequence`.

  All messages have an attached sequence number, however *messages will be
  placed on the output channel in the order in which they were received,
  i.e. not in sequence*.  Reordering messages is the responsibility of the
  consumer."
  [conn pair
   & [{out-ch      :chan
       str->number :str->number :as opts
       :or {str->number util/default-str->number}}]]
  (let [xform  (comp (mapcat #(flatten-orders % str->number))
                     (map    #(order->map % str->number)))
        chan   (async/chan 1 xform)]
    (wamp/subscribe! conn (munge/->pair pair) chan)
    (cond-> chan out-ch (util/pipe* out-ch))))

(util/with-doc-examples! follow!
  [{:message           :order-book-modify
    :type              :ask
    :rate              0.01881999M
    :amount            47.77001114M
    :poloniex/sequence 91752915}]
  [{:message           :new-trade,
    :amount            0.01594050M,
    :date              (inst "2016-08-23..."),
    :rate              0.01881999M,
    :total             0.00030000M,
    :trade-id          "16056968",
    :type              :buy
    :poloniex/sequence 91752915}]
  [{:message           :order-book-remove
    :type              :ask
    :rate              0.02149902M
    :poloniex/sequence 91752915}]
  [(follow!
    conn [:BTC :ETH]
    {:chan (async/chan
            1 (comp
               (filter (comp #{:order-book-modify} :message))
               (map    (juxt :type :amount))))})
   (Channel= [:bid 47.7701114M] ...)])

(defn ^:no-doc -main [& _]
  (let [conn   (connect!!)
        ticker (ticker! conn)
        btcxmr (follow! conn [:BTC :XMR])
        troll  (trollbox! conn)]
    (loop []
      (async/alt!!
        ticker ([m] (log/info "Ticker" m))
        btcxmr ([m] (log/info "Pair"   m))
        troll  ([m] (log/info "Troll"  m)))
      (recur))))
