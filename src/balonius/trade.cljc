(ns balonius.trade
  "This namespace has a function per remote API method, barring the
  margin-specific operations, which aren't currently implemented.

  The first parameter of all functions is a `creds` record having keys `:key` &
  `:secret`, and providing an implementation of [[Credentials]].

  The constraints of the remote API are such that the nonce can't be reset or
  read (at least not machine-readably), and is expected to monotonically
  increase across requests (requiring, e.g. some kind of coordination between
  concurrent instances of an application).  Accordingly, managing this process
  is entirely the responsibility of the user.

  A trivial example:

```clojure
(defrecord Creds [key secret nonce-atom]
  balonius.trade/Credentials
  (increment-nonce [_]
    (swap! nonce-atom inc)))

(defonce creds (->Creds \"...\" \"...\" (atom <initial-value>)))
```

  The final optional argument of all functions is an `opts` map, which may
  contain a function under `:str->number`.  Poloniex represents some numerical
  values as Number literals and others as Strings.  By default, Strings under
  numerical keys will be parsed with `bigdec` (JVM) or `js/parseFloat` (cljs).
  This behaviour may be undesireable, and can be overriden by passing an
  alternate function (e.g. `identity`).  Numbers which appear as literals in the
  JSON API responses (e.g. order identifiers) will be have their representation
  dictated by the JSON parser.

  Outgoing amounts & rates will be stringified before converting to JSON."
  (:require [kvlt.core :as kvlt]
            [kvlt.middleware.params :refer [query-string]]
            [clojure.string :as str]
            [balonius.util :as util]
            [balonius.munge :as munge]
            [balonius.platform.sign :as sign]
            [promesa.core :as p]
            [camel-snake-kebab.core :as csk]))

(def ^:private url "https://poloniex.com/tradingApi")

(defprotocol Credentials
  (increment-nonce [_]
    "Increment the value of a nonce and return its current value"))

(defn- trade!
  [{:keys [cmd creds opts body]} & [cont]]
  (let [body    (query-string (assoc body
                                :command cmd
                                :nonce   (increment-nonce creds)))
        headers {:Key  (:key creds)
                 :Sign (sign/hmac-sha512 body (:secret creds))}]
    (p/then
      (kvlt/request! {:url     url
                      :method  :post
                      :body    body
                      :type    :application/x-www-form-urlencoded
                      :headers headers
                      :as      :json})
      (fn [{body :body :as resp}]
        (if-let [error (:error body)]
          (throw (ex-info error {:balonius/error    :poloniex
                                 :balonius/response resp}))
          (if-not cont
            body
            (binding [util/*str->number* (get opts :str->number util/default-str->number)]
              (cont body))))))))

(defn- munge-balances [m]
  (util/map-vals util/str->number m))

(defn balances!
  "`returnBalances`."
  [creds & [opts]]
  (trade!
   {:cmd "returnBalances" :creds creds :opts opts}
   munge-balances))

(util/with-doc-examples! balances!
  [@(balances! creds)
   {:BTC   1.01890086M
    :PIGGY 0E-8M
    :ETC   ...}])

(defn account-balances!
  "Balances by account, defaulting to all. `:account` may be
  specified (e.g. `:margin`, `:exchange`, etc.)

  Corresponds to `returnAvailableAccountBalances`."
  [creds & [{:keys [account]} opts]]
  (let [body (when account {:account (name account)})]
    (trade!
     {:cmd  "returnAvailableAccountBalances"
      :creds creds
      :opts opts
      :body body}
     (fn [resp]
       (let [m (util/map-vals munge-balances resp)]
         (cond-> m
           (and account (not= (name account) "all"))
           (-> vals first)))))))

(util/with-doc-examples! account-balances!
  [@(balonius.trade/account-balances! creds)
   {:exchange {:BCY 3.99000001M
               :ETH 2.80805683M}
    :margin   ...}]
  [@(balonius.trade/account-balances! creds {:account :exchange})
   {:BCY 3.99000001M :ETH 2.80805683M}])

(defn- munge-complete-balance-entry [entry]
  (util/traduce
   (fn [acc k v]
     (assoc! acc (util/->kebab k) (util/str->number v)))
   entry))

(defn- munge-complete-balances [balances]
  (util/map-vals munge-complete-balance-entry balances))

(defn complete-balances!
  "Return per-currency balance information for some account.

  Defaults remotely to the exchange account.  This behaviour can be modulated by
  specifying an `:account` name (e.g. `:exchange`, `:margin`, `:lending`,
  `:all`), or passing `:all?` (identical to `{:account :all}`.

  Corresponds to `returnCompleteBalances`."
  [creds & [{:keys [all? account]} opts]]
  (let [body (cond all?    {:account "all"}
                   account {:account (name account)})]
    (trade! {:cmd  "returnCompleteBalances"
             :creds creds
             :opts opts
             :body body} munge-complete-balances)))

(defn deposit-addresses!
  "`returnDepositAddresses`."
  [creds & [opts]]
  (trade! {:cmd "returnDepositAddresses" :creds creds :opts opts}))

(util/with-doc-examples! deposit-addresses!
  [@(deposit-addresses! creds)
   {:NEOS  "NY7w4WdsEU5zjUiymE...",
    :RDD   "RrHZnx66uiMnYYH18kz...",
    :BTC   "1HHXk6rDPqfg16nEA5t...",
    :ETH   "0x2b05b6a4ba4500353...",
    :STEEM "3046b305577c88f6",
    :ETC   "0xf0bfe77452896f719...",
    :XMR   "4aeb82ba84705e1ab71...",
    :DOGE  "DEqKsA1j8magrnfohd...",
    :BCY   "1G7cAg8KfyQ6eEwXPsB...",
    :BTCD  "RBW5AwPEsDRPPXh29o..."}])

(defn new-deposit-address!
  "Generates a new deposit address for a specific currency.  There are
  restrictions around how often this operation may be performed.

  Takes a currency symbol and returns either a String address, or throws an
  `ExceptionInfo` instance with the remote message & response body.

  Corresponds to `generateNewAddress`."
  [creds currency & [opts]]
  (trade!
   {:cmd "generateNewAddress"
    :creds creds
    :opts opts
    :body {:currency (name (munge/->currency currency))}}
   (fn [{:keys [response] :as body}]
     (if (zero? (body :success))
       (throw (ex-info response {:balonius/error    :poloniex
                                 :balonius/response body}))
       response))))

(defn- split-status [s]
  (let [[status r] (str/split s #":\s+" 2)]
    (cond-> [(util/->kebab status)] r (conj r))))

(defn- munge-history [m]
  (util/traduce
   (fn [acc k v]
     (let [v (case k
               :timestamp (munge/->inst v)
               :currency  (munge/->currency v)
               :amount    (util/str->number v)
               :status    (split-status v)
               v)]
       (assoc! acc (util/->kebab k) v)))
   m))

(def ^:private munge-histories (partial mapv munge-history))

(defn history!
  "Retrieve this account's deposit and withdrawal history within the given time
  range.  Returns a map containing sequences under `:deposits` and
  `:widthdrawals`.

  Corresponds to `returnDepositsWithdrawals`."
  [creds {:keys [start end]} & [opts]]
  (trade!
   {:cmd "returnDepositsWithdrawals"
    :creds creds
    :opts opts
    :body {:start (munge/->timestamp start)
           :end   (munge/->timestamp end)}}
   (fn [m]
     (-> m
         (update :deposits    munge-histories)
         (update :withdrawals munge-histories)))))

(util/with-doc-examples! history!
  [@(history! creds {:start 0 :end (System/currentTimeMillis)})
   {:deposits [{:currency :BTC,
                :address "1HHXk6rDPqfg16nEA5thkoKEKgfoifkRXW",
                :amount   0.00556000M,
                :confirmations 1,
                :txid      "a80f4ab8353c83609...",
                :timestamp (inst "2016-09-06T11:39:18.000-00:00"),
                :status    [:complete]}
               ...]
    :withdrawals [{:withdrawal-number 1824746,
                   :currency          :STEEM,
                   :address           "shapeshiftio",
                   :amount            22.37766169M,
                   :timestamp  (inst "2016-09-11T22:05:06.000-00:00"),
                   :status     [:complete "6f5b855ee3..."],
                   :ip-address "86.143.90.2"}
                  ...]}])

(let [numeric #{:rate :amount :total}]
  (defn- munge-open-order [m]
    (util/traduce
     (fn [acc k v]
       (assoc! acc
               (util/->kebab k)
               (cond (numeric k) (util/str->number v)
                     (= :type k) (util/->kebab v)
                     :else       v)))
     m)))

(defn open-orders!
  "If `:pair` is specified, retrieve open orders within that market (sequence of
  maps), else retrieve open orders across all markets (mapping between pairs and
  sequences of maps).

  Corresponds to `returnOpenOrders`."
  [creds & [{:keys [pair] :or {pair "all"}} opts]]
  (let [body {:currencyPair pair}]
    (trade!
     {:cmd "returnOpenOrders"
      :creds creds
      :opts opts
      :body body}
     (partial munge/fapply-pairs munge-open-order))))

(defn- munge-trade-history [trades]
  (munge/fapply-pairs munge/munge-trade trades))

(defn trade-history!
  "If `:pair` is specified, retrieve trade history within that market (sequence
  of maps), else retrieve trade history across all markets (mapping between
  pairs and sequences of maps).  One or more of `:start` or `:end` (epoch msecs
  or Date) may be supplied to constrain the results, otherwise the API defaults
  to one day's worth.

  Corresponds to `returnTradeHistory`."
  [creds & [{:keys [pair start end] :or {pair "all"}} opts]]
  (let [body (util/assoc-when {:currencyPair pair}
               :start (some-> start munge/->timestamp)
               :end   (some-> end   munge/->timestamp))]
    (trade!
     {:cmd  "returnTradeHistory"
      :creds creds
      :opts opts
      :body body}
     (partial munge/fapply-pairs munge/munge-trade))))

(util/with-doc-examples! trade-history!
  [@(trade-history! creds {:start 0})
   {[:ETH :STEEM]
    [{:category :exchange
      :amount   15.76489881M
      :fee      0.00250000M
      :date     (inst "2016-09-11T21:01:00.000-00:00")
      :trade-id 13963M
      :order-number 9781080298M
      :type  :buy
      :rate  0.05532985M
      :total 0.87226948M
      :global-trade-id 54719106}
     ...]}])

(defn order-trades!
  "Retrieve trades for the given order number.

  Corresponds to `returnOrderTrades`."
  [creds order & [opts]]
  (trade!
   {:cmd "returnOrderTrades"
    :creds creds
    :opts opts
    :body {:orderNumber order}}
   (partial mapv munge/munge-trade)))

(util/with-doc-examples! order-trades!
  [@(order-trades! creds 9781080298M)
   [{:amount          15.76489881M
     :fee             0.00250000M
     :date            (inst "2016-09-11T21:01:00.000-00:00")
     :trade-id        13963
     :type            :buy
     :rate            0.05532985M
     :total           0.87226948M
     :global-trade-id 54719106
     :pair            [:ETH :STEEM]} ...]])

(let [renames {:resultingTrades :trades}]
  (defn- munge-order [order]
    (util/rewrite-map order k v
      (util/->kebab (renames k k))
      (case k
        :resultingTrades (mapv munge/munge-trade v)
        :orderNumber     (util/str->number v)
        :amountUnfilled  (util/str->number v)
        v))))

(defn- buy-sell [t creds {:keys [pair rate amount constraint] :as args} opts]
  (let [body (cond-> {:currencyPair (munge/->pair pair)
                      :rate         (munge/number->str rate)
                      :amount       (munge/number->str amount)}
               constraint (assoc (csk/->camelCase constraint) 1))]
    (trade!
     {:cmd  (name t)
      :creds creds
      :opts opts
      :body body}
     munge-order)))

(defn buy!
  "Place a buy order for the given `:pair`, `:rate` & `:amount`.

  `:constraint`, if supplied, may be one of `:fill-or-kill`,
  `:immediate-or-cancel` or `:post-only`.

  Corresponds to `buy`."
  [creds {:keys [pair rate amount constraint] :as args} & [opts]]
  (buy-sell :buy creds args opts))

(util/with-doc-examples! buy!
  [@(buy! {:pair [:btc :bcy] :amount 1 :rate 0.00056513M})
   {:order-number 2566300282M
    :trades
    ({:amount   0.67393284M,
      :date     (inst "2016-09-10T..."),
      :rate     0.00056367M,
      :total    0.00037987M,
      :trade-id 90269M,
      :type     :buy}
     {:amount   0.32606716M,
      :date     (inst "2016-09-10T..."),
      :rate     0.00056368M,
      :total    0.00018379M,
      :trade-id 90270M,
      :type     :buy})}])

(defn sell!
  "Place a buy order for the given `:pair`, `:rate` & `:amount`.

  `:constraint`, if supplied, may be one of `:fill-or-kill`,
  `:immediate-or-cancel` or `:post-only`.

  Corresponds to `sell`."
  [creds {:keys [pair rate amount constraint] :as args} & [opts]]
  (buy-sell :sell creds args opts))

(util/with-doc-examples! sell!
  [@(sell! {:pair [:btc :doge] :amount 6911.08839865M :rate 3.6E-7})
   {:order-number 3468692862M,
    :trades
    ({:amount   6911.08839865M,
      :date     (inst "2016-09-10T..."),
      :rate     3.6E-7M,
      :total    0.00248799M,
      :trade-id 896278M,
      :type     :sell}),
    :amount-unfilled 0M}])

(defn- remote-error [resp]
  (throw (ex-info (resp :response "Unknown") {:balonius/error    :poloniex
                                              :balonius/response resp})))

(defn cancel-order!
  "Cancel the order w/ the given number.  Returns nothing in the event of success.

  Corresponds to `cancelOrder`."
  [creds order & [opts]]
  (trade!
   {:cmd  "cancelOrder"
    :creds creds
    :opts opts
    :body {:orderNumber order}}
   (fn [resp]
     (when (zero? (resp :success))
       (remote-error resp)))))

(defn move-order!
  "Move the given `:order` to `:rate`, optionally changing the `:amount`.

  Corresponds to `moveOrder`."
  [creds {:keys [order rate amount]} & [opts]]
  (let [body (util/assoc-when {:order order :rate (munge/number->str rate)}
               :amount (some-> amount munge/number->str))]
    (trade!
     {:cmd  "moveOrder"
      :creds creds
      :opts opts
      :body body}
     (fn [resp]
       (if (zero? (resp :success))
         (remote-error resp)
         (munge-order (dissoc resp :success)))))))

(defn withdraw!
  "Withdraw `:amount` in `:currency` to `:address`.  For XMR, `:payment-id` may
  be specified.  Returns the string API response on success.

  Corresponds to `withdraw`."
  [creds {:keys [amount currency address payment-id]} & [opts]]
  (let [body (util/assoc-when {:amount   (munge/number->str amount)
                               :currency (name (munge/->currency currency))
                               :address  address}
               :paymentId payment-id)]
    (trade!
     {:cmd  "withdraw"
      :creds creds
      :opts opts
      :body body}
     :response)))

(util/with-doc-examples! withdraw!
  [@(withdraw! creds {:amount   0.00266609M
                      :currency :btc
                      :address  "182mu7gfGgUns1mjW9P66GKkZEwZ6RzVT"})
   "Withdrew 0.00266609 BTC."])

(defn transfer!
  "Transfer `:amount` in `:currency` from account `:from` (e.g. `:lending`) to
  account to `:to` (`:margin`).  On success, returns API's String response.

  Corresponds to `transferBalance`."
  [creds {:keys [amount currency from to]} & [opts]]
  (let [body {:amount      (munge/number->str amount)
              :currency    (name (munge/->currency currency))
              :fromAccount (name from)
              :toAccount   (name to)}]
    (trade!
     {:cmd  "transferBalance"
      :creds creds
      :opts  opts
      :body  body}
     (fn [resp]
       (if (zero? (resp :success))
         (remote-error resp)
         (resp :message))))))
