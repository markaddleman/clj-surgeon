(ns clj-surgeon.code-index-fence
  "Pure evaluation fence of query_clojure.

   `read-expression` reads exactly one form with the SCI reader and evaluates
   nothing. `admit` walks that form against an allowlist and returns the first
   denial as data. `check-def` guards the session `def` rules. No function here
   touches the file system, starts a process or evaluates repository bytes."
  (:require
   [clj-surgeon.code-index-functions :as functions]
   [clj-surgeon.edit-dsl :as edit-dsl]
   [clojure.string :as str]
   [sci.core :as sci]))

(def denied-symbols
  "Symbols that admission always refuses. Interop and dynamic vars are refused
   by shape, in `shape-denied?`."
  '#{require in-ns ns eval load-string load-file slurp spit future agent atom
     alter-var-root set! new})

(def ^:private expansion-symbols
  "Symbols that trusted core macros expand into."
  '[lazy-seq loop loop* recur case* throw unchecked-inc
    chunked-seq? chunk-first chunk-rest chunk-buffer chunk-append chunk chunk-cons])

(def ^:private extra-core-symbols
  '[def defn str keys vals sort-by frequencies group-by name namespace count])

(def ^:private core-allowed
  "Unqualified symbols admitted besides locals, bound functions and defs."
  (-> (set edit-dsl/pure-core-symbols)
      (into extra-core-symbols)
      (into expansion-symbols)
      (->> (remove denied-symbols))
      set))

(def ^:private pure-core-names
  "Names a session may not `def` over."
  (-> (set edit-dsl/pure-core-symbols)
      (into extra-core-symbols)
      set))

(defn- bound-names
  "The names of the bound functions, as strings."
  []
  (mapv str (functions/bound-symbols)))

(defn- bound-set
  []
  (set (functions/bound-symbols)))

;; @spec MCP-OP-QUERY-075
(defn- invalid-expression!
  [reason message]
  (throw (ex-info (str "Invalid expression: " message)
                  {:error-type :invalid-expression
                   :reason reason
                   :message message})))

(def ^:private reader-context
  (delay (sci/init {:classes {}})))

;; @spec MCP-OP-QUERY-075
(defn read-expression
  "Read exactly one form from `text` with the SCI reader. Evaluates nothing.
   Throws `:invalid-expression` with `:reason` `:empty`, `:multiple` or
   `:unreadable`."
  [text]
  (when-not (string? text)
    (invalid-expression! :empty "no expression"))
  (let [ctx @reader-context
        reader (sci/reader text)
        form (try
               (sci/parse-next ctx reader)
               (catch Exception e
                 (invalid-expression! :unreadable (or (ex-message e) (str (class e))))))
        trailing (when-not (= :sci.core/eof form)
                   (try
                     (sci/parse-next ctx reader)
                     (catch Exception e
                       (invalid-expression! :unreadable (or (ex-message e) (str (class e)))))))]
    (cond
      (= :sci.core/eof form) (invalid-expression! :empty "no expression")
      (not= :sci.core/eof trailing) (invalid-expression! :multiple "more than one expression")
      :else form)))

;; ---------------------------------------------------------------------------
;; Admission

(def ^:private expansion-context
  "Trusted context that only macroexpands quoted core macros. It never sees a
   user form as code."
  (delay (sci/init {:classes {}})))

(defn- shape-denied?
  "True for interop symbols and dynamic var names."
  [sym]
  (let [n (name sym)]
    (or (str/starts-with? n ".")
        (and (namespace sym) (not= "user" (namespace sym)) (not= "clojure.core" (namespace sym)))
        (and (> (count n) 2) (str/starts-with? n "*") (str/ends-with? n "*")))))

(defn- symbol-admitted?
  [sym locals defs]
  (let [space (namespace sym)
        base (symbol (name sym))]
    (and (not (contains? denied-symbols base))
         (not (shape-denied? sym))
         (or (contains? locals sym)
             (contains? defs sym)
             (contains? defs (str sym))
             (and (nil? space)
                  (or (contains? core-allowed sym)
                      (contains? (bound-set) sym)))
             (and (= "clojure.core" space) (contains? core-allowed base))
             (and (= "user" space) (contains? (bound-set) base))))))

(defn- denial
  [sym]
  {:error-type :denied-symbol
   :symbol (str sym)
   :bound (bound-names)})

(defn- unshadowed?
  [head locals]
  (and (symbol? head) (not (contains? locals head))))

(defn- bare-name
  [head]
  (when (symbol? head) (symbol (name head))))

;; @spec MCP-OP-QUERY-076
;; @spec MCP-OP-QUERY-077
;; @spec MCP-OP-QUERY-078
(defn admit
  "Walk `form` without evaluating any part of it. Returns nil when every symbol
   is admitted, else the first denial in left-to-right, depth-first order:
   `{:error-type :denied-symbol :symbol s :bound [...]}`. `defs` is the set of
   symbols that the session has `def`d. A quoted form is data and is not walked."
  [form defs]
  (let [defs (set defs)]
    (letfn [(walk-many [xs locals] (some #(walk % locals) xs))
            (bindings [pairs locals body]
              (if (seq pairs)
                (or (walk (second pairs) locals)
                    (bindings (nnext pairs) (conj locals (first pairs)) body))
                (walk-many body locals)))
            (fn-body [parts locals]
              (let [[locals parts] (if (symbol? (first parts))
                                     [(conj locals (first parts)) (next parts)]
                                     [locals parts])
                    arities (if (vector? (first parts)) [parts] parts)]
                (some (fn [[args & body]]
                        (when (vector? args)
                          (walk-many body (into locals (remove #{'&} args)))))
                      arities)))
            (walk-def [node locals]
              (let [target (second node)
                    locals (if (symbol? target) (conj locals target) locals)]
                (walk-many (nnext node) locals)))
            (walk-list [node locals]
              (let [head (first node)
                    bare (bare-name head)
                    free? (unshadowed? head locals)]
                (cond
                  (and free? (= 'quote bare)) nil
                  (and free? (= 'fn* bare)) (fn-body (next node) locals)
                  (and free? (#{'let* 'loop*} bare))
                  (when (and (vector? (second node)) (even? (count (second node))))
                    (bindings (second node) locals (nnext node)))
                  (and free? (#{'case 'case*} bare))
                  (let [clauses (nnext node)]
                    (or (walk (second node) locals)
                        (walk-many (take-nth 2 (next clauses)) locals)
                        (when (odd? (count clauses))
                          (walk (last clauses) locals))))
                  (and free? (= 'def bare)) (walk-def node locals)
                  :else
                  (let [macro-var (when free? (sci/resolve @expansion-context head))]
                    (if (:macro (meta macro-var))
                      (walk (sci/eval-form @expansion-context
                                           (list 'macroexpand (list 'quote node)))
                            locals)
                      (walk-many node locals))))))
            (walk [node locals]
              (cond
                (symbol? node) (when-not (symbol-admitted? node locals defs)
                                 (denial node))
                (seq? node)
                (let [head (first node)]
                  (or (when (symbol? head)
                        (when-not (symbol-admitted? head locals defs)
                          (denial head)))
                      (walk-list node locals)))
                (coll? node) (walk-many node locals)
                :else nil))]
      (walk form #{}))))

;; ---------------------------------------------------------------------------
;; Defs

;; @spec MCP-OP-QUERY-080
(defn def-names
  "The names of every `def` and `defn` form in `form`, nested ones included, in
   walk order. Quoted data is not walked."
  [form]
  (letfn [(def-head? [head]
            (and (symbol? head)
                 (contains? #{'def 'defn} (bare-name head))
                 (contains? #{nil "clojure.core"} (namespace head))))
          (walk [node]
            (cond
              (seq? node)
              (let [head (first node)]
                (cond
                  (= 'quote head) []
                  (def-head? head)
                  (into (if (symbol? (second node)) [(second node)] [])
                        (mapcat walk (rest node)))
                  :else (into [] (mapcat walk) node)))
              (coll? node) (into [] (mapcat walk) node)
              :else []))]
    (walk form)))

;; @spec MCP-OP-QUERY-073
;; @spec MCP-OP-QUERY-080
(defn check-def
  "Examine the `def-names` of `form`. Returns nil when all is well, else the
   first problem as data: `:denied-symbol` for a qualified name, a bound
   function name or a core name, `:session-full` when the names would take the
   session over `max-defs`."
  [form defs max-defs]
  (let [names (def-names form)
        held (set (map symbol defs))]
    (or (some (fn [sym]
                (when (or (namespace sym)
                          (contains? (bound-set) sym)
                          (contains? pure-core-names sym))
                  (denial sym)))
              names)
        (when (> (count (into held names)) max-defs)
          {:error-type :session-full
           :defs (count held)
           :limit max-defs}))))
