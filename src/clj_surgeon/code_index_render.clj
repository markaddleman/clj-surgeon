(ns clj-surgeon.code-index-render
  "Pure text rendering for the code index: var lines, form blocks, use lines,
   namespace lines, the pull format, and the bounded renderer behind the
   `query_clojure` result bound. Nothing here touches the file system."
  (:require
   [clojure.string :as str])
  (:import
   (java.io Writer)))

(def ^:private intent-limit 200)
(def ^:private seq-count-limit 10000)

;; @spec MCP-OP-QUERY-024
(defn first-sentence
  "The first sentence of `s`, whitespace joined to one space and cut to 200
   characters. The terminator `.`, `!` or `?` stays when a space or the end of
   the text follows it. Nil for nil, blank and non-string input."
  [s]
  (when (string? s)
    (let [text (str/trim (str/replace s #"\s+" " "))]
      (when-not (str/blank? text)
        (let [end (if-let [m (re-find #"^.*?[.!?](?= |$)" text)]
                    m
                    text)]
          (subs end 0 (min intent-limit (count end))))))))

(defn- joined-text
  [s]
  (when (string? s)
    (let [text (str/trim (str/replace s #"\s+" " "))]
      (when-not (str/blank? text) text))))

(defn- called-name
  [ns-name qname]
  (let [i (str/last-index-of qname "/")]
    (if (nil? i)
      qname
      (let [q-ns (subs qname 0 i)
            n (subs qname (inc i))]
        (if (= q-ns ns-name)
          n
          (str (last (str/split q-ns #"\.")) "/" n))))))

;; @spec MCP-OP-QUERY-059
;; @spec MCP-OP-QUERY-060
(defn var-line
  "One line for a var map: name, arglists, then calls, effects, throws,
   private and intent, each only when present."
  [{:keys [name ns arglists calls fx throws? private? intent]}]
  (let [call-names (sort (distinct (map #(called-name ns %) calls)))
        intent-text (joined-text intent)]
    (str "λ " name "(" (str/join " | " arglists) ")"
         (when (seq call-names) (str " | calls " (str/join "," call-names)))
         (when (seq fx) (str " | fx " (str/join "," fx)))
         (when throws? " | throws")
         (when private? " | private")
         (when intent-text (str " — " intent-text)))))

;; @spec MCP-OP-QUERY-058
(defn ns-line
  "The namespace line of a skeleton: `ns <name> — <first sentence of doc>`."
  [{:keys [ns doc]}]
  (str "ns " ns (when-let [s (first-sentence doc)] (str " — " s))))

;; @spec MCP-OP-QUERY-063
(defn namespace-line
  "One line for a namespace map: `ns <name> (<n> vars) — <first sentence>`."
  [{:keys [ns vars doc]}]
  (str "ns " ns " (" vars " vars)"
       (when-let [s (first-sentence doc)] (str " — " s))))

;; @spec MCP-OP-QUERY-061
(defn form-block
  "A header line `;; <qname>  <file>:<start>-<end>` and the unchanged source."
  [{:keys [qname file start end source]}]
  (str ";; " qname "  " file ":" start "-" end "\n" source))

;; @spec MCP-OP-QUERY-062
(defn use-line
  "One line for a use map: `<file>:<line> <ns>/<from> <use-kind>`."
  [{:keys [file line ns from use-kind]}]
  (str file ":" line " " ns (when from (str "/" from)) " " (name use-kind)))

;; ---------------------------------------------------------------------------
;; Pull rendering

(def ^:private id-keys #{:var/qname :ns/name :kw/name})

(def ^:private join-order
  [[:var/calls "calls"]
   [:var/_calls "called by"]
   [:var/uses-kw "uses"]
   [:var/_uses-kw "used by"]
   [:var/reads-kw "reads"]
   [:var/_reads-kw "read by"]
   [:var/in-ns "in"]
   [:ns/_in-ns "vars"]
   [:var/_in-ns "vars"]])

(defn- pull-id
  [entity]
  (some entity [:var/qname :ns/name :kw/name]))

(defn- var-entity-line
  [entity]
  (let [{:var/keys [qname kind private? start end arglists intent doc
                    external?]} entity
        tail (or (some-> (joined-text intent) (->> (str " — ")))
                 (some-> (joined-text doc) (->> (str " — "))))]
    (str qname
         (when external? " external")
         (when kind (str " " kind))
         (when private? " private")
         (when (and start end) (str " " start "-" end))
         (when (seq arglists) (str " " (str/join " | " arglists)))
         tail)))

(defn- entity-line
  "The line of `entity`. `parent` is the id of the entity whose join holds it,
   or nil for the root."
  [entity parent]
  (cond
    (contains? entity :pull/cycle-to)
    (str (pull-id entity) " cycle: returns to " (:pull/cycle-to entity)
         " (closed by " parent ")")
    (contains? entity :var/qname) (var-entity-line entity)
    (contains? entity :ns/name)
    (str (:ns/name entity)
         (when-let [s (first-sentence (:ns/doc entity))] (str " — " s)))
    :else (str (:kw/name entity))))

(defn- indent-text
  [n]
  (apply str (repeat n \space)))

;; @spec MCP-OP-QUERY-097
(defn- id-only?
  "True for a map that holds an id and nothing else. A cycle map holds two keys,
   so it never counts as a plain id."
  [element]
  (and (map? element)
       (= 1 (count element))
       (contains? id-keys (key (first element)))))

(declare entity-lines)

(defn- join-lines
  [level label parent elements]
  (let [pad (indent-text (+ level 2))]
    (if (every? id-only? elements)
      [(str pad label ": " (str/join ", " (map pull-id elements)))]
      (into [(str pad label ":")]
            (mapcat #(entity-lines (+ level 4) % parent))
            elements))))

(defn- entity-lines
  [level entity parent]
  (let [pad (indent-text level)
        inner (indent-text (+ level 2))
        id (pull-id entity)]
    (-> [(str pad (entity-line entity parent))]
        (into (when-let [source (:var/source entity)]
                (concat [(str inner "```clojure")]
                        (map #(str inner %) (str/split-lines source))
                        [(str inner "```")])))
        (into (mapcat (fn [[k label]]
                        (when-let [elements (seq (get entity k))]
                          (join-lines level label id elements))))
              join-order))))

(defn- pull-result?
  [x]
  (and (map? x) (= :pull (:kind (meta x)))))

;; @spec MCP-OP-QUERY-064
;; @spec MCP-OP-QUERY-097
(defn- pull-text
  [result]
  (str/join "\n" (entity-lines 0 result nil)))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private line-kinds #{:var :form :use :ns})

(defn- element-kind
  "The shared kind of a non-empty vector of realized elements, or nil."
  [xs]
  (let [xs (seq xs)]
    (cond
      (nil? xs) nil
      (every? pull-result? xs) :pull
      :else
      (let [kind (when (map? (first xs)) (:kind (first xs)))]
        (when (and (contains? line-kinds kind)
                   (every? #(and (map? %) (= kind (:kind %))) xs))
          kind)))))

(defn- check-interrupt!
  "The default interrupt fn: throw the `timeout` refusal when the thread is
   interrupted."
  []
  (when (.isInterrupted (Thread/currentThread))
    (throw (ex-info "Rendering was interrupted" {:error-type :timeout}))))

;; @spec MCP-OP-QUERY-118
;; @spec MCP-OP-QUERY-107
(defn- realize-prefix
  "A vector of the first `limit + 1` elements of `coll`, or all of them when
   there are fewer. It realizes no element past that and polls `check!` for
   each element."
  [coll limit check!]
  (loop [s coll acc (transient [])]
    (if (> (count acc) limit)
      (persistent! acc)
      (if-let [s (seq s)]
        (do (check!)
            (recur (rest s) (conj! acc (first s))))
        (persistent! acc)))))

(defn- classify
  "The kind of `value` and the elements that the rendering walks. A sequence is
   cut to its first `limit + 1` elements and its kind comes from them."
  [value limit check!]
  (cond
    (string? value) [:string value]
    (pull-result? value) [:pull value]
    (and (map? value) (contains? line-kinds (:kind value))) [(:kind value) value]
    (sequential? value)
    (let [xs (realize-prefix value limit check!)
          kind (element-kind xs)]
      (cond
        (and (= :skeleton (:kind (meta value)))
             (or (empty? xs) (= :var kind))) [:skeleton xs]
        (some? kind) [[:many kind] xs]
        :else [:pr value]))
    :else [:pr value]))

(defn- single-text
  [kind value]
  (case kind
    :var (var-line value)
    :form (form-block value)
    :use (use-line value)
    :ns (namespace-line value)
    :pull (pull-text value)))

(defn- write-lines!
  "Write the `texts` of the sequence `xs` to `w` joined by `sep`, and polling
   `check!` for each one."
  [^Writer w sep texts check!]
  (loop [s (seq texts) first? true]
    (when s
      (check!)
      (when-not first? (.write w ^String sep))
      (.write w ^String (first s))
      (recur (next s) false))))

(defn- write-rendering!
  "Write the text of `value` to `w` as `render` gives it, but stop once the
   writer refuses. `limit` bounds the elements of a sequence."
  [^Writer w value limit check!]
  (let [[kind items] (classify value limit check!)]
    (cond
      (= :pr kind) (binding [*out* w] (pr items))
      (= :string kind) (.write w ^String items)
      (= :skeleton kind)
      (write-lines! w "\n" (cons (ns-line (meta value)) (map var-line items)) check!)
      (vector? kind)
      (let [k (second kind)
            lines (map #(single-text k %) items)]
        (case k
          :form (write-lines! w "\n\n" lines check!)
          :use (do (write-lines! w "\n" lines check!)
                   (.write w (str "\ntotal " (count items))))
          (write-lines! w "\n" lines check!)))
      :else (.write w ^String (single-text kind items)))))

;; @spec MCP-OP-QUERY-056
;; @spec MCP-OP-QUERY-057
;; @spec MCP-OP-QUERY-058
;; @spec MCP-OP-QUERY-061
;; @spec MCP-OP-QUERY-062
;; @spec MCP-OP-QUERY-063
;; @spec MCP-OP-QUERY-065
;; @spec MCP-OP-QUERY-066
(defn render
  "The text of a final value, with no limit. Strings pass unchanged. Var,
   form, use, namespace and pull values get their compact layouts. Anything
   else goes through `pr-str`."
  [value]
  (let [w (java.io.StringWriter.)]
    (write-rendering! w value Long/MAX_VALUE (fn []))
    (str w)))

;; ---------------------------------------------------------------------------
;; render-def

(defn- seq-shape
  [value]
  (let [n (count (take (inc seq-count-limit) value))]
    (if (> n seq-count-limit)
      (str "seq of " seq-count-limit "+")
      (str "seq of " n))))

;; @spec MCP-OP-QUERY-074
(defn render-def
  "The two lines for a var that the expression `def`d: `#'user/<name>` and the
   kind and count of its value."
  [name value]
  (str "#'user/" name "\n"
       (cond
         (vector? value) (str "vector of " (count value))
         (map? value) (str "map of " (count value))
         (set? value) (str "set of " (count value))
         (list? value) (str "list of " (count value))
         (sequential? value) (seq-shape value)
         (seq? value) (seq-shape value)
         (string? value) (str "string of " (count value))
         (number? value) "number"
         (boolean? value) "boolean"
         (keyword? value) "keyword"
         (symbol? value) "symbol"
         (nil? value) "nil"
         :else "value")))

;; ---------------------------------------------------------------------------
;; render-bounded

(defn- limited-writer
  "A Writer that keeps at most `limit` characters and throws a marked
   ex-info once one more character arrives. It calls `check!` at each write."
  ^Writer [^StringBuilder sb limit check!]
  (proxy [Writer] []
    (write
      ([x]
       (check!)
       (if (string? x)
         (.append sb ^String x)
         (.append sb (char x)))
       (when (> (.length sb) limit)
         (throw (ex-info "limit" {::limit true}))))
      ([x off len]
       (check!)
       (if (string? x)
         (.append sb ^String x (int off) (int (+ off len)))
         (.append sb ^chars x (int off) (int len)))
       (when (> (.length sb) limit)
         (throw (ex-info "limit" {::limit true})))))
    (flush [])
    (close [])))

(defn- limit-hit?
  [^Throwable t]
  (loop [t t]
    (cond
      (nil? t) false
      (::limit (ex-data t)) true
      :else (recur (.getCause t)))))

(defn- bounded-result
  [^String text limit over?]
  (let [text (if (> (count text) limit) (subs text 0 limit) text)]
    {:text text :over-limit? (boolean over?) :chars (count text)}))

;; @spec MCP-OP-QUERY-085
;; @spec MCP-OP-QUERY-107
;; @spec MCP-OP-QUERY-118
(defn render-bounded
  "Render `value` like `render`, stopping at `limit` characters. Every kind of
   value streams into a writer that stops at the limit. A sequence is realized
   up to `limit + 1` elements, and its kind comes from those elements, so an
   endless lazy seq ends at once. `interrupt-fn` is a zero-argument function
   that the renderer calls for each element. It throws to stop the rendering.
   The default throws the `timeout` refusal when the thread is interrupted.
   Returns `:text`, `:over-limit?` and `:chars`."
  ([value limit] (render-bounded value limit check-interrupt!))
  ([value limit interrupt-fn]
   (let [sb (StringBuilder.)
         w (limited-writer sb limit interrupt-fn)
         hit? (try
                (write-rendering! w value limit interrupt-fn)
                false
                (catch Throwable t
                  (if (limit-hit? t) true (throw t))))]
     (bounded-result (str sb) limit hit?))))
