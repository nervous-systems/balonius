(ns balonius.public
  "1:1 mapping to Poloniex's unauthenticated/public endpoints
  <https://poloniex.com/support/api/>."
  (:require [kvlt.core :as kvlt]
            [clojure.string :as str]
            [clojure.set :refer [rename-keys]]
            [balonius.util :as util]
            [balonius.munge :as munge]
            [promesa.core :as p]
            [camel-snake-kebab.core :as csk]))

(def ^:private public-url "https://poloniex.com/public")

(defn- public!
  [command cont opts & [query]]
  (p/then (kvlt/request! {:url   public-url
                          :query (assoc query :command command)
                          :as    :json})
    (fn [{body :body :as resp}]
      (if-let [error (:error body)]
        (throw (ex-info error {:balonius/error    :poloniex
                               :balonius/response resp}))
        (binding [util/*str->number* (get opts :str->number util/default-str->number)]
          (cont body))))))

(def ^:private tick-renames
  {:lowestAsk     :low-ask
   :highestBid    :high-bid
   :percentChange :change
   :baseVolume    :base-vol
   :quoteVolume   :quote-vol
   :isFrozen      :frozen?
   :high24hr      :high-24
   :low24hr       :low-24})

(defn- munge-ticker [m]
  (munge/->pair-map #(munge/->tick (rename-keys % tick-renames)) m))

(defn ticker! [& [pair opts]]
  (public!
   "returnTicker"
   (fn [resp]
     (let [ticker (munge-ticker resp)]
       (cond-> ticker pair (get (mapv munge/->currency pair)))))
   opts))

(util/with-doc-examples! ticker!
  [@(ticker!)
   {[:BTC :DOGE]
    {:quote-vol 1723468221.12964487M,
     :high-bid  4.1E-7M,
     :base-vol  702.80488396M,
     :low-24    3.8E-7M,
     :low-ask   4.2E-7M,
     :frozen?   false,
     :id        27,
     :high-24   4.3E-7M,
     :change    0.05128205M,
     :last      4.1E-7M}
    [:ETC :ETC] ...}])

(defn- numeric-vals [m]
  (util/map-vals util/str->number m))

(defn- munge-totals [m]
  (util/traduce
   (fn [acc k total]
     (let [curr (subs (name k) 5)]
       (assoc! acc (keyword curr) (util/str->number total))))
   m))

(defn- munge-volume [m]
  (let [[totals pairs] (util/separate #(str/starts-with? (name %) "total") m)]
    {:pairs  (munge/->pair-map #(numeric-vals %) pairs)
     :totals (munge-totals totals)}))

(defn volume-24!
  "Corresponds to `return24hVolume`.  Response is a map with keys `:pairs` and
  `:totals`."
  [& [opts]]
  (public! "return24hVolume" munge-volume opts))

(defn trade-history!
  "Corresponds to `returnTradeHistory`.  Accepts a map containing a
  `:pair` (vector of keywords) and an optional `:start` and `:end`
  (msecs since epoch or Date). Returns a sequence of maps."
  [{:keys [pair start end]} & [opts]]
  (let [query (util/assoc-when {:currencyPair (munge/->pair pair)}
                :start (some-> start munge/->timestamp)
                :end   (some-> end   munge/->timestamp))]
    (public!
     "returnTradeHistory"
     (fn [trades]
       (mapv munge/munge-trade trades))
     opts query)))

(util/with-doc-examples! trade-history!
  [@(trade-history! {:pair  [:btc :ltc]
                     :start (- (System/currentTimeMillis) (* 60 1000))
                     :end   (java.util.Date.)})
   [{:global-trade-id 55814927,
     :trade-id        1804686,
     :date            (inst "2016-09-17T..."),
     :type            :buy,
     :rate            0.00630996M,
     :amount          0.02013374M,
     :total           0.00012704M} ...]])

(defn- munge-book-entry [entry]
  (let [entry (rename-keys entry {:isFrozen :frozen? :seq :sequence})
        parse #(mapv (fn [v] (update v 0 util/str->number)) %)]
    (-> entry
        (update :frozen? #(= % "1"))
        (update :bids    parse)
        (update :asks    parse))))

(defn- munge-order-book [pair book]
  (if pair
    (munge-book-entry book)
    (munge/->pair-map munge-book-entry book)))

(defn order-book!
  "Corresponds to `returnOrderBook`.  Takes an optional map having
  `:pair` (vector of keywords), and perhaps `:depth.`"
  [& [{:keys [pair depth]} opts]]
  (let [query (util/assoc-when {:currencyPair (if pair (munge/->pair pair) "all")}
                :depth depth)]
    (public! "returnOrderBook"
             (partial munge-order-book pair)
             opts query)))

(let [renames {:quoteVolume     :quote-vol
               :weightedAverage :weighted-avg}]
  (defn- munge-chart-entry [entry]
    (-> entry
        (rename-keys renames)
        (update :date munge/->inst))))

(defn- munge-chart [chart]
  (mapv chart munge-chart-entry))

(defn chart!
  "Corresponds to `returnChartData`.  Accepts a map requiring keys: `:pair`,
  `:period` (number), `:start` & `:end` (msecs since epoch, or Date)."
  [{:keys [pair period start end]} & [opts]]
  (let [query {:currencyPair (munge/->pair pair)
               :period       period
               :start        (munge/->timestamp start)
               :end          (munge/->timestamp end)}]
    (public! "returnChartData" munge-chart opts query)))

(let [booleans #{:disabled :frozen :delisted}
      renames  {:txFee :tx-fee :minConf :min-conf :depositAddress :deposit-addr
                :disabled :disabled? :delisted :delisted? :frozen :frozen?}]
  (defn- munge-currency [entry]
    (util/traduce
     (fn [acc k v]
       (assoc! acc (renames k k)
               (cond (= k :txFee) (util/str->number v)
                     (booleans k) (= v 1)
                     :else        v)))
     entry)))

(defn- munge-currencies [curs]
  (munge/->pair-map munge-currency curs))

(defn currencies!
  "Corresponds to `returnCurrencies`."
  [& [opts]]
  (public! "returnCurrencies" munge-currencies opts))

(util/with-doc-examples! currencies!
  [@(currencies!)
   {:PPC {:id           172
          :name         "Peercoin"
          :tx-fee       0.01M
          :min-conf     6
          :disabled?    false
          :delisted?    false
          :frozen?      false}
    :ENC ...}])

(let [renames {:rangeMin :range-min :rangeMax :range-max}]
  (defn- munge-loan-order [order]
    (util/traduce
     (fn [acc k v]
       (assoc! acc (renames k k)
               (cond-> v (#{:amount :rate} k) util/str->number)))
     order)))

(def ^:private munge-loan-orders (partial mapv munge-loan-order))

(defn loan-orders!
  "Takes a currency (e.g. `:btc`), and returns a map containing `:offers` and
  `:demands` sequences describing active loan orders.

  Corresponds to `returnLoanOrders`."
  [cur & [opts]]
  (public!
   "returnLoanOrders"
   (fn [orders]
     (-> orders
         (update :offers  munge-loan-orders)
         (update :demands munge-loan-orders)))
   opts
   {:currency (name (munge/->currency cur))}))
