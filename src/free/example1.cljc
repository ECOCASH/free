(ns free.example1
  (:use ;[free.matrix-arithmetic]
        [free.scalar-arithmetic]
        [free.level])
  (:require [free.dists :as pd])) ; will be clj or cljs depending on dialect

(def I (make-identity-obj dims))

;;;;;;;;;;;;;;;;;;
;; from ex. 3 in Bogacz
(def v-p 3)
(def sigma-p 1)
(def sigma-u 1)
(def u 2)
(def dt 0.01)
(def error-p 0)
(def error-u 0)

;;;;;;;;;;;;;;;;;;;;;
;; These define the function g that Bogacz offers as an example on p. 2.
;; i.e. for g(phi) = theta * h(phi), where g just squares its argument.

(def example-theta I)

;; these really shouldn't be the same at every level--doesn't make sense
(defn example-h  [phi] (m-square phi))
(defn example-h' [phi] (m* phi 2))

;; from ex. 5:
(def next-bottom (make-next-bottom #(pd/sample-normal 1 :mean 5 :sd 1.4142)))

(def initial-bottom
  (map->Level {:phi u
               :eps error-u
               :sigma sigma-u
               :theta I
               :h  example-h
               :h' example-h'}))

(def initial-middle
  (map->Level {:phi v-p
               :eps error-p
               :sigma sigma-p
               :theta I
               :h  example-h
               :h' example-h'}))

(def top (map->Level {:phi v-p})) ; other fields will be nil

(def initial-levels [initial-bottom initial-middle top])