(ns shrimp-chain.dev
  (:require [shrimp-chain.core :as core]
            [figwheel.client :as fw]))

(defn -main []
  (fw/start { }))

(set! *main-cli-fn* -main)
