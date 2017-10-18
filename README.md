## What is Shrimp-Chain?

Shrimp-Chain is a [ClojureScript](https://clojurescript.org/) library of macros to chain async functions, it is built on top of [Shrimp](https://github.com/pepzer/shrimp) and [Red Lobster](https://github.com/whamtet/redlobster) promise library.  
This library targets [Node.js](https://nodejs.org/en/) and could be used with [Lumo](https://github.com/anmonteiro/lumo).  
One goal is to be able to use async functions inside several Clojure macros consistently (as much as possible) with how the macros are commonly used.  
A further goal is to provide safety for the chain of operations, so the execution of a chain is aborted whenever an error occurs, and the library offers optional features to monitor and handle errors.

## Warning

This is an early release, tests are present but not comprehensive and severe bugs might be present.
The result of a bug could be unpredictable due to the inherent complexity of intervening macros and functions.
If you decide to use this library for operations that could potentially damage your system you do it at your own risk!

## Leiningen/Clojars

[![Clojars Project](https://img.shields.io/clojars/v/shrimp-chain.svg)](https://clojars.org/shrimp-chain)

If you use [Leiningen](https://github.com/technomancy/leiningen) add redlobster, shrimp and shrimp-chain to the dependencies in your project.clj file.
  
    :dependencies [... 
                   [org.clojars.pepzer/redlobster "0.2.2"]
                   [shrimp "0.1.0"]
                   [shrimp-chain "0.1.1"]]
    
For Lumo you could either download the dependencies with Leiningen/Maven and then add them to Lumo like so:

    $ lumo -D org.clojars.pepzer/redlobster:0.2.2,shrimp:0.1.0,shrimp-chain:0.1.1
    
Or you could download the jar files and add them to the Lumo classpath:

    $ lumo -c redlobster-0.2.2.jar:shrimp-0.1.0.jar:shrimp-chain-0.1.1.jar
    
## REPL

To run a REPL with Lumo clone this project and run the lumo-repl.cljsh script:
   
    $ bash lumo-repl.cljsh
    
This will run the REPL and will also listen on the port 12345 of the localhost for connections.  
You could connect with Emacs and inf-clojure-connect.
 
To run a REPL with lein figwheel (optionally with rlwrap):
   
    $ rlwrap lein figwheel dev

With [Node.js](https://nodejs.org/en/) and npm installed open a shell, navigate to the root of the project and run:

    $ npm install ws
    $ node target/out/shrimp-chain.js

Then the REPL should connect in the lein figwheel window.
   
## Usage

Shrimp-Chain provides chain versions of let, do, ->, ->>, cond-> and cond->> that work with async functions.  
To be used inside a chain an async function must return a redlobster promise, all operations on shrimp channels return promises and could be used inside the chain.
Node.js async calls could be wrapped with the chain-node macro (a modified version of redlobster's defer-node) to integrate with shrimp-chain.  
The chain macro modify the macro immediately following, an example of using the chain-let macro is:

    (require '[shrimp-chain.core :as shc])
    (require '[clojure.string :refer [split-lines]])
    (use-macros '[shrimp-chain.macros :only [chain chain-node]])
    
    (defonce fs (js/require "fs"))
    
    (defn read-file 
      [filename]
      (chain-node (.readFile fs filename) str))

    (chain []
      (let [filename "README.md"
            content (read-file filename)
            lines (split-lines content)]
        (println (first lines))))

Using the threading macro -> and shrimp channels:

    (require '[shrimp.core :as sh])

    (let [chan1 (sh/chan)]

      (chain []
        (-> chan1
            sh/take!
            println))
            
      (sh/put! chan1 "foo")
      
      (sh/close! chan1))
      
    => foo

These macros build a chain of expressions where by default each one waits the realization of the previous one before executing and aborts execution when an error occurs.  
Each expression could be wrapped in a directive, which is like a function call, when not specified an implicit :chain/wait directive is assumed.
The previous examples are equivalent to these verbose versions:

    (chain []
      ; The first expression waits for the completition of the init function.
      (let [filename (:chain/wait "README.md")
            content (:chain/wait (read-file filename))
            lines (:chain/wait (split-lines content))]
        (println (first lines))))
        

    (let [chan1 (sh/chan)]

      (chain []
        (-> (:chain/wait chan1)
            (:chain/wait sh/take!)
            (:chain/wait println)))
            
      (sh/put! chan1 "foo")
      
      (sh/close! chan1))
 
Shrimp-Chain recognizes other directives:

### :chain/go

This tells the chain that execution could be immediate, but the result must be provided to the next waiting expression.

     (chain []
       (let [content1 (:chain/go (read-file "README.md"))
             content2 (:chain/go (read-file "project.clj"))
             line1 (first (split-lines content1))
             line2 (first (split-lines content2))
             contents (str line1 "\n" line2)]
         (println contents)))

In the example above both async read execute immediately but the following expression runs only when both have returned with success and the values are bound to the symbols content1 and content2.  
Because :chain/go expressions run immediately, local bindings cannot be used inside these expressions, in the previous example the expression for 'content2' cannot refer to 'content1', if it's necessary to refer to 'content1' then :chain/wait should be used (or nothing as it is the default).  
Because execution happens before other expressions, all calls to :chain/go should be placed at the beginning of the chain, to avoid confusion.  
:chain/go directives are *not* allowed in threading macros as it would make no sense since each expression needs the result of the previous one.

### :chain/fork

This directive is useful to perform side effects with the condition that all previous operations were successful.
Like with :chain/wait all preceding bindings are available, following the standard behaviour of the let macro.  
The return value is the result received from the chain, that is forwarded without waiting (if async) the result of the fork expression.
After this step the result carried by the chain will contain the same value it had before :chain/fork.
This behaviour allows the use of :chain/fork in threading macros.

    (chain []
       (let [content (read-file "README.md")
             _ (:chain/fork (println (str "file loaded, content lenght: " (count content))))
             lines (split-lines content)]
         (println (first lines))))

         
    (let [chan1 (sh/chan)]

      (chain []
        (->> chan1
             sh/take!
             ; Thread in the result of take!, ignore the result of this expr.
             ; Immediately jump to println and thread in the result of take!.
             (:chain/fork (prn :take-value))
             println))
             
      (sh/put! chan1 "foo")
      (sh/close! chan1))


    (chain []
      (do (chain-node (.writeFile fs "file" "foo content"))
          ; Possibly slow async operation, go to next expression immediately,
          ; Ignore possible errors for this call.
          (:chain-fork (chain-node (.writeFile fs "log-file" "file content updated")))
          [:foobar]))

### :chain/end

This directive could be used only once as the last expression in the chain. It isn't part of the chain itself its purpose is to define a custom handler for what is returned by the chain.  
The chain carries around a ChainSignal record containing fields like :result and :error, the function wrapped by :chain/end will receive two arguments, an id for the end expression (for logging purposes) and the ChainSignal record as returned by the last step in the chain.
The namespace shrimp-chain.core defines two simple handlers, <-result and <-error, these handlers extracts respectively the :result and :error field from the record.

    (require '[shrimp-chain.core :refer [<-error]])

    (chain []
      (let [content (read-file "README.md") 
            lines (split-lines content)
            bad-expr (js/Error. "This is serious!")
            err (:chain/end <-error)]
        (if err
          (println "Better stopping here!")
          (println (first lines)))))
          
In the chain-let macro the body is executed even if the chain was partially aborted because of an error in the bindings, the reason is that it might be possible to recover from the error. The directive :chain/end could be used to explicitly check for an error like in the example above.

The :chain/end could be used also to terminate a threading macro, the result carried by the chain is *not* threaded in the handler function.
The result of the entire chain is then what the :chain/end handler returns.

    (let [shrimp-channel (sh/chan)]

      (chain []
        (-> shrimp-channel
            sh/take!
            (:chain/end (fn [_ {:keys [result]}]
                          (println "end handler returning the result")
                          result))))
                          
      (sh/put! shrimp-channel :foo)

      (chain []
        (cond->> shrimp-channel
          true sh/take!
          false (into [])
          true (:chain/end (fn [_ {:keys [error result]}]
                             (if error
                               (println :default-value)
                               (println result))))))
                               
      (sh/put! shrimp-channel :bar)
      (sh/close! shrimp-channel))

A chain invocation is an async block that returns a redlobster promise, hence chains allow composition:

    (chain []
      (let [content (read-file "README.md")
            shrimp-channel (sh/chan)
            chan-res (chain []
                       (-> shrimp-channel
                           (:chain/fork (sh/put! :chan-res))
                           sh/take!
                           pr-str))
            close? (sh/close! shrimp-channel)
            lines (split-lines content)]
        (println (str chan-res " " close? " " (first lines)))))

The chain macro accepts three optional arguments as init options:

    - A chain-id, e.g. a keyword, that is bound to the :chain-id field of the ChainSignal record, and could be useful for logging.

    - A logging function that receives the ChainSignal record at each step of the chain, its return value is ignored and the ChainSignal is unchanged.  
      Currently in order to log the last step of the chain it is necessary to add a :chain/end directive, e.g. (:chain/end <-result).

    - A transformer function that receives the ChainSignal at each step, what is passed to the next step is the result of applying transformer to the ChainSignal.  
      Using this is probably a bad idea in most cases.

All directives accept an optional step-id as first argument that is assigned to the :step-id field of the ChainSignal, the id assigned to :chain/end is passed as the first argument to its handler.

    (chain [:log-chain
            (fn [{:keys [chain-id step-id]}]
              (prn [chain-id step-id]))]

      (let [content1 (:chain/go :read1 (read-file "README.md"))
            content2 (:chain/go :read2 (read-file "project.clj"))
            line1 (first (split-lines content1))
            line2 (first (split-lines content2))
            contents (:chain/wait :str (str line1 "\n" line2))
            err (:chain/end :ignored-id <-error)]
        (when-not err
          (println contents))))

    => [:log-chain :chain/init]
    => [:log-chain :read1]
    => [:log-chain :read2]
    => [:log-chain :chain/wait]
    => [:log-chain :chain/wait]
    => [:log-chain :str]
    => ...

### ChainSignal

This record is carried by the chain and modified at each step, it contains the following fields:

    - *chain-id*, provided as the first element in the vector following the symbol chain, defaults to :shrimp-chain.

    - *result-id*, contains the id of the step that successfully produced the most recent result, defaults to the directive :chain/wait, :chain/go, etc.

    - *result*, the most recent result returned by a step completed with success, is not overwritten if an error occurs, could be nil.

    - *error-id*, the id of the first (and almost always the last too) step that produced an error or nil if no errors occurred.

    - *error*, the first error produced by the chain or nil if no errors occurred.

    - *step-id*, contains the id of the most recent intervening step in the chain (even if execution was aborted), defaults to the directive.

## Tests

To run the tests with Leiningen use:

    $ lein cljsbuild once
    $ node target/out-test/shrimp-chain.js
    
With Lumo:

    $ bash lumo-test.sh
    
## Notes

A Clojure(Script) adaptation of these macros to work with [core.async](https://github.com/clojure/core.async) is currently in the making.
    
## Contacts

[Giuseppe Zerbo](https://github.com/pepzer), [giuseppe (dot) zerbo (at) gmail (dot) com](mailto:giuseppe.zerbo@gmail.com).

## License

Copyright © 2017 Giuseppe Zerbo.  
Distributed under the [Mozilla Public License, v. 2.0](http://mozilla.org/MPL/2.0/).
