(ns balonius.test.trade-test
  (:require #? (:clj  [clojure.test :refer [deftest is testing]]
                :cljs [cljs.test :refer-macros [deftest is testing]])
               [balonius.trade :as trade]
               [balonius.platform.sign :as platform.sign]
               [balonius.test.util :as test.util]
               [clojure.string :as str]))

(defn unquery [s]
  (into {}
    (for [pair (str/split s #"&")
          :let [[k v] (str/split pair #"=")
                k     (keyword k)]]
      [k v])))

(def nonce (atom 71))

(defrecord Creds [key secret]
  trade/Credentials
  (increment-nonce [_]
    (swap! nonce inc)))

(def creds (->Creds (str ::key) (str ::secret)))

(defn with-response [f arg resp]
  (with-redefs [kvlt.core/request! (test.util/responding {:status 200
                                                          :body   resp})]
    (f creds arg {:str->number
                  (fn [x] [::number x])})))

(deftest auth
  (dotimes [_ 2]
    (let [[req] (with-response trade/balances! nil {})]
      (is (str/includes? (req :body) (str "nonce=" @nonce)))
      (is (= (str ::key) (some (req :headers) #{:key :Key})))
      (let [sig (some (req :headers) #{:sign :Sign})]
        (is (= sig (platform.sign/hmac-sha512 (req :body) (str ::secret))))))))

(def trade
  {:amount   "338.8732"
   :date     "2014-10-18 23:03:21"
   :rate     "0.00000173"
   :total    "0.00058625"
   :tradeID  "16164"
   :type     "sell"})

(def order
  {:pair       [:btc :doge]
   :amount     "691.08"
   :rate       0.36
   :constraint :fill-or-kill})

(deftest sell!
  (let [[req resp] (with-response trade/sell! order
                     {:orderNumber (str 0xFAFF)
                      :resultingTrades [trade]})]
    (testing "request"
      (let [query (unquery (req :body))]
        (is (= query
               {:currencyPair (balonius.munge/->pair [:btc :doge])
                :rate         (str (order :rate))
                :amount       (str (order :amount))
                :fillOrKill   "1"
                :command      "sell"
                :nonce        (str @nonce)}))))
    (testing "response"
      (is (= (resp :order-number) [::number (str 0xFAFF)]))
      (let [[trade' & trades] (resp :trades)]
        (doseq [k [:rate :total :amount]]
          (is (= (trade' k) [::number (trade k)])))
        (is (= (trade' :trade-id) [::number (trade :tradeID)]))
        (is (= (trade' :type)     :sell))
        (is (= (trade' :date)     (balonius.munge/->inst (trade :date))))
        (is (not trades))))))
