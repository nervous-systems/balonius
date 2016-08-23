(ns ^:no-doc balonius.util
  (:require [clojure.string :as str]
            [clojure.core.async :as async]
            #? (:clj  [clojure.pprint :as pprint]
                :cljs [cljs.pprint :as pprint])))

(defn default-str->number [s]
  #? (:clj  (bigdec s)
      :cljs (js/parseFloat s)))

(def ^:dynamic *str->number* default-str->number)

(defn str->number [s]
  (*str->number* s))

(defn ->currency [x]
  (keyword (str/upper-case x)))

(def ->pair
  (memoize
   (fn [x]
     (if (string? x)
       (let [[l r] (str/split x #"_")]
         [(->currency l) (->currency r)])
       (let [l (str/upper-case (name (first x)))
             r (str/upper-case (name (second x)))]
         (str l "_" r))))))

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

#? (:clj
    (defmacro with-doc-examples! [vvar & examples]
      `(doc-examples! #'~vvar (quote ~examples))))
