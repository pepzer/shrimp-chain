;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp-chain.core
  (:require [redlobster.promise :as p]
            [shrimp.core :as sc])
  (:use-macros [redlobster.macros :only [when-realised let-realised]]))

(defprotocol IChain
  (-success [this result-id result])
  (-failure [this error-id error])
  (-update [this step-id]))

(defrecord ChainSignal
    [chain-id
     result-id
     result
     error-id
     error
     step-id])

(extend-protocol IChain
  ChainSignal
  (-success [this result-id result]
    (assoc this :result-id result-id :result result :step-id result-id))

  (-failure [this error-id error]
    (assoc this :error-id error-id :error error :step-id error-id))

  (-update [this step-id]
    (assoc this :step-id step-id)))

(defn- chain-wait
  ([chain-ch wait-fn] (chain-wait chain-ch :chain/wait wait-fn))
  ([chain-ch wait-id wait-fn]
   (let-realised [prom (sc/take! chain-ch)]
     (let [{:keys [error] :as signal} @prom]
       (if error
         (do
           (sc/put! chain-ch (-update signal wait-id))
           nil)
         (let [res-prom (p/promise)]
           (try
             (p/realise res-prom (wait-fn))
             (catch js/Object e
               (sc/try-realise res-prom {:chain/error e})))

           (when-realised [res-prom]
             (let [new-result @res-prom]

               (if (or (and (associative? new-result)
                            (:chain/error new-result))
                       (instance? js/Error new-result))
                 (sc/put! chain-ch (-failure signal wait-id new-result))
                 (sc/put! chain-ch (-success signal wait-id new-result)))
               new-result))))))))

(defn- chain-go
  ([chain-ch async-fn] (chain-go chain-ch :chain/go async-fn))
  ([chain-ch async-id async-fn]
   (let [async-ch (sc/chan)]
     (try
       (let-realised [async-prom (async-fn)]
         (sc/put! async-ch @async-prom))
       (catch js/Object e
         (sc/put! async-ch {:chain/error e})))

     (let-realised [alts-prom (sc/alts! [chain-ch async-ch])]
       (let [[alts-res alts-ch] @alts-prom]
         (cond
           (= alts-ch chain-ch)
           (let [signal alts-res]
             (if (:error signal)
               (do (sc/put! chain-ch (-update signal async-id))
                   (let-realised [res-prom (sc/take! async-ch)]
                     (sc/close! async-ch)
                     @res-prom))

               (let-realised [res-prom (sc/take! async-ch)]
                 (sc/close! async-ch)
                 (let [async-res @res-prom]
                   (if (or (and (associative? async-res)
                                (:chain/error async-res))
                           (instance? js/Error async-res))

                     (do
                       (sc/put! chain-ch (-failure signal async-id async-res))
                       async-res)

                     (do
                       (sc/put! chain-ch (-success signal async-id async-res))
                       async-res))))))

           (= alts-ch async-ch)
           (let-realised [chain-prom (sc/take! chain-ch)]
             (sc/close! async-ch)
             (let [signal @chain-prom]
               (if (:error signal)
                 (do
                   (sc/put! chain-ch (-update signal async-id))
                   alts-res)

                 (if (or (and (associative? alts-res)
                              (:chain/error alts-res))
                         (instance? js/Error alts-res))
                   (do
                     (sc/put! chain-ch (-failure signal async-id alts-res))
                     alts-res)

                   (do
                     (sc/put! chain-ch (-success signal async-id alts-res))
                     alts-res)))))
           :else
           (throw (js/Error. "Unknown channel!"))))))))

(defn- chain-fork
  ([chain-ch fork-fn] (chain-fork chain-ch :chain/fork fork-fn))
  ([chain-ch fork-id fork-fn]
   (let-realised [prom (sc/take! chain-ch)]
     (let [{:keys [error] :as signal} @prom]
       (when-not error
         (fork-fn))
       (do (sc/put! chain-ch (-update signal fork-id))
           (:result signal))))))

(defn- chain-end
  ([chain-ch handler] (chain-end chain-ch :chain/end handler))
  ([chain-ch handler-id handler]
   (let-realised [prom (sc/take! chain-ch)]
     (try
       (handler handler-id @prom)
       (catch js/Object e
         (throw (js/Error. "chain-end: error running the handler!")))))))

(defn init-chain!
  "Initialize the chain, create the channel and send the initial ChainSignal.

  For details on the arguments refer to the chain macro doc.
  "
  ([] (init-chain! :shrimp-chain nil nil))
  ([chain-id] (init-chain! chain-id nil nil))
  ([chain-id log-fn] (init-chain! chain-id log-fn nil))
  ([chain-id log-fn tran-fn]
   (let [transfer-fn (fn [value]
                       (and (fn? log-fn) (log-fn value))
                       (or (and (fn? tran-fn) (tran-fn value)) value))
         chain-ch (sc/chan 1024 transfer-fn)]
     (sc/put! chain-ch (map->ChainSignal {:chain-id chain-id
                                          :step-id :chain/init}))
     [chain-ch
      chain-wait
      chain-go
      chain-fork
      chain-end])))

(defn <-error
  "Extract the error field from the ChainSignal and return it.

  Could return nil if the chain terminated correctly.
  This handler is for use with the :chain/end directive, refer to the chain doc.
  "
  [_ {:keys [error]}]
  error)

(defn <-result
  "Extract the result from the ChainSignal and return it, or the error if any.

  This handler is for use with the :chain/end directive, refer to the chain doc.
  "
  [_ {:keys [error result] :as signal}]
  (or error result))
