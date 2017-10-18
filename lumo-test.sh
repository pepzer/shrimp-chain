#!/bin/bash
lumo -D org.clojars.pepzer/redlobster:0.2.2,shrimp:0.1.0 -c src/cljs:src/cljc:test/cljs -K -m shrimp-chain.tests
