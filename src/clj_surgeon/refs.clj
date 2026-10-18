(ns clj-surgeon.refs
  "Cross-file references of one var (`:op :refs`).

   The analysis is the clj-kondo analysis (`:var-usages`, `:var-definitions`),
   because kondo resolves the namespace alias, the `:refer`, the `:rename` and
   the local shadowing of each file. This namespace has no grep and no text
   search for references. Text is read for one purpose only: to pick the
   candidate files that carry the name of the var, so that kondo does not
   analyse the whole tree. A file that never contains the name cannot
   reference the var.

   Pure functions take data and return data. `run-kondo!` and `find-refs` are
   the I/O boundary."
  (:require
   [babashka.process :as proc]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [rewrite-clj.zip :as z]))

;; ============================================================
;; Pure: the target
;; ============================================================

(defn parse-sym
  "Split a qualified symbol string `my.ns/my-fn` into `{:ns :name}`.
   Returns nil when the string has no namespace part or no name part.
   A leading `#'` is forgiven. The name keeps a `/` only for the symbol `/`."
  [s]
  (let [s (some-> s str str/trim (str/replace-first #"^#'" ""))
        i (some-> s (str/index-of "/"))]
    (when (and i (pos? i) (< (inc i) (count s)))
      {:ns (subs s 0 i) :name (subs s (inc i))})))

(defn ns-of-source
  "The name of the first `ns` form of a source string, or nil.
   Reader conditionals around the ns form are not needed: the ns form of a
   .clj, .cljs or .cljc file is a plain top-level list."
  [source]
  (let [root (z/of-string source)
        ns-loc (loop [loc root]
                 (cond (nil? loc) nil
                       (and (z/list? loc)
                            (= 'ns (some-> loc z/down z/sexpr)))
                       loc
                       :else (recur (z/right loc))))]
    (some-> ns-loc z/down z/right z/sexpr str)))

(defn candidate-source?
  "True when the source text can reference a var with this simple name."
  [var-name source]
  (str/includes? source var-name))

;; ============================================================
;; Pure: the usages
;; ============================================================

(defn- classify
  "Kind of one reference from the kondo usage maps found at one position."
  [usages]
  (cond
    (some :refer usages)           :refer
    (some (complement :arity) usages) :value
    (some :alias usages)           :alias-call
    :else                          :call))

(defn usages->refs
  "Reference maps for the var `ns`/`name` from a kondo analysis map.
   One map per source position: kondo reports a .cljc call once for each
   language, and a function passed as an argument twice. The result is
   sorted by file, line and column."
  [analysis ns-name var-name]
  (->> (:var-usages analysis)
       (filter #(and (= ns-name (str (:to %)))
                     (= var-name (str (:name %)))))
       (group-by (juxt :filename :row :col))
       vals
       (map (fn [usages]
              (let [named (or (first (filter :name-row usages)) (first usages))
                    kind (classify usages)]
                {:file (:filename named)
                 :line (or (:name-row named) (:row named))
                 :col (or (:name-col named) (:col named))
                 :ns (str (:from named))
                 :form (cond (= :refer kind) "(ns)"
                             (:from-var named) (str (:from-var named))
                             :else "(top-level)")
                 :kind kind})))
       (sort-by (juxt :file :line :col))
       vec))

(defn definition
  "The `{:file :line}` of the definition of `ns`/`name` in a kondo analysis,
   or nil when kondo found none."
  [analysis ns-name var-name]
  (some (fn [d] (when (and (= ns-name (str (:ns d))) (= var-name (str (:name d))))
                  {:file (:filename d) :line (:row d)}))
        (:var-definitions analysis)))

;; ============================================================
;; Pure: output
;; ============================================================

(defn format-compact
  "Compact text: one line per reference, then one total line.
   `context` maps [file line] to the source line; used when present."
  [refs {:keys [sym definition dir]} context]
  (let [files (count (distinct (map :file refs)))
        lines (mapcat (fn [{:keys [file line ns form kind] :as r}]
                        (cons (str file ":" line " " ns "/" form " " (name kind))
                              (when-let [text (get context [file line])]
                                [(str "    " text)])))
                      refs)
        total (str (count refs) " reference" (when (not= 1 (count refs)) "s")
                   " to " sym " in " files " file" (when (not= 1 files) "s")
                   (if definition
                     (str "; defined at " (:file definition) ":" (:line definition))
                     (str "; no definition found under " dir)))]
    (str/join "\n" (concat lines [total]))))

;; ============================================================
;; I/O: kondo
;; ============================================================

(def ^:private kondo-chunk-size 1500)

(def ^:private kondo-config
  (pr-str {:output {:format :edn :canonical-paths false}
           :analysis {:var-usages true :var-definitions true}}))

(defn- refuse [error-type message & {:as extra}]
  (merge {:error message :error-type error-type} extra))

(defn run-kondo!
  "One kondo run over `files` (absolute paths) from the directory `cwd`.
   Returns the analysis map, or a refusal map with `:error`."
  [cwd files]
  (let [result (try
                 (apply proc/shell
                        {:out :string :err :string :continue true :dir cwd}
                        "clj-kondo" "--lint" (concat files
                                                     ["--parallel" "--cache" "false"
                                                      "--config" kondo-config]))
                 (catch Exception e
                   (refuse :analyzer-unavailable
                           (str "clj-kondo could not be started: " (ex-message e))
                           :next-action "install_clj_kondo_on_path")))]
    (cond
      (:error result) result
      (not (#{0 2 3} (:exit result)))
      (refuse :analysis-failed "clj-kondo did not finish the analysis"
              :exit (:exit result) :diagnostic (str/trim (str (:err result))))
      :else
      (try (:analysis (edn/read-string (:out result)))
           (catch Exception e
             (refuse :analysis-invalid
                     (str "clj-kondo returned output that is not EDN: " (ex-message e))))))))

(defn- merge-analyses [analyses]
  {:var-usages (vec (mapcat :var-usages analyses))
   :var-definitions (vec (mapcat :var-definitions analyses))})

(defn- context-lines
  "Map of [file line] to the trimmed source line, for the refs."
  [dir refs]
  (into {}
        (for [[file rs] (group-by :file refs)
              :let [lines (vec (str/split-lines (slurp (str dir "/" file))))]
              r rs]
          [[file (:line r)] (str/trim (get lines (dec (:line r)) ""))])))

(defn- relativize [dir file]
  (let [prefix (str dir "/")]
    (cond-> (str file) (str/starts-with? (str file) prefix) (subs (count prefix)))))

(defn find-refs
  "Find the references of `target` (`{:ns :name}`) in `files` (absolute paths).
   `opts`: `:dir` absolute root, `:format` `:compact` or `:edn`, `:context`.
   Returns a string (compact), a vector (edn) or a refusal map."
  [{:keys [dir context] output-format :format :or {output-format :compact}} target files]
  (let [candidates (filterv #(candidate-source? (:name target) (slurp %)) files)
        analyses (map #(run-kondo! dir %) (partition-all kondo-chunk-size candidates))]
    (if-let [failure (first (filter :error analyses))]
      failure
      (let [analysis (merge-analyses analyses)
            rel (fn [m] (update m :file #(relativize dir %)))
            refs (mapv rel (usages->refs analysis (:ns target) (:name target)))
            ctx (when context (context-lines dir refs))
            refs (if context
                   (mapv #(assoc % :context (get ctx [(:file %) (:line %)])) refs)
                   refs)
            defn-site (some-> (definition analysis (:ns target) (:name target)) rel)]
        (if (= :edn output-format)
          refs
          (format-compact refs {:sym (str (:ns target) "/" (:name target))
                                :definition defn-site
                                :dir dir}
                          ctx))))))
