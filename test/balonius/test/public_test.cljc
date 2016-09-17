(ns balonius.test.public-test
  (:require #? (:clj  [clojure.test :refer [deftest is]]
                :cljs [cljs.test :refer-macros [deftest is]])
               [balonius.public :as public]
               [balonius.test.util :refer [with-response]]))

(deftest loan-orders
  (let [[query resp] (with-response public/loan-orders! :btc
                       {:offers  [{:rate     "0.002"
                                   :amount   "64.663"
                                   :rangeMin 2
                                   :rangeMax 8}]
                        :demands [{:rate     "0.0017"
                                   :amount   "26.64"
                                   :rangeMin 2
                                   :rangeMax 2}]})]
    (is (= query {:command "returnLoanOrders" :currency "BTC"}))
    (let [offer (-> resp :offers first)]
      (is (= 8 (offer :range-max)))
      (is (= [:balonius.test/number "0.002"] (offer :rate))))
    (let [demand (-> resp :demands first)]
      (is (= 2 (demand :range-min)))
      (is (= [:balonius.test/number "26.64"] (demand :amount))))))

(deftest trade-history
  (let [trade-date   15803000
        [query resp] (with-response public/trade-history!
                       {:pair  [:btc :ltc]
                        :start 3000
                        :end   #? (:clj  (java.util.Date. 1000)
                                   :cljs (js/Date. 1000))}
                       [{:date   "1970-01-01 04:23:23"
                         :type   "buy"
                         :rate   "0.007"
                         :amount "140"
                         :total  "0.01604"}])]
    (is (= query {:command      "returnTradeHistory"
                  :start        3
                  :end          1
                  :currencyPair "BTC_LTC"}))
    (let [[trade & trades] resp]
      (is (= trade-date (-> trade :date .getTime)))
      (is (= [:balonius.test/number "0.007"] (trade :rate)))
      (is (= :buy (trade :type)))
      (is (not trades)))))

(def book
  {:asks     [["0.007" 1.01]]
   :bids     [["0.01"  6.14]]
   :isFrozen "0"
   :seq      26})

(deftest order-book-pair
  (let [[query resp] (with-response public/order-book!
                       {:pair [:DOGE :eth] :depth 1}
                       book)]
    (is (= query {:command      "returnOrderBook"
                  :currencyPair "DOGE_ETH"
                  :depth        1}))
    (is (= [[[:balonius.test/number "0.007"] 1.01]] (resp :asks)))
    (is (not (resp :frozen? true)))))

(deftest order-book-all
  (let [[query resp] (with-response public/order-book! nil
                       {:BTC_NXT (assoc book :isFrozen "1")})]
    (is (= query {:command "returnOrderBook" :currencyPair "all"}))
    (let [book (resp [:BTC :NXT])]
      (is (book :frozen?)))))
