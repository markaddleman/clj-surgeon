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
  ([reason message]
   (invalid-expression! reason message nil))
  ([reason message remedy]
   (throw (ex-info (str "Invalid expression: " message)
                   (cond-> {:error-type :invalid-expression
                            :reason reason
                            :message message}
                     remedy (assoc :remedy remedy))))))

(def ^:private reader-context
  (delay (sci/init {:classes {}})))

;; ---------------------------------------------------------------------------
;; Guard for Throwables and the depth scan

(def default-max-depth
  "Default bound of MCP-OP-QUERY-124 when the caller gives none."
  256)

(def ^:private max-error-chars 1000)

(defn- cut
  [text n]
  (if (> (count text) n) (subs text 0 n) text))

;; @spec MCP-OP-QUERY-124
;; @spec MCP-OP-QUERY-125
(defn too-deep-data
  "The ex-data of the refusal `:expression-too-deep` for a `StackOverflowError`,
   with the depth bound `limit` in the remedy. One type for one cause."
  [limit]
  {:error-type :expression-too-deep
   :limit limit
   :remedy (str "The expression nests too deeply; the bound is " limit
                ". Flatten it: use fewer nested forms, or split it into several requests.")})

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-126
(defn- guarded
  "Run `f`. A typed refusal passes. A `StackOverflowError` becomes
   `:expression-too-deep` with the remedy of MCP-OP-QUERY-124. Any other
   `Throwable` becomes `:internal-error`, with no stack trace."
  ([f] (guarded f default-max-depth))
  ([f max-depth]
   (try
     (f)
     (catch StackOverflowError _
       (throw (ex-info "Expression too deep: the expression is nested too deeply"
                       (too-deep-data max-depth))))
     (catch Throwable t
       (if (and (instance? clojure.lang.ExceptionInfo t) (:error-type (ex-data t)))
         (throw t)
         (let [text (cut (str (.getName (class t)) ": " (ex-message t)) max-error-chars)]
           (throw (ex-info (str "Internal error: " text)
                           {:error-type :internal-error
                            :error text
                            :remedy (str "Send the request again. If it fails again, report"
                                         " the failure with this text.")}))))))))

(defn- complete
  "A form ended: let each pending prefix that this form completes end too.
   `stack` holds `:b` for an open bracket and a count of forms still needed for
   a prefix."
  [stack]
  (loop [st stack]
    (let [top (peek st)]
      (cond
        (integer? top) (if (> top 1) (conj (pop st) (dec top)) (recur (pop st)))
        :else st))))

(defn- close
  [stack]
  (loop [st stack]
    (let [top (peek st)]
      (cond
        (nil? top) st
        (integer? top) (recur (pop st))
        :else (complete (pop st))))))

(defn- token-end
  "The index after the atom that starts at `i`."
  [^String text i]
  (let [n (count text)]
    (loop [j i]
      (if (< j n)
        (let [c (.charAt text j)]
          (if (or (Character/isWhitespace c) (contains? #{\, \( \) \[ \] \{ \} \" \;} c))
            j
            (recur (inc j))))
        j))))

(defn- string-end
  "The index after the string that opens at `i`."
  [^String text i]
  (let [n (count text)]
    (loop [j (inc i)]
      (cond
        (>= j n) n
        (= \\ (.charAt text j)) (recur (+ j 2))
        (= \" (.charAt text j)) (inc j)
        :else (recur (inc j))))))

;; @spec MCP-OP-QUERY-124
(defn expression-depth
  "The greatest nesting depth of `text`, by one iterative scan: no recursion and
   no reader. Each open `(`, `[`, `{`, `#(`, `#{` counts one level, and each
   prefix (`'`, backquote, `~`, `~@`, `@`, `^`, `#'`, `#_`, `#?`, `#?@`) counts
   one level until the form that it applies to ends. Strings, comments and
   character literals count for nothing."
  [text]
  (let [^String text (or text "")
        n (count text)]
    (loop [i 0 stack [] deepest 0]
      (if (>= i n)
        deepest
        (let [c (.charAt text i)
              step (fn [st width] [(+ i width) st])
              [i' stack'] (cond
                            (or (Character/isWhitespace c) (= \, c)) (step stack 1)
                            (= \; c) [(let [j (.indexOf text "\n" i)] (if (neg? j) n (inc j)))
                                      stack]
                            (= \" c) [(string-end text i) (complete stack)]
                            (= \\ c) [(token-end text (min n (+ i 2))) (complete stack)]
                            (contains? #{\( \[ \{} c) (step (conj stack :b) 1)
                            (contains? #{\) \] \}} c) (step (close stack) 1)
                            (contains? #{\' \` \@} c) (step (conj stack 1) 1)
                            (= \^ c) (step (conj stack 2) 1)
                            (= \~ c) (step (conj stack 1) (if (and (< (inc i) n) (= \@ (.charAt text (inc i)))) 2 1))
                            (= \# c)
                            (let [d (when (< (inc i) n) (.charAt text (inc i)))]
                              (cond
                                (contains? #{\( \{} d) (step (conj stack :b) 2)
                                (contains? #{\' \_} d) (step (conj stack 1) 2)
                                (= \? d) (step (conj stack 1
                                                     (if (and (< (+ i 2) n) (= \@ (.charAt text (+ i 2)))) 3 2)))
                                :else (step stack 1)))
                            :else [(token-end text i) (complete stack)])]
          (recur i' stack' (max deepest (count stack'))))))))

;; @spec MCP-OP-QUERY-124
(defn check-depth!
  "Throw `:expression-too-deep` when the nesting depth of `text` is greater than
   `max-depth` (default `default-max-depth`). Returns the depth otherwise."
  ([text] (check-depth! text default-max-depth))
  ([text max-depth]
   (let [depth (expression-depth text)]
     (when (> depth max-depth)
       (throw (ex-info (str "Expression too deep: " depth " levels")
                       {:error-type :expression-too-deep
                        :depth depth
                        :limit max-depth
                        :remedy (str "The expression nests " depth " levels deep; the bound is "
                                     max-depth ". Flatten it, or split it into several requests.")})))
     depth)))

;; @spec MCP-OP-QUERY-075
;; @spec MCP-OP-QUERY-124
;; @spec MCP-OP-QUERY-125
(defn read-expression
  "Read exactly one form from `text` with the SCI reader. Evaluates nothing.
   Throws `:expression-too-deep` before it reads when the nesting is over the
   bound (`max-depth`, default 256), and `:invalid-expression` with `:reason`
   `:empty`, `:multiple` or `:unreadable`."
  ([text] (read-expression text default-max-depth))
  ([text max-depth]
   (when-not (string? text)
     (invalid-expression! :empty "no expression"))
   (guarded
    (fn []
      (check-depth! text max-depth)
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
    max-depth)))

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

;; @spec MCP-OP-QUERY-102
(defn- malformed!
  "Refuse a special form that admission cannot walk completely."
  [what]
  (invalid-expression!
   :malformed-form
   (str "malformed " what)
   (str "Write " what " in its documented shape, so that every part of it can be checked.")))

;; @spec MCP-OP-QUERY-076
;; @spec MCP-OP-QUERY-077
;; @spec MCP-OP-QUERY-078
(declare def-shape-problem def-shape-message def-nodes)

(defn- rethrow-expansion-failure
  "A macro of `form` failed to expand, for example `(defn f)`. When a `def` or
   `defn` of `form` is not in an allowed shape, that is the cause: throw the
   `def-shape` refusal. Else rethrow `e`."
  [form ^Throwable e]
  (if-let [message (some def-shape-message (def-nodes form))]
    (let [problem (def-shape-problem message)]
      (throw (ex-info (str "Invalid expression: " message) problem e)))
    (throw e)))

;; @spec MCP-OP-QUERY-102
;; @spec MCP-OP-QUERY-103
(defn- admit*
  "Walk `form` without evaluating any part of it. Returns nil when every symbol
   is admitted, else the first denial in left-to-right, depth-first order:
   `{:error-type :denied-symbol :symbol s :bound [...]}`. `defs` is the set of
   symbols that the session has `def`d. A quoted form is data and is not walked.
   The reader metadata of every symbol and collection is code and is walked.
   Throws `:invalid-expression` for a special form that it cannot walk
   completely."
  [form defs]
  (let [defs (set defs)]
    (letfn [(walk-many [xs locals] (some #(walk % locals) xs))
            (walk-meta [node locals]
              (when (and (instance? clojure.lang.IMeta node) (meta node))
                (walk (meta node) locals)))
            (binder [node locals what]
              (when-not (symbol? node) (malformed! what))
              (walk-meta node locals))
            (bindings [pairs locals body]
              (if (seq pairs)
                (or (binder (first pairs) locals "let* or loop* binding")
                    (walk (second pairs) locals)
                    (bindings (nnext pairs) (conj locals (first pairs)) body))
                (walk-many body locals)))
            (arity [[args & body] locals]
              (or (walk-meta args locals)
                  (some #(binder % locals "fn* argument") args)
                  (walk-many body (into locals (remove #{'&} args)))))
            (fn-body [parts locals]
              (let [named? (symbol? (first parts))
                    fn-name (when named? (first parts))
                    locals (if named? (conj locals fn-name) locals)
                    parts (if named? (next parts) parts)
                    arities (if (vector? (first parts)) [parts] parts)]
                (or (when named? (walk-meta fn-name locals))
                    (some (fn [a]
                            (when-not (and (seq? a) (vector? (first a)))
                              (malformed! "fn* arity"))
                            (arity a locals))
                          arities))))
            (walk-def [node locals]
              (let [target (second node)
                    locals (if (symbol? target) (conj locals target) locals)]
                (or (walk-meta target locals)
                    (walk-many (nnext node) locals))))
            (walk-list [node locals]
              (let [head (first node)
                    bare (bare-name head)
                    free? (unshadowed? head locals)]
                (cond
                  (and free? (= 'quote bare)) nil
                  (and free? (= 'fn* bare)) (fn-body (next node) locals)
                  (and free? (#{'let* 'loop*} bare))
                  (do (when-not (and (vector? (second node)) (even? (count (second node))))
                        (malformed! "let* or loop* binding vector"))
                      (or (walk-meta (second node) locals)
                          (bindings (second node) locals (nnext node))))
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
                      (walk (try
                              (sci/eval-form @expansion-context
                                             (list 'macroexpand (list 'quote node)))
                              (catch StackOverflowError e
                                (throw e))
                              (catch Throwable e
                                (rethrow-expansion-failure form e)))
                            locals)
                      (walk-many node locals))))))
            (walk [node locals]
              (or (walk-meta node locals)
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
                    :else nil)))]
      (walk form #{}))))

;; @spec MCP-OP-QUERY-125
(defn admit
  "Walk `form` without evaluating any part of it (see `admit*`). A
   `StackOverflowError` or any other `Throwable` becomes a typed refusal."
  [form defs]
  (guarded #(admit* form defs)))

;; ---------------------------------------------------------------------------
;; Defs

;; @spec MCP-OP-QUERY-080
(defn- def-nodes
  "Every `def` and `defn` list in `form`, nested ones included, in walk order.
   Quoted data is not walked."
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
                  (def-head? head) (into [node] (mapcat walk (rest node)))
                  :else (into [] (mapcat walk) node)))
              (coll? node) (into [] (mapcat walk) node)
              :else []))]
    (walk form)))

;; @spec MCP-OP-QUERY-080
(defn def-names
  "The names of every `def` and `defn` form in `form`, nested ones included, in
   walk order. Quoted data is not walked."
  [form]
  (into [] (keep #(when (symbol? (second %)) (second %))) (def-nodes form)))

(def ^:private position-keys
  "Keys that the reader adds to the metadata of a form."
  #{:line :column :end-line :end-column :file})

;; @spec MCP-OP-QUERY-104
;; @spec MCP-OP-QUERY-121
(defn- macro-marker
  "The marker keyword (`:macro` or `:sci/macro`) when a metadata map may make a
   var a macro, else nil. A key that is not a keyword literal could evaluate to
   a marker, so it counts as `:macro`."
  [m]
  (let [ks (remove position-keys (keys m))]
    (cond
      (some #{:sci/macro} ks) :sci/macro
      (some #{:macro} ks) :macro
      (some (complement keyword?) ks) :macro)))

;; @spec MCP-OP-QUERY-104
(defn- macro-def
  "The marker keyword when the `def` or `defn` list `node` may define a macro:
   its name, or an attribute map of a `defn` in any position before the body
   of a single arity (after the arities of a multi-arity `defn` too), carries
   macro metadata."
  [node]
  (or (macro-marker (meta (second node)))
      (when (= 'defn (bare-name (first node)))
        (some #(when (map? %) (macro-marker %))
              (take-while (complement vector?) (drop 2 node))))))

(def ^:private def-shape-remedy
  (str "Write a def in one of these shapes: (def name), (def name value),"
       " (def name \"doc\" value), (defn name \"doc\"? [params] body...) or"
       " (defn name \"doc\"? ([params] body...)+). Do not write an attr-map."
       " The only metadata on the name are :doc (a string) and :private"
       " (true or false), as literals."))

(defn- def-shape-problem
  [message]
  {:error-type :invalid-expression
   :reason :def-shape
   :message message
   :remedy def-shape-remedy})

(defn- arity-list?
  [x]
  (and (seq? x) (vector? (first x))))

(defn- defn-rest-ok?
  "True when `more`, the items of a `defn` after its name, are `\"doc\"?` and then
   a parameter vector with a body, or one or more arity lists."
  [more]
  (let [more (if (string? (first more)) (rest more) more)]
    (cond
      (vector? (first more)) true
      (seq more) (every? arity-list? more)
      :else false)))

(defn- name-meta-problem
  "A description of the first metadata key or value on the name `sym` that
   MCP-OP-QUERY-131 does not allow, else nil."
  [sym]
  (some (fn [[k v]]
          (cond
            (not (contains? #{:doc :private} k)) (str "metadata key " (pr-str k) " is not allowed")
            (and (= :doc k) (not (string? v))) "the value of :doc must be a string"
            (and (= :private k) (not (boolean? v))) "the value of :private must be true or false"))
        (apply dissoc (meta sym) position-keys)))

;; @spec MCP-OP-QUERY-130
;; @spec MCP-OP-QUERY-131
(defn- def-shape-message
  "Nil when the `def` or `defn` list `node` is in an allowed shape, else a
   message that names the form."
  [node]
  (let [shown (binding [*print-length* 4 *print-level* 3 *print-meta* false]
                (cut (pr-str (take 4 node)) 120))
        [head target & more] node
        defn? (= 'defn (bare-name head))
        structure-ok? (if defn?
                        (defn-rest-ok? more)
                        (case (count more)
                          0 true
                          1 true
                          2 (string? (first more))
                          false))]
    (cond
      ;; A computed name, such as (with-meta 'a {...}), is refused. Another
      ;; non-symbol is not a name that SCI can define; it appears when a
      ;; threading macro supplies the real name, so it is left to evaluation.
      (seq? target) (str "the name in " shown " must be a plain symbol")
      (not (symbol? target)) nil
      (not structure-ok?) (str "the form " shown " is not in an allowed shape")
      :else (when-let [p (name-meta-problem target)]
              (str "on " target ": " p)))))

;; @spec MCP-OP-QUERY-073
;; @spec MCP-OP-QUERY-080
;; @spec MCP-OP-QUERY-104
;; @spec MCP-OP-QUERY-116
(defn- check-def*
  "Examine the `def-names` of `form`. Returns nil when all is well, else the
   first problem as data: `:denied-symbol` for a qualified name, a bound
   function name, a core name or a macro definition (`:symbol \":macro\"`),
   `:session-full` when the names would take the session over `max-defs`. The
   limit applies only when `form` adds a name that `defs` does not hold."
  [form defs max-defs]
  (let [names (def-names form)
        held (set (map symbol defs))
        added (remove held names)]
    (or (some (fn [sym]
                (when (or (namespace sym)
                          (contains? (bound-set) sym)
                          (contains? pure-core-names sym))
                  (denial sym)))
              names)
        (when-let [marker (some macro-def (def-nodes form))]
          (denial marker))
        (when-let [message (some def-shape-message (def-nodes form))]
          (def-shape-problem message))
        (when (and (seq added) (> (count (into held names)) max-defs))
          {:error-type :session-full
           :defs (count held)
           :limit max-defs}))))

;; @spec MCP-OP-QUERY-125
(defn check-def
  "Examine the `def-names` of `form` (see `check-def*`). A `StackOverflowError`
   or any other `Throwable` becomes a typed refusal."
  [form defs max-defs]
  (guarded #(check-def* form defs max-defs)))
