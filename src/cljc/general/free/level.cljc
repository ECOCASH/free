;;; This software is copyright 2016 by Marshall Abrams, and
;;; is distributed under the Gnu General Public License version 3.0 as
;;; specified in the file LICENSE.

;; Based on Rafal Bogacz's, "A Tutorial on the Free-energy Framework 
;; for Modelling Perception and Learning", _Journal of Mathematical 
;; Psychology_ (online 2015), http://dx.doi.org/10.1016/j.jmp.2015.11.003

;; SEE doc/level.md for documentation on general features of the code below.

#?(:clj  (ns free.level
           (:require [free.arithmetic :refer [e* m* m+ m- tr inv make-identity-obj limit-sigma]] ; could be scalar or matrix
                     [utils.string :as us]))
   :cljs (ns free.level
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
         err-inc   next-err 
         sigma-inc next-sigma
         gen-wt-inc next-gen-wt
         next-level next-levels
         m-square)

;;;;;;;;;;;;;;;;;;;;;
;; Level

                                       ; In Bogacz:
(defrecord Level [phi err sigma gen-wt ; phi, epsilon, sigma, theta
                  gen gen'             ; h, h'
                  phi-dt err-dt sigma-dt gen-wt-dt]) ; increment sizes for approaching limit

(def Level-docstring
  "\n  A Level records values at one level of a prediction-error/free-energy
  minimization model.  
  phi:     Current value of input at this level, or generative function parameter.
  err:     Epsilon--the error at this level.
  sigma:   Covariance matrix or variance of assumed distribution over inputs 
           at this level.  Variance should usually be >= 1 (p. 5 col 2).
  gen-wt:  Scaling factor theta (scalar or matrix) for generative function.  When 
           gen-wt is multiplied by result of gen(phi), the result is the current 
           estimated mean of the assumed distrubtion.  
           i.e. g(phi) = gen-wt * gen(phi), where '*' here is scalar or matrix 
           multiplication as appropriate.
  <x>-dt:  A scalar multiplier (e.g. 0.01) determining how fast <x> is updated.
  gen, gen': See gen-wt; gen' is the derivative of gen.  These never change.

  All of these notations are defined in Bogacz's \"Tutorial\" paper.
  phi and err can be scalars, in which case gen-wt and sigma are as well.  
  Or phi and err can be vectors of length n, in which case sigma and gen-wt
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
         :phi (next-phi  -level  level)
         :err (next-err   level +level)
         :sigma (next-sigma level)
         :gen-wt (next-gen-wt level +level)))

;; See notes in levels.md on this function.
(defn next-levels
  "Given a functions for updating gen, gen', and a bottom-level creation function
  that accepts two levels (its level and the next up), along with a sequence of 
  levels at one timestep, returns a vector of levels at the next timestep.  
  The top level will be used to calculate the next level down, but won't be 
  remade; it will be used again, as is, as the new top level."
  [next-bottom levels]
  (concat [(next-bottom (take 2 levels))] ; Bottom level is special case.
          (map next-level                 ; Each middle level depends on levels
               (partition 3 1 levels))    ;  immediately below and above it.
          [(last levels)]))               ; make sure top is carried forward

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
           :err (next-err level +level)
           :sigma (next-sigma level)
           :gen-wt (next-gen-wt level +level))))

(defn make-top-level
  "Makes a top level with constant value phi for :phi.  Also sets :gen to
  the identity function, which the next level down will use to update eps.
  Other fields will be nil."
  [phi]
  (map->Level {:phi phi :gen identity})) ; other fields will be nil

;;;;;;;;;;;;;;;;;;;;;
;; phi update

(defn phi-inc
  "Calculates slope/increment to the next 'hypothesis' phi from the 
  current phi using the error -err from the level below, scaled by
  the generative function scaling factor gen-wt and the derivative gen' of 
  the generative function gen at this level, and subtracting the error at 
  this level.  See equations (44), (53) in Bogacz's \"Tutorial\"."
  [phi err -err -gen-wt gen']
  (m- (e* (gen' phi)
          (m* (tr -gen-wt) -err))
      err))

(defn next-phi 
  "Calculates the the next-timestep 'hypothesis' phi from this level 
  and the one below."
  [-level level]
  (let [{:keys [phi phi-dt err gen']} level
        -err (:err -level)
        -gen-wt (:gen-wt -level)]
    (m+ phi 
        (e* phi-dt
            (phi-inc phi err -err -gen-wt gen')))))

;;;;;;;;;;;;;;;;;;;;;
;; epsilon update

(defn err-inc 
  "Calculates the slope/increment to the next 'error' epsilon from 
  the current epsilon, using the mean of the generative model at the
  next level up, but scaling the current error err by the
  variance/cov-matrix at this level, and making the whole thing
  relative to phi at this level. See equation (54) in Bogacz's \"Tutorial\"."
  [err phi +phi sigma gen-wt +gen]
  (m- phi 
      (m* gen-wt (+gen +phi))
      (m* sigma err)))

(defn next-err
  "Calculates the next-timestep 'error' epsilon from this level and the one
  above."
  [level +level]
  (let [{:keys [phi err err-dt sigma gen-wt]} level
        +phi (:phi +level)
        +gen (:gen +level)]
    (m+ err
        (e* err-dt
            (err-inc err phi +phi sigma gen-wt +gen)))))

;;;;;;;;;;;;;;;;;;;;;
;; sigma update

(defn sigma-inc
  "Calculates the slope/increment to the next sigma from the current sigma,
  i.e. the variance or the covariance matrix of the distribution of inputs 
  at this level.  See equation (55) in Bogacz's \"Tutorial\".  (Note uses 
  matrix inversion for vector/matrix calcualtions, a non-Hebbian calculation,
  rather than the local update methods of section 5.)"
  [err sigma]
  (e* 0.5 (m- (m-square err)
              (inv sigma))))

(defn next-sigma
  "Calculates the next-timestep sigma, i.e. the variance or the covariance 
  matrix of the distribution of inputs at this level."
  [level]
  (let [{:keys [err sigma sigma-dt]} level]
    (limit-sigma
      (m+ sigma
          (e* sigma-dt
              (sigma-inc err sigma))))))


;;;;;;;;;;;;;;;;;;;;;
;; gen-wt update

(defn gen-wt-inc
  "Calculates the slope/increment to the next gen-wt component of the mean
  value function from the current gen-wt using the error err at this level
  along with the mean of the generative function at the next level up.  
  See equation (56) in Bogacz's \"Tutorial\"."
  [err +phi +gen]
  (m* err 
      (tr (+gen +phi))))

(defn next-gen-wt
  "Calculates the next-timestep gen-wt component of the mean value function
  from this level and the one above."
  [level +level]
  (let [{:keys [err gen-wt gen-wt-dt]} level
        +phi (:phi +level)
        +gen (:gen +level)]
    (m+ gen-wt
        (e* gen-wt-dt
            (gen-wt-inc err +phi +gen)))))

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

