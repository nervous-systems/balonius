(ns balonius.stream
  (:require [balonius.wamp :as wamp]
            [balonius.util :as util]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk.extras]
            [#? (:clj  clojure.core.async
                 :cljs cljs.core.async) :as async]))

(defn- subscription! [conn topic f
                      & [{:keys [chan str->number]
                          :or {str->number util/default-str->number}}]]
  (let [inner-ch (async/chan 1 (map #(f % str->number)))]
    (wamp/subscribe! conn topic inner-ch)
    (cond-> inner-ch chan (async/pipe chan))))

(def ^:private ticker-cols
  [:pair :last :low-ask :high-bid :change :base-vol :quote-vol :frozen? :high-24 :low-24])

(def ^:private number-col?
  (disj (into #{} ticker-cols)
        :frozen? :pair))

(defn- ticker-frame->map [{args :args} str->number]
  (persistent!
   (reduce
    (fn [acc [k v]]
      (assoc! acc k (cond (number-col? k) (str->number v)
                          (= k :frozen?)  (= v "1")
                          (= k :pair)     (util/->pair v))))
    (transient {})
    (zipmap ticker-cols args))))

(defn- trollbox-frame->map [{args :args} _]
  (zipmap [:type :id :user :body :reputation] args))

(defn connect! [& [opts]]
  (wamp/connect!
   (merge {:uri   "wss://api.poloniex.com"
           :realm "realm1"} opts)))

#? (:clj
    (defn connect!! [& [opts]]
      @(connect! opts)))

(defn ticker! [conn & [opts]]
  (subscription! conn "ticker" ticker-frame->map opts))

(defn trollbox! [conn & [opts]]
  (subscription! conn "trollbox" trollbox-frame->map opts))

(defn- flatten-orders [{:keys [args kw]} str->number]
  (let [sn (get kw "seq")]
    (for [order args]
      (assoc (into {} order) :sequence-number sn))))

(defn- order->map [order str->number]
  (let [out {:message (csk/->kebab-case-keyword (order "type"))}]
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
           v)))
      (transient out)
      (order "data")))))

(defn follow! [conn pair
               & [{out-ch      :chan
                   str->number :str->number :as opts
                   :or {str->number util/default-str->number}}]]
  (let [pair (util/->pair pair)
        opts (assoc opts :chan
               (async/chan
                1 (comp
                   (mapcat #(flatten-orders % str->number))
                   (map    #(order->map % str->number)))))
        chan (subscription! conn pair (fn [o _] o) opts)]
    (cond-> chan out-ch (async/pipe out-ch))))
