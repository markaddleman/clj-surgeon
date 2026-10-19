(ns clj-surgeon.mcp-query-clojure
  "query_clojure: read-only questions about the Clojure code of a workspace.

   The caller sends one Clojure expression. The expression runs in a fenced
   SCI context that binds six functions over an in-memory index of the
   workspace: `namespaces`, `skeleton`, `form`, `refs`, `pull` and `help`. The
   tool never writes. Before each evaluation it runs discovery and the stale
   check, so the answer always reflects the files on disk.

   This namespace only composes the code-index namespaces. The data shapes
   and the refusal table are in docs/intent/code-index/code-index-api.md."
  (:require
   [cheshire.core :as json]
   [clj-surgeon.code-index :as code-index]
   [clj-surgeon.code-index-discovery :as discovery]
   [clj-surgeon.code-index-fence :as fence]
   [clj-surgeon.code-index-functions :as functions]
   [clj-surgeon.code-index-ingest :as ingest]
   [clj-surgeon.code-index-judge :as judge]
   [clj-surgeon.code-index-pull :as pull]
   [clj-surgeon.code-index-render :as render]
   [clj-surgeon.code-index-session :as session]
   [clj-surgeon.mcp-operation :as mcp-operation]
   [clj-surgeon.mcp-runtime :as runtime]
   [clj-surgeon.mcp-workspace :as workspace]
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import
   (java.nio.file Files Path Paths)
   (java.util.concurrent Callable ExecutionException Executors Future)))

;; ---------------------------------------------------------------------------
;; Tool description, schemas and annotations
;; ---------------------------------------------------------------------------

(def ^:private spike-description-lead
  "SPIKE (issue #27, stage B2): when locate is bound, the description leads with it."
  (str "Start with (locate \"the issue text\"): a cheap model searches the index in three "
       "rounds and returns up to 8 vars that may need to change, most likely first, each "
       "with a reason and the relevant lines. (judge question data) asks the same model "
       "one question about data, for example (judge \"which lines read ServiceName?\" "
       "(form ['a.b/f])). "))

;; @spec MCP-OP-QUERY-001
(def query-clojure-description
  (str
   (when judge/enabled? spike-description-lead)
   "Find and read the Clojure code of a workspace. Send one Clojure "
   "expression in expression. It runs against an index of the workspace, "
   "which this tool refreshes before each call. Start with words from the "
   "task: (search \"ServiceName data page\") gives the best-matching vars, "
   "one line each with namespace, arguments, calls and first docstring "
   "sentence. Then read bodies with (form ['some.ns/a 'some.ns/b]), see a "
   "whole namespace with (skeleton 'some.ns), and find users with "
   "(refs 'some.ns/name). One expression can do several steps, for example "
   "(map :qname (search \"words\" {:limit 5})). Bound functions: (search text), "
   "(namespaces), (skeleton 'ns), (form 'ns/name), (refs 'ns/name), "
   "(pull pattern eid) and (help). "
   "(pull '[:var/qname {:var/calls 2}] [:var/qname \"some.ns/name\"]) "
   "follows calls and users. A def in one MCP session stays in that session. "
   "This tool reads only. It writes nothing."))

;; @spec MCP-OP-QUERY-002
(def query-clojure-schema
  {:type "object"
   :additionalProperties false
   :properties {"expression" {:type "string"}
                "workspace_root" {:type "string"}}
   :required ["expression"]})

(def query-clojure-output-schema
  {:type "object"
   :additionalProperties true
   :properties {"ok" {:type "boolean"}
                "operation" {:type "string"}
                "value" {:type "string"}
                "value_chars" {:type "integer"}
                "session" {:type "object"}
                "index" {:type "object"}
                "read_complete" {:type "boolean"}
                "skipped" {:type "array"}
                "workspace_root" {:type "string"}
                "error_type" {:type "string"}
                "error" {:type "string"}
                "next_call" {:type "object"}
                "remedy" {:type "string"}
                "problems" {:type "array"}
                "elapsed_ms" {:type "number" :minimum 0}}
   :required ["ok" "operation" "elapsed_ms"]})

(def query-clojure-annotations
  {:title "Query Clojure"
   :read-only true
   :destructive false
   :idempotent true
   :open-world false
   :return-direct false})

(def ^:private operation "query-clojure")

(def ^:private request-fields #{:expression :workspace_root})

(def ^:private runtime-config runtime/tool-config)

(defn init!
  "Set the live query_clojure tool configuration. Passing nil disarms it."
  [config]
  (reset! runtime-config
          (when config
            (assoc config :workspace-router (workspace/router config)))))

;; ---------------------------------------------------------------------------
;; Global state, used when the config does not carry its own
;; ---------------------------------------------------------------------------

(defonce ^:private global-registry (atom {}))

(defonce ^:private global-sessions (atom nil))

(defn- bounds-of
  "The effective bounds of a config: the defaults merged with the partial map."
  [config]
  (cond-> (merge code-index/default-bounds (:code-index/bounds config))
    ;; SPIKE (issue #27): three model rounds of locate need more than 10 s
    judge/enabled? (assoc :eval-timeout-ms judge/eval-timeout-ms)))

(defn- sessions-of
  [config bounds]
  (or (:code-index/sessions config)
      (or @global-sessions
          (swap! global-sessions
                 #(or % (session/sessions
                         (select-keys bounds [:max-contexts :idle-ms :max-defs :max-def-items])))))))

(defn- entry-for!
  "The workspace entry of `root` in `registry`, made on first use."
  [registry root]
  (get (swap! registry
              (fn [entries]
                (if (contains? entries root)
                  entries
                  (assoc entries root (code-index/new-entry)))))
       root))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(defn- narrow-call
  "A continuation that runs `expression`, with the workspace_root of the request
   when the request had one."
  [params expression]
  (cond-> {:tool "query_clojure" :expression expression}
    (contains? params :workspace_root)
    (assoc :workspace_root (:workspace_root params))))

;; @spec MCP-OP-QUERY-005
;; @spec MCP-OP-QUERY-091
(defn- problem
  "One problem of a refusal: `error_type`, `error` and exactly one of `next_call`
   and `remedy`.

   `ctx` holds the normalized `:params`. A `next-call` that equals the request
   is dropped and the `remedy` is given instead (MCP-OP-QUERY-005), for each
   problem as for the top level."
  [ctx error-type message {:keys [next-call remedy]}]
  (let [call (when (and next-call
                        (not= (dissoc next-call :tool) (:params ctx)))
               next-call)]
    (cond-> {:error_type (name error-type)
             :error message}
      call (assoc :next_call call)
      (not call) (assoc :remedy (or remedy "Change the request and send it again.")))))

;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-091
(defn- refusal
  "A typed refusal that reports `problems`, a non-empty vector. The top-level
   `error_type`, `error` and continuation are copies of the first problem.
   `ctx` may hold the routed `:root`; `:root?` false leaves it out."
  [ctx problems]
  ;; The messages and the remedies can quote caller text, for example an
  ;; unknown field name. They pass through the shared receipt encoder.
  (let [problems (mapv mcp-operation/canonicalize-receipt-text problems)]
    (mcp-operation/canonicalize-receipt-text
     (cond-> (merge {:ok false :operation operation}
                    (select-keys (first problems) [:error_type :error :next_call :remedy])
                    {:problems problems})
       (:root ctx) (assoc :workspace_root (:root ctx))))))

(defn- refuse
  "A typed refusal with one problem."
  [ctx error-type message continuation]
  (refusal ctx [(problem ctx error-type message continuation)]))

(defn- cut
  [text limit]
  (if (> (count text) limit) (subs text 0 limit) text))

(defn- bound-list
  [data]
  (str/join ", " (or (seq (:bound data))
                     (map str (functions/bound-symbols)))))

(defn- depth-one
  "The pull pattern with every recursion depth and every `...` replaced by 1."
  [pattern]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v]] [k (if (or (number? v) (= '... v)) 1 v)])) x)
       x))
   pattern))

(defn- pull-narrow-expression
  [data]
  (when (vector? (:pattern data))
    (binding [*print-namespace-maps* false]
      (str "(pull '" (pr-str (depth-one (:pattern data))) " " (pr-str (:eid data)) ")"))))

(defn- data-problem
  "The problem of an error that carries an `:error-type` in `data`: an ex-info
   from the index functions, the fence, the session or the refresh."
  [ctx bounds data message]
  (let [message (or message (:message data) "The request failed.")
        data (if (or (:stack-overflow? data)
                     (and (= :invalid-expression (:error-type data))
                          (= :too-deep (:reason data))))
               (assoc data :error-type :expression-too-deep :stack-overflow? true)
               data)]
    (case (:error-type data)
      :invalid-expression
      (problem ctx :invalid-expression message
               {:remedy (or (:remedy data)
                            (str "Send exactly one readable Clojure form, without reader "
                                 "eval. Reader message: " message))})

      :denied-symbol
      (problem ctx :denied-symbol
               (str "Denied symbol: " (:symbol data))
               {:remedy (str "The symbol " (:symbol data) " is not allowed. Use only "
                             "the bound functions " (bound-list data)
                             " and the pure core functions.")})

      :unknown-namespace
      (let [nearest (first (:nearest data))]
        (problem ctx :unknown-namespace message
                 {:next-call (when nearest
                               (narrow-call (:params ctx) (str "(skeleton '" nearest ")")))
                  :remedy "Run (namespaces) to list the namespaces of the index."}))

      :unknown-var
      (problem ctx :unknown-var message
               {:next-call (when (and (:ns-known? data) (:ns data))
                             (narrow-call (:params ctx) (str "(skeleton '" (:ns data) ")")))
                :remedy (str "Run (namespaces) to list the namespaces, then "
                             "(skeleton 'some.ns) to list the vars of one.")})

      :pull-too-deep
      (problem ctx :pull-too-deep message
               {:next-call (some->> (pull-narrow-expression data)
                                    (narrow-call (:params ctx)))
                :remedy (str "The recursion depth is at most " (:max-pull-depth bounds)
                             ", and ... is not allowed. Use a smaller depth.")})

      :session-full
      (problem ctx :session-full
               (str "The session holds " (:defs data) " names. The limit is "
                    (:limit data) ".")
               {:remedy (str "The session cannot hold more than " (:limit data)
                             " names. Use a name that the session already holds, "
                             "or use a new session.")})

      :busy
      (problem ctx :busy message
               {:remedy (or (:remedy data)
                            "Workers of earlier timeouts are still running. Wait a few seconds and send the request again.")})

      :result-too-large
      (problem ctx :result-too-large message
               {:remedy (or (:remedy data)
                            "The result is too large. Ask for less, with a smaller pattern or a smaller part.")})

      :expression-too-deep
      (problem ctx :expression-too-deep message
               {:remedy (or (:remedy data)
                            (if (:stack-overflow? data)
                              (str "The expression is nested too deeply for the evaluator. "
                                   "The depth bound is " (:max-expression-depth bounds)
                                   " levels. Flatten the expression, or split it into "
                                   "several requests.")
                              (str "The expression nests more than " (:limit data)
                                   " levels. Flatten it, or split it into several requests.")))})

      :internal-error
      (problem ctx :internal-error (or (:error data) message)
               {:remedy (or (:remedy data)
                            "Send the request again. If it fails again, report the failure with this text.")})

      :timeout
      (problem ctx :timeout message
               {:remedy (if (= :refresh (:phase data))
                          (str "The refresh of the index ran longer than " (:limit-ms data)
                               " ms. The files that it finished are kept. Send the request "
                               "again to continue the refresh.")
                          (str "The request ran longer than " (:limit-ms data)
                               " ms. Ask for a smaller part of the index."))})

      (problem ctx :evaluation-error
               (cut message (:max-error-chars bounds))
               {:remedy "Fix the expression and send it again."}))))

(defn- overflow-cause?
  "True when a `StackOverflowError` is `t` or in the cause chain of `t`."
  [^Throwable t]
  (loop [e t n 0]
    (cond (nil? e) false
          (instance? StackOverflowError e) true
          (> n 20) false
          :else (recur (ex-cause e) (inc n)))))

(defn- typed-refusal
  "Turn an ex-info that carries an `:error-type` into a refusal. An
   evaluation error that a `StackOverflowError` caused is `expression-too-deep`."
  [ctx bounds ^Throwable e]
  (let [data (ex-data e)
        data (if (and (= :evaluation-error (:error-type data)) (overflow-cause? e))
               (assoc data :stack-overflow? true)
               data)]
    (refusal ctx [(data-problem ctx bounds data (ex-message e))])))

(defn- index-unavailable
  [ctx ^Throwable e]
  (refuse ctx :index-unavailable
          (str "The index of the workspace is not available: " (ex-message e))
          {:remedy (str "The index build or the refresh failed. Cause: "
                        (or (ex-message e) (str (class e)))
                        ". Fix the cause and send the request again.")}))

;; ---------------------------------------------------------------------------
;; Request shape
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-002
;; @spec MCP-OP-QUERY-092
(defn- shape-messages
  "One message for each problem of the shape of `params`, in order: each unknown
   field, then `expression`, then `workspace_root`. Empty when the shape is right."
  [params]
  (if-not (map? params)
    ["The request must be an object with the string field expression."]
    (vec
     (concat
      (for [k (sort-by name (remove request-fields (keys params)))]
        (str "Unknown field: " (name k) ". The fields are expression and workspace_root."))
      (when-not (string? (:expression params))
        ["The field expression is required and must be a string."])
      (when (and (contains? params :workspace_root)
                 (not (string? (:workspace_root params))))
        ["The field workspace_root must be a string."])))))

(defn- normalize-params
  "Normalize `params` through a JSON round trip, with keyword keys."
  [params]
  (try
    (json/parse-string (json/generate-string params) true)
    (catch Throwable _ ::unreadable)))

;; ---------------------------------------------------------------------------
;; Index refresh
;; ---------------------------------------------------------------------------

(defn- bounded-map
  "Map `f` over `xs` on a pool of at most `size` threads. Rethrow the cause of
   the first failure."
  [size f xs]
  (if (empty? xs)
    []
    (let [pool (Executors/newFixedThreadPool (int (max 1 (min size (count xs)))))]
      (try
        (let [futures (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))) xs)]
          (mapv (fn [^Future future]
                  (try
                    (.get future)
                    (catch ExecutionException e
                      (throw (or (.getCause e) e)))))
                futures))
        (finally
          (.shutdownNow pool))))))

(defn- real-path
  ^Path [^String p]
  (.toRealPath (Paths/get p (make-array String 0)) (make-array java.nio.file.LinkOption 0)))

(defn- inside-root?
  "True when the real path of `abs` lies inside the real path of `root`."
  [root abs]
  (try
    (.startsWith (real-path abs) (real-path root))
    (catch Exception _ false)))

(defn- ingest-path
  "Read one stable file and make its file result, or describe why it is skipped.
   `expired?` is checked before the file is read (MCP-OP-QUERY-108): an expired
   refresh answers `{:pending path}` and reads nothing.
   An `ingest-fn` throw is not caught here: it aborts the refresh."
  [{:keys [root discovery by-path stat-fn read-fn ingest-fn expired?]} path]
  (if (expired?)
    {:pending path}
    (let [{:keys [abs size]} (get by-path path)
          abs (or abs (str root "/" path))
          custom? (or stat-fn read-fn)
          skip (fn [reason] {:skipped (cond-> {:file path :reason reason}
                                        size (assoc :size size))})]
      (if (and (not custom?) (not (inside-root? root abs)))
        (skip :unreadable)
        (let [read (discovery/read-stable abs (cond-> {}
                                                stat-fn (assoc :stat-fn stat-fn)
                                                read-fn (assoc :read-fn read-fn)))]
          (if (:ok read)
            {:result (ingest-fn {:path path
                                 :text (String. ^bytes (:bytes read) "UTF-8")
                                 :mtime (:mtime read)
                                 :size (:size read)
                                 :sha256 (:sha256 read)
                                 :lint-as (:lint-as discovery)})}
            (skip (:reason read))))))))

(defn- refresh-timeout
  "The `timeout` ex-info of a refresh that passed its bound."
  [limit-ms detail]
  (ex-info (str "The refresh of the index exceeded " limit-ms " ms")
           (assoc detail :error-type :timeout :phase :refresh :limit-ms limit-ms)))

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-031
;; @spec MCP-OP-QUERY-034
;; @spec MCP-OP-QUERY-035
;; @spec MCP-OP-QUERY-108
;; @spec MCP-OP-QUERY-119
(defn refresh-index!
  "Bring the index of `entry` up to date with the files under `root`.

   Runs discovery and the stale plan, reads and ingests each file to ingest,
   then commits one `apply-ingest` result with one `reset!`, all under the
   ingest lock. A cold entry ingests on a pool of `:pool-size` workers, sets
   `:built-ms`, and reports no re-ingested files. A throw of `ingest-fn` or of
   `discover-fn` leaves the index as it was.

   The whole refresh runs under the refresh time bound `:refresh-timeout-ms`
   (default 60 s). Discovery and each file read check it first. When it passes,
   the files that were finished go into the index in one step, no later file is
   read, and the refresh throws a `timeout` ex-info (`:phase :refresh`). A cold
   entry stays cold, so the next call continues the build.

   Returns `{:index map :reingested [path ...]}`."
  [entry root bounds opts]
  (let [{:keys [ingest-fn discover-fn stat-fn read-fn now-ms]
         :or {ingest-fn ingest/file-result
              discover-fn discovery/discover
              now-ms #(System/currentTimeMillis)}} opts
        limit-ms (or (:refresh-timeout-ms bounds)
                     (:refresh-timeout-ms code-index/default-bounds))]
    (code-index/with-ingest-lock
      entry
      (fn []
        (let [cold? @(:cold? entry)
              started (now-ms)
              expired? (fn [] (>= (- (now-ms) started) limit-ms))
              found (try
                      (discover-fn root (assoc bounds :expired? expired?))
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :timeout (:error-type (ex-data e)))
                          (throw (refresh-timeout limit-ms {:detail (ex-message e)}))
                          (throw e))))
              index @(:index entry)
              {to-ingest :ingest to-remove :remove} (code-index/stale-plan index (:files found))
              env {:root root
                   :discovery found
                   :by-path (into {} (map (juxt :path identity)) (:files found))
                   :stat-fn stat-fn
                   :read-fn read-fn
                   :ingest-fn ingest-fn
                   :expired? expired?}
              ;; MCP-OP-QUERY-119: the total-byte cap decides before any read.
              plan (code-index/plan-reads
                    (mapv (fn [path] {:path path :size (get-in env [:by-path path :size])}) to-ingest)
                    (or (:max-total-bytes bounds) (:max-total-bytes code-index/default-bounds)))
              to-read (:read plan)
              outcomes (if cold?
                         (bounded-map (:pool-size bounds) #(ingest-path env %) to-read)
                         (mapv #(ingest-path env %) to-read))
              upserts (vec (keep :result outcomes))
              failures (into (vec (keep :skipped outcomes)) (:skipped plan))
              pending (vec (sort (keep :pending outcomes)))
              skipped (into (vec (:skipped found)) failures)
              ;; MCP-OP-QUERY-141: nothing changed, so keep the identical map.
              unchanged? (and (not cold?) (empty? to-ingest) (empty? to-remove)
                              (= skipped (vec (:skipped index))))
              next-index (if unchanged?
                           index
                           (code-index/apply-ingest
                            index
                            (cond-> {:upsert upserts
                                     :remove to-remove
                                     :skipped skipped}
                              (and cold? (empty? pending))
                              (assoc :built-ms (- (now-ms) started)))))]
          (reset! (:index entry) next-index)
          (when (seq pending)
            (throw (refresh-timeout limit-ms {:ingested (count upserts) :pending (count pending)})))
          (when cold? (reset! (:cold? entry) false))
          {:index next-index
           :reingested (if cold?
                         []
                         (vec (sort (concat (map :path upserts) to-remove))))})))))

;; ---------------------------------------------------------------------------
;; Evaluation and result
;; ---------------------------------------------------------------------------

(defn- skeleton?
  [value]
  (and (vector? value) (= :skeleton (:kind (meta value)))))

(defn- quoted-text
  "The text of an argument form, with (quote x) written as 'x."
  [x]
  (if (and (seq? x) (= 'quote (first x)) (= 2 (count x)))
    (str "'" (pr-str (second x)))
    (pr-str x)))

(defn- narrower-expression
  "An expression that asks for less than `form` did, or nil (MCP-OP-QUERY-086)."
  [form value]
  (when (seq? form)
    (case (first form)
      form
      (when (and (vector? value)
                 (>= (count value) 2)
                 (every? #(= :form (:kind %)) value))
        (when-let [ns-name (pull/qname-ns (:qname (first value)))]
          (str "(skeleton '" ns-name ")")))

      refs
      (let [[_ target opts] form]
        (cond
          (= 2 (count form))
          (str "(refs " (quoted-text target) " {:include-tests false})")

          (and (= 3 (count form)) (map? opts) (not (false? (:include-tests opts))))
          (str "(refs " (quoted-text target) " "
               (pr-str (assoc opts :include-tests false)) ")")))

      nil)))

(defn- too-large
  [ctx bounds form value skeleton? chars]
  (let [limit (if skeleton? (:max-skeleton-chars bounds) (:max-result-chars bounds))
        narrower (when-not skeleton? (narrower-expression form value))]
    (refuse ctx :result-too-large
            (str "The result is longer than " limit " characters"
                 (when chars (str "; the text was cut at " chars))
                 ".")
            {:next-call (some->> narrower (narrow-call (:params ctx)))
             :remedy (if skeleton?
                       (str "The skeleton is longer than " limit " characters. "
                            "Use (form 'some.ns/name) or (pull ...) for the vars you need.")
                       (str "The result is longer than " limit " characters. "
                            "Ask for fewer items, or take a part with count, take or filter."))})))

;; @spec MCP-OP-QUERY-107
;; @spec MCP-OP-QUERY-109
;; @spec MCP-OP-QUERY-112
;; @spec MCP-OP-QUERY-129
(defn- evaluate
  "Evaluate and render under one time limit. The bound `pull` takes the node
   budget and the deadline check, the renderer takes the deadline check, and the
   worker takes the stop grace and the worker cap, all from `bounds`. Returns the
   evaluation result with `:rendered`."
  [sessions handle form index touched bounds]
  (session/run-with-timeout
   (:eval-timeout-ms bounds)
   (fn []
     (let [result (session/eval-in-session sessions handle form
                                           (functions/bindings
                                            index touched
                                            {:max-pull-nodes (:max-pull-nodes bounds)
                                             :interrupt-fn session/check-deadline!}))
           value (:value result)
           skeleton (and (nil? (:def-name result)) (skeleton? value))
           limit (if skeleton (:max-skeleton-chars bounds) (:max-result-chars bounds))
           shown (if (:def-name result)
                   (render/render-def (:def-name result) value)
                   value)]
       (assoc result
              :skeleton? skeleton
              :limit limit
              :rendered (render/render-bounded shown limit session/check-deadline!))))
   {:grace-ms (:stop-grace-ms bounds)
    :worker-cap (:max-live-workers bounds)}))

(defn- skipped-out
  [skipped]
  (mapv (fn [entry]
          (into {} (map (fn [[k v]] [k (if (keyword? v) (name v) v)])) entry))
        skipped))

(defn- success
  [{:keys [root reset? index reingested touched evaluated]}]
  (let [text (:text (:rendered evaluated))]
    ;; MCP-OP-QUERY-135: the file receipts are metadata, so the reply does not carry them.
    (with-meta
      {:ok true
       :operation operation
       :value text
       :value_chars (count text)
       :session {:defs (:defs evaluated) :reset (boolean reset?)}
       :index {:files (count (:files index))
               :vars (count (:vars index))
               :skipped (count (:skipped index))
               :built_ms (:built-ms index)}
       :read_complete (boolean (:read-complete? index))
       :skipped (skipped-out (:skipped index))
       :workspace_root root}
      {:files (mapv (fn [path] {:path path :sha256 (get-in index [:files path :sha256])})
                    (sort touched))
       :reingested (vec reingested)})))

(defn- as-problems
  "The problems of a fence check as a vector: nil gives none, one problem map
   gives one, a collection of problems gives them all."
  [result]
  (cond (nil? result) []
        (map? result) [result]
        :else (vec result)))

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-126
(defn- throwable-data
  "The problem data of a `Throwable` that a step before the worker threw. An
   ex-info with an `:error-type` keeps it (a malformed special form, a refusal of
   the fence). A `StackOverflowError` is an `expression-too-deep`. Any other `Throwable` is an `internal-error` with its class and
   its message, cut at the caller-text ceiling, and no stack trace."
  [^Throwable t]
  (cond
    (and (instance? clojure.lang.ExceptionInfo t) (:error-type (ex-data t)))
    (assoc (ex-data t) :message (ex-message t))

    (instance? StackOverflowError t)
    {:error-type :expression-too-deep
     :stack-overflow? true
     :message "The expression is nested too deeply."}

    :else
    (let [text (cut (str (.getName (class t)) (when-let [m (ex-message t)] (str ": " m)))
                    mcp-operation/caller-text-ceiling)]
      {:error-type :internal-error
       :error text
       :message text
       :remedy "Send the request again. If it fails again, report the failure with this text."})))

(defn- attempt
  "Call `f`. Returns `{:value v}`, or `{:data problem-data}` when it throws any
   `Throwable`."
  [f]
  (try {:value (f)}
       (catch Throwable t {:data (throwable-data t)})))

(defn- mask-symbol
  "`form` with each symbol whose text is `symbol-text` replaced by a bound
   function name, which admission always accepts. Only quoted data and reader
   metadata keep the symbol."
  [form symbol-text]
  (walk/postwalk (fn [x] (if (and (symbol? x) (= symbol-text (str x))) 'help x)) form))

;; @spec MCP-OP-QUERY-093
(defn- admission-problems
  "Every problem of admission, one for each distinct denied symbol in the order
   of its first occurrence. `fence/admit` may answer with the first denial only
   (a map). Then this function masks that symbol and admits the form again,
   until no new denial is left, so that the caller sees every symbol in one pass.
   A collection of problems from `fence/admit` is taken as it is."
  [form defs]
  (let [first-result (try (fence/admit form defs)
                          (catch Throwable t [(throwable-data t)]))]
    (if-not (map? first-result)
      (as-problems first-result)
      (loop [found [first-result]
             masked (mask-symbol form (str (:symbol first-result)))]
        (let [more (try (fence/admit masked defs)
                        (catch Throwable t (throwable-data t)))
              seen (set (map #(str (:symbol %)) found))]
          (cond
            (not (map? more)) (into found (as-problems more))
            (contains? seen (str (:symbol more))) found
            (not= :denied-symbol (:error-type more)) (conj found more)
            :else (recur (conj found more) (mask-symbol masked (str (:symbol more))))))))))

;; @spec MCP-OP-QUERY-094
(defn- def-problems
  "Every problem of the check of the `def` names: each refused name, in walk
   order, then `session-full` when the names would take the session over
   `max-defs`. `fence/check-def` may answer with the first problem only (a
   map). Then this function checks each name on its own and adds the limit, so
   that the caller sees every problem in one pass. A collection of problems from
   `fence/check-def` is taken as it is."
  [form defs max-defs]
  (let [result (try (fence/check-def form defs max-defs)
                    (catch Throwable t [(throwable-data t)]))]
    (if-not (map? result)
      (as-problems result)
      (let [held (set (map symbol defs))
            names (fence/def-names form)
            per-name (keep (fn [n]
                             (let [r (fence/check-def (list 'def n 1) defs max-defs)]
                               (when (and (map? r) (= :denied-symbol (:error-type r))) r)))
                           names)
            denied (cond-> (vec per-name)
                     (and (= :denied-symbol (:error-type result))
                          (not-any? #(= (str (:symbol result)) (str (:symbol %))) per-name))
                     (conj result))
            added (remove held names)
            limit (when (and (seq added) (> (count (into held names)) max-defs))
                    {:error-type :session-full :defs (count held) :limit max-defs})]
        (cond-> denied limit (conj limit))))))

;; @spec MCP-OP-QUERY-092
;; @spec MCP-OP-QUERY-093
;; @spec MCP-OP-QUERY-094
;; @spec MCP-OP-QUERY-125
(defn- expression-problems
  "Read, admit and check the `def` names of `expression`, in that order, and do
   not stop at the first problem. Admission and the `def` check need a form that
   the reader could read. `defs` is the set of names that the session holds.
   Each step turns any `Throwable` into a problem of its own (MCP-OP-QUERY-125),
   so the steps that do not need its output still run.
   Returns `{:form form-or-nil :problems [data ...]}`."
  [expression defs max-defs max-depth]
  (let [read (attempt #(fence/read-expression expression max-depth))]
    (if-let [read-problem (:data read)]
      {:problems [read-problem]}
      (let [form (:value read)
            guard (fn [f] (let [r (attempt f)] (or (:value r) [(:data r)])))]
        {:form form
         :problems (into (guard #(admission-problems form defs))
                         (guard #(def-problems form defs max-defs)))}))))

;; @spec MCP-OP-QUERY-092
;; @spec MCP-OP-QUERY-008
;; @spec MCP-OP-QUERY-124
;; @spec MCP-OP-QUERY-125
(defn- checks
  "Run the checks of MCP-OP-QUERY-092 in order, none stopping the next: each
   field, the length of the expression, the workspace root, the reading, the
   admission and the check of the `def` names. A check runs unless its input is
   missing. Returns `{:problems [problem ...] :form :handle :reset? :root}` where
   each problem is a refusal problem map, and `:root` is the routed root."
  [config params normalized opts bounds]
  (let [ctx0 {:params params}
        shape (shape-messages normalized)
        field-problems (mapv #(problem ctx0 :invalid-request %
                                       {:remedy (str % " Send expression, and optionally workspace_root.")})
                             shape)
        expression (when (map? normalized) (:expression normalized))
        expression? (string? expression)
        too-long? (and expression? (> (count expression) (:max-expression-chars bounds)))
        length-problems (when too-long?
                          [(problem ctx0 :expression-too-large
                                    (str "The expression has " (count expression) " characters. The limit is "
                                         (:max-expression-chars bounds) ".")
                                    {:remedy (str "Send an expression of at most "
                                                  (:max-expression-chars bounds) " characters.")})])
        ;; MCP-OP-QUERY-124: one iterative scan, after the length check and
        ;; before the reading.
        max-depth (or (:max-expression-depth bounds) fence/default-max-depth)
        depth-try (when (and expression? (not too-long?))
                    (attempt #(fence/check-depth! expression max-depth)))
        depth-problems (when-let [data (:data depth-try)]
                         [(data-problem ctx0 bounds data (:message data))])
        asked (when (map? normalized) (:workspace_root normalized))
        root-input? (and (map? normalized)
                         (or (not (contains? normalized :workspace_root)) (string? asked)))
        routing (when root-input?
                  (attempt
                   #(let [router (or (:workspace-router config) (workspace/router config))]
                      (workspace/resolve-request router (select-keys normalized request-fields)))))
        routed (:value routing)
        root-ok? (and routed (:ok routed) (or (nil? asked) (= asked (:workspace-root routed))))
        routing-problems (when-let [data (:data routing)]
                           [(data-problem ctx0 bounds data (:message data))])
        root-problems (when (and root-input? (not (:data routing)) (not root-ok?))
                        [(problem ctx0 :invalid-workspace-root
                                  "workspace_root must be an existing canonical absolute directory."
                                  {:remedy (str "Send workspace_root as the canonical absolute path "
                                                "of an existing directory, or omit it to use the "
                                                "server project root.")})])
        root (when root-ok? (:workspace-root routed))
        sessions (sessions-of config bounds)
        checkout-try (when root
                       (attempt #(session/checkout! sessions (:session-id opts) root)))
        checkout (:value checkout-try)
        defs (if checkout (set (map symbol (session/handle-defs (:handle checkout)))) #{})
        read? (and expression? (not too-long?) (not (:data depth-try)))
        found (when read? (expression-problems expression defs (:max-defs sessions) max-depth))
        ctx (assoc ctx0 :root root)
        step-ps (mapv #(data-problem ctx bounds % (:message %))
                      (some-> (:data checkout-try) vector))
        expression-ps (mapv #(data-problem ctx bounds % (:message %)) (:problems found))
        early? (or (seq field-problems) (seq length-problems) (seq depth-problems))]
    {:problems (-> []
                   (into field-problems)
                   (into length-problems)
                   (into depth-problems)
                   (into routing-problems)
                   (into root-problems)
                   (into step-ps)
                   (into expression-ps))
     ;; A refusal for the shape or the length names no workspace_root.
     :report-root (when-not early? root)
     :root root
     :form (:form found)
     :handle (:handle checkout)
     :reset? (:reset? checkout)
     :sessions sessions}))

(defn- run-routed
  "Everything after the checks passed: refresh, evaluation and the result."
  [config ctx opts bounds checked]
  (try
    (let [{:keys [form handle reset? sessions]} checked
          entry (entry-for! (or (:code-index/registry config) global-registry) (:root ctx))
          refreshed (try
                      (refresh-index! entry (:root ctx) bounds opts)
                      (catch Throwable e
                        (if (= :timeout (:error-type (ex-data e)))
                          (throw e)
                          (throw (ex-info (or (ex-message e) (str (class e)))
                                          {:error-type :index-unavailable} e)))))
          index (:index refreshed)
          touched (atom #{})
          evaluated (evaluate sessions handle form index touched bounds)
          rendered (:rendered evaluated)]
      (if (:over-limit? rendered)
        (too-large ctx bounds form (:value evaluated) (:skeleton? evaluated) (:chars rendered))
        (success {:root (:root ctx)
                  :reset? reset?
                  :index index
                  :reingested (:reingested refreshed)
                  :touched @touched
                  :evaluated evaluated})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :index-unavailable (:error-type (ex-data e)))
        (index-unavailable ctx e)
        (typed-refusal ctx bounds e)))
    (catch Throwable e
      (if (overflow-cause? e)
        (refusal ctx [(data-problem ctx bounds {:error-type :expression-too-deep
                                                :stack-overflow? true}
                                    "The expression is nested too deeply.")])
        (refuse ctx :evaluation-error
                (cut (or (ex-message e) (str (class e))) (:max-error-chars bounds))
                {:remedy "Fix the expression and send it again."})))))

;; @spec MCP-OP-QUERY-003
;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-008
;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-084
;; @spec MCP-OP-QUERY-092
;; @spec MCP-OP-QUERY-125
(defn execute-request!
  "Run one query_clojure request and return the result map, with no
   `elapsed_ms`. `opts` may hold `:session-id`, `:ingest-fn`, `:discover-fn`,
   `:stat-fn`, `:read-fn` and `:now-ms`.

   The checks of MCP-OP-QUERY-092 run first and collect every problem (C25);
   only a request without a problem refreshes the index and evaluates."
  ([config params] (execute-request! config params {}))
  ([config params opts]
   (try
     (let [normalized (normalize-params params)
           request (if (map? normalized) normalized {})
           bounds (bounds-of config)
           checked (checks config request normalized opts bounds)]
       (if (seq (:problems checked))
         (refusal {:root (:report-root checked)} (:problems checked))
         (run-routed config
                     {:params request :root (:root checked)}
                     opts bounds checked)))
     ;; The backstop of MCP-OP-QUERY-125: no Throwable leaves this function.
     (catch Throwable t
       (let [ctx {:params {}}
             data (throwable-data t)]
         (refusal {} [(data-problem ctx code-index/default-bounds data (:message data))]))))))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn- invoke-getter
  [exchange method-name]
  (try
    (let [method (.getMethod (class exchange) method-name (make-array Class 0))
          value (.invoke method exchange (object-array 0))]
      (when (some? value) (str value)))
    (catch Throwable _ nil)))

;; @spec MCP-OP-QUERY-067
(defn session-id-of
  "The MCP session id of `exchange`, or nil. A map holds it under `:session-id`."
  [exchange]
  (cond
    (nil? exchange) nil
    (map? exchange) (:session-id exchange)
    :else (or (invoke-getter exchange "sessionId")
              (invoke-getter exchange "getSessionId"))))

;; @spec MCP-OP-QUERY-006
(defn summary
  "The one summary text of a query_clojure result."
  [result]
  (if (:ok result)
    (str (:value result) "\n;; " (:value_chars result) " chars · "
         (mcp-operation/format-elapsed-ms (:elapsed_ms result)))
    (str "query_clojure refused · " (:error_type result) " · "
         (mcp-operation/format-elapsed-ms (:elapsed_ms result))
         "\n" (:error result) "\nnothing was written")))

;; @spec MCP-OP-QUERY-006
;; @spec MCP-OP-QUERY-127
(defn handle-query-clojure
  "clojure-mcp callback handler retained as a Var for hot reload."
  [exchange params callback]
  (mcp-operation/invoke!
   {:operation operation
    :execute (fn []
               (if-let [config @runtime-config]
                 (execute-request! config params {:session-id (session-id-of exchange)})
                 (refuse {:params {}} :server-not-initialized
                         "query_clojure server is not initialized"
                         {:remedy "Start the clj-surgeon MCP server, then send the request again."})))
    :summarize summary
    :callback callback}))

;; @spec MCP-OP-QUERY-001
(def query-clojure-tool
  {:id :query-clojure
   :name "query_clojure"
   :description query-clojure-description
   :schema query-clojure-schema
   :output-schema query-clojure-output-schema
   :annotations query-clojure-annotations
   :outcome-classes #{:read-success :typed-refusal}
   :structured? true
   :tool-fn #'handle-query-clojure})
