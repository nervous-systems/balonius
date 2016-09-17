(ns balonius.test.platform.sign-test
  (:require #? (:clj  [clojure.test :refer [deftest is]]
                :cljs [cljs.test :refer-macros [deftest is]])
               [balonius.platform.sign :as platform.sign]))

(deftest hmac-sha512
  (is (= (str "79b8a5d616238782c0deeda2c7e0970be36207219a205b2f1e5bd095523bf837"
              "338d543265b8f1c00c86a5cd5a45ac45d4c19c98efb5d821e000219d0b33a1df")
         (platform.sign/hmac-sha512 "ok" "key"))))
