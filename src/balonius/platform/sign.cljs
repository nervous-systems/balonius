(ns balonius.platform.sign
  (:require [cljs.nodejs :as nodejs]))

(let [crypto (nodejs/require "crypto")]
  (defn hmac-sha512 [s k]
    (let [mac (.createHmac crypto "sha512" k)]
      (.update mac (js/Buffer. s "utf8"))
      (.digest mac "hex"))))
