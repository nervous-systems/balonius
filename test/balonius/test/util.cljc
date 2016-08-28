(ns balonius.test.util
  (:require [promesa.core :refer [IPromise]]
            [kvlt.core :as kvlt]))

(kvlt/quiet!)

(defn responding [resp]
  (fn [req]
    (reify IPromise
      (-map [_ cb]
        [req (cb resp)]))))

(defn with-response [f arg resp]
  (with-redefs [kvlt/request! (responding {:status 200
                                           :body   resp})]
    (let [[req resp] (f arg {:str->number (fn [x] [:balonius.test/number x])})]
      [(req :query) resp])))
