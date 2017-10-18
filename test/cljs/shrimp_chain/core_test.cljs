;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp-chain.core-test
  (:require [shrimp.test :as st]
            [shrimp-chain.core]
            [clojure.string :as cs]
            [cljs.test :refer [deftest run-tests is testing]])
  (:use-macros [shrimp-chain.macros :only [chain chain-node]]
               [redlobster.macros :only [when-realised]]))

(defonce cproc (js/require "child_process"))

(defn- sh-echo [cmd]
  (chain-node (.exec cproc (str "echo '" cmd "'")) str))

(defn- sh-echo-sync [cmd]
  (str (.execSync cproc (str "echo '" cmd "'"))))

(defn- sh-exit1 []
  (chain-node (.exec cproc "exit 1")))

(deftest test-let
  (testing "chain-let"
    (let [cmd "ls"
          ls (sh-echo-sync cmd)
          lines (cs/split-lines ls)
          result (first lines)]
      (chain []
             (let [c-ls (sh-echo cmd)
                   c-lines (cs/split-lines c-ls)
                   c-result (first c-lines)]
               (is (= result c-result) "chain-let sh")
               (st/done! 'test-let))))))

(deftest test-do
  (testing "chain-do"
    (let [cmd "ls"
          result (do :foo
                     nil
                     (sh-echo-sync cmd))
          c-result (chain []
                          (do :foo
                              nil
                              (sh-echo cmd)))]
      (when-realised [c-result]
        (is (= result @c-result) "chain-do sh")
        (st/done! 'test-do)))))

(deftest test-->
  (testing "chain-->"
    (let [cmd "foobar"
          result (-> cmd
                     sh-echo-sync
                     keyword)
          c-result (chain []
                          (-> cmd
                              sh-echo
                              keyword))]
      (when-realised [c-result]
        (is (= result @c-result) "chain--> sh")
        (let [cmd "foobar2"
              result (-> cmd
                         (str "bar")
                         (sh-echo-sync)
                         (keyword))
              c-result (chain []
                              (-> cmd
                                  (str "bar")
                                  (sh-echo)
                                  (keyword)))]
          (when-realised [c-result]
            (is (= result @c-result) "chain--> sh paren")
            (st/done! 'test-->)))))))

(deftest test-->>
  (testing "chain-->>"
    (let [cmd "foobar"
          result (->> cmd
                     (str "echo ")
                     (sh-echo-sync)
                     vec
                     (map cs/upper-case)
                     (map keyword))
          c-result (chain []
                          (->> cmd
                               (str "echo ")
                               sh-echo
                               vec
                               (map cs/upper-case)
                               (map keyword)))]
      (when-realised [c-result]
        (is (= result @c-result) "chain-->> sh")
        (st/done! 'test->>)))))

(deftest test-cond->
  (testing "chain-cond->"
    (let [expr (range 10)
          result (cond-> expr
                   :always (list 'echo)
                   :always reverse
                   (vector? expr) (throw (js/Error. "test-cond->"))
                   :always pr-str
                   :always (cs/replace #"\(|\)" "")
                   :always sh-echo-sync)

          c-result (chain []
                          (cond-> expr
                            :always (list 'echo)
                            :always reverse
                            (vector? expr) (throw (js/Error. "test-cond->"))
                            :always pr-str
                            :always (cs/replace #"\(|\)" "")
                            :always sh-echo))]
      (when-realised [c-result]
        (is (= result @c-result) "chain-cond-> sh")
        (st/done! 'test-cond->)))))

(deftest test-cond->>
  (testing "chain-cond->>"
    (let [expr (range 10)
          result (cond->> expr
                   :always (take 5)
                   :always (cons "echo ")
                   nil reverse
                   :always (apply str)
                   (vector? expr) (throw (js/Error. "test-cond->>"))
                   true sh-echo-sync)
          c-result (chain []
                          (cond->> expr
                            :always (take 5)
                            :always (cons "echo ")
                            nil reverse
                            :always (apply str)
                            (vector? expr) (throw (js/Error. "test-cond->>"))
                            true sh-echo-sync))]
      (when-realised [c-result]
        (is (= result @c-result) "chain-cond->> sh")
        (st/done! 'test-cond->>)))))

(deftest test-composition
  (testing "composition"
    (let [cmd1 "foobar"
          cmd2 "ls"
          cmd3 "foo2"
          result (let [res1 (sh-echo-sync cmd1)
                       res2 (sh-echo-sync cmd2)
                       res3 (->> res1
                                 (str cmd3)
                                 sh-echo-sync)]
                   (cond->> res1
                     (> (count res2)
                        (count res3)) cs/upper-case
                     (< (count res1)
                        (count res3)) (str cmd3 cmd2)
                     :always sh-echo-sync))
          c-result (chain []
                          (let [res1 (sh-echo cmd1)
                                res2 (sh-echo cmd2)
                                res3 (chain []
                                            (->> res1
                                                 (str cmd3)
                                                 sh-echo))]
                            (chain []
                                   (cond->> res1
                                     (> (count res2)
                                        (count res3)) cs/upper-case
                                     (< (count res1)
                                        (count res3)) (str cmd3 cmd2)
                                     :always sh-echo))))]
      (when-realised [c-result]
        (is (= result @c-result) "composition")
        (st/done! 'test-composition)))))

(deftest test-execution-log
  (let [cmd1 "foobar"
        cmd2 "ls"
        cmd3 "foo2"
        result (let [res1 (sh-echo-sync cmd1)
                     res2 (sh-echo-sync cmd2)
                     res3 (->> res1
                               (str cmd3)
                               sh-echo-sync)]
                 (cond->> res1
                   (> (count res2)
                      (count res3)) cs/upper-case
                   (< (count res1)
                      (count res3)) (str cmd3 cmd2)
                   :always sh-echo-sync
                   :always (list res1 res3)))
        exec-log (atom [])
        exec-target [:exec-cmd2 :chain/init :exec-cmd1
                     :cmd1 :cmd2 :exec-fork :fork
                     "foo2foobar\n\n" :->>]
        signal (atom nil)
        signal-target {:chain-id :let
                       :result-id :->>
                       :result "foo2foobar\n\n"
                       :error-id nil
                       :error nil
                       :step-id :->>}
        c-result (chain [:let #(swap! exec-log conj
                                      (:step-id %))]
                        (let [res1 (:chain/wait :cmd1 (do (swap! exec-log
                                                                 conj :exec-cmd1)
                                                          (sh-echo cmd1)))
                              res2 (:chain/go :cmd2 (do (swap! exec-log
                                                               conj :exec-cmd2)
                                                        (sh-echo cmd2)))
                              _ (:chain/fork :fork (do (sh-echo cmd2)
                                                       (swap! exec-log
                                                              conj :exec-fork)))
                              res3 (:chain/wait :->>
                                                (chain []
                                                       (->> res1
                                                            (str cmd3)
                                                            sh-echo
                                                            (:chain/end
                                                             (fn [_ {:keys [result]}]
                                                               (swap! exec-log
                                                                      conj result)
                                                               result)))))
                              signal-let (:chain/end (fn [_ s] s))]
                          (reset! signal signal-let)
                          (chain []
                                 (cond->> res1
                                   (> (count res2)
                                      (count res3)) cs/upper-case
                                   (< (count res1)
                                      (count res3)) (str cmd3 cmd2)
                                   :always sh-echo
                                   :always (list res1 res3)))))]
    (when-realised [c-result]
      (is (= signal-target (into {} @signal))
          "test-log chain signal")
      (is (= exec-target @exec-log)
          "test-log execution log")
      (is (= result @c-result)
          "test-log result")
      (st/done! 'test-execution-log))))

(deftest test-abort-chain
  (let [cmd1 "foobar"
        cmd2 "ls"
        exec-log (atom [])
      exec-target [:exec-exit1 :chain/init :exec-cmd1 :cmd1 :exit1 :fork]
      signal-target {:chain-id :let
                     :result-id :cmd1
                     :result "foobar\n"
                     :error-id :exit1
                     :step-id :fork}
        c-result (chain [:let #(swap! exec-log conj
                                      (:step-id %))]
                        (let [res1 (:chain/wait :cmd1 (do (swap! exec-log
                                                                 conj :exec-cmd1)
                                                          (sh-echo cmd1)))
                              res2 (:chain/go :exit1 (do (swap! exec-log
                                                               conj :exec-exit1)
                                                        (sh-exit1)))
                              _ (:chain/fork :fork (do (sh-echo cmd2)
                                                       (swap! exec-log
                                                              conj :exec-fork)))
                              signal (:chain/end (fn [_ s] s))]
                          signal))]
    (when-realised [c-result]
      (is (= exec-target @exec-log)
          "test-abort execution log")
      (is (= signal-target (dissoc @c-result :error))
          "test-abort chain signal")
      (st/done! 'test-abort-chain))))

