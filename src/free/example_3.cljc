;; simple vector/matrix example

(ns free.example-3
  (:require [free.config :as conf]
            [free.matrix-arithmetic :as ar]
            [clojure.core.matrix :as mx]
            [free.dists :as pd])) ; clj or cljs depending on dialect

;; Use matrix version of free.level:
(reset! conf/use-core-matrix$ true)
(require '[free.level :as lvl])

(defn gen [phi] (let [x1 (mx/mget phi 0 0)
                      x2 (mx/mget phi 1 0)]
                  (mx/matrix [[(* x1 x1 x2)]
                              [(* x2 x2 x1)]])))

(defn gen' [phi] (let [x1 (mx/mget phi 0 0)
                       x2 (mx/mget phi 1 0)]
                   (mx/matrix [[(* x2 2.0 x1)]
                               [(* x1 2.0 x2)]])))

(def next-bottom (lvl/make-next-bottom 
                   #(ar/col-mat [(pd/next-gaussian 2 5)
                                 (pd/next-gaussian -1 3)])))

(def init-gen-wt (ar/make-identity-obj 2)) ; i.e. initially pass value of gen(phi) through unchanged

; what phi is initialized to, and prior mean at top:
(def v-p (ar/col-mat [3.0 3.0]))

(def bot-map {:phi   (ar/col-mat [0.0 0.0]) ; immediately replaced by next-bottom
              :err   (ar/col-mat [0.0 0.0])
              :sigma (mx/matrix [[2.0  0.25]
                                 [0.25 2.0]])
              :gen-wt init-gen-wt
              :gen  nil
              :gen' nil
              :phi-dt    (ar/col-mat [0.01 0.01])
              :err-dt    (ar/col-mat [0.01 0.01])
              :sigma-dt  (ar/col-mat [0.0 0.0])
              :gen-wt-dt (ar/col-mat [0.0 0.0])})

(def mid-map {:phi v-p
              :err   (ar/col-mat [0.0 0.0])
              :sigma (mx/matrix [[2.0  0.25]
                                 [0.25 2.0]])
              :gen-wt init-gen-wt
              :gen  gen
              :gen' gen'
              :phi-dt    (ar/col-mat [0.0001 0.0001])
              :err-dt    (ar/col-mat [0.01   0.01])
              :sigma-dt  (ar/col-mat [0.0001 0.0001])
              :gen-wt-dt (ar/col-mat [0.01   0.01])})

(def init-bot (lvl/map->Level bot-map))
(def init-mid (lvl/map->Level mid-map))
(def top      (lvl/make-top-level v-p))

(defn make-stages [] (iterate (partial lvl/next-levels next-bottom)
                              [init-bot init-mid top]))
