(ns clj-surgeon.code-index
  "Pure core of the code index behind `query_clojure`.

   The index is one immutable map. `apply-ingest` turns an index and a set of
   file results into the next index, `stale-plan` compares an index with a
   discovery result, and `with-ingest-lock` makes ingest single-writer for one
   workspace entry. Only the lock helper touches mutable state, and only the
   lock itself. The data shapes are in docs/intent/code-index/code-index-api.md."
  (:import
   (java.util.concurrent.locks ReentrantLock)))

;; @spec MCP-OP-QUERY-088
(def default-bounds
  "Every bound of the code index in one map. A caller passes a partial map and
   uses `(merge default-bounds partial)`."
  {:max-files 10000
   :max-file-bytes 2097152
   :max-expression-chars 4000
   :eval-timeout-ms 10000
   :max-result-chars 30000
   :max-skeleton-chars 200000
   :max-pull-depth 3
   :max-contexts 32
   :idle-ms 7200000
   :max-defs 64
   :max-error-chars 1000
   :pool-size 4})

;; @spec MCP-OP-QUERY-025
(def empty-index
  "The index of a workspace that holds no file."
  {:files {}
   :namespaces {}
   :vars {}
   :callers {}
   :kw-users {}
   :uses-by-target {}
   :skipped []
   :read-complete? true
   :built-ms 0})

;; ---------------------------------------------------------------------------
;; apply-ingest
;; ---------------------------------------------------------------------------

(defn- smaller-path-wins
  "Choose between two records of one qname or namespace by their :file."
  [old new]
  (if (and old (neg? (compare (:file old) (:file new))))
    old
    new))

(defn- file-entry
  "The index entry of a file result: its stat facts, namespace, var qnames and uses."
  [file-result]
  {:mtime (:mtime file-result)
   :size (:size file-result)
   :sha256 (:sha256 file-result)
   :ns (:ns file-result)
   :vars (vec (distinct (map :qname (:vars file-result))))
   :uses (:uses file-result)})

(defn- namespace-entry
  "The :namespaces entry of a file result. The :doc key is absent without a docstring."
  [file-result]
  (cond-> {:name (:ns file-result) :file (:path file-result)}
    (:ns-doc file-result) (assoc :doc (:ns-doc file-result))))

(defn- remove-path
  "Remove a path: its file entry, its var records and its namespace entry."
  [index path]
  (if-let [entry (get-in index [:files path])]
    (-> index
        (update :files dissoc path)
        (update :vars (fn [vars]
                        (reduce (fn [m q]
                                  (if (= path (:file (get m q))) (dissoc m q) m))
                                vars
                                (:vars entry))))
        (update :namespaces (fn [nss]
                              (if (= path (:file (get nss (:ns entry))))
                                (dissoc nss (:ns entry))
                                nss))))
    index))

(defn- add-file
  "Add a file result. A qname or namespace that another file holds goes to the smaller path."
  [index file-result]
  (let [path (:path file-result)
        own-vars (reduce (fn [m v] (if (contains? m (:qname v)) m (assoc m (:qname v) v)))
                         {}
                         (:vars file-result))
        ns-rec (namespace-entry file-result)]
    (-> index
        (assoc-in [:files path] (file-entry file-result))
        (update :vars (fn [vars]
                        (reduce-kv (fn [m q v] (update m q smaller-path-wins v))
                                   vars
                                   own-vars)))
        (update :namespaces update (:ns file-result) smaller-path-wins ns-rec))))

(defn- use-sort-key
  "Sort key of a use: file, line, from (nil first), kind name."
  [u]
  [(:file u) (:line u) (or (:from u) "") (name (:kind u))])

(defn- add-reverse
  "Add `from` to the set at each key of `ks`."
  [m from ks]
  (reduce (fn [acc k] (update acc k (fnil conj #{}) from)) m ks))

(defn- rebuild-derived
  "Rebuild :callers, :kw-users and :uses-by-target from the vars and file uses."
  [index]
  (let [vars (vals (:vars index))]
    (assoc index
           :callers (reduce (fn [m v] (add-reverse m (:qname v) (:calls v))) {} vars)
           :kw-users (reduce (fn [m v] (add-reverse m (:qname v) (:uses-kw v))) {} vars)
           :uses-by-target (->> (vals (:files index))
                                (mapcat :uses)
                                (group-by :target)
                                (into {} (map (fn [[t us]] [t (vec (sort-by use-sort-key us))])))))))

;; @spec MCP-OP-QUERY-025
;; @spec MCP-OP-QUERY-026
;; @spec MCP-OP-QUERY-027
;; @spec MCP-OP-QUERY-028
(defn apply-ingest
  "Return the next index from `index` and `ingest`.

   `ingest` holds optional :upsert (file results), :remove (paths), :skipped
   (replaces the skipped list when the key is present) and :built-ms (replaces
   the build time when present). A removed path that the index does not hold is
   ignored. A path in both :remove and :upsert ends up present. The result does
   not depend on the order of :upsert."
  [index {:keys [upsert] :as ingest}]
  (let [upserted (set (map :path upsert))
        stripped (reduce remove-path
                         index
                         (concat (:remove ingest) upserted))
        merged (reduce add-file stripped upsert)
        result (cond-> (rebuild-derived merged)
                 (contains? ingest :skipped) (assoc :skipped (vec (:skipped ingest)))
                 (contains? ingest :built-ms) (assoc :built-ms (:built-ms ingest)))]
    (assoc result :read-complete? (empty? (:skipped result)))))

;; ---------------------------------------------------------------------------
;; stale-plan
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-031
(defn stale-plan
  "Compare the index with the discovered files.

   Returns {:ingest [path ...] :remove [path ...]}, both sorted. :ingest holds
   the paths that are new or whose mtime or size differs. :remove holds the
   indexed paths that discovery no longer lists."
  [index discovered]
  (let [indexed (:files index)
        found (set (map :path discovered))]
    {:ingest (->> discovered
                  (filter (fn [{:keys [path mtime size]}]
                            (let [entry (get indexed path)]
                              (or (nil? entry)
                                  (not= mtime (:mtime entry))
                                  (not= size (:size entry))))))
                  (map :path)
                  distinct
                  sort
                  vec)
     :remove (->> (keys indexed)
                  (remove found)
                  sort
                  vec)}))

;; ---------------------------------------------------------------------------
;; Workspace entry and ingest lock
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-029
(defn new-entry
  "A workspace entry: the index atom, the ingest lock and the cold-build flag."
  []
  {:index (atom empty-index)
   :lock (ReentrantLock.)
   :cold? (atom true)})

;; @spec MCP-OP-QUERY-029
(defn with-ingest-lock
  "Call the zero-argument `f` while this thread holds the lock of `entry`.
   Release the lock in a finally and return the value of `f`. The lock is
   reentrant. Reads of the index atom never take it."
  [entry f]
  (let [^ReentrantLock lock (:lock entry)]
    (.lock lock)
    (try
      (f)
      (finally
        (.unlock lock)))))
