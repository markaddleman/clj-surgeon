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
  [entity]
  (cond
    (contains? entity :var/qname) (var-entity-line entity)
    (contains? entity :ns/name)
    (str (:ns/name entity)
         (when-let [s (first-sentence (:ns/doc entity))] (str " — " s)))
    :else (str (:kw/name entity))))

(defn- indent-text
  [n]
  (apply str (repeat n \space)))

(defn- id-only?
  [element]
  (and (map? element)
       (= 1 (count element))
       (contains? id-keys (key (first element)))))

(declare entity-lines)

(defn- join-lines
  [level label elements]
  (let [pad (indent-text (+ level 2))]
    (if (every? id-only? elements)
      [(str pad label ": " (str/join ", " (map pull-id elements)))]
      (into [(str pad label ":")]
            (mapcat #(entity-lines (+ level 4) %))
            elements))))

(defn- entity-lines
  [level entity]
  (let [pad (indent-text level)
        inner (indent-text (+ level 2))]
    (-> [(str pad (entity-line entity))]
        (into (when-let [source (:var/source entity)]
                (concat [(str inner "```clojure")]
                        (map #(str inner %) (str/split-lines source))
                        [(str inner "```")])))
        (into (mapcat (fn [[k label]]
                        (when-let [elements (seq (get entity k))]
                          (join-lines level label elements))))
              join-order))))

(defn- pull-result?
  [x]
  (and (map? x) (= :pull (:kind (meta x)))))

;; @spec MCP-OP-QUERY-064
(defn- pull-text
  [result]
  (str/join "\n" (entity-lines 0 result)))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private line-kinds #{:var :form :use :ns})

(defn- element-kind
  "The shared kind of a non-empty sequential value, or nil."
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

(defn- classify
  [value]
  (cond
    (string? value) :string
    (pull-result? value) :pull
    (and (map? value) (contains? line-kinds (:kind value))) (:kind value)
    (and (sequential? value) (= :skeleton (:kind (meta value)))
         (or (empty? value) (= :var (element-kind value)))) :skeleton
    (and (sequential? value) (some? (element-kind value)))
    [:many (element-kind value)]
    :else :pr))

(defn- single-text
  [kind value]
  (case kind
    :var (var-line value)
    :form (form-block value)
    :use (use-line value)
    :ns (namespace-line value)
    :pull (pull-text value)))

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
  (let [kind (classify value)]
    (cond
      (= :pr kind) (pr-str value)
      (= :string kind) value
      (= :skeleton kind)
      (str/join "\n" (cons (ns-line (meta value)) (map var-line value)))
      (vector? kind)
      (let [k (second kind)
            lines (map #(single-text k %) value)]
        (case k
          :form (str/join "\n\n" lines)
          :use (str (str/join "\n" lines) "\ntotal " (count value))
          (str/join "\n" lines)))
      :else (single-text kind value))))

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
   ex-info once one more character arrives."
  ^Writer [^StringBuilder sb limit]
  (proxy [Writer] []
    (write
      ([x]
       (if (string? x)
         (.append sb ^String x)
         (.append sb (char x)))
       (when (> (.length sb) limit)
         (throw (ex-info "limit" {::limit true}))))
      ([x off len]
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
(defn render-bounded
  "Render `value` like `render`, stopping at `limit` characters. A value that
   goes through `pr-str` streams into a writer that stops at the limit, so an
   endless lazy seq ends at once. Returns `:text`, `:over-limit?` and `:chars`."
  [value limit]
  (if (= :pr (classify value))
    (let [sb (StringBuilder.)
          w (limited-writer sb limit)
          hit? (try
                 (binding [*out* w] (pr value))
                 false
                 (catch Throwable t
                   (if (limit-hit? t) true (throw t))))]
      (bounded-result (str sb) limit hit?))
    (let [text (render value)]
      (bounded-result text limit (> (count text) limit)))))
