;; TODO: think through exception types
;; TODO: thread format string position through to all exceptions and add 
;; positional errors

(ns format
  (:import [format InternalFormatException]))

(def teststr "The answer is ~7D.")
(def teststr1 "The answer is ~7,3,'*@D.")

;;; Forward references
(declare compile-format)
(declare execute-format)
(declare arg-navigator)
;;; End forward references

(defn cl-format 
  "An implementation of a (mostly) Common Lisp compatible format function"
  [stream format-in & args]
  (let [compiled-format (if (string? format-in) (compile-format format-in) format-in)
	navigator (struct arg-navigator args args 0) ]
    (execute-format stream compiled-format navigator))
)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helper functions for digesting formats in the various
;;; phases of their lives.
;;; These functions are actually pretty general.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn map-passing-context [func initial-context lis]
  (loop [context initial-context
	 lis lis
	 acc []]
    (if (not lis)
      [acc context]
    (let [this (first lis)
	  remainder (rest lis)
	  [result new-context] (apply func [this context])]
      (recur new-context remainder (conj acc result))))))

(defn consume [func initial-context]
  (loop [context initial-context
	 acc []]
    (let [[result new-context] (apply func [context])]
      (if (not result)
	[acc context]
      (recur new-context (conj acc result))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Argument navigators manage the argument list
;;; as the format statement moves through the list
;;; (possibly going forwards and backwards as it does so)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defstruct arg-navigator :seq :rest :pos )

(defn next-arg [ navigator ]
  (let [ rst (:rest navigator) ]
    (if rst
      [(first rst) (struct arg-navigator (:seq navigator ) (rest rst) (inc (:pos navigator)))]
      (throw (new Exception  "Not enough arguments for format definition")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; When looking at the parameter list, we may need to manipulate
;;; the argument list as well (for 'V' and '#' parameter types).
;;; We hide all of this behind a function, but clients need to
;;; manage changing arg navigator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: validate parameters when they come from arg list
(defn realize-parameter [[param raw-val] navigator]
  (let [[real-param new-navigator]
	(cond 
	 (contains? #{ :at :colon } param) ;pass flags through unchanged - this really isn't necessary
	 [raw-val navigator]

	 (= raw-val :parameter-from-args) 
	 (next-arg navigator)

	 (= raw-val :remaining-arg-count) 
	 [(count (:rest navigator)) navigator]

	 true 
	 [raw-val navigator])]
    [[param real-param] new-navigator]))
	 
(defn realize-parameter-list [parameter-map navigator]
  (let [[pairs new-navigator] 
	(map-passing-context realize-parameter navigator parameter-map)]
    [(into {} pairs) new-navigator]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; The table of directives we support, each with its params,
;;; properties, and the compilation function
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; We start with a couple of helpers
(defn process-directive-table-element [ [ char params flags & generator-fn ] ]
  [char, 
   {:directive char,
    :params `(array-map ~@params),
    :flags flags,
    :generator-fn (concat '(fn [ params ]) generator-fn) }])

(defmacro defdirectives 
  [ & directives ]
  `(def directive-table (hash-map ~@(mapcat process-directive-table-element directives))))

(defdirectives 
  (\A 
   [ :mincol [0 Integer] :colinc [1 Integer] :minpad [0 Integer] :padchar [\space Character] ] 
   #{ :at }
   (fn [ params arg-navigator ]
     (let [ [arg arg-navigator] (next-arg arg-navigator) ]
       (print arg)				; TODO: support padding and columns
       arg-navigator)))
  (\% 
   [ :count [1 Integer] ] 
   #{ }
   (fn [ params arg-navigator ]
     (dotimes [i (:count params)]
       (prn)
       arg-navigator)))
)
 
(def param-pattern #"^([vV]|#|('.)|([+-]?\d+)|(?=,))")
(def special-params #{ :parameter-from-args :remaining-arg-count })

(defn extract-param [[s offset saw-comma]]
  (let [m (re-matcher param-pattern s)
	param (re-find m)]
    (if param
      (let [token-str (first (re-groups m))
	    remainder (subs s (.end m))
	    new-offset (+ offset (.end m))]
	(if (not (= \, (nth remainder 0)))
	  [ token-str [remainder new-offset false]]
	  [ token-str [(subs remainder 1) (inc new-offset) true]]))
      (if saw-comma 
	(throw (InternalFormatException. "Badly formed parameters in format directive" offset))
	[ nil [s offset]]))))


(defn extract-params [s] 
  (consume extract-param [s 0 false]))

(defn translate-param [p]
  "Translate the string representation of a param to the internalized
  representation"
  (cond 
   (= (.length p) 0) nil
   (and (= (.length p) 1) (contains? #{\v \V} (nth p 0))) :parameter-from-args
   (and (= (.length p) 1) (= \# (nth p 0))) :remaining-arg-count
   (and (= (.length p) 2) (= \' (nth p 0))) (nth p 1)
   true (new Integer p)))
 
(def flag-defs { \: :colon, \@ :at })

(defn extract-flags [s offset]
  (consume
   (fn [[s offset flags]]
    (if (= 0 (.length s))
      [nil [s offset flags]]
      (let [flag (get flag-defs (first s))]
	(if flag
	  (if (contains? flags flag)
	    (throw 
	     (InternalFormatException. 
	      (str "Flag \"" (first s) "\" appears more than once in a directive")
	      offset))
	    [true [(subs s 1) (inc offset) (assoc flags flag true)]])
	  [nil [s offset flags]]))))
   [s offset {}]))

(defn check-flags [def flags]
  (let [allowed (:flags def)]
    (if (and (not (:at allowed)) (:at flags))
      (throw (new Exception 
		  (str "@ is an illegal flag for format directive \"" (:directive def) "\""))))
    (if (and (not (:colon allowed)) (:colon flags))
      (throw (new Exception 
		  (str ": is an illegal flag for format directive \"" (:directive def) "\""))))
    (if (and (not (:both allowed)) (:at flags) (:colon flags))
      (throw (new Exception 
		  (str "Cannot combine @ and : flags for format directive \"" (:directive def) "\""))))))

(defn map-params [def params flags]
  "Takes a directive definition and the list of actual parameters and
   a map of flags and returns a map of the parameters and flags with defaults
   filled in. We check to make sure that there are the right types and number
   of parameters as well."
  (check-flags def flags)
  (cond 
   (> (count params) (count (:params def)))
   (throw (new Exception (str "Too many parameters for directive \"" (:directive def) 
			      "\": specified " (count params) " but received " 
			      (count (:params def)))))

   (filter not (map #(or 
		      (nil? %1) 
		      (contains? special-params %1)
		      (instance? (frest (frest %2)) %1)) 
		    params (:params def)))
   (throw (new Exception (str "Bad parameter type for directive \"" (:directive def) "\""))) 
   
   true
   (merge ; create the result map
    (into (array-map)  ; start with the default values, make sure the order is right
	  (reverse (for [[name [default]] (:params def)] [name default])))
    (reduce #(apply assoc %1 %2) {} (filter #(nth % 1) (zipmap (keys (:params def)) params))) ; add the specified parameters, filtering out nils
    flags)) ; and finally add the flags
)

;; TODO helpful error handling
(defn compile-directive [s]
  (let [[raw-params [rest offset]] (extract-params s)
	[_ [rest offset flags]] (extract-flags rest offset)
	directive (nth rest 0)
	def (get directive-table directive)
	params (map-params def (map translate-param raw-params) flags)
	]
    [[ ((:generator-fn def) params) directive params] (subs rest 1)]))
    
(defn compile-raw-string [s]
  [ (fn [_ a] (print s) a) nil nil])

(defn compile-format [ format-str ]
  (first (consume 
	  (fn [s]
	    (if (= 0 (.length s))
	      [nil s]
	      (let [tilde (.indexOf s (int \~))]
		(cond
		 (neg? tilde) [(compile-raw-string s) ""]
		 (zero? tilde)  (compile-directive (subs s 1))
		 true [(compile-raw-string (subs s 0 tilde)) (subs s tilde)]))))
	  format-str)))

(defn execute-format [stream format args]
  (let [real-stream (cond 
		     (not stream) (new java.io.StringWriter)
		     (= stream true) *out*
		     true stream)]
	(binding [*out* real-stream]
	  (map-passing-context 
	   (fn [element context] 
	     (let [[params args] (realize-parameter-list (nth element 2) context)]
	       [nil (apply (first element) [params args])] ))
	   args
	   format)
	  (if (not stream) (.toString real-stream)))))


