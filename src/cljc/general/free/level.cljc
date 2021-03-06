;;; This software is copyright 2016 by Marshall Abrams, and
;;; is distributed under the Gnu General Public License version 3.0 as
;;; specified in the file LICENSE.

;; Based on Rafal Bogacz's, "A Tutorial on the Free-energy Framework 
;; for Modelling Perception and Learning", _Journal of Mathematical 
;; Psychology_ (online 2015), http://dx.doi.org/10.1016/j.jmp.2015.11.003

;; SEE doc/level.md for documentation on general features of the code below.

#?(:clj  (ns free.level
           (:require [clojure.spec :as s]
                     [free.arithmetic :refer [e* m* m+ m- tr inv make-identity-obj limit-sigma]] ; could be scalar or matrix
                     [utils.string :as us]))
   :cljs (ns free.level
           (:require [cljs.spec :as s]    ; for clojure spec tests at bottom of this file
                     [clojure.core.matrix :as mx]) ; needed for matrix arithmetic macros even though not explicitly used below. s/b irrelevant for scalar version.
           ;; free.arithmetic contains macros defined in terms of either 
           ;; Clojure primitives or core.matrix functions.  When Clojurescript
           ;; sees this code, the macros have already been expanded, so if
           ;; they use core.matrix, that will be needed here:
           ;(:require [clojure.core.matrix :as mx]) ; does nothing if scalar free.arithmetic
           ;(:require [utils.string :as us])
           (:require-macros [free.arithmetic :refer [e* m* m+ m- tr inv make-identity-obj limit-sigma]]))) ; could be scalar or matrix

                     ;; Clojurescript kludge: requiring core.matrix next will do 
                     ;; nothing if free.arithmetic is scalar version, but needed
                     ;; for matrix version when macros expand (?):
                     ; [clojure.core.matrix :refer [mmul mul add sub transpose inverse identity-matrix]])

;;;;;;;;;;;;;;;;;;;;;
(declare phi-inc   next-phi 
         epsilon-inc   next-epsilon 
         sigma-inc next-sigma
         theta-inc next-theta
         next-level next-levels
         m-square)

;;;;;;;;;;;;;;;;;;;;;
;; Level

(defrecord Level [phi epsilon sigma theta ; names from Bogacz
                  gen gen'             ; h, h'
                  phi-dt epsilon-dt sigma-dt theta-dt]) ; increment sizes for approaching limit

(def Level-docstring
  "\n  A Level records values at one level of a prediction-error/free-energy
  minimization model.  
  phi:     Current value of input at this level, or generative function parameter.
  epsilon: Epsilon--the error at this level.
  sigma:   Covariance matrix or variance of assumed distribution over inputs 
           at this level.  Variance should usually be >= 1 (p. 5 col 2).
  theta:  Scaling factor theta (scalar or matrix) for generative function.  When 
           theta is multiplied by result of gen(phi), the result is the current 
           estimated mean of the assumed distrubtion.  
           i.e. g(phi) = theta * gen(phi), where '*' here is scalar or matrix 
           multiplication as appropriate.
  <x>-dt:  A scalar multiplier (e.g. 0.01) determining how fast <x> is updated.
  gen, gen': See theta; gen' is the derivative of gen.  These never change.

  All of these notations are defined in Bogacz's \"Tutorial\" paper.
  phi and epsilon can be scalars, in which case theta and sigma are as well.  
  Or phi and epsilon can be vectors of length n, in which case sigma and theta
  are n x n square matrices.  gen and gen' are functions that can be applied to 
  phi.  See doc/level.md for more information.")

#?(:clj (us/add-to-docstr! ->Level    Level-docstring))
#?(:clj (us/add-to-docstr! map->Level Level-docstring))

;;;;;;;;;;;;;;;;;;;;;
;; Functions to calculate next state of system

(defn next-level
  "Returns the value of this level for the next timestep."
  [[-level level +level]]
  (assoc level 
         :phi     (next-phi -level level)
         :epsilon (next-epsilon    level +level)
         :sigma   (next-sigma      level)
         :theta   (next-theta      level +level)))

;; See notes in levels.md on this function.
(defn next-levels
  "Given a functions for updating gen, gen', and a bottom-level creation function
  that accepts two levels (its level and the next up), along with a sequence of 
  levels at one timestep, returns a vector of levels at the next timestep.  
  The top level will be used to calculate the next level down, but won't be 
  remade; it will be used again, as is, as the new top level."
  [next-bottom [level-0 level-1 :as levels]]
  (cons (next-bottom [level-0 level-1])     ; Bottom level is special case.
        (conj
          (vec (map next-level            ; Each middle level depends on levels
                 (partition 3 1 levels))) ;  immediately below and above it.
          (last levels))))                ; Top is carried forward as is

(defn next-levels-3
  "Version of next-levels that may be more efficient with exactly three levels.
  Given a functions for updating gen, gen', and a bottom-level creation function
  that accepts two levels (its level and the next up), along with a sequence of 
  levels at one timestep, returns a vector of levels at the next timestep.  
  The top level will be used to calculate the next level down, but won't be 
  remade; it will be used again, as is, as the new top level."
  [next-bottom [level-0 level-1 :as levels]]
  [(next-bottom [level-0 level-1]) ; Bottom level is special case.
   (next-level levels)             ; Each middle level depends on levels immediately below and above it.
   (last levels)])                 ; top is carried forward as-is

;; To see that it's necessary to calculate the error in the usual way
;; at the bottom level, cf. e.g. eq (14) in Bogacz.
(defn make-next-bottom
  "Returns a function similar to next-level, but in which the new phi is
  generated by phi-generator rather than being calculated in the normal way
  using the error epsilon from the next level down.  gen' is not needed since
  it's only used by the normal phi calculation process.  The phi produced by
  phi-generator represents sensory input from outside the system."
  [phi-generator]
  (fn [[level +level]]
    (assoc level 
           :phi (phi-generator)
           :epsilon (next-epsilon level +level)
           :sigma (next-sigma level)
           :theta (next-theta level +level))))

(defn make-top-level
  "Makes a top level with constant value phi for :phi.  Also sets :gen to
  the identity function, which the next level down will use to update eps.
  Other fields will be nil."
  [phi]
  (map->Level {:phi phi :gen identity})) ; other fields will be nil, normally
              ;; DEBUG: 
              ; :epsilon 0.01 :sigma 0.01 :theta 0.01 :gen' identity :phi-dt 0.01 :epsilon-dt 0.01 :sigma-dt 0.01 :theta-dt 0.01}))



;;;;;;;;;;;;;;;;;;;;;
;; phi update

(defn phi-inc
  "Calculates slope/increment to the next 'hypothesis' phi from the 
  current phi using the error -epsilon from the level below, scaled by
  the generative function scaling factor theta and the derivative gen' of 
  the generative function gen at this level, and subtracting the error at 
  this level.  See equations (44), (53) in Bogacz's \"Tutorial\"."
  [phi epsilon -epsilon -theta gen']
  (m- (e* (gen' phi)
          (m* (tr -theta) -epsilon))
      epsilon))

(defn next-phi 
  "Calculates the next-timestep 'hypothesis' phi from this level 
  and the one below."
  [-level level]
  (let [{:keys [phi phi-dt epsilon gen']} level
        -epsilon (:epsilon -level)
        -theta (:theta -level)]
    (m+ phi 
        (e* phi-dt
            (phi-inc phi epsilon -epsilon -theta gen')))))

;;;;;;;;;;;;;;;;;;;;;
;; epsilon update

(defn epsilon-inc 
  "Calculates the slope/increment to the next 'error' epsilon from 
  the current epsilon, using the mean of the generative model at the
  next level up, but scaling the current error epsilon by the
  variance/cov-matrix at this level, and making the whole thing
  relative to phi at this level. See equation (54) in Bogacz's \"Tutorial\"."
  [epsilon phi +phi sigma theta +gen]
  (m- phi 
      (m* theta (+gen +phi))
      (m* sigma epsilon)))

(defn next-epsilon
  "Calculates the next-timestep 'error' epsilon from this level and the one
  above."
  [level +level]
  (let [{:keys [phi epsilon epsilon-dt sigma theta]} level
        +phi (:phi +level)
        +gen (:gen +level)
        scaled-increment (e* epsilon-dt (epsilon-inc epsilon phi +phi sigma theta +gen))]
    (m+ epsilon scaled-increment)))

;(defn next-epsilon
;  "Calculates the next-timestep 'error' epsilon from this level and the one
;  above."
;  [level +level]
;  (let [{:keys [phi epsilon epsilon-dt sigma theta]} level
;        +phi (:phi +level)
;        +gen (:gen +level)]
;    (m+ epsilon
;        (e* epsilon-dt
;            (epsilon-inc epsilon phi +phi sigma theta +gen)))))

;;;;;;;;;;;;;;;;;;;;;
;; sigma update

(defn sigma-inc
  "Calculates the slope/increment to the next sigma from the current sigma,
  i.e. the variance or the covariance matrix of the distribution of inputs 
  at this level.  See equation (55) in Bogacz's \"Tutorial\".  (Note uses 
  matrix inversion for vector/matrix calcualtions, a non-Hebbian calculation,
  rather than the local update methods of section 5.)"
  [epsilon sigma]
  (e* 0.5 (m- (m-square epsilon)
              (inv sigma))))

(defn next-sigma
  "Calculates the next-timestep sigma, i.e. the variance or the covariance 
  matrix of the distribution of inputs at this level."
  [level]
  (let [{:keys [epsilon sigma sigma-dt]} level]
    (limit-sigma
      (m+ sigma
          (e* sigma-dt
              (sigma-inc epsilon sigma))))))


;;;;;;;;;;;;;;;;;;;;;
;; theta update

(defn theta-inc
  "Calculates the slope/increment to the next theta component of the mean
  value function from the current theta using the error epsilon at this level
  along with the mean of the generative function at the next level up.  
  See equation (56) in Bogacz's \"Tutorial\"."
  [epsilon +phi +gen]
  (m* epsilon 
      (tr (+gen +phi))))

(defn next-theta
  "Calculates the next-timestep theta component of the mean value function
  from this level and the one above."
  [level +level]
  (let [{:keys [epsilon theta theta-dt]} level
        +phi (:phi +level)
        +gen (:gen +level)]
    (m+ theta
        (e* theta-dt
            (theta-inc epsilon +phi +gen)))))

;;;;;;;;;;;;;;;;;;;;;
;; Utility functions

(defn m-square
  "Calculates the matrix or scalar square of a value."
  [x]
  (m* x (tr x)))

;(defn print-level
;  [level]
;  (doseq [[k v] level] ; level is a record/map, i.e. collection of map-entries
;    (when (and v (not (instance? clojure.lang.IFn v))) ; nils, fns: uninformative
;      (println k)
;      (pm v))))

;(defn print-stage
;  [stage]
;  (doseq [level stage] ; stage is a sequence of levels
;    (print-level level)
;    (println)))


;;;;;;;;;;;;;;;;;;;;;
;; spec

(s/def ::pos-num (s/and number? pos?)) ; doesn't work. why?

(s/def ::phi number?)
(s/def ::epsilon number?)
(s/def ::sigma ::pos-num)
(s/def ::theta number?)

(s/def ::phi-dt ::pos-num)
(s/def ::epsilon-dt ::pos-num)
(s/def ::sigma-dt ::pos-num)
(s/def ::theta-dt ::pos-num)

(s/def ::level-params 
  (s/keys :req-un [::phi ::epsilon ::sigma ::theta
                   ::phi-dt ::epsilon-dt ::sigma-dt ::theta-dt]))
