(ns balonius.core
  (:require [kvlt.core :as kvlt]
            [clojure.string :as str]
            [balonius.util :as util]
            [promesa.core :as p]
            [camel-snake-kebab.core :as csk]
            [#? (:clj  clojure.core.async
                 :cljs cljs.core.async) :as async]))

(def ^:private public-url "https://poloniex.com/public")

(defn- public! [command & [query]]
  (p/then (kvlt/request! {:url   public-url
                          :query (assoc query :command command)
                          :as    :json})
    (fn [{body :body :as resp}]
      (if-let [error (:error body)]
        (throw (ex-info error {:balonius/error    :poloniex
                               :balonius/response resp}))
        body))))

(def ^:private tick-renames
  {:lowestAsk     :low-ask
   :highestBid    :high-bid
   :percentChange :change
   :baseVolume    :base-vol
   :quoteVolume   :quote-vol
   :isFrozen      :frozen?
   :high24hr      :high-24
   :low24hr       :low-24})

(defn- munge-pairs [tidy-val m]
  (util/traduce
   (fn [acc pair v]
     (assoc! acc (util/->pair pair) (tidy-val v)))
   m))

(defn- munge-ticker [m str->number]
  (munge-pairs
   #(util/tidy-tick
     (util/rename-keys % tick-renames)
     str->number)
   m))

(defn ticker! [& [opts]]
  (p/then (kvlt/request!
           {:url   public-url
            :query {:command "returnTicker"}
            :as    :json})
    #(munge-ticker (:body %) (get opts :str->number util/default-str->number))))

(defn- numeric-vals [m str->number]
  (util/map-vals str->number m))

(defn- munge-totals [m str->number]
  (util/traduce
   (fn [acc k total]
     (let [curr (subs (name k) 5)]
       (assoc! acc (keyword curr) (str->number total))))
   m))

(defn- munge-volume [m str->number]
  (let [[totals pairs] (util/separate #(str/starts-with? (name %) "total") m)]
    {:pairs  (munge-pairs #(numeric-vals % str->number) pairs)
     :totals (munge-totals totals str->number)}))

(defn volume-24!
  "Corresponds to `return24hVolume`.  Response is a map with keys `:pairs` and
  `:totals`."
  [& [opts]]
  (p/then (kvlt/request!
           {:url   public-url
            :query {:command "return24hVolume"}
            :as    :json})
    #(munge-volume (:body %) (get opts :str->number util/default-str->number))))

(defn- ->timestamp [x]
  (let [x  (if (number? x) x (.getTime x))
        ts (/ x 1000.0)]
    #? (:clj  (int (Math/floor ts))
        :cljs (.floor js/Math ts))))

(let [renames {:globalTradeID :global-trade-id
               :tradeID        :trade-id}]
  (defn- munge-historic-trade [trade str->number]
    (util/traduce
     (fn [acc k v]
       (assoc! acc (renames k k)
               (case k
                 :date (util/->inst v)
                 :type (csk/->kebab-case-keyword v)
                 (cond-> v (string? v) str->number))))
     trade)))

(defn- munge-trade-history [trades str->number]
  (for [trade trades]
    (munge-historic-trade trade str->number)))

(defn trade-history!
  "Corresponds to `returnTradeHistory`.  Accepts a map containing a a
  pair (vector of keywords) and optional start and end timestamps. Returns a
  sequence of maps."
  [{:keys [pair start end]} & [opts]]
  (let [query (cond-> {:currencyPair (util/->pair pair)}
                start (assoc :start (->timestamp start))
                end   (assoc :end   (->timestamp end)))]
    (p/then (public! "returnTradeHistory" query)
      #(munge-trade-history % (get opts :str->number util/default-str->number)))))

(defn- munge-book-entry [entry str->number]
  (let [entry (util/rename-keys entry {:isFrozen :frozen? :seq :sequence})
        parse #(mapv (fn [v] (update v 0 str->number)) %)]
    (-> entry
        (update :frozen? #(= % "1"))
        (update :bids    parse)
        (update :asks    parse))))

(defn- munge-order-book [book pair str->number]
  (if pair
    (munge-book-entry book str->number)
    (munge-pairs #(munge-book-entry % str->number) book)))

(defn order-book!
  "Corresponds to `returnOrderBook`.  Takes an optional map having
  `:pair` (vector of keywords), and perhaps `:depth.`"
  [& [{:keys [pair depth]} opts]]
  (p/then (public! "returnOrderBook"
                   (cond-> {:currencyPair (if pair (util/->pair pair) "all")}
                     depth (assoc :depth depth)))
    #(munge-order-book % pair (get opts :str->number util/default-str->number))))
