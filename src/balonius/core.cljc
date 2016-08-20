(ns balonius.core
  (:require [kvlt.core :as kvlt]
            [#? (:clj  clojure.core.async
                 :cljs cljs.core.async) :as async]))

(def ^:private ticker-url "ws://api.poloniex.com")

(defn ticker! []
  (kvlt/websocket! ticker-url))
