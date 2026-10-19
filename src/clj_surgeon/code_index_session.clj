(ns clj-surgeon.code-index-session
  "Session contexts of query_clojure.

   A sessions object holds bounded SCI contexts keyed by [session-id
   workspace-root]. Eviction is by idle time and by least recent use.
   `eval-in-session` runs the fence admission and the def checks, then
   evaluates in a context that has no host classes and only the bound
   functions in `user`. `run-with-timeout` bounds the work on a worker thread
   with a deadline that the SCI context, the interruptible core and the loops
   of the bound functions check (`check-deadline!`). A request is one
   transaction on its context: it runs under the lock of the handle, and a
   throw, a timeout or a refused bound restores the context as it was.
   Nothing here touches the file system."
  (:require
   [clj-surgeon.code-index :as index]
   [clj-surgeon.code-index-fence :as fence]
   [sci.core :as sci]
   [sci.interrupt :as interrupt])
  (:import
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantLock)))

(def ^:private typed-error-types
  "Error types of bound functions that pass through unwrapped."
  #{:unknown-namespace :unknown-var :pull-too-deep :invalid-expression :timeout
    :result-too-large :busy})

(def ^:private dropped-memory-factor 32)

(def ^:private default-max-def-items
  "Default item bound of MCP-OP-QUERY-117 when the bounds map holds none."
  100000)

;; ---------------------------------------------------------------------------
;; Deadline

(def ^:dynamic *deadline*
  "nil, or `{:at nanos :limit-ms ms}` for the evaluation on this thread."
  nil)

;; @spec MCP-OP-QUERY-105
;; @spec MCP-OP-QUERY-106
;; @spec MCP-OP-QUERY-107
(defn check-deadline!
  "Throw the typed timeout when the deadline of this thread has passed or the
   thread is interrupted. The signal comes from `sci.interrupt/interrupt!`, so
   evaluated code cannot catch it. Does nothing when no deadline is bound.
   Bound functions and the renderer call it in every loop whose length depends
   on the data."
  []
  (when-let [{:keys [at limit-ms]} *deadline*]
    (when (or (not (neg? (- (System/nanoTime) at)))
              (.isInterrupted (Thread/currentThread)))
      (interrupt/interrupt! (str "Evaluation exceeded " limit-ms " ms")
                            {:error-type :timeout :limit-ms limit-ms}))))

;; @spec MCP-OP-QUERY-067
(defn sessions
  "Make a sessions object. `config` may hold `:max-contexts`, `:idle-ms`,
   `:max-defs`, `:max-def-items` and `:clock` (a function of no arguments that
   gives millis)."
  [config]
  (let [bounds (merge {:max-def-items default-max-def-items}
                      (select-keys index/default-bounds [:max-contexts :idle-ms :max-defs
                                                         :max-def-items])
                      (select-keys config [:max-contexts :idle-ms :max-defs :max-def-items]))]
    (assoc bounds
           :clock (or (:clock config) #(System/currentTimeMillis))
           :state (atom {:contexts {} :tick 0 :dropped []}))))

;; @spec MCP-OP-QUERY-123
(defn- new-context
  "A fresh SCI context: no host classes, an empty `user` namespace, the
   interruptible core, the denied symbols of MCP-OP-QUERY-077 as `:deny`, and
   the deadline as its interrupt hook."
  []
  (sci/init {:classes {}
             :namespaces {'user {} 'clojure.core interrupt/clojure-core}
             :deny (vec fence/denied-symbols)
             :interrupt-fn check-deadline!}))

(defn- new-handle
  [session-id workspace-root]
  {:session-id session-id
   :workspace-root workspace-root
   :ctx (new-context)
   :lock (ReentrantLock.)})

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

(defn- user-vars
  "The vars that the context holds in `user`: its defs. The bound functions are
   plain functions, not vars."
  [ctx]
  (into {}
        (filter (fn [[_ v]] (instance? sci.lang.Var v)))
        (get-in @(:env ctx) [:namespaces 'user])))

;; @spec MCP-OP-QUERY-072
;; @spec MCP-OP-QUERY-073
(defn handle-defs
  "Sorted names that the context of `handle` really holds."
  [handle]
  (vec (sort (map str (keys (user-vars (:ctx handle)))))))

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

(defn- stack-overflow?
  "True when `t` or a cause of it is a `StackOverflowError`."
  [^Throwable t]
  (loop [e t n 0]
    (cond
      (nil? e) false
      (> n 20) false
      (instance? StackOverflowError e) true
      :else (recur (ex-cause e) (inc n)))))

;; @spec MCP-OP-QUERY-083
;; @spec MCP-OP-QUERY-125
(defn- evaluation-error
  "Throw the typed error of a bound function, `:expression-too-deep` for a
   `StackOverflowError`, else an `:evaluation-error`."
  [^Throwable t]
  (cond
    (unwrap-typed t) (throw (unwrap-typed t))
    (stack-overflow? t) (throw (ex-info "Expression too deep: the evaluation overflowed the stack"
                                        (fence/too-deep-data fence/default-max-depth)
                                        t))
    :else (throw (ex-info (or (ex-message t) (str (class t)))
                          {:error-type :evaluation-error
                           :message (or (ex-message t) (str (class t)))}
                          t))))

(defn- do-form?
  [form]
  (and (seq? form)
       (let [head (first form)]
         (and (symbol? head)
              (= "do" (name head))
              (contains? #{nil "clojure.core"} (namespace head))))))

;; @spec MCP-OP-QUERY-132
(defn sub-forms
  "The forms that a request evaluates one at a time: a top-level `do`, and each
   `do` that is a direct sub-form of it, are split into their sub-forms. Any
   other form is its own single sub-form. `(do)` gives one `nil` sub-form."
  [form]
  (if (do-form? form)
    (let [subs (into [] (mapcat sub-forms) (rest form))]
      (if (seq subs) subs [nil]))
    [form]))

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

;; ---------------------------------------------------------------------------
;; Transaction on a context

(defn- snapshot
  "The state of `ctx` that a request can change: the environment value and the
   root of each var."
  [ctx]
  {:env @(:env ctx)
   :roots (into {} (map (fn [[_ v]] [v (.getRawRoot ^sci.lang.Var v)])) (user-vars ctx))})

(defn- restore!
  "Put `ctx` back to `snap`."
  [ctx {:keys [env roots]}]
  (reset! (:env ctx) env)
  (doseq [[^sci.lang.Var v root] roots]
    (.bindRoot v root)))

;; @spec MCP-OP-QUERY-117
(defn- count-items
  "The number of items in `roots`, counted through nested collections. A map
   entry counts as its key and its value. A lazy sequence is realized one item
   past the count at most. Stops as soon as the count passes `limit`; the result
   is then greater than `limit`."
  [roots limit]
  (loop [stack [(seq roots)] n 0]
    (cond
      (> n limit) n
      (empty? stack) n
      :else
      (let [top (peek stack)]
        (if (nil? top)
          (recur (pop stack) n)
          (let [x (first top)
                stack (conj (pop stack) (next top))
                entry? (instance? java.util.Map$Entry x)]
            (when (zero? (bit-and n 1023)) (check-deadline!))
            (cond
              entry? (recur (conj stack (seq [(key x) (val x)])) n)
              (and (coll? x) (not (string? x))) (recur (conj stack (seq x)) (inc n))
              :else (recur stack (inc n)))))))))

(defn- check-bounds!
  "Throw `:session-full` when the context holds more names than `max-defs` or
   values of more than `max-def-items` items."
  [sessions handle]
  (let [vars (user-vars (:ctx handle))
        limit (:max-def-items sessions)]
    (when (> (count vars) (:max-defs sessions))
      (throw (ex-info "Refused: session-full"
                      {:error-type :session-full
                       :defs (count vars)
                       :limit (:max-defs sessions)})))
    (when (> (count-items (map deref (vals vars)) limit) limit)
      (throw (ex-info "Refused: session-full"
                      {:error-type :session-full
                       :reason :items
                       :limit limit
                       :remedy (str "The values that the session holds are longer than " limit
                                    " items. Do not def a large value; compute it again when"
                                    " you need it.")})))))

;; @spec MCP-OP-QUERY-121
;; @spec MCP-OP-QUERY-122
(defn- macro-marker
  "The marker (`:macro` or `:sci/macro`) that makes the var `v` a macro for SCI
   0.15.58, or nil. SCI reads `:macro` on the var, and `:sci/macro` or `:macro`
   on the value at the head of a call. Any truthy value counts."
  [^sci.lang.Var v]
  (let [vm (meta v)
        root (.getRawRoot v)
        rm (when (instance? clojure.lang.IMeta root) (meta root))]
    (cond
      (:sci/macro vm) :sci/macro
      (:macro vm) :macro
      (:sci/macro rm) :sci/macro
      (:macro rm) :macro)))

(defn- check-no-macro!
  "Throw `:denied-symbol` naming the var and the marker when a var of `user` is a
   macro. The check covers each var, so it also finds a var that was changed.
   `bound` is the list of bound names for the refusal."
  [ctx bound]
  (doseq [[sym v] (user-vars ctx)]
    (when-let [marker (macro-marker v)]
      (throw (ex-info (str "Denied symbol: " marker)
                      {:error-type :denied-symbol
                       :symbol (str marker)
                       :var (str sym)
                       :bound bound
                       :remedy (str "A session cannot define a macro. " sym " carries "
                                    marker ". Define a function instead.")})))))

(defn- with-lock
  "Run `f` holding the lock of `handle`. Waits for the lock no longer than the
   deadline."
  [handle f]
  (let [^ReentrantLock lock (:lock handle)
        {:keys [at limit-ms]} *deadline*]
    (if at
      (when-not (.tryLock lock (max 0 (- at (System/nanoTime))) TimeUnit/NANOSECONDS)
        (throw (ex-info (str "Evaluation exceeded " limit-ms " ms")
                        {:error-type :timeout :limit-ms limit-ms})))
      (.lock lock))
    (try (f) (finally (.unlock lock)))))

;; @spec MCP-OP-QUERY-068
;; @spec MCP-OP-QUERY-069
;; @spec MCP-OP-QUERY-073
;; @spec MCP-OP-QUERY-078
;; @spec MCP-OP-QUERY-079
;; @spec MCP-OP-QUERY-080
;; @spec MCP-OP-QUERY-083
;; @spec MCP-OP-QUERY-105
;; @spec MCP-OP-QUERY-113
;; @spec MCP-OP-QUERY-114
;; @spec MCP-OP-QUERY-115
;; @spec MCP-OP-QUERY-116
;; @spec MCP-OP-QUERY-117
;; @spec MCP-OP-QUERY-121
;; @spec MCP-OP-QUERY-122
;; @spec MCP-OP-QUERY-128
(defn eval-in-session
  "Admit, check the defs of, and evaluate `form` in the context of `handle`.
   `bindings` is the map from `code-index-functions/bindings`. Returns
   `{:value v :def-name s-or-nil :defs [..]}`. Throws `:denied-symbol`,
   `:invalid-expression`, `:session-full`, `:timeout`, a typed error of a bound
   function, or `:evaluation-error`.
   The request runs under the lock of the handle, so requests of one session
   run one after another. It is one transaction: when it throws, passes the
   deadline or breaks a bound, the context and its names are as they were."
  [sessions handle form bindings]
  (with-lock
    handle
    (fn []
      (let [ctx (:ctx handle)
            held (set (keys (user-vars ctx)))]
        (when-let [denial (fence/admit form held)]
          (throw (ex-info (str "Denied symbol: " (:symbol denial)) denial)))
        (when-let [problem (fence/check-def form held (:max-defs sessions))]
          (throw (ex-info (if (= :def-shape (:reason problem))
                            (str "Invalid expression: " (:message problem))
                            (str "Refused: " (name (:error-type problem))))
                          problem)))
        (let [snap (snapshot ctx)
              bound (mapv str (keys bindings))]
          (try
            (let [value (reduce (fn [_ sub]
                                  (let [outcome (try
                                                  [:ok (eval-form handle sub bindings)]
                                                  (catch Throwable t [:err t]))]
                                    (check-no-macro! ctx bound)
                                    (when (= :err (first outcome))
                                      (evaluation-error (second outcome)))
                                    (second outcome)))
                                nil
                                (sub-forms form))]
              (check-deadline!)
              (check-bounds! sessions handle)
              {:value value
               :def-name (final-def-name form)
               :defs (handle-defs handle)})
            (catch Throwable t
              (restore! ctx snap)
              (throw t))))))))

;; ---------------------------------------------------------------------------
;; Time bound

(def ^:private zombies
  "Workers that outlived their timeout and have not ended."
  (atom #{}))

(defn- live-zombies
  "Prune the ended workers and return the live ones."
  []
  (swap! zombies (fn [ws] (into #{} (filter #(.isAlive ^Thread %)) ws))))

(def ^:private default-run-opts
  {:grace-ms 1000 :worker-cap 2})

;; @spec MCP-OP-QUERY-082
;; @spec MCP-OP-QUERY-105
;; @spec MCP-OP-QUERY-106
;; @spec MCP-OP-QUERY-109
(defn run-with-timeout
  "Run `f` on a daemon worker thread with the deadline bound to `ms`
   milliseconds. Returns the value of `f`, or rethrows what it threw. At the
   limit it interrupts the worker, waits up to the stop grace (`:grace-ms`,
   default 1000) for it to end, and throws `{:error-type :timeout :limit-ms
   ms}`. A worker that does not end counts as a zombie: while `:worker-cap`
   (default 2) zombies are alive, a new call throws `{:error-type :busy}`."
  ([ms f] (run-with-timeout ms f nil))
  ([ms f opts]
   (let [{:keys [grace-ms worker-cap]} (merge default-run-opts opts)]
     (when (>= (count (live-zombies)) worker-cap)
       (throw (ex-info "Workers of earlier timeouts are still running"
                       {:error-type :busy
                        :workers (count (live-zombies))
                        :remedy "Wait a few seconds and send the request again."})))
     (let [result (promise)
           ended (promise)
           deadline {:at (+ (System/nanoTime) (* ms 1000000)) :limit-ms ms}
           worker (doto (Thread. ^Runnable
                         (bound-fn []
                           (binding [*deadline* deadline]
                             (try
                               (deliver result
                                        (try [:ok (f)]
                                             (catch Throwable t [:err t])))
                               (finally (deliver ended true))))))
                    (.setDaemon true)
                    (.start))
           r (deref result ms ::timeout)]
       (cond
         (= ::timeout r)
         (do (.interrupt worker)
             (when (= ::timeout (deref ended grace-ms ::timeout))
               (swap! zombies conj worker))
             (throw (ex-info (str "Evaluation exceeded " ms " ms")
                             {:error-type :timeout :limit-ms ms})))
         (= :ok (first r)) (second r)
         :else (throw (second r)))))))
