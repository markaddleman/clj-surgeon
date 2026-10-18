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

;; @spec MCP-OP-QUERY-001
(def query-clojure-description
  (str
   "Ask a question about the Clojure code of a workspace. Send one Clojure "
   "expression in expression. It runs against an index of the workspace, "
   "which this tool refreshes before each call. Bound functions: "
   "(namespaces), (skeleton 'ns), (form 'ns/name), (refs 'ns/name), "
   "(pull pattern eid) and (help). Start with (skeleton 'some.ns) to see "
   "the vars of a namespace, then (form 'some.ns/name) to read one var. "
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
                "files" {:type "array"}
                "index" {:type "object"}
                "reingested" {:type "array"}
                "read_complete" {:type "boolean"}
                "skipped" {:type "array"}
                "workspace_root" {:type "string"}
                "error_type" {:type "string"}
                "error" {:type "string"}
                "next_call" {:type "object"}
                "remedy" {:type "string"}
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
  (merge code-index/default-bounds (:code-index/bounds config)))

(defn- sessions-of
  [config bounds]
  (or (:code-index/sessions config)
      (or @global-sessions
          (swap! global-sessions
                 #(or % (session/sessions
                         (select-keys bounds [:max-contexts :idle-ms :max-defs])))))))

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

;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-005
(defn- refuse
  "A typed refusal with exactly one of `next_call` and `remedy`.

   `ctx` holds the normalized `:params` and, once the workspace is routed, the
   `:root`. A `next-call` that equals the request is dropped and the `remedy`
   is given instead (MCP-OP-QUERY-005)."
  [ctx error-type message {:keys [next-call remedy]}]
  (let [call (when (and next-call
                        (not= (dissoc next-call :tool) (:params ctx)))
               next-call)]
    ;; The message and the remedy can quote caller text, for example an
    ;; unknown field name. They pass through the shared receipt encoder.
    (mcp-operation/canonicalize-receipt-text
      (cond-> {:ok false
               :operation operation
               :error_type (name error-type)
               :error message}
        call (assoc :next_call call)
        (not call) (assoc :remedy (or remedy "Change the request and send it again."))
        (:root ctx) (assoc :workspace_root (:root ctx))))))

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

(defn- typed-refusal
  "Turn an ex-info that carries an `:error-type` into a refusal."
  [ctx bounds ^Throwable e]
  (let [data (ex-data e)
        message (or (ex-message e) (:message data) "The request failed.")]
    (case (:error-type data)
      :invalid-expression
      (refuse ctx :invalid-expression message
              {:remedy (str "Send exactly one readable Clojure form, without reader "
                            "eval. Reader message: " message)})

      :denied-symbol
      (refuse ctx :denied-symbol
              (str "Denied symbol: " (:symbol data))
              {:remedy (str "The symbol " (:symbol data) " is not allowed. Use only "
                            "the bound functions " (bound-list data)
                            " and the pure core functions.")})

      :unknown-namespace
      (let [nearest (first (:nearest data))]
        (refuse ctx :unknown-namespace message
                {:next-call (when nearest
                              (narrow-call (:params ctx) (str "(skeleton '" nearest ")")))
                 :remedy "Run (namespaces) to list the namespaces of the index."}))

      :unknown-var
      (refuse ctx :unknown-var message
              {:next-call (when (and (:ns-known? data) (:ns data))
                            (narrow-call (:params ctx) (str "(skeleton '" (:ns data) ")")))
               :remedy (str "Run (namespaces) to list the namespaces, then "
                            "(skeleton 'some.ns) to list the vars of one.")})

      :pull-too-deep
      (refuse ctx :pull-too-deep message
              {:next-call (some->> (pull-narrow-expression data)
                                   (narrow-call (:params ctx)))
               :remedy (str "The recursion depth is at most " (:max-pull-depth bounds)
                            ", and ... is not allowed. Use a smaller depth.")})

      :session-full
      (refuse ctx :session-full
              (str "The session holds " (:defs data) " names. The limit is "
                   (:limit data) ".")
              {:remedy (str "The session cannot hold more than " (:limit data)
                            " names. Use a name that the session already holds, "
                            "or use a new session.")})

      :timeout
      (refuse ctx :timeout message
              {:remedy (str "The request ran longer than " (:limit-ms data)
                            " ms. Ask for a smaller part of the index.")})

      (refuse ctx :evaluation-error
              (cut message (:max-error-chars bounds))
              {:remedy "Fix the expression and send it again."}))))

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
(defn- shape-violation
  "A message when `params` has the wrong shape, else nil."
  [params]
  (cond
    (not (map? params))
    "The request must be an object with the string field expression."

    (not (every? request-fields (keys params)))
    (str "Unknown field: "
         (str/join ", " (map name (remove request-fields (keys params))))
         ". The fields are expression and workspace_root.")

    (not (string? (:expression params)))
    "The field expression is required and must be a string."

    (and (contains? params :workspace_root)
         (not (string? (:workspace_root params))))
    "The field workspace_root must be a string."))

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
   An `ingest-fn` throw is not caught here: it aborts the refresh."
  [{:keys [root discovery by-path stat-fn read-fn ingest-fn]} path]
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
          (skip (:reason read)))))))

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-031
;; @spec MCP-OP-QUERY-034
;; @spec MCP-OP-QUERY-035
(defn refresh-index!
  "Bring the index of `entry` up to date with the files under `root`.

   Runs discovery and the stale plan, reads and ingests each file to ingest,
   then commits one `apply-ingest` result with one `reset!`, all under the
   ingest lock. A cold entry ingests on a pool of `:pool-size` workers, sets
   `:built-ms`, and reports no re-ingested files. A throw of `ingest-fn` or of
   `discover-fn` leaves the index as it was.

   Returns `{:index map :reingested [path ...]}`."
  [entry root bounds opts]
  (let [{:keys [ingest-fn discover-fn stat-fn read-fn now-ms]
         :or {ingest-fn ingest/file-result
              discover-fn discovery/discover
              now-ms #(System/currentTimeMillis)}} opts]
    (code-index/with-ingest-lock
      entry
      (fn []
        (let [cold? @(:cold? entry)
              started (when cold? (now-ms))
              found (discover-fn root bounds)
              index @(:index entry)
              {to-ingest :ingest to-remove :remove} (code-index/stale-plan index (:files found))
              env {:root root
                   :discovery found
                   :by-path (into {} (map (juxt :path identity)) (:files found))
                   :stat-fn stat-fn
                   :read-fn read-fn
                   :ingest-fn ingest-fn}
              outcomes (if cold?
                         (bounded-map (:pool-size bounds) #(ingest-path env %) to-ingest)
                         (mapv #(ingest-path env %) to-ingest))
              upserts (vec (keep :result outcomes))
              failures (vec (keep :skipped outcomes))
              next-index (code-index/apply-ingest
                          index
                          (cond-> {:upsert upserts
                                   :remove to-remove
                                   :skipped (into (vec (:skipped found)) failures)}
                            cold? (assoc :built-ms (- (now-ms) started))))]
          (reset! (:index entry) next-index)
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

(defn- evaluate
  "Evaluate and render under one time limit. Returns the evaluation result with
   `:rendered`."
  [sessions handle form index touched bounds]
  (session/run-with-timeout
   (:eval-timeout-ms bounds)
   (fn []
     (let [result (session/eval-in-session sessions handle form
                                           (functions/bindings index touched))
           value (:value result)
           skeleton (and (nil? (:def-name result)) (skeleton? value))
           limit (if skeleton (:max-skeleton-chars bounds) (:max-result-chars bounds))
           shown (if (:def-name result)
                   (render/render-def (:def-name result) value)
                   value)]
       (assoc result
              :skeleton? skeleton
              :limit limit
              :rendered (render/render-bounded shown limit))))))

(defn- skipped-out
  [skipped]
  (mapv (fn [entry]
          (into {} (map (fn [[k v]] [k (if (keyword? v) (name v) v)])) entry))
        skipped))

(defn- success
  [{:keys [root reset? index reingested touched evaluated]}]
  (let [text (:text (:rendered evaluated))]
    {:ok true
     :operation operation
     :value text
     :value_chars (count text)
     :session {:defs (:defs evaluated) :reset (boolean reset?)}
     :files (mapv (fn [path] {:path path :sha256 (get-in index [:files path :sha256])})
                  (sort touched))
     :index {:files (count (:files index))
             :vars (count (:vars index))
             :skipped (count (:skipped index))
             :built_ms (:built-ms index)}
     :reingested (vec reingested)
     :read_complete (boolean (:read-complete? index))
     :skipped (skipped-out (:skipped index))
     :workspace_root root}))

(defn- run-routed
  "Everything after the workspace is routed to `root`."
  [config ctx opts bounds expression]
  (try
    (let [form (fence/read-expression expression)
          sessions (sessions-of config bounds)
          {:keys [handle reset?]} (session/checkout! sessions (:session-id opts) (:root ctx))
          defs (set (map symbol (session/handle-defs handle)))]
      (when-let [denial (fence/admit form defs)]
        (throw (ex-info (str "Denied symbol: " (:symbol denial)) denial)))
      (when-let [problem (fence/check-def form defs (:max-defs sessions))]
        (throw (ex-info "Refused definition" problem)))
      (let [entry (entry-for! (or (:code-index/registry config) global-registry) (:root ctx))
            refreshed (try
                        (refresh-index! entry (:root ctx) bounds opts)
                        (catch Throwable e
                          (throw (ex-info (or (ex-message e) (str (class e)))
                                          {:error-type :index-unavailable} e))))
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
                    :evaluated evaluated}))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :index-unavailable (:error-type (ex-data e)))
        (index-unavailable ctx e)
        (typed-refusal ctx bounds e)))
    (catch Throwable e
      (refuse ctx :evaluation-error
              (cut (or (ex-message e) (str (class e))) (:max-error-chars bounds))
              {:remedy "Fix the expression and send it again."}))))

;; @spec MCP-OP-QUERY-003
;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-008
;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-084
(defn execute-request!
  "Run one query_clojure request and return the result map, with no
   `elapsed_ms`. `opts` may hold `:session-id`, `:ingest-fn`, `:discover-fn`,
   `:stat-fn`, `:read-fn` and `:now-ms`."
  ([config params] (execute-request! config params {}))
  ([config params opts]
   (let [normalized (normalize-params params)
         ctx {:params (if (map? normalized) normalized {})}
         bounds (bounds-of config)]
     (if-let [violation (shape-violation normalized)]
       (refuse ctx :invalid-request violation
               {:remedy (str violation " Send expression, and optionally workspace_root.")})
       (let [expression (:expression normalized)]
         (if (> (count expression) (:max-expression-chars bounds))
           (refuse ctx :expression-too-large
                   (str "The expression has " (count expression) " characters. The limit is "
                        (:max-expression-chars bounds) ".")
                   {:remedy (str "Send an expression of at most "
                                 (:max-expression-chars bounds) " characters.")})
           (let [router (or (:workspace-router config) (workspace/router config))
                 routed (workspace/resolve-request router normalized)
                 asked (:workspace_root normalized)]
             (if (or (not (:ok routed))
                     (and asked (not= asked (:workspace-root routed))))
               (refuse ctx :invalid-workspace-root
                       "workspace_root must be an existing canonical absolute directory."
                       {:remedy (str "Send workspace_root as the canonical absolute path "
                                     "of an existing directory, or omit it to use the "
                                     "server project root.")})
               (run-routed config
                           (assoc ctx :root (:workspace-root routed))
                           opts bounds expression)))))))))

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
         (count (:files result)) " file(s) · "
         (count (:reingested result)) " re-ingested · "
         (mcp-operation/format-elapsed-ms (:elapsed_ms result)))
    (str "query_clojure refused · " (:error_type result) " · "
         (mcp-operation/format-elapsed-ms (:elapsed_ms result))
         "\n" (:error result) "\nnothing was written")))

;; @spec MCP-OP-QUERY-006
(defn handle-query-clojure
  "clojure-mcp callback handler retained as a Var for hot reload."
  [exchange params callback]
  (mcp-operation/invoke!
   {:execute (fn []
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
