(ns clj-surgeon.code-index-ingest
  "Ingest of one source file into a code-index file result.

   clj-kondo runs in process over the text of the file: `with-in-str` and
   `:lint [\"-\"]`, all linters off, no hooks, and only the `:lint-as` map that
   the caller passes in. The namespace does file work only through its
   arguments: it never reads or writes a file.

   The pure steps (the kondo config, the throw scan, the intent rule, the
   assembly of var records) are small functions, so a test checks them with
   literal sources."
  (:require
   [clj-kondo.core :as kondo]
   [clj-kondo.impl.config :as kondo-config]
   [clojure.string :as str]))

(def fx-names
  "Names of the side-effect functions that the `:fx` facet reports."
  #{"swap!" "reset!" "spit" "transact" "transact!" "sh" "shell" "process"
    "delete-file" "send" "put!" ">!" "future" "println"})

(def ^:private intent-max-chars
  "The longest intent, in characters."
  200)

(def ^:private no-config-dir
  "A kondo config directory that does not exist, so that kondo loads no
   workspace `.clj-kondo/config.edn` and runs no workspace hook by itself."
  "/nonexistent/clj-surgeon-no-kondo-config")

;; ---------------------------------------------------------------------------
;; kondo configuration
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-018
;; @spec MCP-OP-QUERY-019
(defn kondo-config
  "The clj-kondo `:config` map for one analysis. Arglists and keywords are
   analysed, locals are not, every linter is off and there are no hooks. The
   `lint-as` map (symbol to symbol, may be nil) is the only workspace setting
   that applies."
  [lint-as]
  {:analysis {:arglists true :keywords true :locals false}
   :linters (into {}
                  (map (fn [k] [k {:level :off}]))
                  (keys (:linters kondo-config/default-config)))
   :lint-as (or lint-as {})
   :output {:canonical-paths false}})

;; ---------------------------------------------------------------------------
;; throws?
;; ---------------------------------------------------------------------------

(defn- string-end
  "Index of the closing quote of the string that opens at index `start`, or the
   last index of the text when the string is not closed."
  [^String text start]
  (let [n (count text)]
    (loop [k (inc start)]
      (cond
        (>= k n) (dec n)
        (= \\ (.charAt text k)) (recur (+ k 2))
        (= \" (.charAt text k)) k
        :else (recur (inc k))))))

(defn- line-end
  "Index of the next newline at or after `start`, or the text length."
  [^String text start]
  (let [k (.indexOf text "\n" (int start))]
    (if (neg? k) (count text) k)))

(defn- blank-literals
  "The text with each string, `;` comment, character literal and `'`-quoted
   collection replaced by spaces, so a scan for a call head sees code only."
  [^String text]
  (let [n (count text)
        sb (StringBuilder.)
        blank! (fn [from to] (dotimes [_ (- to from)] (.append sb \space)))]
    (loop [i 0 depth 0]
      (if (>= i n)
        (str sb)
        (let [c (.charAt text i)
              nxt (when (< (inc i) n) (.charAt text (inc i)))]
          (cond
            (= \" c) (let [j (string-end text i)]
                       (blank! i (inc j))
                       (recur (inc j) depth))
            (= \; c) (let [j (line-end text i)]
                       (blank! i j)
                       (recur j depth))
            (= \\ c) (let [j (min n (+ i 2))]
                       (blank! i j)
                       (recur j depth))
            (and (zero? depth) (= \' c) (contains? #{\( \[ \{} nxt))
            (do (blank! i (+ i 2))
                (recur (+ i 2) 1))
            (pos? depth) (do (blank! i (inc i))
                             (recur (inc i)
                                    (cond
                                      (contains? #{\( \[ \{} c) (inc depth)
                                      (contains? #{\) \] \}} c) (dec depth)
                                      :else depth)))
            :else (do (.append sb c)
                      (recur (inc i) depth))))))))

;; @spec MCP-OP-QUERY-023
(defn throws?
  "True exactly when the text holds a list whose head is the unqualified symbol
   `throw` or `ex-info`. A string, a `;` comment or a quoted collection does
   not count."
  [source-text]
  (boolean
   (re-find #"\(\s*(?:throw|ex-info)(?=[\s)\]}]|$)"
            (blank-literals source-text))))

;; ---------------------------------------------------------------------------
;; intent
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-024
(defn- intent
  "The first sentence of the docstring, with whitespace joined to one space,
   terminator kept and cut to 200 characters. Nil for a blank docstring."
  [doc]
  (let [text (str/trim (str/replace (str doc) #"\s+" " "))]
    (when-not (str/blank? text)
      (let [m (re-matcher #"[.!?](?= |$)" text)
            sentence (if (.find m) (subs text 0 (.end m)) text)]
        (subs sentence 0 (min intent-max-chars (count sentence)))))))

;; ---------------------------------------------------------------------------
;; names and facets
;; ---------------------------------------------------------------------------

(defn- clojure-ns?
  "True for a namespace that the facets leave out: `clojure` or `clojure.*`,
   and `cljs.core`."
  [ns-name]
  (let [s (str ns-name)]
    (or (= "clojure" s)
        (str/starts-with? s "clojure.")
        (= "cljs.core" s))))

(defn- usage-kind
  "The kind of a kondo var usage: `:refer`, `:alias-call`, `:call` or `:value`."
  [u]
  (cond
    (:refer u) :refer
    (and (:alias u) (:arity u)) :alias-call
    (:arity u) :call
    :else :value))

(defn- usage-target
  "The qname of the var that a kondo usage names, or nil when kondo did not
   resolve the namespace."
  [u]
  (when (symbol? (:to u))
    (str (:to u) "/" (:name u))))

(defn- usage->use
  "A use map from a kondo var usage, or nil when the target is unresolved."
  [path u]
  (when-let [target (usage-target u)]
    {:file path
     :line (:row u)
     :col (:col u)
     :from (when (:from-var u) (str (:from u) "/" (:from-var u)))
     :target target
     :kind (usage-kind u)
     :target-ns (str (:to u))
     :target-name (str (:name u))}))

(defn- keyword-name
  "The name of a kondo keyword entry: `scope` for `:scope`, `x/k` for `:x/k`."
  [k]
  (if (and (:ns k) (not (:auto-resolved k)))
    (str (:ns k) "/" (:name k))
    (str (:name k))))

(defn- sorted-distinct
  [xs]
  (vec (sort (distinct xs))))

;; ---------------------------------------------------------------------------
;; var records
;; ---------------------------------------------------------------------------

(defn- source-lines
  "The whole lines `start` through `end` (1-based, inclusive), joined by a
   newline, with no trailing newline."
  [lines start end]
  (let [n (count lines)
        from (max 0 (dec start))
        to (min n end)]
    (str/join "\n" (subvec lines from (max from to)))))

;; @spec MCP-OP-QUERY-020
;; @spec MCP-OP-QUERY-021
;; @spec MCP-OP-QUERY-022
;; @spec MCP-OP-QUERY-023
;; @spec MCP-OP-QUERY-024
;; @spec MCP-OP-QUERY-041
(defn- var-record
  "The var record of one kondo var definition. `uses` are the uses whose
   `:from` is this var, `kws` the kondo keywords whose `:from-var` is this var,
   `lines` the vector of the lines of the file."
  [path lines d uses kws]
  (let [ns-name (str (:ns d))
        var-name (str (:name d))
        start (:row d)
        end (:end-row d)
        source (source-lines lines start end)
        doc (:doc d)
        kws (remove :keys-destructuring-ns-modifier kws)
        reads (filter :keys-destructuring kws)
        plain (remove :keys-destructuring kws)]
    (cond-> {:qname (str ns-name "/" var-name)
             :ns ns-name
             :name var-name
             :file path
             :start start
             :end end
             :kind (str (or (:defined-by->lint-as d) (:defined-by d)))
             :private? (boolean (:private d))
             :arglists (vec (:arglist-strs d))
             :source source
             :calls (sorted-distinct
                     (->> uses
                          (remove #(clojure-ns? (:target-ns %)))
                          (map :target)))
             :fx (sorted-distinct
                  (filter fx-names (map :target-name uses)))
             :uses-kw (sorted-distinct (map keyword-name plain))
             :reads-kw (sorted-distinct (map keyword-name reads))
             :throws? (throws? source)}
      (string? doc) (assoc :doc doc)
      (intent doc) (assoc :intent (intent doc)))))

(defn- first-per-qname
  "The var records, source order, with the first one of each qname kept. The
   records all belong to one platform, so the qname is the identity."
  [records]
  (:out
   (reduce (fn [{:keys [seen] :as acc} r]
             (if (contains? seen (:qname r))
               acc
               (-> acc
                   (update :seen conj (:qname r))
                   (update :out conj r))))
           {:seen #{} :out []}
           records)))

(defn- public-use
  "A file-result use: the use map without the working keys."
  [u]
  (select-keys u [:file :line :from :target :kind]))

(defn- dedupe-uses
  "Uses that kondo reports twice for one site (macro expansion, the two
   branches of a `.cljc` file) once."
  [uses]
  (vals (into (sorted-map)
              (map (fn [u] [[(:line u) (:col u) (:from u) (:target u) (:kind u)
                             (:file u)] u]))
              uses)))

;; ---------------------------------------------------------------------------
;; platforms of a var
;; ---------------------------------------------------------------------------

(defn- file-platforms
  "The platforms that the analysis of the file at `path` covers: `[:clj :cljs]`
   for a `.cljc` file, `[:cljs]` for a `.cljs` file, otherwise `[:clj]`."
  [^String path]
  (cond
    (str/ends-with? path ".cljc") [:clj :cljs]
    (str/ends-with? path ".cljs") [:cljs]
    :else [:clj]))

(def ^:private compared-facts
  "The facts of a var record that decide whether the two platform versions of a
   `.cljc` var are one record."
  [:arglists :calls :fx :uses-kw :reads-kw :throws? :kind :private? :doc
   :start :end :source])

(defn- comparable-facts
  "The facts of a record that the platform comparison uses. The defining macro
   of kondo differs by name only (`clojure.core/defn` and `cljs.core/defn`), so
   the `cljs.core` prefix of the kind counts as `clojure.core`."
  [record]
  (-> (select-keys record compared-facts)
      (update :kind #(some-> % (str/replace-first #"^cljs\.core/" "clojure.core/")))))

;; @spec MCP-OP-QUERY-099
(defn- merge-platform-versions
  "The records of one qname from the platforms of a file, `:clj` before
   `:cljs`. Two records with equal facts become one record of the `:clj`
   version with `:platform :cljc`. One record keeps its platform."
  [{clj-v :clj cljs-v :cljs}]
  (cond
    (and clj-v cljs-v)
    (if (= (comparable-facts clj-v) (comparable-facts cljs-v))
      [(assoc clj-v :platform :cljc)]
      [clj-v cljs-v])
    clj-v [clj-v]
    :else [cljs-v]))

(defn- in-lang?
  "True when the kondo entry `x` belongs to `lang`. An entry without `:lang`
   (a file that is not `.cljc`) belongs to every language."
  [lang x]
  (or (nil? (:lang x)) (= lang (:lang x))))

;; ---------------------------------------------------------------------------
;; file-result
;; ---------------------------------------------------------------------------

(defn- validate-input!
  "Throw `:invalid-file-input` when a required key is missing or mistyped."
  [{:keys [path text mtime size sha256 lint-as] :as file-input}]
  (when-not (and (map? file-input)
                 (string? path)
                 (string? text)
                 (integer? mtime)
                 (integer? size)
                 (string? sha256)
                 (or (nil? lint-as) (map? lint-as)))
    (throw (ex-info "invalid file input for ingest"
                    {:error-type :invalid-file-input
                     :path (when (map? file-input) path)}))))

(defn- run-kondo
  "The kondo analysis map of `text`. Throws `:index-unavailable` when kondo
   itself throws."
  [path text lint-as]
  (try
    (:analysis
     (with-in-str text
       (kondo/run! {:lint ["-"]
                    :filename path
                    :config [(kondo-config lint-as)]
                    :config-dir no-config-dir
                    :cache false
                    :parallel false
                    :copy-configs false})))
    (catch Throwable t
      (throw (ex-info "clj-kondo failed"
                      {:error-type :index-unavailable
                       :path path
                       :cause (or (ex-message t) (str (class t)))}
                      t)))))

(defn- first-namespace
  "The first `ns` definition of the analysis, in source order, or nil."
  [analysis]
  (first (sort-by (juxt :row :col) (:namespace-definitions analysis))))

;; @spec MCP-OP-QUERY-099
(defn- platform-records
  "The var records of one platform of the file, source order, with the first
   definition of each qname kept."
  [path lines ns-name analysis cljc? platform]
  (let [keep-lang (if cljc? (partial in-lang? platform) (constantly true))
        uses (dedupe-uses (keep #(usage->use path %)
                                (filter keep-lang (:var-usages analysis))))
        uses-by-var (group-by :from uses)
        kws-by-var (group-by #(when (:from-var %) (str (:from %) "/" (:from-var %)))
                             (filter keep-lang (:keywords analysis)))]
    (->> (:var-definitions analysis)
         (filter keep-lang)
         (filter #(= ns-name (str (:ns %))))
         (sort-by (juxt :row :col))
         (map (fn [d]
                (let [q (str ns-name "/" (:name d))]
                  (assoc (var-record path lines d
                                     (get uses-by-var q)
                                     (get kws-by-var q))
                         :platform platform))))
         first-per-qname)))

(defn- platform-order
  [r]
  (case (:platform r) :cljc 0 :clj 0 :cljs 1))

(defn- file-vars
  "The var records of a file: one record for each (var, platform), source
   order, the `:clj` record before the `:cljs` record at one position."
  [path lines ns-name analysis]
  (let [platforms (file-platforms path)
        cljc? (= 2 (count platforms))
        by-platform (into {}
                          (map (fn [pf]
                                 [pf (platform-records path lines ns-name
                                                       analysis cljc? pf)]))
                          platforms)
        by-qname (apply merge-with merge
                        (for [[pf recs] by-platform
                              r recs]
                          {(:qname r) {pf r}}))]
    (->> by-qname
         vals
         (mapcat merge-platform-versions)
         (sort-by (juxt :start platform-order))
         vec)))

;; @spec MCP-OP-QUERY-017
;; @spec MCP-OP-QUERY-020
;; @spec MCP-OP-QUERY-045
;; @spec MCP-OP-QUERY-098
;; @spec MCP-OP-QUERY-099
(defn file-result
  "The file result of one file input `{:path :text :mtime :size :sha256
   :lint-as}`. It runs clj-kondo on the text and touches no file. A text with a
   syntax error gives a partial or empty result. A `.cljc` file gives one
   record for each platform on which a var differs (MCP-OP-QUERY-099). Throws
   `:invalid-file-input` for a bad input and `:index-unavailable` when kondo
   throws."
  [file-input]
  (validate-input! file-input)
  (let [{:keys [path text mtime size sha256 lint-as]} file-input
        analysis (run-kondo path text lint-as)
        ns-def (first-namespace analysis)
        ns-name (if ns-def (str (:name ns-def)) "user")
        lines (vec (str/split text #"\n" -1))
        all-uses (dedupe-uses (keep #(usage->use path %) (:var-usages analysis)))
        vars (file-vars path lines ns-name analysis)
        uses (->> all-uses
                  (remove #(clojure-ns? (:target-ns %)))
                  (map public-use)
                  (sort-by (juxt :line :from :target :kind))
                  vec)]
    (cond-> {:path path :mtime mtime :size size :sha256 sha256
             :ns ns-name
             :vars vars
             :uses uses}
      (string? (:doc ns-def)) (assoc :ns-doc (:doc ns-def)))))
