(ns ^:no-doc balonius.wamp
  (:require [balonius.wamp.proto :as wamp.proto]
            [balonius.platform.wamp :as platform.wamp]
            [#? (:clj clojure.core.async :cljs cljs.core.async) :as async]))

(defn subscribe! [conn topic & [chan]]
  (wamp.proto/-subscribe! conn topic (or chan (async/chan))))

(defn connect! [{:keys [uri realm] :as opts}]
  (wamp.proto/connect! (platform.wamp/connection opts)))
