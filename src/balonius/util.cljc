(ns ^:no-doc balonius.util
  (:require [clojure.string :as str]
            [clojure.core.async :as async]
            #? (:clj  [clojure.pprint :as pprint]
                :cljs [cljs.pprint :as pprint]))
  #? (:clj (:import [java.text SimpleDateFormat]
                    [java.util Date])))

(defn default-str->number [s]
  #? (:clj  (bigdec s)
      :cljs (js/parseFloat s)))

(def ^:dynamic *str->number* default-str->number)

(defn str->number [s]
  (*str->number* s))

(defn ->currency [x]
  (keyword (str/upper-case x)))

#? (:clj (def ^:private short-date-format (SimpleDateFormat. "yyyy-MM-dd HH:mm")))

(defn ->inst [x]
  #? (:clj  (if (= (count x) 19)
              (.parse short-date-format x))
      :cljs (-> x js/Date .getTime)))

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

(def ^:private tick-numerical?
  #{:last :low-ask :high-bid :change :base-vol :quote-vol :high-24 :low-24})

(defn tidy-tick [m str->number]
  (persistent!
   (reduce
    (fn [acc [k v]]
      (assoc! acc k (cond (tick-numerical? k) (str->number v)
                          (= k :frozen?)      (= v "1")
                          (= k :pair)         (->pair v))))
    (transient {})
    m)))

(defn pipe*
  "Always close the from channel when the to channel closes"
  ([from to] (pipe* from to true))
  ([from to close?]
   (async/go-loop []
      (let [v (async/<! from)]
        (if (nil? v)
          (when close? (async/close! to))
          (if (async/>! to v)
            (recur)
            (async/close! from)))))
   to))

(defn pprint-str [x]
  (str/trimr (with-out-str (pprint/pprint x))))

(defn doc-examples! [vvar examples]
  (alter-meta!
   vvar update :doc str
   "\n\n```clojure\n"
   (str/join
    "\n\n"
    (for [[before after] examples]
      (cond-> (pprint-str before)
        after (str "\n  =>\n" (pprint-str after)))))
   "\n```"))

(defn traduce [f m]
  (persistent!
   (reduce
    (fn [acc kv]
      (f acc (key kv) (val kv)))
    (transient {}) m)))

(defn map-vals [f m]
  (traduce
   (fn [acc k v]
     (assoc! acc k (f v)))
   m))

(defn separate [f m]
  (map
   persistent!
   (reduce
    (fn [acc [k v]]
      (update acc (if (f k) 0 1) assoc! k v))
    [(transient {}) (transient {})]
    m)))

(defn rename-keys [map kmap]
  (let [tmap (transient map)]
    (persistent!
     (reduce
      (fn [acc [old new]]
        (cond-> acc (contains? map old) (assoc! new (get map old))))
      (apply dissoc! tmap (keys kmap)) kmap))))

#? (:clj
    (defmacro with-doc-examples! [vvar & examples]
      `(doc-examples! #'~vvar (quote ~examples))))
