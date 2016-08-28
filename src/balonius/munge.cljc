(ns ^:no-doc balonius.munge
  (:require [balonius.util :as util]
            [clojure.string :as str])
  #? (:clj  (:import [java.text SimpleDateFormat]
                     [java.util Date TimeZone])
      :cljs (:require-macros [balonius.munge])))

(defn ->currency [x]
  (keyword (str/upper-case (name x))))

#? (:clj (def ^:private short-date-format
           (doto (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
             (.setTimeZone (TimeZone/getTimeZone "UTC")))))

(defn ->inst [x]
  (if (number? x)
    #? (:clj  (Date. (* x 1000)) :cljs (js/Date. (* x 1000)))
    #? (:clj  (.parse short-date-format x)
        :cljs (js/Date. x))))

(defn ->timestamp [x]
  (let [x  (if (number? x) x (.getTime x))
        ts (/ x 1000.0)]
    #? (:clj  (int ts)
        :cljs (.floor js/Math ts))))

(def ->pair
  (memoize
   (fn [x]
     (if (or (string? x) (keyword x))
       (let [[l r] (str/split (name x) #"_")]
         (if r
           [(->currency l) (->currency r)]
           x))
       (let [l (str/upper-case (name (first x)))
             r (str/upper-case (name (second x)))]
         (str l "_" r))))))

(let [numeric-ks #{:last :low-ask :high-bid :change :base-vol :quote-vol :high-24 :low-24}]
  (defn ->tick [m & [str->number*]]
    (let [str->number* (or str->number* util/str->number)]
      (util/rewrite-map m k v
        k (cond (numeric-ks k) (str->number* v)
                (= k :frozen?) (= v "1")
                (= k :pair)    (->pair v)
                :else          v)))))

(defn ->pair-map [tidy-val m]
  (util/rewrite-map m pair v
    (->pair pair) (tidy-val v)))

(defn fapply-pairs [f x]
  (if (map? x)
    (->pair-map #(mapv f %) x)
    (mapv f x)))

(let [renames {:currencyPair :pair}]
  (defn munge-trade [trade]
    (util/rewrite-map trade k v
      (util/->kebab (renames k k))
      (case k
        :date         (->inst  v)
        :type         (util/->kebab v)
        :category     (util/->kebab v)
        :currencyPair (->pair v)
        (cond-> v (string? v) util/str->number)))))

(defn number->str [x]
  #? (:clj  (if (instance? BigDecimal x) (.toPlainString x) (str x))
      :cljs (str x)))
