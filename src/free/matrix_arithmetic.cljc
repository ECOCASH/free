(ns free.matrix-arithmetic
  (:require [clojure.core.matrix :as mx :exclude [e*]]))
;; There's also an e* in core.matrix (which is *almost* identical to mul)

;; Note set-current-implementation has a global effect--not just in this namespace.
;; These work with Clojurescript as well as Clojure:
(mx/set-current-implementation :ndarray)
;(set-current-implementation :aljabr)
;; Clojure only:
;(set-current-implementation :vectorz)
;; These are Clojure only, but unlikely to be optimal for this application at this time:
;(set-current-implementation :clatrix)
;(set-current-implementation :nd4clj)

(println "Loading core.matrix operators.  Matrix implementation:" (mx/current-implementation))

;; Note that these are functions, but in free.scalar-arithmetic, I define 
;; them as macros for the sake of performance.  So don't e.g. map the functions 
;; below over a sequence, if you want to preserve substitutability with their 
;; scalar analogues.
(def m* mx/mmul) ; matrix multiplication and inner product
(def e* mx/mul)  ; elementwise (Hadamard) and scalar multiplication
(def m+ mx/add)  ; elementwise addition
(def m- mx/sub)  ; elementwise subtraction, or elementwise negation
(def trans mx/transpose)