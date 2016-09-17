# balonius

[![Clojars Project](https://img.shields.io/clojars/v/io.nervous/balonius.svg)](https://clojars.org/io.nervous/balonius) [![Build Status](https://travis-ci.org/nervous-systems/balonius.svg?branch=master)](https://travis-ci.org/nervous-systems/balonius)

Balonius is a client for the [Poloniex cryptocurrency exchange](poloniex.com),
with support for its public, trading and market data APIs.  The non-streaming
portions of the client (i.e. `balonius.public`, `balonius.trade`) are available
to Clojurescript (Node, mostly, due to the absence of CORS headers on the remote
API).

In general, the API is asynchronous, using (derefable)
[promesa](https://github.com/funcool/promesa) promises for single deferred
results and [core.async](https://github.com/clojure/core.async) channels for
streams of values.

### [API Reference](https://nervous.io/doc/balonius/)

```clojure
@(balonius.trade/buy! {:pair [:btc :bcy] :amount 1 :rate 0.00056513M})
{:order-number 2566300282
 :trades
 ({:amount   0.67393284M
   :date     (inst "2016-09-10T...")
   :rate     0.00056367M
   :total    0.00037987M
   :trade-id 90269
   :type     :buy}
  {:amount   0.32606716M
   :date     (inst "2016-09-10T...")
   :rate     0.00056368M
   :total    0.00018379M
   :trade-id 90270
   :type     :buy})}
```

```clojure
(async/<!! (balonius.trade/ticker! conn))
 {:quote-vol 5550916.88164119M,
  :high-bid  0.00770008M,
  :base-vol  43773.79466888M,
  :low-24    0.00650005M,
  :low-ask   0.00772999M,
  :pair      [:BTC :XMR],
  :frozen?   false,
  :high-24   0.00938421M,
  :change    -0.13631396M,
  :last      0.00772999M}
```

```clojure
(async/<!! (balonius.trade/trollbox!
             conn {:chan (async/chan 1 (filter (comp pos? :reputation))}))
{:type      :trollbox-message,
 :id         9704960,
 :user       "LordBeer",
 :body       "lookup, i party too much on holidays...",
 :reputation 2406}
```

## License

balonius is free and unencumbered public domain software. For more
information, see http://unlicense.org/ or the accompanying UNLICENSE
file.
