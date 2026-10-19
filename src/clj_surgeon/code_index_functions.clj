(ns clj-surgeon.code-index-functions
  "The six functions that `query_clojure` binds in the evaluation context:
   `namespaces`, `skeleton`, `form`, `refs`, `pull` and `help`.

   Each plain function takes the index map first and returns plain data only.
   `bindings` closes them over one index and records the files they touch.
   The data shapes are in docs/intent/code-index/code-index-api.md."
  (:require
   [clj-surgeon.code-index :as code-index]
   [clj-surgeon.code-index-judge :as judge]
   [clj-surgeon.code-index-pull :as pull]
   [clj-surgeon.code-index-search :as search-index]
   [clojure.string :as str]))

;; @spec MCP-OP-QUERY-036
(defn bound-symbols
  "The symbols bound in the evaluation context, in order. The spike functions
   `locate` and `judge` are bound only when CLJ_SURGEON_JUDGE is \"1\"."
  []
  (cond-> '[search namespaces skeleton form refs pull help]
    judge/enabled? (into '[locate judge])))

(defn- refuse
  "Throw an invalid-expression refusal with `message`."
  [message data]
  (throw (ex-info message (assoc data :error-type :invalid-expression))))

;; ---------------------------------------------------------------------------
;; Versions of a var (MCP-OP-QUERY-099, 100)
;; ---------------------------------------------------------------------------

(defn- versions
  "The version records of `qname`: one for each platform on which a `.cljc` file
   defines it, `:clj` before `:cljs`, or one record. Empty when no file defines it.
   A bare record (an index written before the platform split) counts as one version."
  [index qname]
  (let [v (get-in index [:vars qname])]
    (cond (nil? v) []
          (map? v) [v]
          :else (vec v))))

(defn- ns-qnames
  "The qnames of the vars that the file of namespace `ns-name` defines, in the
   order of that file. A qname is listed once, whatever its number of versions."
  [index ns-name]
  (when-let [file (get-in index [:namespaces ns-name :file])]
    (filterv #(some (fn [rec] (= file (:file rec))) (versions index %))
             (get-in index [:files file :vars]))))

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
                        :vars (count (ns-qnames index ns-name))}
                 (:doc rec) (assoc :doc (:doc rec)))))))

(defn- var-map
  "The skeleton map of a var record: no source and no docstring."
  [rec]
  (cond-> {:kind :var
           :qname (:qname rec)
           :ns (:ns rec)
           :name (:name rec)
           :platform (:platform rec)
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
;; @spec MCP-OP-QUERY-100
(defn skeleton
  "The var maps of namespace `ns` (a symbol or string), in start order.
   A var with two versions gives two var maps, the `:clj` version first, each
   with its `:platform`. The vector carries the metadata `{:kind :skeleton :ns :doc}`."
  [index ns]
  (let [ns-name (str ns)
        rec (get-in index [:namespaces ns-name])]
    (when-not rec
      (pull/unknown-namespace! index ns-name))
    (with-meta
      (->> (ns-qnames index ns-name)
           (map (fn [q] (mapv var-map (versions index q))))
           (sort-by #(:start (first %)))
           (into [] cat))
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
;; @spec MCP-OP-QUERY-100
(defn form
  "One `{:kind :form :qname :platform :file :start :end :source}` map for each
   version of each qualified symbol or string in `sym-or-syms`, in the order
   given, the `:clj` version of a var before its `:cljs` version."
  [index sym-or-syms]
  (let [items (if (sequential? sym-or-syms) sym-or-syms [sym-or-syms])
        qnames (mapv qualified-name items)]
    (into []
          (mapcat (fn [q]
                    (let [recs (versions index q)]
                      (when (empty? recs)
                        (pull/unknown-var! index q))
                      (map (fn [rec]
                             {:kind :form
                              :qname q
                              :platform (:platform rec)
                              :file (:file rec)
                              :start (:start rec)
                              :end (:end rec)
                              :source (:source rec)})
                           recs))))
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

;; ---------------------------------------------------------------------------
;; search
;; ---------------------------------------------------------------------------

(defn- invalid-search!
  [message]
  (refuse message {:remedy "Call (search \"words from the task\") or (search \"words\" {:limit 5 :include-tests true})."}))

;; @spec MCP-OP-QUERY-136
(defn- check-search-args!
  [text opts]
  (let [max-chars (:max-expression-chars code-index/default-bounds)]
    (when-not (and (string? text) (not (str/blank? text)))
      (invalid-search! "search needs a text string that is not blank"))
    (when (> (count text) max-chars)
      (invalid-search! (str "search text is longer than " max-chars " characters")))
    (when-not (map? opts)
      (invalid-search! "search options must be a map"))
    (when-let [extra (seq (remove #{:limit :include-tests} (keys opts)))]
      (invalid-search! (str "unknown search option " (pr-str (first extra)))))
    (when (contains? opts :limit)
      (let [n (:limit opts)]
        (when-not (and (integer? n) (<= 1 n 50))
          (invalid-search! "search :limit must be an integer from 1 to 50"))))
    (when (contains? opts :include-tests)
      (when-not (boolean? (:include-tests opts))
        (invalid-search! "search :include-tests must be true or false")))))

;; @spec MCP-OP-QUERY-136
;; @spec MCP-OP-QUERY-137
;; @spec MCP-OP-QUERY-138
;; @spec MCP-OP-QUERY-139
(defn search
  "The skeleton var maps of the vars that best match the words of `text`, best
   first, at most `:limit` (default 10). `:name` holds the qualified name. Test
   files are left out unless `:include-tests` is true. The vector carries the
   metadata `{:kind :search :query text}`."
  ([index text] (search index text {}))
  ([index text opts]
   (check-search-args! text opts)
   (let [limit (:limit opts 10)]
     (with-meta
       (mapv #(assoc (var-map %) :name (:qname %))
             (search-index/ranked-records index text (boolean (:include-tests opts)) limit))
       {:kind :search :query text}))))

(defn search-index
  "The cached search index of `index` (MCP-OP-QUERY-140)."
  [index]
  (search-index/search-index index))

(def ^:private help-blocks
  "The help text of each bound function, as [name block]."
  [["search" (str "(search text) (search text opts)\n"
                  "  Start here: the vars whose name, docstring or source best match the words, best first.\n"
                  "  Give words from the task, such as field names or phrases. Options :limit (1 to 50, default 10)\n"
                  "  and :include-tests (default false).\n"
                  "  Example: (search \"ServiceName data page\" {:limit 5})")]
   ["namespaces" (str "(namespaces )\n"
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

(def ^:private spike-help-blocks
  "SPIKE (issue #27): help for `locate` and `judge`, shown only when they are bound."
  [["locate" (str "(locate text)\n"
                  "  Start here for a task: give the issue text or a few words. A cheap model searches the index in\n"
                  "  three rounds and returns up to 8 vars that may need to change, most likely first, each with a\n"
                  "  reason and the relevant lines. About 2,000 characters. Check them with form before you edit.\n"
                  "  Example: (locate \"The TRD names a service document after its activity, not its connector\")")]
   ["judge" (str "(judge question data)\n"
                 "  Ask a cheap model one question about data, for example the source from form. Returns its answer.\n"
                 "  Example: (judge \"Which lines read ServiceName?\" (form ['a.b/f]))")]])

(defn- all-help-blocks []
  (cond-> help-blocks judge/enabled? (into spike-help-blocks)))

;; @spec MCP-OP-QUERY-046
(defn help
  "The help text of the six bound functions, or of `fn-name` alone. An unknown name
   gives a message string and does not throw."
  ([] (str/join "\n\n" (map second (all-help-blocks))))
  ([fn-name]
   (let [n (str fn-name)]
     (or (some (fn [[k block]] (when (= k n) block)) (all-help-blocks))
         (str "no such function: " n ". Functions: "
              (str/join ", " (map first (all-help-blocks))))))))

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
                 :var/qname (:file (first (versions index v)))
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
;; @spec MCP-OP-QUERY-107
;; @spec MCP-OP-QUERY-112
;; @spec MCP-OP-QUERY-129
(defn bindings
  "The SCI bindings for `user`: the six bound symbols, closed over `index`.
   Each call adds the files of the data it returns to the set in the atom `touched`.
   `opts` may hold `:max-pull-nodes` and `:interrupt-fn` (a zero-argument
   function). `pull` passes both to the pull, which counts its entities against
   the budget and calls the hook for each entity."
  ([index touched] (bindings index touched nil))
  ([index touched opts]
   (let [pull-opts (select-keys opts [:max-pull-nodes :interrupt-fn])]
     (cond-> {'namespaces (touching touched result-files (fn [] (namespaces index)))
              'skeleton (touching touched result-files (fn [ns] (skeleton index ns)))
              'form (touching touched result-files (fn [syms] (form index syms)))
              'refs (touching touched result-files
                              (fn ([sym] (refs index sym))
                                ([sym opts] (refs index sym opts))))
              'pull (touching touched #(pull-files index %)
                              (fn [pattern eid] (pull/pull index pattern eid pull-opts)))
              'search (touching touched result-files
                                (fn ([text] (search index text))
                                  ([text opts] (search index text opts))))
              'help (fn ([] (help)) ([fn-name] (help fn-name)))}
       ;; SPIKE (issue #27): only when CLJ_SURGEON_JUDGE is "1"
       judge/enabled?
       (assoc 'locate (fn [text] (judge/locate index text))
              'judge (fn [question data] (judge/judge question data)))))))

(defn pull
  "See `clj-surgeon.code-index-pull/pull`."
  [index pattern eid]
  (pull/pull index pattern eid))
