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
   :var/platform :platform
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

(def ^:private default-max-pull-nodes
  "The pull node budget of MCP-OP-QUERY-112, used when the bounds map has none."
  2000)

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

(def ^:private platform-rank
  "Sort rank of a platform: `:clj` and `:cljc` before `:cljs`."
  {:clj 0 :cljc 0 :cljs 1})

;; @spec MCP-OP-QUERY-100
(defn- versions
  "The version records of `qname`, `:clj` and `:cljc` before `:cljs`. Empty when
   no file defines it. A bare record (an index written before the platform
   split) counts as one version."
  [index qname]
  (let [v (get-in index [:vars qname])]
    (cond (nil? v) []
          (map? v) [v]
          :else (vec (sort-by #(get platform-rank (:platform %) 0) v)))))

(defn ns-var-qnames
  "The qnames of the vars that the file of namespace `ns-name` defines, in source
   order. A qname is listed once, whatever its number of versions."
  [index ns-name]
  (when-let [file (get-in index [:namespaces ns-name :file])]
    (filterv #(some (fn [rec] (= file (:file rec))) (versions index %))
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

;; @spec MCP-OP-QUERY-111
(defn pattern-depth
  "The depth of a validated pull `pattern`: the greatest number of joins on
   any path from its root. An explicit join counts 1 and its sub-pattern is
   counted below it. A numeric join `n` counts `n`. A pattern with no join
   has depth 0."
  [pattern]
  (reduce (fn [deepest el]
            (if (map? el)
              (let [v (val (first el))]
                (max deepest (if (int? v) v (inc (pattern-depth v)))))
              deepest))
          0
          pattern))

;; @spec MCP-OP-QUERY-111
(defn- check-depth!
  "Throw `pull-too-deep` when the depth of `pattern` is over the bound."
  [pattern eid]
  (let [depth (pattern-depth pattern)
        limit (:max-pull-depth code-index/default-bounds)]
    (when (> depth limit)
      (refuse :pull-too-deep
              (str "pull depth " depth " is too deep. Use a depth of at most " limit)
              {:depth depth :pattern pattern :eid eid}))))

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

(defn- version-ents
  "The var entities `[:var qname platform]` for the versions of `qname` that
   apply to `source-platform`: the same platform or `:cljc`, and every version
   when the source is `:cljc` or has no platform. A var with no record gives one
   entity with the platform nil."
  [index qname source-platform]
  (let [recs (versions index qname)]
    (if (empty? recs)
      [[:var qname nil]]
      (->> recs
           (filter #(or (nil? source-platform)
                        (= :cljc source-platform)
                        (= :cljc (:platform %))
                        (nil? (:platform %))
                        (= source-platform (:platform %))))
           (mapv (fn [rec] [:var qname (:platform rec)]))))))

(defn- version-rec
  "The version record of the var entity `[:var qname platform]`, or nil."
  [index [_ qname platform]]
  (first (filter #(= platform (:platform %)) (versions index qname))))

(defn- entity-attrs
  "All attributes of the entity `[type id platform]` that it has, as a plain map."
  [index [type id :as ent]]
  (case type
    :var (if-let [rec (version-rec index ent)]
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

(defn- sorted-vars
  "The var entities of `qnames`, sorted by qname and then platform, that apply to
   `source-platform`."
  [index source-platform qnames]
  (into [] (mapcat #(version-ents index % source-platform)) (sort qnames)))

(defn- kw-readers
  "The var entities (one for each version) that destructure the keyword name `kw`."
  [index kw]
  (->> (:vars index)
       (sort-by key)
       (mapcat (fn [[q _]]
                 (keep (fn [rec] (when (some #{kw} (:reads-kw rec))
                                   [:var q (:platform rec)]))
                       (versions index q))))
       vec))

(defn- join-targets
  "The entities that the join `join-key` reaches from `ent`, or nil when the join does not apply."
  [index [type id platform :as ent] join-key]
  (case type
    :var (let [rec (version-rec index ent)]
           (case join-key
             :var/calls (sorted-vars index platform (:calls rec))
             :var/_calls (sorted-vars index platform (get-in index [:callers id]))
             :var/uses-kw (mapv (fn [k] [:kw k]) (sort (:uses-kw rec)))
             :var/reads-kw (mapv (fn [k] [:kw k]) (sort (:reads-kw rec)))
             :var/in-ns (when (contains? (:namespaces index) (:ns rec))
                          [[:ns (:ns rec)]])
             nil))
    :ns (case join-key
          (:ns/_in-ns :var/_in-ns) (into [] (mapcat #(version-ents index % nil)) (ns-var-qnames index id))
          nil)
    :kw (case join-key
          :var/_uses-kw (sorted-vars index nil (get-in index [:kw-users id]))
          :var/_reads-kw (kw-readers index id)
          nil)))

(defn- id-map [[type id]] {(get id-keys type) id})

(defn- root-ents
  "The entities that the root `ent` stands for: one for each version of a var,
   `:clj` first. Any other root is one entity."
  [index [type id :as ent]]
  (if (= :var type)
    (version-ents index id nil)
    [ent]))

(declare pull-entity)

(defn- child-pattern
  "The pattern for the entities that join `j` reaches."
  [pattern j sub]
  (if (int? sub)
    (if (= 1 sub)
      (vec (remove #(identical? % j) pattern))
      (mapv #(if (identical? % j) {(key (first j)) (dec sub)} %) pattern))
    sub))

;; @spec MCP-OP-QUERY-112
(defn- reserve-nodes!
  "Count `n` more entities against the node budget of `ctx`. Throw
   `result-too-large` when the total is over the budget, before the caller
   builds any of them."
  [{:keys [nodes max-nodes]} n]
  (let [total (vswap! nodes + n)]
    (when (> total max-nodes)
      (refuse :result-too-large
              (str "the pull reaches more than " max-nodes " entities")
              {:limit max-nodes
               :reason :pull-nodes
               :remedy (str "Pull fewer joins or a smaller depth, or pull one entity at a time. "
                            "A pull returns at most " max-nodes " entities.")}))))

;; @spec MCP-OP-QUERY-052
;; @spec MCP-OP-QUERY-096
(defn- cycle-map
  "The map of an entity that is already on the path: its id and the id of the
   entity on the path that it returns to."
  [[type id :as ent]]
  (assoc (id-map ent) :pull/cycle-to id))

(defn- pull-child
  "Pull one joined entity. An entity (qname and platform) that is already on the
   path from the root, reached through any join, is cut as a cycle map. An external var keeps its id
   and flag."
  [index pattern j sub path ctx ent]
  ((:interrupt-fn ctx))
  (if (contains? path ent)
    (cycle-map ent)
    (let [res (pull-entity index (child-pattern pattern j sub) ent (conj path ent) ctx)]
      (if (and (= :var (first ent)) (external-var? index (second ent)))
        (merge res {:var/qname (second ent) :var/external? true})
        res))))

(defn- pull-entity
  "Apply `pattern` to `ent`. `path` is the set of entities from the root to `ent`."
  [index pattern ent path ctx]
  (let [attrs (entity-attrs index ent)
        base (select-keys attrs (filter keyword? pattern))]
    (reduce (fn [acc j]
              (let [[join-key sub] (first j)
                    targets (join-targets index ent join-key)]
                (if (seq targets)
                  (do (reserve-nodes! ctx (count targets))
                      (assoc acc join-key
                             (mapv #(pull-child index pattern j sub path ctx %) targets)))
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
;; @spec MCP-OP-QUERY-096
;; @spec MCP-OP-QUERY-111
;; @spec MCP-OP-QUERY-112
(defn pull
  "Pull `pattern` for the entity `eid` of `index`.

   `eid` is `[:var/qname s]`, `[:ns/name s]` or `[:kw/name s]`. `pattern` is a
   vector of attribute keywords and one-key join maps. A join value is a
   sub-pattern vector or a recursion depth from 1 to 3. The depth of the whole
   pattern is at most 3 (see `pattern-depth`), and the pull builds at most
   `:max-pull-nodes` entities. An entity that is already on the path from the
   root is returned as its id and `:pull/cycle-to`. `opts` may hold
   `:max-pull-nodes` and `:interrupt-fn`, a zero-argument function that the pull
   calls for each entity. Returns a plain map with the metadata `{:kind :pull}`.
   A root var with two platform versions gives a vector of one such map for each
   version, `:clj` first (MCP-OP-QUERY-100).
   Throws invalid-expression, pull-too-deep, result-too-large, unknown-namespace
   or unknown-var."
  ([index pattern eid] (pull index pattern eid nil))
  ([index pattern eid opts]
   (let [[type id :as ent] (validate-eid eid)
         ctx {:nodes (volatile! 0)
              :max-nodes (or (:max-pull-nodes opts)
                             (:max-pull-nodes code-index/default-bounds)
                             default-max-pull-nodes)
              :interrupt-fn (or (:interrupt-fn opts) (fn []))}]
     (validate-pattern pattern {:pattern pattern :eid eid})
     (check-depth! pattern eid)
     (case type
       :ns (when-not (contains? (:namespaces index) id)
             (unknown-namespace! index id))
       :var (when-not (or (contains? (:vars index) id) (external-var? index id))
              (unknown-var! index id))
       :kw nil)
     (let [roots (root-ents index ent)
           _ (vswap! (:nodes ctx) + (count roots))
           results (mapv #(with-meta (pull-entity index pattern % #{%} ctx) {:kind :pull})
                         roots)]
       (if (= 1 (count results))
         (first results)
         (with-meta results {:kind :pull}))))))
