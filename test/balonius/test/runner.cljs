(ns balonius.test.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [balonius.test.platform.sign-test]
            [balonius.test.public-test]
            [balonius.test.trade-test]))

(doo-tests
 'balonius.test.platform.sign-test
 'balonius.test.public-test
 'balonius.test.trade-test)

