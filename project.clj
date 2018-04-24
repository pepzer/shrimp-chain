(defproject shrimp-chain "0.1.2-SNAPSHOT"
  :description "A ClojureScript library built on top of shrimp providing macros to chain async functions."
  :url "https://github.com/pepzer/shrimp-chain"
  :license {:name "Mozilla Public License Version 2.0"
            :url "http://mozilla.org/MPL/2.0/"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojars.pepzer/redlobster "0.2.2"]
                 [shrimp "0.1.0"]]

  :plugins [[lein-figwheel "0.5.13"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]]

  :clean-targets ^{:protect false} ["target"]

  :source-paths ["src/cljc" "src/cljs" "test/cljs"]

  :cljsbuild {
              :builds [{:id "dev"
                        :source-paths ["src/cljc" "src/cljs"]
                        :figwheel true
                        :compiler {:main shrimp-chain.dev
                                   :output-to "target/out/shrimp-chain.js"
                                   :output-dir "target/out"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}
                       {:id "test-all"
                        :source-paths ["src/cljc" "src/cljs" "test/cljs"]
                        :compiler {:main shrimp-chain.tests
                                   :output-to "target/out-test/shrimp-chain.js"
                                   :output-dir "target/out-test"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}
                       #_{:id "prod"
                        :source-paths ["src/clj" "src/cljs"]
                        :compiler {:output-to "target/out-rel/shrimp-chain.js"
                                   :output-dir "target/out-rel"
                                   :target :nodejs
                                   :optimizations :advanced
                                   :source-map false }}]}

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/clojure "1.9.0-beta1"]
                                  [org.clojure/clojurescript "1.9.946"]]}}
  :figwheel {})

