(ns ^:no-doc balonius.platform.sign
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [javax.xml.bind DatatypeConverter]))

(defn- bytes->hex-str [bytes]
  (.toLowerCase (DatatypeConverter/printHexBinary bytes)))

(let [HMAC-SHA512 "HmacSHA512"]
  (defn hmac-sha512 [s k]
    (let [spec (SecretKeySpec. (.getBytes k "UTF-8") HMAC-SHA512)
          mac  (doto (Mac/getInstance HMAC-SHA512)
                 (.init spec))]
      (-> mac
          (.doFinal (.getBytes s "UTF-8"))
          bytes->hex-str))))
