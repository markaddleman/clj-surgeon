(ns clj-surgeon.code-index-functions
  "The six functions that `query_clojure` binds in the evaluation context:
   `namespaces`, `skeleton`, `form`, `refs`, `pull` and `help`.

   Each plain function takes the index map first and returns plain data only.
   `bindings` closes them over one index and records the files they touch.
   The data shapes are in docs/intent/code-index/code-index-api.md."
  (:require
   [clj-surgeon.code-index-pull :as pull]
   [clojure.string :as str]))

;; @spec MCP-OP-QUERY-036
(defn bound-symbols
  "The symbols bound in the evaluation context, in order."
  []
  '[namespaces skeleton form refs pull help])

(defn- refuse
  "Throw an invalid-expression refusal with `message`."
  [message data]
  (throw (ex-info message (assoc data :error-type :invalid-expression))))

;; ---------------------------------------------------------------------------
;; namespaces and skeleton
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-038
(defn namespaces
  "One `{:kind :ns :ns :file :vars :doc}` map for each indexed namespace, sorted by name."
  [index]
  (->> (:namespaces index)
       (sort-by key)
       (mapv (fn [[ns-name rec]]
               (cond-> {:kind :ns
                        :ns ns-name
                        :file (:file rec)
                        :vars (count (pull/ns-var-qnames index ns-name))}
                 (:doc rec) (assoc :doc (:doc rec)))))))

(defn- var-map
  "The skeleton map of a var record: no source and no docstring."
  [rec]
  (cond-> {:kind :var
           :qname (:qname rec)
           :ns (:ns rec)
           :name (:name rec)
           :file (:file rec)
           :start (:start rec)
           :end (:end rec)
           :arglists (vec (:arglists rec))
           :private? (boolean (:private? rec))
           :calls (vec (:calls rec))
           :fx (vec (:fx rec))
           :throws? (boolean (:throws? rec))}
    (:intent rec) (assoc :intent (:intent rec))))

;; @spec MCP-OP-QUERY-039
;; @spec MCP-OP-QUERY-040
;; @spec MCP-OP-QUERY-041
;; @spec MCP-OP-QUERY-054
(defn skeleton
  "The var maps of namespace `ns` (a symbol or string), in start order.
   The vector carries the metadata `{:kind :skeleton :ns :doc}`."
  [index ns]
  (let [ns-name (str ns)
        rec (get-in index [:namespaces ns-name])]
    (when-not rec
      (pull/unknown-namespace! index ns-name))
    (with-meta
      (->> (pull/ns-var-qnames index ns-name)
           (map #(var-map (get-in index [:vars %])))
           (sort-by :start)
           vec)
      (cond-> {:kind :skeleton :ns ns-name}
        (:doc rec) (assoc :doc (:doc rec))))))

;; ---------------------------------------------------------------------------
;; form
;; ---------------------------------------------------------------------------

(defn- qualified-name
  "The qname string of a qualified symbol or string, else invalid-expression."
  [x]
  (let [s (cond (symbol? x) (when (namespace x) (str x))
                (string? x) x)]
    (if (and s (pos? (or (str/index-of s "/") 0)))
      s
      (refuse (str "form takes qualified symbols, got " (pr-str x))
              {:reason :not-qualified :value (pr-str x)}))))

;; @spec MCP-OP-QUERY-042
;; @spec MCP-OP-QUERY-055
(defn form
  "One `{:kind :form :qname :file :start :end :source}` map for each qualified
   symbol or string in `sym-or-syms`, in the order given."
  [index sym-or-syms]
  (let [items (if (sequential? sym-or-syms) sym-or-syms [sym-or-syms])
        qnames (mapv qualified-name items)]
    (mapv (fn [q]
            (let [rec (get-in index [:vars q])]
              (when-not rec
                (pull/unknown-var! index q))
              {:kind :form
               :qname q
               :file (:file rec)
               :start (:start rec)
               :end (:end rec)
               :source (:source rec)}))
          qnames)))

;; ---------------------------------------------------------------------------
;; refs
;; ---------------------------------------------------------------------------

(defn- test-file?
  "True when a directory segment of `path` is `test` or `tests`."
  [path]
  (boolean (some #{"test" "tests"} (butlast (str/split path #"/")))))

(defn- use-map
  "The refs map of one use."
  [index {:keys [file line from kind]}]
  {:kind :use
   :file file
   :line line
   :ns (if from
         (pull/qname-ns from)
         (get-in index [:files file :ns]))
   :from (when from (pull/qname-name from))
   :use-kind kind})

(defn- known-target?
  "True when `qname` has a var record, uses or callers in the index."
  [index qname]
  (or (contains? (:vars index) qname)
      (contains? (:uses-by-target index) qname)
      (contains? (:callers index) qname)))

;; @spec MCP-OP-QUERY-043
;; @spec MCP-OP-QUERY-044
;; @spec MCP-OP-QUERY-045
;; @spec MCP-OP-QUERY-055
(defn refs
  "One `{:kind :use :file :line :ns :from :use-kind}` map for each use of `sym`,
   sorted by file, line, from and use kind. `opts` `{:include-tests false}` leaves
   out the uses in test directories."
  ([index sym] (refs index sym {}))
  ([index sym opts]
   (when-not (map? opts)
     (refuse "refs options are a map such as {:include-tests false}"
             {:reason :bad-options :value (pr-str opts)}))
   (let [qname (str sym)]
     (when-not (known-target? index qname)
       (pull/unknown-var! index qname))
     (->> (get-in index [:uses-by-target qname])
          (remove #(and (false? (:include-tests opts)) (test-file? (:file %))))
          (map #(use-map index %))
          (sort-by (juxt :file :line #(or (:from %) "") #(name (:use-kind %))))
          vec))))

;; ---------------------------------------------------------------------------
;; help
;; ---------------------------------------------------------------------------

(def ^:private help-blocks
  "The help text of each bound function, as [name block]."
  [["namespaces" (str "(namespaces )\n"
                      "  The namespaces of the index, one map each, sorted by name.\n"
                      "  Example: (namespaces)")]
   ["skeleton" (str "(skeleton ns)\n"
                    "  The vars of a namespace, one var map each, in source order.\n"
                    "  Example: (skeleton 'a.b)")]
   ["form" (str "(form sym-or-syms)\n"
                "  The source of one or more vars, given as qualified symbols.\n"
                "  Example: (form ['a.b/f 'a.b/g])")]
   ["refs" (str "(refs sym) (refs sym opts)\n"
                "  Every use of a var. Option :include-tests false leaves out test files.\n"
                "  Example: (refs 'a.b/f {:include-tests false})")]
   ["pull" (str "(pull pattern eid)\n"
                "  Attributes and joins of one entity. A join holds a pattern or a depth of 1 to 3.\n"
                "  Example: (pull [:var/qname {:var/calls 2}] [:var/qname \"a.b/f\"])")]
   ["help" (str "(help [fn-name])\n"
                "  The help text of all functions, or of the named function.\n"
                "  Example: (help 'form)")]])

;; @spec MCP-OP-QUERY-046
(defn help
  "The help text of the six bound functions, or of `fn-name` alone. An unknown name
   gives a message string and does not throw."
  ([] (str/join "\n\n" (map second help-blocks)))
  ([fn-name]
   (let [n (str fn-name)]
     (or (some (fn [[k block]] (when (= k n) block)) help-blocks)
         (str "no such function: " n ". Functions: "
              (str/join ", " (map first help-blocks)))))))

;; ---------------------------------------------------------------------------
;; bindings
;; ---------------------------------------------------------------------------

(defn- result-files
  "The :file of each map in `result`."
  [result]
  (keep :file result))

(defn- pull-files
  "The files of the vars and namespaces that appear anywhere in a pull result."
  [index result]
  (->> (tree-seq coll? seq result)
       (filter map-entry?)
       (keep (fn [[k v]]
               (case k
                 :var/qname (get-in index [:vars v :file])
                 :ns/name (get-in index [:namespaces v :file])
                 nil)))))

(defn- touching
  "Wrap `f` so that each call adds `(files-of result)` to the `touched` atom."
  [touched files-of f]
  (fn [& args]
    (let [result (apply f args)]
      (swap! touched into (files-of result))
      result)))

;; @spec MCP-OP-QUERY-033
;; @spec MCP-OP-QUERY-036
(defn bindings
  "The SCI bindings for `user`: the six bound symbols, closed over `index`.
   Each call adds the files of the data it returns to the set in the atom `touched`."
  [index touched]
  {'namespaces (touching touched result-files (fn [] (namespaces index)))
   'skeleton (touching touched result-files (fn [ns] (skeleton index ns)))
   'form (touching touched result-files (fn [syms] (form index syms)))
   'refs (touching touched result-files
                   (fn ([sym] (refs index sym))
                     ([sym opts] (refs index sym opts))))
   'pull (touching touched #(pull-files index %)
                   (fn [pattern eid] (pull/pull index pattern eid)))
   'help (fn ([] (help)) ([fn-name] (help fn-name)))})

(defn pull
  "See `clj-surgeon.code-index-pull/pull`."
  [index pattern eid]
  (pull/pull index pattern eid))
