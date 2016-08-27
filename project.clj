(defproject io.nervous/balonius "0.1.0-SNAPSHOT"
  :description "Clojure/script Poloniex (cryptocurrency exchange) client"
  :url "https://github.com/nervous-systems/balonius"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[org.clojure/clojure           "1.8.0"]
                 [org.clojure/clojurescript     "1.8.34"]
                 [org.clojure/core.async        "0.2.374"]
                 [ws.wamp.jawampa/jawampa-netty "0.4.2" :exclusions [[io.netty]]]
                 [io.nervous/kvlt               "0.1.3"]
                 [cheshire                      "5.6.3"]
                 [funcool/promesa               "1.1.1"]
                 [camel-snake-kebab             "0.4.0"]
                 [io.netty/netty-handler        "4.1.0.Final"]]
  :plugins [[lein-npm       "0.6.0"]
            [lein-codox     "0.9.4"]
            [lein-auto      "0.1.2"]
            [lein-cljsbuild "1.1.1-SNAPSHOT"]
            [lein-doo       "0.1.7-SNAPSHOT"]]
  :codox
  {:source-paths ["src"]
   :metadata     {:doc/format :markdown}
   :html         {:transforms ~(read-string (slurp "doc/assets/codox-transforms.edn"))}
   :source-uri   "https://github.com/nervous-systems/balonius/blob/master/{filepath}#L{line}"}
  :auto {"codox" {:file-pattern #"\.(clj[cs]?|md)$" :paths ["doc" "src"]}}
  :cljsbuild {:builds
              [{:id "node-test"
                :source-paths ["src" "test"]
                :compiler {:output-to     "target/node-test/test.js"
                           :output-dir    "target/node-test"
                           :target        :nodejs
                           :optimizations :none
                           :main          balonius.test.runner}}
               {:id "node-test-advanced"
                :source-paths ["src" "test"]
                :compiler {:output-to     "target/node-test-adv/test.js"
                           :output-dir    "target/node-test-adv"
                           :target        :nodejs
                           :optimizations :advanced
                           :main          balonius.test.runner}}
               {:id "generic-test"
                :source-paths ["src" "test"]
                :compiler {:output-to     "target/generic-test/test.js"
                           :optimizations :simple
                           :main          balonius.test.runner}}]})
