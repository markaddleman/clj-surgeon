(ns clj-surgeon.code-index-pull
  "Datomic-style `pull` over the code index of `query_clojure`.

   `pull` is pure. It reads an index map (see docs/intent/code-index/code-index-api.md)
   and returns plain data with the metadata `{:kind :pull}`. The helpers for the
   typed refusals about unknown namespaces and vars are public, because
   `clj-surgeon.code-index-functions` throws the same refusals."
  (:require
   [clj-surgeon.code-index :as code-index]
   [clojure.string :as str]))

(def ^:private id-keys
  "Entity type to the attribute that holds its id."
  {:var :var/qname
   :ns :ns/name
   :kw :kw/name})

(def ^:private eid-types
  "Id attribute to entity type."
  {:var/qname :var
   :ns/name :ns
   :kw/name :kw})

(def ^:private var-attrs
  "Pull attribute to var record key, for a var that has a record."
  {:var/qname :qname
   :var/name :name
   :var/ns :ns
   :var/file :file
   :var/start :start
   :var/end :end
   :var/kind :kind
   :var/private? :private?
   :var/arglists :arglists
   :var/doc :doc
   :var/intent :intent
   :var/source :source
   :var/throws? :throws?
   :var/fx :fx})

(defn- refuse
  "Throw an ex-info of `error-type` with `data` and `message`."
  [error-type message data]
  (throw (ex-info message (assoc data :error-type error-type))))

;; ---------------------------------------------------------------------------
;; Names and refusals shared with code-index-functions
;; ---------------------------------------------------------------------------

(defn qname-ns
  "The namespace part of a qname string, or nil when it has no slash."
  [qname]
  (when-let [i (str/index-of qname "/")]
    (when (pos? i) (subs qname 0 i))))

(defn qname-name
  "The name part of a qname string."
  [qname]
  (if-let [i (str/index-of qname "/")]
    (subs qname (inc i))
    qname))

(defn- levenshtein
  "Edit distance between the strings `a` and `b`."
  [^String a ^String b]
  (let [n (count b)]
    (peek
     (reduce (fn [prev ^Character ca]
               (reduce (fn [row j]
                         (let [cost (if (= ca (.charAt b (dec j))) 0 1)]
                           (conj row (min (inc (peek row))
                                          (inc (nth prev j))
                                          (+ (nth prev (dec j)) cost)))))
                       [(inc (first prev))]
                       (range 1 (inc n))))
             (vec (range (inc n)))
             a))))

;; @spec MCP-OP-QUERY-054
(defn nearest-namespaces
  "At most five indexed namespace names, ordered by distance to `ns-name`, then by name."
  [index ns-name]
  (->> (keys (:namespaces index))
       (sort-by (juxt #(levenshtein ns-name %) identity))
       (take 5)
       vec))

;; @spec MCP-OP-QUERY-054
(defn unknown-namespace!
  "Throw the `unknown-namespace` refusal for `ns-name`."
  [index ns-name]
  (let [nearest (nearest-namespaces index ns-name)]
    (refuse :unknown-namespace
            (str "unknown namespace " ns-name
                 (when (seq nearest) (str ". Nearest: " (str/join ", " nearest))))
            {:name ns-name :nearest nearest})))

;; @spec MCP-OP-QUERY-055
(defn unknown-var!
  "Throw the `unknown-var` refusal for `qname`."
  [index qname]
  (let [ns-name (qname-ns qname)]
    (refuse :unknown-var
            (str "unknown var " qname)
            {:qname qname
             :ns ns-name
             :ns-known? (boolean (and ns-name (contains? (:namespaces index) ns-name)))})))

(defn ns-var-qnames
  "The qnames of the vars that the file of namespace `ns-name` defines, in source order."
  [index ns-name]
  (when-let [file (get-in index [:namespaces ns-name :file])]
    (filterv #(= file (get-in index [:vars % :file]))
             (get-in index [:files file :vars]))))

(defn external-var?
  "True when `qname` has no var record but some var calls it or some file uses it."
  [index qname]
  (and (not (contains? (:vars index) qname))
       (or (contains? (:callers index) qname)
           (contains? (:uses-by-target index) qname))))

;; ---------------------------------------------------------------------------
;; Pattern validation
;; ---------------------------------------------------------------------------

(declare validate-pattern)

(defn- validate-join-value
  "Check the value of one join: a vector sub-pattern or a depth number."
  [v ctx]
  (let [{:keys [pattern eid]} ctx]
    (cond
      (= '... v)
      (refuse :pull-too-deep "pull depth '...' is too deep. Use a depth of at most 3"
              {:depth "..." :pattern pattern :eid eid})

      (int? v)
      (cond
        (> v (:max-pull-depth code-index/default-bounds))
        (refuse :pull-too-deep (str "pull depth " v " is too deep. Use a depth of at most "
                                    (:max-pull-depth code-index/default-bounds))
                {:depth v :pattern pattern :eid eid})
        (< v 1)
        (refuse :invalid-expression "pull depth must be a positive integer"
                {:reason :bad-depth :pattern pattern})
        :else nil)

      (vector? v) (validate-pattern v ctx)

      :else
      (refuse :invalid-expression "a pull join holds a vector or a depth number"
              {:reason :bad-join :pattern pattern}))))

(defn- validate-pattern
  "Check that `pattern` is a vector of keywords and one-key join maps."
  [pattern ctx]
  (when-not (vector? pattern)
    (refuse :invalid-expression "a pull pattern is a vector"
            {:reason :pattern-not-vector :pattern pattern}))
  (doseq [el pattern]
    (cond
      (keyword? el) nil
      (and (map? el) (= 1 (count el)) (keyword? (key (first el))))
      (validate-join-value (val (first el)) ctx)
      :else
      (refuse :invalid-expression
              "a pull pattern holds keywords and one-key join maps only"
              {:reason :bad-element :pattern (:pattern ctx) :element el}))))

;; @spec MCP-OP-QUERY-047
(defn- validate-eid
  "Return the entity `[type id]` of `eid`, or throw invalid-expression."
  [eid]
  (if (and (vector? eid)
           (= 2 (count eid))
           (contains? eid-types (first eid))
           (or (string? (second eid)) (symbol? (second eid))))
    [(get eid-types (first eid)) (str (second eid))]
    (refuse :invalid-expression
            "eid is [:var/qname s], [:ns/name s] or [:kw/name s]"
            {:reason :bad-eid :eid eid})))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(defn- entity-attrs
  "All attributes of the entity `[type id]` that it has, as a plain map."
  [index [type id]]
  (case type
    :var (if-let [rec (get-in index [:vars id])]
           (reduce-kv (fn [m attr k] (if (some? (get rec k)) (assoc m attr (get rec k)) m))
                      {}
                      var-attrs)
           {:var/qname id
            :var/name (qname-name id)
            :var/ns (qname-ns id)
            :var/external? true})
    :ns (let [rec (get-in index [:namespaces id])]
          (cond-> {:ns/name id}
            (:doc rec) (assoc :ns/doc (:doc rec))
            (:file rec) (assoc :ns/file (:file rec))))
    :kw {:kw/name id}))

(defn- sorted-vars [qnames] (mapv (fn [q] [:var q]) (sort qnames)))

(defn- kw-readers
  "The qnames of the vars that destructure the keyword name `kw`."
  [index kw]
  (keep (fn [[q rec]] (when (some #{kw} (:reads-kw rec)) q)) (:vars index)))

(defn- join-targets
  "The entities that the join `join-key` reaches from `ent`, or nil when the join does not apply."
  [index [type id] join-key]
  (case type
    :var (let [rec (get-in index [:vars id])]
           (case join-key
             :var/calls (sorted-vars (:calls rec))
             :var/_calls (sorted-vars (get-in index [:callers id]))
             :var/uses-kw (mapv (fn [k] [:kw k]) (sort (:uses-kw rec)))
             :var/reads-kw (mapv (fn [k] [:kw k]) (sort (:reads-kw rec)))
             :var/in-ns (when (contains? (:namespaces index) (:ns rec))
                          [[:ns (:ns rec)]])
             nil))
    :ns (case join-key
          (:ns/_in-ns :var/_in-ns) (mapv (fn [q] [:var q]) (ns-var-qnames index id))
          nil)
    :kw (case join-key
          :var/_uses-kw (sorted-vars (get-in index [:kw-users id]))
          :var/_reads-kw (sorted-vars (kw-readers index id))
          nil)))

(defn- id-map [[type id]] {(get id-keys type) id})

(declare pull-entity)

(defn- child-pattern
  "The pattern for the entities that join `j` reaches."
  [pattern j sub]
  (if (int? sub)
    (if (= 1 sub)
      (vec (remove #(identical? % j) pattern))
      (mapv #(if (identical? % j) {(key (first j)) (dec sub)} %) pattern))
    sub))

(defn- pull-child
  "Pull one joined entity. An external var keeps its id and flag. A recursive join
   returns an entity that is already on the path as its id map."
  [index pattern j sub path ent]
  (cond
    (and (int? sub) (contains? path ent)) (id-map ent)
    :else
    (let [res (pull-entity index (child-pattern pattern j sub) ent (conj path ent))]
      (if (and (= :var (first ent)) (external-var? index (second ent)))
        (merge res {:var/qname (second ent) :var/external? true})
        res))))

(defn- pull-entity
  "Apply `pattern` to `ent`. `path` is the set of entities from the root to `ent`."
  [index pattern ent path]
  (let [attrs (entity-attrs index ent)
        base (select-keys attrs (filter keyword? pattern))]
    (reduce (fn [acc j]
              (let [[join-key sub] (first j)
                    targets (join-targets index ent join-key)]
                (if (seq targets)
                  (let [children (mapv #(pull-child index pattern j sub path %) targets)]
                    (assoc acc join-key children))
                  acc)))
            base
            (filter map? pattern))))

;; ---------------------------------------------------------------------------
;; pull
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-047
;; @spec MCP-OP-QUERY-048
;; @spec MCP-OP-QUERY-049
;; @spec MCP-OP-QUERY-050
;; @spec MCP-OP-QUERY-051
;; @spec MCP-OP-QUERY-052
;; @spec MCP-OP-QUERY-053
(defn pull
  "Pull `pattern` for the entity `eid` of `index`.

   `eid` is `[:var/qname s]`, `[:ns/name s]` or `[:kw/name s]`. `pattern` is a
   vector of attribute keywords and one-key join maps. A join value is a
   sub-pattern vector or a recursion depth from 1 to 3. Returns a plain map with
   the metadata `{:kind :pull}`. Throws invalid-expression, pull-too-deep,
   unknown-namespace or unknown-var."
  [index pattern eid]
  (let [[type id :as ent] (validate-eid eid)]
    (validate-pattern pattern {:pattern pattern :eid eid})
    (case type
      :ns (when-not (contains? (:namespaces index) id)
            (unknown-namespace! index id))
      :var (when-not (or (contains? (:vars index) id) (external-var? index id))
             (unknown-var! index id))
      :kw nil)
    (with-meta (pull-entity index pattern ent #{ent})
      {:kind :pull})))
