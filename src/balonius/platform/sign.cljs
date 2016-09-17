(ns balonius.platform.sign
  (:require [goog.crypt])
  (:import [goog.crypt Hmac Sha512]))

(defn hmac-sha512 [s k]
  (let [s (goog.crypt/stringToByteArray s)
        k (goog.crypt/stringToByteArray k)]
    (-> (Sha512.) (Hmac. k) (.getHmac s) goog.crypt/byteArrayToHex)))
