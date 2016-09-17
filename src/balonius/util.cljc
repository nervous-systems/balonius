(ns ^:no-doc balonius.util
  (:require [clojure.string :as str]
            [#? (:clj clojure.core.async :cljs cljs.core.async) :as async]
            [camel-snake-kebab.core :as csk]
            #? (:clj  [clojure.pprint :as pprint]
                :cljs [cljs.pprint :as pprint]))
  #? (:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]
                             [balonius.util])))

(defn default-str->number [s]
  #? (:clj  (bigdec s)
      :cljs (js/parseFloat s)))

(def ^:dynamic *str->number* default-str->number)

(defn str->number [s]
  (*str->number* s))

(def ->kebab (memoize csk/->kebab-case-keyword))

(defn pipe*
  "Always close the from channel when the to channel closes"
  ([from to] (pipe* from to true))
  ([from to close?]
   (#? (:clj async/go-loop :cljs go-loop) []
    (let [v (async/<! from)]
      (if (nil? v)
        (when close?
          (async/close! to))
        (if (async/>! to v)
          (recur)
          (async/close! from)))))
   to))

(defn traduce [f m]
  (persistent! (reduce-kv f (transient {}) m)))

#? (:clj
    (defmacro rewrite-map [m k-var v-var k-expr v-expr]
      `(traduce
        (fn [acc# ~k-var ~v-var]
          (assoc! acc# ~k-expr ~v-expr))
        ~m)))

(defn map-kv [kf vf m]
  (traduce
   (fn [acc k v]
     (assoc! acc (kf k) (vf v)))
   m))

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

(defn assoc-when [m & kvs]
  (persistent!
   (reduce
    (fn [acc [k v]]
      (cond-> acc (not (nil? v)) (assoc! k v)))
    (transient m)
    (partition 2 kvs))))

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

#? (:clj
    (defmacro with-doc-examples! [vvar & examples]
      `(doc-examples! #'~vvar (quote ~examples))))
