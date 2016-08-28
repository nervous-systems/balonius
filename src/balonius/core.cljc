(ns balonius.core
  (:require [balonius.munge :as munge]
            [clojure.string :as str]))

(defn upcase-pair
  "Uppercase both keywords in vector `v`.  This is performed by all operations
  which accept pairs, however may be useful when looking up pairs as map keys."
  [v]
  [(keyword (str/upper-case (name (first v))))
   (keyword (str/upper-case (name (second v))))])

