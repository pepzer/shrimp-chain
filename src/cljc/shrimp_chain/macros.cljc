;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp-chain.macros
  #?(:cljs (:require [redlobster.promise]
                     [shrimp.core]
                     [shrimp-chain.core]))
  #?(:cljs (:use-macros [redlobster.macros :only [let-realised when-realised]])))

(def ^:private r-let-realised 'redlobster.macros/let-realised)
(def ^:private r-when-realised 'redlobster.macros/when-realised)
(def ^:private r-realise 'redlobster.promise/realise)
(def ^:private r-promise 'redlobster.promise/promise)
(def ^:private a-close! 'shrimp.core/close!)
(def ^:private c-init! 'shrimp-chain.core/init-chain!)
(def ^:private c-prefix "shrimp-chain.macros/chain-")
(def ^:private macro-allowed? #{'let 'do '-> '->> 'cond-> 'cond->>})

(defmacro chain-node
  "Wraps a Node.js async method in a Red Lobster promise.

  Take the form with a node async method invocation and add a callback that
  realises a promise.
  On error realise the promise as a map containing the error {:chain/error error}.
  This macro is an adaptation of redlobster's defer-node to support error
  handling on the chain.

  :param form
    A Node.js async call without a callback, i.e. (.read fs \"file\").

  :param transformer
    An optional method to apply to the result before returning it, i.e. js->clj.

  :return
    A redlobster promise realised with the result or a map with key :chain/error
    and the error as value.
  "
  ([form] `(chain-node ~form identity))
  ([form transformer]
   `(let [promise# (redlobster.promise/promise)
          callback# (fn [error# value#]
                      (if error#
                        (redlobster.promise/realise promise#
                                                    {:chain/error error#})
                        (redlobster.promise/realise promise#
                                                    (~transformer value#))))]
      (~@form callback#)
      promise#)))

(defmulti build-form (fn [[dir & _] & _]
                       dir))

(defn- build-hold-form
  "Build a form for directives that wait to obtain the previous result before
  execution, i.e. :chain/wait and :chain/fork."
  [hold-fn chan xs sym macro]
  (let [opts (butlast xs)
        function (last xs)
        inner-fn (or (and  macro (list macro sym function))
                     function)
        outer-fn (list 'fn [] inner-fn)
        all (concat opts [outer-fn])]
    (apply list hold-fn chan all)))

(defmethod build-form :chain/wait
  [[_ & xs] {:keys [wait]} chan _ sym macro]
  (build-hold-form wait chan xs sym macro))

(defmethod build-form :chain/fork
  [[_ & xs] {:keys [fork]} chan _ sym macro]
  (build-hold-form fork chan xs sym macro))

(defmethod build-form :chain/go
  [[_ & xs] {:keys [go]} chan go-allowed? sym macro]
  (if go-allowed?
    (let [opts (butlast xs)
          function (last xs)
          inner-fn (or (and macro (list macro sym function))
                       function)
          prom (gensym "go-prom-")
          outer-fn (list 'fn [] prom)
          all (concat opts [outer-fn])]
      [:chain/go
       [prom (list r-promise)]
       (list r-realise prom inner-fn)
       (apply list go chan all)])

    #?(:clj (throw (Exception. ":chain/go not allowed in this macro!"))
       :cljs (throw (js/Error. ":chain/go not allowed in this macro!")))))

(defmethod build-form :chain/unchain
  [[_ & xs] _ _ go-allowed? _ _]
  (if go-allowed?
    [:chain/unchain (last xs)]
    #?(:clj (throw (Exception. ":chain/unchain not allowed in this macro!"))
       :cljs (throw (js/Error. ":chain/unchain not allowed in this macro!")))))

(defmethod build-form :chain/end
  [[_ & xs] {:keys [end]} chan _ _ _]
  (apply list end chan xs))

(defmethod build-form :no-seq
  [[_ form] {:keys [wait]} chan _ sym macro]
  (let [inner-fn (if macro
                   (list macro sym form)
                   form)
        outer-fn (list 'fn [] inner-fn)]
    (list wait chan outer-fn)))

(defmethod build-form :default
  [form {:keys [wait]} chan _ sym macro]
  (let [inner-fn (if macro
                   (list macro sym form)
                   form)
        outer-fn (list 'fn [] inner-fn)]
    (list wait chan outer-fn)))

(defn- pre-process
  "Substitute keyword directives with method invocations.

  Pass the chain channel as first argument.
  For :chain/go and :chain/unchain return a preliminary vector to be handled by
  build-forms-map.
  "
  ([fns chan form] (pre-process fns chan form false nil nil))
  ([fns chan form go-allowed?] (pre-process fns chan form go-allowed? nil nil))
  ([fns chan form go-allowed? sym] (pre-process fns chan form go-allowed? sym nil))
  ([fns chan form go-allowed? sym macro]
   (let [seq-form (or (and (sequential? form) form)
                      [:no-seq form])
         new-form (build-form seq-form fns chan go-allowed? sym macro)]
     (if sym
       [sym new-form]
       new-form))))

(defn- dispatch-forms
  "Return a closure to dispatch the forms to the appropriate part of the chain."
  [with-sym?]
  (fn [coll form]
    (let [sym (and with-sym? (first form))
          in-form (or (and with-sym? (second form)) form)
          tag (and (vector? in-form) (first in-form))
          out-form (and tag (drop 1 in-form))]
      (cond
        (and with-sym? (= tag :chain/unchain)) (update-in coll [:head] conj [sym (first out-form)])
        (= tag :chain/unchain) (update-in coll [:head] conj (first out-form))
        (= tag :chain/go) (cond-> coll
                            :always (update-in [:middle] conj (first out-form))
                            :always (update-in [:middle] conj ['_ (second out-form)])
                            with-sym? (update-in [:tail] conj [sym (last out-form)])
                            (not with-sym?) (update-in [:tail] conj (last out-form)))
        :else (update-in coll [:tail] conj form)))))

(defn- build-forms-map
  "Take pre-processed forms and separate them in three blocks, i.e. :head, :middle and :tail.

  :param with-sym?
    A flag to know if forms are bindings pairs like for the let macro.

  :forms forms
    Pre-processed forms.

  :return
    A map with forms assigned to sections :head, :middle and :tail."
  [with-sym? forms]
  (reduce (dispatch-forms with-sym?) {:head [] :middle [] :tail []} forms))

(defn- realise-form
  "Build a code block that waits the result of expr and executes in-form."
  [in-form expr]
  (let [[sym form] expr
        prom-sym (gensym (str sym "-prom-"))]
    (list r-let-realised [prom-sym form]
          (list 'let [sym (list 'deref prom-sym)]
                in-form))))

(defn- realise-tail
  "Build a chain of let-realised blocks to execute a form after the previous has
  returned.

  :param tail
    The tail portion of forms-map, containing forms to execute with
    \"wait-then-run\" semantic.

  :param return
    The symbol used to bind the return value of each block.

  :param forms
    Forms to execute in the body of a macro, these require all bindings in place
    hence will be executed at the end of the chain.
  "
  [tail return forms]
 (reduce realise-form
          (list r-realise return (cons 'do forms))
          (reverse tail)))

(defmacro chain
  "Convert let, do, ->, ->>, cond->, cond->> to work with async functions.

  Build a chain of expressions where by default each one waits the realization
  of the previous one before executing and aborts execution if receives an error.
  Expressions could be async function invocations where the return value is a
  redlobster promise, like an async Node.js API call wrapped with chain-node,
  or a put!/take! to/from a shrimp channel.

  Examples:

    (chain []
      (let [content (chain-node (.read fs \"file\"))
            lines (split-lines content)]
        (println (first lines))))

    (chain []
      (-> shrimp-channel
          take!
          println))

  Each expression could be wrapped in a directive, which is like a function call,
  when not specified an implicit :chain/wait directive is assumed.
  The previous examples are equivalent to these:

    (chain []
      (let [content (:chain/wait (chain-node (.read fs \"file\")))
            lines (:chain/wait (split-lines content))]
        (println (first lines))))

    (chain []
      (-> (:chain/wait shrimp-channel)
          (:chain/wait take!)
          (:chain/wait println)))

  Allowed directives include:

    - :chain/go

      This tells the chain that execution could be immediate, but the result
      must be provided to the next waiting expression.

        (chain []
          (let [content1 (:chain/go (chain-node (.read fs \"file1\")))
                content2 (:chain/go (chain-node (.read fs \"file2\")))
                contents (str content1 content2)]
            (println contents)))

      In the example above both async reading execute immediately but the
      following expression runs only when both have returned with success and
      the values are bound to the symbols content1 and content2.
      Because :chain/go expressions run immediately, local bindings cannot be
      used inside these expressions, in the previous example the expression for
      content2 cannot refer to content1, if it is necessary to refer to content1
      then :chain/wait should be used (or nothing as it is the default).
      Because execution happens first, all calls to :chain/go should be placed
      at the beginning of the chain, to avoid confusion.
      :chain/go directives are not allowed in threading macros as it would make
      no sense since each expression needs the result of the previous one.

    - :chain/fork

      This directive is useful to perform side effects but only if the previous
      operations were successful.
      Expressions wrapped with :chain/fork wait for the results of preceding
      operations and execute only if error is nil, as with :chain/wait all
      preceding bindings are available.
      The return value is nil and the chain is advanced without waiting for the
      result of the fork expression.
      The result carried by the chain will remain bound to the return value of
      the expression preceding :chain/fork, this implies that this directive is
      allowed in threading macros too.

        (chain []
          (let [content (chain-node (.read fs \"file\"))
                _ (:chain/fork (println (str \"file loaded, content: \" content)))
                lines (split-lines content)]
            (println (first lines))))

        (chain []
          (->> shrimp-channel
               take!
               ; Thread in the result of take!, ignore the result of this expr.
               ; Immediately jump to println and thread in the result of take!.
               (:chain/fork (prn :take-value))
               println))

        (chain []
          (do (chain-node (.write fs \"file\" \"foo content\"))
              ; Possibly slow async operation, go to next expression immediately,
              ; Ignore possible errors for this call.
              (:chain-fork (chain-node (.write fs \"log-file\" \"file content updated\")))
              [:foobar]))

    - :chain/end

      This directive must be the last expression in the chain and is useful to
      implement a custom handler for the chain that is *always* executed.
      The chain carries around a ChainSignal record containing :result and :error
      fields (among others), the function wrapped by :chain/end will receive two
      arguments, an id for the end expression (for logging purposes) and the
      ChainSignal record.
      The namespace shrimp-chain.core defines two handlers: <-result and <-error,
      these handlers extracts respectively the :result and :error field.

        (chain []
          (let [content (chain-node (.read fs \"file\"))
                lines (split-lines content)
                err (:chain/end <-error)]
            (when-not err
              (println (first lines)))))

      In the chain-let macro the body is executed even if the chain was partially
      aborted because of an error, the reason is that it might be possible to
      recover from the error.
      The directive :chain/end could be used to explicitly check for an error
      like in the example above.
      The :chain/end could be used to terminate a threading macro, the handler
      function is *not* threaded and the final result of the chain will be
      whatever the handler returns:

        (chain []
          (-> shrimp-channel
              take!
              (:chain/end (fn [_ {:keys [result]}]
                            (println \"end handler returning the result\")
                            result))))

        (chain []
          (cond-> shrimp-channel
            true take!
            false (put! :foo)
            true (:chain/end (fn [_ {:keys [error result]}]
                               (if error
                                 :default-value
                                 result)))))

  A chain invocation is an async block that returns a redlobster promise, hence
  chains allow composition:

    (chain []
      (let [content (chain-node (.read fs \"file\"))
            chan-res (chain []
                       (-> shrimp-channel
                           take!
                           pr-str))
            lines (split-lines content)]
        (println (str chan-res \" \" (first lines)))))

  The chain macro accepts three optional arguments as init options:

    - A chain-id, e.g. a keyword, that is bound to the :chain-id field of the
      ChainSignal record, and could be useful for logging.

    - A logging function that receives the ChainSignal record at each step of the
      chain, its return value is ignored and the ChainSignal is unchanged.
      Currently to log the last step of the chain it is necessary to add a
      :chain/end directive, e.g. (:chain/end <-result).

    - A transformer function that receives the ChainSignal at each step,
      what is passed to the next step is the result of applying transformer to
      the ChainSignal. Using this is probably a bad idea in most cases.

  All directives accept an optional step-id as first argument that is assigned
  to the :step-id field of the ChainSignal.

    (chain [:log-chain
            (fn [{:keys [chain-id step-id]}]
              (prn [chain-id step-id]))]

      (let [content1 (:chain/go :read1 (chain-node (.read fs \"file1\")))
            content2 (:chain/go :read2 (chain-node (.read fs \"file2\")))
            contents (:chain/wait :str (str content1 content2))
            err (:chain/end <-error)]
        (when-not err
          (println contents))))

    => [:log-chain :chain/init]
    => [:log-chain :read1]
    => [:log-chain :read2]
    => [:log-chain :str]
    => ...
  "
  [init-opts macro-form]
  (let [macro (first macro-form)
        chain-macro (symbol (str c-prefix (name macro)))]
    (if (macro-allowed? macro)
      (cons chain-macro
            (cons init-opts
                  (rest macro-form)))
      #?(:clj (throw (Exception. (str "Cannot apply chain to " macro)))
         :cljs (throw (js/Error. (str "Cannot apply chain to " macro)))))))

(defn- gen-symbols []
  (let [chain-ch (gensym "chain-ch-")
        wait (gensym "wait-")
        go (gensym "go-")
        fork (gensym "fork-")
        end (gensym "end-")
        return (gensym "return-")
        fns {:wait wait
             :go go
             :fork fork
             :end end}]
    [chain-ch wait
     go fork end
     return fns]))

(defmacro chain-let
  "This is the expanded form of (chain [] (let ...)) refer to chain macro doc."
  [init-opts bindings & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        new-bindings (for [[sym form] (partition 2 bindings)]
                       (if (= form :chain/channel)
                         [sym chain-ch]
                         (pre-process fns chain-ch form true sym)))
        forms-map (build-forms-map true new-bindings)]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@(apply concat (:head forms-map))
           ~@(apply concat (:middle forms-map))]
       ~(realise-tail (:tail forms-map) return forms)
       (~r-when-realised [~return]
        (~a-close! ~chain-ch)
        (deref ~return)))))

(defmacro chain-do
  "This is the expanded form of (chain [] (do ...)) refer to chain macro doc."
  [init-opts & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        forms-ret (gensym "forms-ret-")
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form true forms-ret))
        forms-map (build-forms-map true forms-bindings)]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@(apply concat (:head forms-map))
           ~@(apply concat (:middle forms-map))]
       ~(realise-tail (:tail forms-map) return (list forms-ret))
       (~r-when-realised [~return]
        (~a-close! ~chain-ch)
        (deref ~return)))))

(defmacro chain-->
  "This is the expanded form of (chain [] (-> ...)) refer to chain macro doc."
  [init-opts expr & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-ret (gensym "expr-ret-")
        forms-ret (gensym "forms-ret-")
        expr-binding (pre-process fns chain-ch expr false expr-ret)
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form false forms-ret '->))]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@expr-binding]
       (~r-when-realised [~expr-ret]
        (let [~forms-ret (deref ~expr-ret)]
          ~(realise-tail forms-bindings return (list forms-ret))
          (~r-when-realised [~return]
           (~a-close! ~chain-ch)
           (deref ~return)))))))

(defmacro chain-->>
  "This is the expanded form of (chain [] (->> ...)) refer to chain macro doc."
  [init-opts expr & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-ret (gensym "expr-ret-")
        forms-ret (gensym "forms-ret-")
        expr-binding (pre-process fns chain-ch expr false expr-ret)
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form false forms-ret '->>))]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@expr-binding]
       (~r-when-realised [~expr-ret]
        (let [~forms-ret (deref ~expr-ret)]
          ~(realise-tail forms-bindings return (list forms-ret))
          (~r-when-realised [~return]
           (~a-close! ~chain-ch)
           (deref ~return)))))))

(defn- pred->binding [[pred [sym form]]]
  [sym (list 'or (list 'and pred form)
             (list 'let ['or-prom (list r-promise)]
                   (list r-realise 'or-prom sym)
                   'or-prom))])

(defmacro chain-cond->
  "This is the expanded form of (chain [] (cond-> ...)) refer to chain macro doc."
  [init-opts expr & clauses]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-ret (gensym "expr-ret-")
        forms-ret (gensym "forms-ret-")
        expr-binding (pre-process fns chain-ch expr false expr-ret)
        pred-forms (for [[pred form] (partition 2 clauses)]
                     [pred (pre-process fns chain-ch form false forms-ret '->)])
        bindings (map pred->binding pred-forms)]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@expr-binding]
       (~r-when-realised [~expr-ret]
        (let [~forms-ret (deref ~expr-ret)]
          ~(realise-tail bindings return (list forms-ret))
          (~r-when-realised [~return]
           (~a-close! ~chain-ch)
           (deref ~return)))))))

(defmacro chain-cond->>
  "This is the expanded form of (chain [] (cond->> ...)) refer to chain macro doc."
  [init-opts expr & clauses]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-ret (gensym "expr-ret-")
        forms-ret (gensym "expr-ret-")
        expr-binding (pre-process fns chain-ch expr false expr-ret)
        pred-forms (for [[pred form] (partition 2 clauses)]
                     [pred (pre-process fns chain-ch form false forms-ret '->>)])
        bindings (map pred->binding pred-forms)]

    `(let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
           ~return (~r-promise)
           ~@expr-binding]
       (~r-when-realised [~expr-ret]
        (let [~forms-ret (deref ~expr-ret)]
          ~(realise-tail bindings return (list forms-ret))
          (~r-when-realised [~return]
           (~a-close! ~chain-ch)
           (deref ~return)))))))
