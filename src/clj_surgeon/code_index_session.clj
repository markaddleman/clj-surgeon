(ns clj-surgeon.code-index-session
  "Session contexts of query_clojure.

   A sessions object holds bounded SCI contexts keyed by [session-id
   workspace-root]. Eviction is by idle time and by least recent use.
   `eval-in-session` runs the fence admission and the def checks, then
   evaluates in a context that has no host classes and only the bound
   functions in `user`. `run-with-timeout` bounds the work on a worker thread.
   Nothing here touches the file system."
  (:require
   [clj-surgeon.code-index :as index]
   [clj-surgeon.code-index-fence :as fence]
   [sci.core :as sci]))

(def ^:private typed-error-types
  "Error types of bound functions that pass through unwrapped."
  #{:unknown-namespace :unknown-var :pull-too-deep :invalid-expression})

(def ^:private dropped-memory-factor 32)

;; @spec MCP-OP-QUERY-067
(defn sessions
  "Make a sessions object. `config` may hold `:max-contexts`, `:idle-ms`,
   `:max-defs` and `:clock` (a function of no arguments that gives millis)."
  [config]
  (let [bounds (merge (select-keys index/default-bounds [:max-contexts :idle-ms :max-defs])
                      (select-keys config [:max-contexts :idle-ms :max-defs]))]
    (assoc bounds
           :clock (or (:clock config) #(System/currentTimeMillis))
           :state (atom {:contexts {} :tick 0 :dropped []}))))

(defn- new-context
  "A fresh SCI context: no host classes, an empty `user` namespace."
  []
  (sci/init {:classes {} :namespaces {'user {}}}))

(defn- new-handle
  [session-id workspace-root]
  {:session-id session-id
   :workspace-root workspace-root
   :ctx (new-context)
   :defs (atom #{})})

(defn- remember-dropped
  "Add `ks` to the dropped-key memory, oldest out past the cap."
  [dropped ks max-contexts]
  (let [cap (* dropped-memory-factor max-contexts)
        all (into (vec dropped) ks)]
    (if (> (count all) cap)
      (subvec all (- (count all) cap))
      all)))

(defn- drop-idle
  [state now idle-ms max-contexts]
  (let [idle (->> (:contexts state)
                  (filter (fn [[_ e]] (>= (- now (:last-use e)) idle-ms)))
                  (map key)
                  sort)]
    (-> state
        (update :contexts #(apply dissoc % idle))
        (update :dropped remember-dropped idle max-contexts))))

(defn- evict-lru
  "Drop least recently used contexts until the count fits `max-contexts`."
  [state max-contexts]
  (loop [st state]
    (if (> (count (:contexts st)) max-contexts)
      (let [[k _] (apply min-key (comp :tick val) (:contexts st))]
        (recur (-> st
                   (update :contexts dissoc k)
                   (update :dropped remember-dropped [k] max-contexts))))
      st)))

(defn- checkout-step
  "Pure step of `checkout!`. Returns [state' handle reset?]."
  [state key' {:keys [max-contexts idle-ms]} now]
  (let [st (drop-idle state now idle-ms max-contexts)
        tick (inc (:tick st))
        known (get-in st [:contexts key'])]
    (if known
      [(-> st
           (assoc :tick tick)
           (update-in [:contexts key'] assoc :last-use now :tick tick))
       (:handle known)
       false]
      (let [reset? (boolean (some #{key'} (:dropped st)))
            handle (new-handle (first key') (second key'))
            st' (-> st
                    (assoc :tick tick)
                    (update :dropped (fn [d] (vec (remove #{key'} d))))
                    (assoc-in [:contexts key'] {:handle handle :last-use now :tick tick})
                    (evict-lru max-contexts))]
        [st' handle reset?]))))

;; @spec MCP-OP-QUERY-067
;; @spec MCP-OP-QUERY-070
;; @spec MCP-OP-QUERY-071
(defn checkout!
  "Find or create the context for [session-id workspace-root]. Returns
   `{:handle h :reset? bool}`. A nil `session-id` gives a fresh context that is
   not stored."
  [sessions session-id workspace-root]
  (if (nil? session-id)
    {:handle (new-handle nil workspace-root) :reset? false}
    (locking (:state sessions)
      (let [now ((:clock sessions))
            [state' handle reset?] (checkout-step @(:state sessions)
                                                  [session-id workspace-root]
                                                  sessions now)]
        (reset! (:state sessions) state')
        {:handle handle :reset? reset?}))))

(defn context-count
  "The number of stored contexts."
  [sessions]
  (count (:contexts @(:state sessions))))

(defn session-keys
  "The stored keys, sorted by `pr-str`."
  [sessions]
  (vec (sort-by pr-str (keys (:contexts @(:state sessions))))))

(defn handle-defs
  "Sorted names that the context of `handle` holds."
  [handle]
  (vec (sort (map str @(:defs handle)))))

(defn- final-def-name
  "The name when the final form of `form` is a `def` or `defn`, else nil."
  [form]
  (when (seq? form)
    (let [head (first form)]
      (cond
        (contains? '#{def defn} head) (when (symbol? (second form)) (str (second form)))
        (= 'do head) (final-def-name (last form))
        :else nil))))

(defn- unwrap-typed
  "The first ex-info in the cause chain with a typed `:error-type`, or nil."
  [^Throwable t]
  (loop [e t n 0]
    (cond
      (nil? e) nil
      (> n 20) nil
      (and (instance? clojure.lang.ExceptionInfo e)
           (contains? typed-error-types (:error-type (ex-data e)))) e
      :else (recur (ex-cause e) (inc n)))))

(defn- evaluation-error
  "Throw the typed error of a bound function, else an `:evaluation-error`."
  [^Throwable t]
  (if-let [typed (unwrap-typed t)]
    (throw typed)
    (throw (ex-info (or (ex-message t) (str (class t)))
                    {:error-type :evaluation-error
                     :message (or (ex-message t) (str (class t)))}
                    t))))

(defn- eval-form
  "Evaluate `form` in the context of `handle` with `bindings` in `user`.
   Returns the value; a final `def` gives the bound value, not the var."
  [handle form bindings]
  (let [ctx (sci/merge-opts (:ctx handle) {:namespaces {'user bindings}})
        v (sci/binding [sci/out (java.io.StringWriter.)
                        sci/err (java.io.StringWriter.)]
            (sci/eval-form ctx form))]
    (if (and (final-def-name form) (instance? clojure.lang.IDeref v))
      (deref v)
      v)))

;; @spec MCP-OP-QUERY-068
;; @spec MCP-OP-QUERY-069
;; @spec MCP-OP-QUERY-073
;; @spec MCP-OP-QUERY-078
;; @spec MCP-OP-QUERY-079
;; @spec MCP-OP-QUERY-080
;; @spec MCP-OP-QUERY-083
(defn eval-in-session
  "Admit, check the defs of, and evaluate `form` in the context of `handle`.
   `bindings` is the map from `code-index-functions/bindings`. Returns
   `{:value v :def-name s-or-nil :defs [..]}`. Throws `:denied-symbol`,
   `:session-full`, a typed error of a bound function, or `:evaluation-error`."
  [sessions handle form bindings]
  (let [defs @(:defs handle)]
    (when-let [denial (fence/admit form defs)]
      (throw (ex-info (str "Denied symbol: " (:symbol denial)) denial)))
    (when-let [problem (fence/check-def form defs (:max-defs sessions))]
      (throw (ex-info (str "Refused: " (name (:error-type problem))) problem)))
    (let [value (try
                  (eval-form handle form bindings)
                  (catch Throwable t (evaluation-error t)))]
      (swap! (:defs handle) into (fence/def-names form))
      {:value value
       :def-name (final-def-name form)
       :defs (handle-defs handle)})))

;; @spec MCP-OP-QUERY-082
(defn run-with-timeout
  "Run `f` on a daemon worker thread and wait `ms` milliseconds. Returns the
   value of `f`, or rethrows what it threw. At the limit it interrupts the
   worker and throws `{:error-type :timeout :limit-ms ms}`."
  [ms f]
  (let [result (promise)
        worker (doto (Thread. ^Runnable
                      (bound-fn []
                        (deliver result
                                 (try [:ok (f)]
                                      (catch Throwable t [:err t])))))
                 (.setDaemon true)
                 (.start))
        r (deref result ms ::timeout)]
    (cond
      (= ::timeout r)
      (do (.interrupt worker)
          (throw (ex-info (str "Evaluation exceeded " ms " ms")
                          {:error-type :timeout :limit-ms ms})))
      (= :ok (first r)) (second r)
      :else (throw (second r)))))
