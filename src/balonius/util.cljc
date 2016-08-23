(ns balonius.util
  (:require [clojure.string :as str]
            #? (:clj  [clojure.core.async.impl.protocols :as p]
                :cljs [cljs.core.async.impl.protocols :as p])))

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

(defn read-only-chan
  [read-ch write-ch & [{:keys [on-close close?] :or {close? true}}]]
  (reify
    p/ReadPort
    (take! [_ handler]
      (p/take! read-ch handler))

    p/Channel
    (close! [_]
      (when close?
        (p/close! read-ch))
      (when on-close
        (on-close)))))
