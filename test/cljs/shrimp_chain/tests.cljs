(ns shrimp-chain.tests
  (:require [shrimp-chain.core-test]
            [cljs.nodejs :as nodejs])
  (:use-macros [shrimp.test.macros :only [run-async-tests]]))

(nodejs/enable-util-print!)

(defn -main 
  [& args]
(run-async-tests
 shrimp-chain.core-test))

(set! *main-cli-fn* -main)
