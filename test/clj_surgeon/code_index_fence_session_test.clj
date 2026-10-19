(ns clj-surgeon.code-index-fence-session-test
  "Pure witnesses for the evaluation fence and the session contexts of
   query_clojure (MCP-OP-QUERY-067 to 083).

   The fence tests use literal forms. The session tests use an injected clock
   and the literal index fixtures. No file, no socket, no child process."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index :as index]
   [clj-surgeon.code-index-fence :as fence]
   [clj-surgeon.code-index-fixtures :as fx]
   [clj-surgeon.code-index-functions :as fns]
   [clj-surgeon.code-index-session :as session]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [sci.core :as sci]))

(def bound-names ["search" "namespaces" "skeleton" "form" "refs" "pull" "help"])

(defn- thrown-data
  "Run f and return the ex-data of the ex-info it throws, or :no-throw."
  [f]
  (try
    (f)
    :no-throw
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn- fixture-bindings
  []
  (let [idx (index/apply-ingest index/empty-index {:upsert fx/all-files})]
    (fns/bindings idx (atom #{}))))

(defn- new-sessions
  "Sessions with an injected clock. Returns [sessions clock-atom]."
  [config]
  (let [t (atom 0)]
    [(session/sessions (merge {:clock #(deref t)} config)) t]))

(defn- handle-of
  [s id root]
  (:handle (session/checkout! s id root)))

(defn- ev
  "Read text, evaluate it in the handle with the fixture bindings."
  [s h text]
  (session/eval-in-session s h (fence/read-expression text) (fixture-bindings)))

;; ---------------------------------------------------------------------------
;; read-expression (075)

;; @spec MCP-OP-QUERY-075
(deftest read-expression-returns-the-one-form-as-data
  (is (= '(+ 1 2) (fence/read-expression "(+ 1 2)")))
  (is (= '(skeleton 'a.b) (fence/read-expression "  (skeleton 'a.b)  "))))

;; @spec MCP-OP-QUERY-075
(deftest read-expression-refuses-text-with-no-form
  (doseq [text ["" "   \n " "; only a comment" nil]]
    (let [data (thrown-data #(fence/read-expression text))]
      (is (= :invalid-expression (:error-type data)) (pr-str text))
      (is (= :empty (:reason data)) (pr-str text)))))

;; @spec MCP-OP-QUERY-075
(deftest read-expression-refuses-two-forms
  (let [data (thrown-data #(fence/read-expression "(+ 1 2) (+ 3 4)"))]
    (is (= :invalid-expression (:error-type data)))
    (is (= :multiple (:reason data)))))

;; @spec MCP-OP-QUERY-075
(deftest read-expression-refuses-text-the-reader-cannot-read
  (doseq [text ["(+ 1" "#<x>"]]
    (let [data (thrown-data #(fence/read-expression text))]
      (is (= :invalid-expression (:error-type data)) text)
      (is (= :unreadable (:reason data)) text)
      (is (and (string? (:message data)) (seq (:message data))) text))))

;; @spec MCP-OP-QUERY-075
;; @spec MCP-OP-QUERY-078
(deftest read-expression-refuses-reader-eval-without-running-it
  (let [data (thrown-data #(fence/read-expression "#=(+ 1 2)"))]
    (is (= :invalid-expression (:error-type data)))
    (is (= :unreadable (:reason data)))))

;; ---------------------------------------------------------------------------
;; admission (076, 077, 078)

(def repl-examples
  "REPL examples of the LLD. Each must be admitted."
  ["(namespaces)"
   "(count (namespaces))"
   "(skeleton 'a.b)"
   "(map :name (skeleton 'a.b))"
   "(sort-by :start (skeleton 'a.b))"
   "(filter :private? (skeleton 'a.b))"
   "(form 'a.b/f)"
   "(form '[a.b/f a.b/g])"
   "(refs 'a.b/f)"
   "(refs 'a.b/f {:include-tests false})"
   "(pull '[:var/qname {:var/calls 2}] [:var/qname \"a.b/f\"])"
   "(help)"
   "(help 'pull)"
   "(frequencies (map :kind (namespaces)))"
   "(group-by :ns (refs 'a.b/f))"
   "(let [s (skeleton 'a.b)] (count s))"
   "(map (fn [v] (str (:name v))) (skeleton 'a.b))"
   "(def xs (skeleton 'a.b))"
   "(defn n [] (count (namespaces)))"
   "(count '[slurp])"])

;; @spec MCP-OP-QUERY-076
(deftest admission-allows-every-repl-example-of-the-design
  (doseq [text repl-examples]
    (is (nil? (fence/admit (fence/read-expression text) #{})) text)))

;; @spec MCP-OP-QUERY-076
(deftest admission-allows-a-name-the-session-has-defined
  (is (nil? (fence/admit '(count xs) '#{xs})))
  (is (= {:error-type :denied-symbol :symbol "xs" :bound bound-names}
         (fence/admit '(count xs) #{}))))

(def denied-table
  "[expression-text expected-denied-symbol]"
  [["(require 'a.b)" "require"]
   ["(in-ns 'x)" "in-ns"]
   ["(ns x)" "ns"]
   ["(eval 1)" "eval"]
   ["(load-string \"1\")" "load-string"]
   ["(load-file \"x.clj\")" "load-file"]
   ["(slurp \"/etc/passwd\")" "slurp"]
   ["(spit \"/tmp/x\" \"y\")" "spit"]
   ["(future 1)" "future"]
   ["(agent 1)" "agent"]
   ["(atom 1)" "atom"]
   ["(alter-var-root #'x inc)" "alter-var-root"]
   ["(set! x 1)" "set!"]
   ["(new String \"x\")" "new"]
   ["(.length \"abc\")" ".length"]
   ["(System/exit 0)" "System/exit"]
   ["(Math/abs 1)" "Math/abs"]
   ["*out*" "*out*"]
   ["(str *ns*)" "*ns*"]
   ["(foo 1)" "foo"]
   ["(fn [x] (slurp x))" "slurp"]
   ["(def v (slurp \"x\"))" "slurp"]
   ["(map (fn [x] (System/exit x)) [1])" "System/exit"]])

;; @spec MCP-OP-QUERY-076
;; @spec MCP-OP-QUERY-077
(deftest admission-denies-each-symbol-of-the-denied-table
  (doseq [[text sym] denied-table]
    (is (= {:error-type :denied-symbol :symbol sym :bound bound-names}
           (fence/admit (fence/read-expression text) #{}))
        text)))

;; @spec MCP-OP-QUERY-077
(deftest admission-denies-each-member-of-denied-symbols
  (is (seq fence/denied-symbols))
  (doseq [s fence/denied-symbols]
    (let [denial (fence/admit (list s) #{})]
      (is (= :denied-symbol (:error-type denial)) (str s))
      (is (= (str s) (:symbol denial)) (str s))
      (is (= bound-names (:bound denial)) (str s)))))

;; @spec MCP-OP-QUERY-076
(deftest admission-reports-the-first-denial-left-to-right
  (is (= "foo" (:symbol (fence/admit '(do (foo 1) (slurp "x")) #{})))))

;; Probe class: a class outside every :classes allowlist whose static
;; initializer sets a system property. Loading it with initialization is the
;; observable effect of the SCI string-hint escape.
(defn- define-probe-class!
  "Define a class named `class-name` whose static initializer sets the system
   property `prop` to \"initialized\". Defining does not initialize it."
  [^String class-name ^String prop]
  (let [cw (clojure.asm.ClassWriter. clojure.asm.ClassWriter/COMPUTE_MAXS)
        internal (.replace class-name \. \/)]
    (.visit cw clojure.asm.Opcodes/V1_8 clojure.asm.Opcodes/ACC_PUBLIC internal nil "java/lang/Object" nil)
    (let [mv (.visitMethod cw clojure.asm.Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
      (.visitCode mv)
      (.visitLdcInsn mv prop)
      (.visitLdcInsn mv "initialized")
      (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESTATIC "java/lang/System" "setProperty"
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
      (.visitInsn mv clojure.asm.Opcodes/POP)
      (.visitInsn mv clojure.asm.Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (.visitEnd cw)
    (.defineClass (clojure.lang.DynamicClassLoader.) class-name (.toByteArray cw) nil)))

;; @spec MCP-OP-QUERY-079
(deftest string-type-hint-cannot-load-a-class-outside-the-allowlist
  ;; SCI 0.13.53 fixed a sandbox escape: a string type hint on a symbol
  ;; argument of an instance method call loaded and statically initialized any
  ;; class, whatever :classes allowed. Admission already refuses interop
  ;; heads, so this test removes admission and checks that the context alone
  ;; still refuses to load the class.
  (let [id (str (random-uuid))
        class-name (str "clj_surgeon.probe.QueryEscape" (str/replace id "-" ""))
        prop (str "clj-surgeon.probe." id)
        text (str "(let [s \"abc\" v 1] (.indexOf s ^\"" class-name "\" v))")
        [s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (define-probe-class! class-name prop)
    (try
      (is (nil? (System/getProperty prop)) "the probe class starts uninitialized")
      (is (= :denied-symbol (:error-type (thrown-data #(ev s h text))))
          "admission refuses the interop head before evaluation")
      (is (nil? (System/getProperty prop)))
      (with-redefs [fence/admit (fn [_ _] nil)]
        (thrown-data #(ev s h text)))
      (is (nil? (System/getProperty prop))
          "a string hint must not load a class outside the :classes allowlist")
      (finally (System/clearProperty prop)))))

;; @spec MCP-OP-QUERY-078
(deftest a-denied-expression-changes-nothing-in-the-context
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (ev s h "(def kept 1)")
    (is (= :denied-symbol
           (:error-type (thrown-data #(ev s h "(do (def lost 2) (slurp \"/etc/passwd\"))")))))
    (is (= ["kept"] (session/handle-defs h)))))

;; ---------------------------------------------------------------------------
;; def rules (073, 080)

;; @spec MCP-OP-QUERY-080
(deftest def-names-lists-def-and-defn-names-in-walk-order
  (is (= '[a b] (fence/def-names '(do (def a 1) (defn b [] 2)))))
  (is (= [] (fence/def-names '(count '(def q 1))))))

;; @spec MCP-OP-QUERY-080
(deftest check-def-passes-a-simple-new-name
  (is (nil? (fence/check-def '(def a 1) #{} 64))))

;; @spec MCP-OP-QUERY-080
(deftest check-def-refuses-a-qualified-name
  (is (= {:error-type :denied-symbol :symbol "a/b" :bound bound-names}
         (fence/check-def '(def a/b 1) #{} 64))))

;; @spec MCP-OP-QUERY-080
(deftest check-def-refuses-a-bound-function-name
  (is (= {:error-type :denied-symbol :symbol "skeleton" :bound bound-names}
         (fence/check-def '(def skeleton 1) #{} 64))))

;; @spec MCP-OP-QUERY-080
(deftest check-def-refuses-a-pure-core-name
  (is (= {:error-type :denied-symbol :symbol "map" :bound bound-names}
         (fence/check-def '(def map 1) #{} 64))))

;; @spec MCP-OP-QUERY-073
(deftest check-def-refuses-a-def-over-the-limit
  (is (= {:error-type :session-full :defs 2 :limit 2}
         (fence/check-def '(def c 1) '#{a b} 2)))
  (testing "redefining a held name adds nothing"
    (is (nil? (fence/check-def '(def a 2) '#{a b} 2)))))

;; @spec MCP-OP-QUERY-080
(deftest a-refused-def-leaves-the-context-as-it-was
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (ev s h "(def ok 1)")
    (doseq [[text sym] [["(def a/b 1)" "a/b"]
                        ["(def skeleton 1)" "skeleton"]
                        ["(def map 1)" "map"]]]
      (let [data (thrown-data #(ev s h text))]
        (is (= :denied-symbol (:error-type data)) text)
        (is (= sym (:symbol data)) text)
        (is (= ["ok"] (session/handle-defs h)) text)))))

;; ---------------------------------------------------------------------------
;; sessions (067-072, 074)

;; @spec MCP-OP-QUERY-067
(deftest the-same-session-id-and-root-reuse-one-context
  (let [[s _] (new-sessions {})
        h1 (handle-of s "s1" "/r")]
    (ev s h1 "(def x 5)")
    (let [r (session/checkout! s "s1" "/r")]
      (is (false? (:reset? r)))
      (is (= {:value 5 :def-name nil :defs ["x"]} (ev s (:handle r) "x"))))
    (is (= 1 (session/context-count s)))
    (is (= [["s1" "/r"]] (session/session-keys s)))))

;; @spec MCP-OP-QUERY-067
(deftest the-same-session-id-with-another-root-is-another-context
  (let [[s _] (new-sessions {})
        h1 (handle-of s "s1" "/r1")
        h2 (handle-of s "s1" "/r2")]
    (ev s h1 "(def x 5)")
    (is (= 2 (session/context-count s)))
    (is (= [] (session/handle-defs h2)))
    (is (= [["s1" "/r1"] ["s1" "/r2"]] (session/session-keys s)))))

;; @spec MCP-OP-QUERY-067
(deftest a-request-with-no-session-id-gets-a-fresh-context-used-once
  (let [[s _] (new-sessions {})
        r1 (session/checkout! s nil "/r")
        r2 (session/checkout! s nil "/r")]
    (is (false? (:reset? r1)))
    (is (= 0 (session/context-count s)))
    (ev s (:handle r1) "(def x 5)")
    (is (= [] (session/handle-defs (:handle r2))))))

;; @spec MCP-OP-QUERY-068
;; @spec MCP-OP-QUERY-072
(deftest a-def-persists-to-a-later-call-of-the-same-session
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (is (= {:value 5 :def-name "x" :defs ["x"]} (ev s h "(def x 5)")))
    (is (= {:value 6 :def-name nil :defs ["x"]}
           (ev s (handle-of s "s1" "/r") "(inc x)")))
    (is (= ["x"] (session/handle-defs h)))))

;; @spec MCP-OP-QUERY-072
(deftest the-result-lists-defs-sorted-and-names-the-final-def
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (is (= {:value 1 :def-name nil :defs ["a"]} (ev s h "(do (def a 1) 1)")))
    (let [r (ev s h "(defn b [] 2)")]
      (is (= "b" (:def-name r)))
      (is (= ["a" "b"] (:defs r))))
    (is (= {:value 3 :def-name nil :defs ["a" "b"]} (ev s h "(+ a (b))")))))

;; @spec MCP-OP-QUERY-069
(deftest a-def-is-not-visible-to-another-session
  (let [[s _] (new-sessions {})
        h1 (handle-of s "s1" "/r")
        h2 (handle-of s "s2" "/r")]
    (ev s h1 "(def x 5)")
    (is (= {:error-type :denied-symbol :symbol "x" :bound bound-names}
           (select-keys (thrown-data #(ev s h2 "x")) [:error-type :symbol :bound])))
    (is (= [] (session/handle-defs h2)))))

;; @spec MCP-OP-QUERY-070
(deftest the-least-recently-used-context-goes-first
  (let [[s _] (new-sessions {:max-contexts 2})]
    (handle-of s "a" "/r")
    (handle-of s "b" "/r")
    (handle-of s "a" "/r")
    (handle-of s "c" "/r")
    (is (= 2 (session/context-count s)))
    (is (= [["a" "/r"] ["c" "/r"]] (session/session-keys s)))))

;; @spec MCP-OP-QUERY-070
;; @spec MCP-OP-QUERY-071
(deftest an-idle-context-is-dropped-at-the-idle-limit
  (let [[s t] (new-sessions {:max-contexts 5 :idle-ms 1000})
        h (handle-of s "a" "/r")]
    (ev s h "(def x 5)")
    (reset! t 999)
    (let [r (session/checkout! s "a" "/r")]
      (is (false? (:reset? r)))
      (is (= ["x"] (session/handle-defs (:handle r)))))
    (testing "idle is measured from the last use"
      (reset! t 1998)
      (is (false? (:reset? (session/checkout! s "a" "/r")))))
    (testing "an idle time equal to the limit drops the context"
      (reset! t 2998)
      (let [r (session/checkout! s "a" "/r")]
        (is (true? (:reset? r)))
        (is (= [] (session/handle-defs (:handle r))))))))

;; @spec MCP-OP-QUERY-070
(deftest another-checkout-drops-the-idle-contexts
  (let [[s t] (new-sessions {:max-contexts 5 :idle-ms 1000})]
    (handle-of s "a" "/r")
    (reset! t 1000)
    (handle-of s "b" "/r")
    (is (= [["b" "/r"]] (session/session-keys s)))
    (is (= 1 (session/context-count s)))))

;; @spec MCP-OP-QUERY-071
(deftest a-dropped-session-gets-a-fresh-context-and-the-reset-flag-once
  (let [[s _] (new-sessions {:max-contexts 1})
        h (handle-of s "a" "/r")]
    (ev s h "(def x 5)")
    (is (false? (:reset? (session/checkout! s "b" "/r"))))
    (let [r (session/checkout! s "a" "/r")]
      (is (true? (:reset? r)))
      (is (= [] (session/handle-defs (:handle r)))))
    (testing "the flag is given once"
      (is (false? (:reset? (session/checkout! s "a" "/r")))))))

;; @spec MCP-OP-QUERY-071
(deftest a-session-never-seen-before-has-no-reset-flag
  (let [[s _] (new-sessions {})]
    (is (false? (:reset? (session/checkout! s "fresh" "/r"))))))

;; @spec MCP-OP-QUERY-073
(deftest a-def-over-the-limit-is-refused-and-the-context-is-unchanged
  (let [[s _] (new-sessions {:max-defs 2})
        h (handle-of s "s1" "/r")]
    (ev s h "(def a 1)")
    (ev s h "(def b 2)")
    (let [data (thrown-data #(ev s h "(def c 3)"))]
      (is (= :session-full (:error-type data)))
      (is (= 2 (:defs data)))
      (is (= 2 (:limit data))))
    (is (= ["a" "b"] (session/handle-defs h)))
    (testing "redefining a held name is allowed"
      (is (= ["a" "b"] (:defs (ev s h "(def a 10)")))))))

;; @spec MCP-OP-QUERY-074
(deftest the-final-value-of-a-def-is-the-value-it-bound
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (is (= 5 (:value (ev s h "(def x 5)"))))
    (is (= "x" (:def-name (ev s h "(def x 5)"))))))

;; ---------------------------------------------------------------------------
;; time bound (082) and evaluation error (083)

;; @spec MCP-OP-QUERY-082
(deftest run-with-timeout-returns-the-value-of-a-fast-function
  (is (= 42 (session/run-with-timeout 5000 (fn [] 42)))))

;; @spec MCP-OP-QUERY-082
(deftest run-with-timeout-rethrows-what-the-function-throws
  (let [data (thrown-data #(session/run-with-timeout
                            5000 (fn [] (throw (ex-info "x" {:error-type :other :k 1})))))]
    (is (= :other (:error-type data)))
    (is (= 1 (:k data)))))

;; @spec MCP-OP-QUERY-082
(deftest an-endless-sequence-hits-the-time-bound
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        form (fence/read-expression "(count (range))")
        start (System/nanoTime)
        data (thrown-data #(session/run-with-timeout
                            200 (fn [] (session/eval-in-session s h form (fixture-bindings)))))
        elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
    (is (= {:error-type :timeout :limit-ms 200} (select-keys data [:error-type :limit-ms])))
    (is (< elapsed-ms 5000))))

;; @spec MCP-OP-QUERY-082
(deftest a-worker-that-ignores-the-interrupt-still-times-out
  ;; Changed in fix group "fence and session": the worker was an endless native
  ;; loop, which no code can stop (MCP-OP-QUERY-105 now requires that a worker
  ;; ends) and which stayed a zombie for the rest of the test run. It is a
  ;; native spin of 400 ms that ignores both the interrupt and the deadline.
  (let [data (thrown-data #(session/run-with-timeout
                            100 (fn [] (let [end (+ (System/nanoTime) 400000000)]
                                         (while (< (System/nanoTime) end))))
                            {:grace-ms 10}))]
    (is (= :timeout (:error-type data)))
    (is (= 100 (:limit-ms data)))
    (Thread/sleep 500)))

;; @spec MCP-OP-QUERY-083
(deftest a-throwing-expression-gives-an-evaluation-error-with-the-message
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        data (thrown-data #(ev s h "(/ 1 0)"))]
    (is (= :evaluation-error (:error-type data)))
    (is (= "Divide by zero" (:message data)))))

;; @spec MCP-OP-QUERY-083
(deftest a-typed-error-of-a-bound-function-is-not-wrapped
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        unknown-ns (thrown-data #(ev s h "(skeleton 'no.such)"))
        unknown-var (thrown-data #(ev s h "(form 'a.b/zzz)"))]
    (is (= :unknown-namespace (:error-type unknown-ns)))
    (is (= "no.such" (:name unknown-ns)))
    (is (= :unknown-var (:error-type unknown-var)))
    (is (= "a.b/zzz" (:qname unknown-var)))))

;; @spec MCP-OP-QUERY-076
;; @spec MCP-OP-QUERY-068
(deftest an-admitted-expression-runs-against-the-bound-functions
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (is (= ["f" "g"] (:value (ev s h "(mapv :name (skeleton 'a.b))"))))))

;; ---------------------------------------------------------------------------
;; Fix group "fence and session": issues #6, #7 and #9

(defn- real-var-names
  "The names of the vars that the SCI context of `h` really holds in `user`,
   read from the environment and not from the bookkeeping of the session."
  [h]
  (->> (get-in @(:env (:ctx h)) [:namespaces 'user])
       (filter (fn [[_ v]] (instance? sci.lang.Var v)))
       (map (comp str key))
       sort
       vec))

(defn- refusal-of
  "The ex-data of what `text` throws in a fresh session."
  [text]
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (thrown-data #(ev s h text))))

;; @spec MCP-OP-QUERY-102
(deftest malformed-let-star-and-loop-star-bindings-are-refused
  ;; Issue #6 (a). Before the fix the body of these forms was never walked and
  ;; (eval '(+ 1 2)) returned 3.
  (doseq [text ["(let* (a 1) (eval '(+ 1 2)))"
                "(let* [a] (eval '(+ 1 2)))"
                "(loop* (a 1) (eval '(+ 1 2)))"
                "(let* [a 1 b] (eval '(+ 1 2)))"
                "(let* (a 1) (str (:protectionDomain (bean (class skeleton)))))"]]
    (let [data (refusal-of text)]
      (is (= :invalid-expression (:error-type data)) text)
      (is (string? (:remedy data)) text))))

;; @spec MCP-OP-QUERY-102
(deftest admission-throws-for-a-form-it-cannot-walk
  (doseq [form ['(let* (a 1) a) '(loop* [a] a) '(fn* (eval 1)) '(fn* 1)
                '(fn* ([x] x) (eval 1))]]
    (is (= :invalid-expression (:error-type (thrown-data #(fence/admit form #{})))) (pr-str form)))
  (testing "well-formed forms are still admitted"
    (is (nil? (fence/admit '(fn* ([x] x) ([x y] y)) #{})))
    (is (nil? (fence/admit '(fn* named [x] x) #{})))
    (is (nil? (fence/admit '(let* [a 1 b a] b) #{})))))

;; @spec MCP-OP-QUERY-102
(deftest a-fn-arity-that-is-not-a-list-with-a-vector-is-refused
  (doseq [text ["(fn* (eval '(+ 1 2)))" "(fn* ([x] x) (eval '(+ 1 2)))"]]
    (is (= :invalid-expression (:error-type (refusal-of text))) text)))

;; @spec MCP-OP-QUERY-103
(deftest metadata-is-walked-as-code
  ;; Issue #6 (b). Before the fix these returned {:x 3}, atoms and so on.
  (doseq [[text sym] [["(meta ^{:x (eval '(+ 1 2))} [1])" "eval"]
                      ["(def ^{:x (atom 1)} y 1)" "atom"]
                      ["(let [^{:a (eval 1)} x 1] x)" "eval"]
                      ["(fn [^{:a (eval 1)} x] x)" "eval"]
                      ["(map (fn [x] x) ^{:a (load-string \"1\")} [1])" "load-string"]]]
    (let [data (refusal-of text)]
      (is (= :denied-symbol (:error-type data)) text)
      (is (= sym (:symbol data)) text))))

;; @spec MCP-OP-QUERY-103
(deftest harmless-metadata-is-still-admitted
  (is (nil? (fence/admit (fence/read-expression "(def ^:private z 1)") #{})))
  (is (nil? (fence/admit (fence/read-expression "(meta ^{:x (inc 1)} [1])") #{}))))

;; @spec MCP-OP-QUERY-104
(deftest a-macro-definition-is-refused-and-so-no-user-macro-exists
  ;; Issue #6 (c). Before the fix (m2) returned 3 and (m3) returned 42.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (doseq [text ["(defn ^:macro m2 [&form &env] '(eval '(+ 1 2)))"
                  "(def ^:macro m3 (fn [&form &env] '(load-string \"(+ 40 2)\")))"
                  "(def ^{:macro true} m4 (fn [&form &env] 1))"
                  "(defn m5 {:macro true} [&form &env] 1)"
                  "(defn m6 \"doc\" {:macro true} [&form &env] 1)"
                  "(def ^{(keyword \"macro\") true} m7 (fn [&form &env] 1))"
                  "(do (def ^:macro m8 (fn [&form &env] 1)) 1)"]]
      (let [data (thrown-data #(ev s h text))]
        (is (= :denied-symbol (:error-type data)) text)
        (is (= ":macro" (:symbol data)) text)))
    (is (= [] (real-var-names h)))
    (is (= :denied-symbol (:error-type (thrown-data #(ev s h "(m2)")))))
    (is (= :denied-symbol (:error-type (thrown-data #(ev s h "(m3)")))))))

;; @spec MCP-OP-QUERY-104
(deftest check-def-refuses-macro-metadata-and-passes-plain-metadata
  (is (= ":macro" (:symbol (fence/check-def (fence/read-expression "(def ^:macro a 1)") #{} 64))))
  (is (nil? (fence/check-def (fence/read-expression "(def ^:private a 1)") #{} 64)))
  ;; Round 1c, MCP-OP-QUERY-130: an attr-map is no longer allowed, so the plain
  ;; map is a def-shape refusal. The old expectation was nil.
  (is (= :def-shape
         (:reason (fence/check-def (fence/read-expression "(defn a {:doc \"x\"} [] 1)") #{} 64))))
  (is (nil? (fence/check-def (fence/read-expression "(defn a \"x\" [] 1)") #{} 64))))

;; @spec MCP-OP-QUERY-106
;; @spec MCP-OP-QUERY-105
(deftest an-evaluation-that-passes-the-deadline-ends-and-its-worker-stops
  ;; Issue #7. Before the fix the worker stayed RUNNABLE after the refusal.
  (doseq [text ["(loop [] (recur))"
                "(last (range))"
                "(count (range))"
                "(count (apply str (range)))"
                "(reduce + (range))"
                "(last (map inc (range)))"
                "(count (filter odd? (map inc (range))))"
                "(count (take-while pos? (map inc (range))))"
                "(into [] (range))"]]
    (let [[s _] (new-sessions {})
          h (handle-of s "s1" "/r")
          worker (atom nil)
          start (System/nanoTime)
          data (thrown-data #(session/run-with-timeout
                              300 (fn []
                                    (reset! worker (Thread/currentThread))
                                    (ev s h text))))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (is (= :timeout (:error-type data)) text)
      (is (= 300 (:limit-ms data)) text)
      (is (< elapsed-ms 3000) text)
      (when-let [^Thread t @worker]
        (.join t 500)
        (is (not (.isAlive t)) (str "the worker ended: " text)))
      (is (= [] (real-var-names h)) text))))

;; @spec MCP-OP-QUERY-105
;; @spec MCP-OP-QUERY-114
(deftest a-late-worker-cannot-complete-a-def
  ;; Issue #7. A call that never checks the deadline finishes late and then
  ;; its def must have no effect. `late` stands for a pure Java call.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        worker (atom nil)
        spin (fn [] (let [end (+ (System/nanoTime) 600000000)]
                      (while (< (System/nanoTime) end))
                      1))]
    (with-redefs [fence/admit (fn [_ _] nil)]
      (let [data (thrown-data
                  #(session/run-with-timeout
                    100 (fn []
                          (reset! worker (Thread/currentThread))
                          (session/eval-in-session s h (fence/read-expression "(do (late) (def late-def 1))")
                                                   {'late spin}))
                    {:grace-ms 50}))]
        (is (= :timeout (:error-type data)))
        (.join ^Thread @worker 3000)
        (is (not (.isAlive ^Thread @worker)))
        (is (= [] (session/handle-defs h)))
        (is (= [] (real-var-names h)))))))

;; @spec MCP-OP-QUERY-109
(deftest workers-that-do-not-end-make-a-new-request-busy
  (let [spin (fn [ms] (fn [] (let [end (+ (System/nanoTime) (* ms 1000000))]
                               (while (< (System/nanoTime) end))
                               1)))]
    (doseq [_ (range 2)]
      (is (= :timeout (:error-type (thrown-data #(session/run-with-timeout
                                                  50 (spin 800) {:grace-ms 10}))))))
    (let [busy (thrown-data #(session/run-with-timeout 50 (fn [] 1) {:grace-ms 10}))]
      (is (= :busy (:error-type busy)))
      (is (string? (:remedy busy))))
    (Thread/sleep 1200)
    (is (= 1 (session/run-with-timeout 1000 (fn [] 1))))))

;; @spec MCP-OP-QUERY-113
(deftest a-throw-leaves-the-context-and-its-names-as-they-were
  ;; Issue #9 (1). 60 defs and then a throw left 60 vars in the context.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        defs-of (fn [prefix] (str/join " " (for [i (range 60)] (str "(def " prefix i " 1)"))))]
    (ev s h "(def keep-me 1)")
    (doseq [prefix ["a" "b" "c" "d" "e"]]
      (is (= :evaluation-error
             (:error-type (thrown-data #(ev s h (str "(do " (defs-of prefix) " (inc nil))")))))
          prefix))
    (is (= ["keep-me"] (real-var-names h)))
    (is (= ["keep-me"] (session/handle-defs h)))
    (is (= 1 (:value (ev s h "keep-me"))))))

;; @spec MCP-OP-QUERY-113
(deftest a-throw-restores-the-value-of-a-redefined-name
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (ev s h "(def a 1)")
    (thrown-data #(ev s h "(do (def a 2) (inc nil))"))
    (is (= 1 (:value (ev s h "a"))))))

;; @spec MCP-OP-QUERY-113
(deftest a-timeout-leaves-the-context-as-it-was
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (ev s h "(def a 1)")
    (is (= :timeout
           (:error-type (thrown-data #(session/run-with-timeout
                                       200 (fn [] (ev s h "(do (def a 2) (def b 3) (count (range)))")))))))
    (is (= ["a"] (real-var-names h)))
    (is (= 1 (:value (ev s h "a"))))))

;; @spec MCP-OP-QUERY-113
;; @spec MCP-OP-QUERY-117
(deftest the-names-that-a-refused-request-would-add-are-not-held
  (let [[s _] (new-sessions {:max-defs 3})
        h (handle-of s "s1" "/r")]
    (is (= :session-full (:error-type (thrown-data #(ev s h "(do (def a 1) (def b 1) (def c 1) (def d 1))")))))
    (is (= [] (real-var-names h)))))

;; @spec MCP-OP-QUERY-073
(deftest the-limit-counts-the-names-the-context-really-holds
  ;; The limit is enforced after evaluation too, so a name that a macro adds and
  ;; that the form scan does not see cannot take the context over the limit.
  ;; (-> c (def 5)) expands to (def c 5); the scan sees (def 5) and no name.
  (let [[s _] (new-sessions {:max-defs 2})
        h (handle-of s "s1" "/r")]
    (ev s h "(def a 1)")
    (ev s h "(def b 1)")
    (is (= :session-full (:error-type (thrown-data #(ev s h "(-> c (def 5))")))))
    (is (= ["a" "b"] (real-var-names h)))))

;; @spec MCP-OP-QUERY-116
(deftest a-request-that-adds-no-name-is-never-refused-with-session-full
  ;; Issue #9 (4). check-def tested the held names even for a request that
  ;; defines nothing, so a session over the limit was bricked.
  (is (nil? (fence/check-def '(inc 1) '#{a b c} 2)))
  (is (nil? (fence/check-def '(def a 2) '#{a b c} 2)))
  (is (= :session-full (:error-type (fence/check-def '(def d 2) '#{a b c} 2)))))

;; @spec MCP-OP-QUERY-117
(deftest a-def-over-the-item-bound-is-refused-and-the-context-is-unchanged
  ;; Issue #9 (2). A 2M-element vector was accepted with no bound.
  (let [[s _] (new-sessions {:max-def-items 1000})
        h (handle-of s "s1" "/r")]
    (ev s h "(def small (vec (range 100)))")
    (doseq [text ["(def big (vec (range 5000)))"
                  "(def xs (range))"
                  "(def nested {:a (vec (range 600)) :b (vec (range 600))})"
                  "(def m (zipmap (range 2000) (range 2000)))"
                  "(def deep [[(vec (range 500))] [(vec (range 600))]])"]]
      (let [data (thrown-data #(ev s h text))]
        (is (= :session-full (:error-type data)) text)
        (is (string? (:remedy data)) text)))
    (is (= ["small"] (real-var-names h)))
    (testing "the items of all defs count together"
      (ev s h "(def more (vec (range 800)))")
      (is (= :session-full (:error-type (thrown-data #(ev s h "(def third (vec (range 300)))")))))
      (is (= ["more" "small"] (real-var-names h))))
    (testing "a value under the bound is accepted"
      (is (= 100 (count (:value (ev s h "small"))))))))

;; @spec MCP-OP-QUERY-117
(deftest repeated-throws-after-a-big-def-do-not-accumulate
  (let [[s _] (new-sessions {:max-def-items 1000})
        h (handle-of s "s1" "/r")]
    (dotimes [i 4]
      (thrown-data #(ev s h (str "(do (def big" i " (vec (range 900))) (inc nil))"))))
    (is (= [] (real-var-names h)))
    (ev s h "(def ok (vec (range 900)))")
    (is (= ["ok"] (real-var-names h)))))

;; @spec MCP-OP-QUERY-115
;; @spec MCP-OP-QUERY-073
(deftest requests-of-one-session-run-one-after-another
  ;; Issue #9 (3). Two parallel requests of 60 defs each both succeeded and the
  ;; session held 120 names.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        gate (fn [] (Thread/sleep 300) nil)
        defs-of (fn [prefix] (str/join " " (for [i (range 60)] (str "(def " prefix i " 1)"))))
        run (fn [prefix]
              (future
                (thrown-data
                 #(session/run-with-timeout
                   5000 (fn [] (session/eval-in-session
                                s h (fence/read-expression (str "(do " (defs-of prefix) " (gate))"))
                                {'gate gate}))))))]
    (with-redefs [fence/admit (fn [_ _] nil)]
      (let [r1 (run "a")
            _ (Thread/sleep 50)
            r2 (run "b")
            results [@r1 @r2]]
        (is (= 1 (count (filter #{:no-throw} results))) (pr-str results))
        (is (= [:session-full] (vec (keep :error-type (remove #{:no-throw} results)))))
        (is (= 60 (count (real-var-names h))))
        (is (<= (count (real-var-names h)) 64))))
    (testing "a later read-only request is still served"
      (is (= 2 (:value (ev s h "(inc 1)")))))))

;; ---------------------------------------------------------------------------
;; Fix round 1b: macros through SCI markers (#18), depth and Throwables (#19)

(defn- nest
  "`n` copies of `open` before `1` and the matching `close` after it."
  [open close n]
  (str (str/join (repeat n open)) "1" (str/join (repeat n close))))

;; @spec MCP-OP-QUERY-104
;; @spec MCP-OP-QUERY-121
;; @spec MCP-OP-QUERY-122
(deftest a-sci-macro-marker-is-refused-in-every-shape-and-the-macro-cannot-run
  ;; Issue #18. Each repro below returned 42, host text or a live Atom.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (doseq [text ["(defn ^:sci/macro m [&form &env] '(load-string \"(+ 40 2)\"))"
                  "(def ^:sci/macro m (fn [&form &env] '(str (:protectionDomain (bean (class skeleton))))))"
                  "(defn m {:sci/macro true} [&form &env] (list 'atom 1))"
                  "(def ^{:sci/macro true} m (fn [&form &env] 1))"
                  "(def ^{:sci/macro (= 1 1)} m (fn [&form &env] 1))"]]
      (let [data (thrown-data #(ev s h text))]
        (is (= :denied-symbol (:error-type data)) text)
        (is (= ":sci/macro" (:symbol data)) text)))
    (testing "a computed key could be either marker"
      (let [data (thrown-data
                  #(ev s h "(def ^{(keyword \"sci\" \"macro\") true} m (fn [&form &env] 1))"))]
        (is (= :denied-symbol (:error-type data)))
        (is (contains? #{":macro" ":sci/macro"} (:symbol data)))))
    (is (= [] (real-var-names h)))
    (is (= :denied-symbol (:error-type (thrown-data #(ev s h "(m)")))))))

;; @spec MCP-OP-QUERY-122
(deftest a-macro-made-by-a-computed-value-is-found-after-evaluation-and-rolled-back
  ;; Issue #18: the early check cannot see a marker on the root value or one
  ;; that a function adds.
  (doseq [[text marker]
          [["(def m (with-meta (fn [&form &env] 1) {:sci/macro true}))" ":sci/macro"]
           ["(def m (vary-meta (fn [&form &env] 1) assoc :sci/macro true))" ":sci/macro"]
           ["(def m (with-meta (fn [&form &env] 1) {:macro true}))" ":macro"]]]
    (let [[s _] (new-sessions {})
          h (handle-of s "s1" "/r")
          _ (ev s h "(def keep-me 1)")
          data (thrown-data #(ev s h text))]
      (testing text
        (is (= :denied-symbol (:error-type data)))
        (is (= marker (:symbol data)))
        (is (= ["keep-me"] (real-var-names h)))
        (is (= ["keep-me"] (session/handle-defs h)))))))

;; @spec MCP-OP-QUERY-122
(deftest the-macro-refusal-has-priority-over-an-evaluation-error
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        data (thrown-data
              #(ev s h "(do (def m (with-meta (fn [&form &env] 1) {:sci/macro true})) (inc nil))"))]
    (is (= :denied-symbol (:error-type data)))
    (is (= ":sci/macro" (:symbol data)))
    (is (= [] (real-var-names h)))))

;; @spec MCP-OP-QUERY-123
(deftest the-session-context-denies-the-symbols-of-077
  (let [ctx (#'session/new-context)]
    (doseq [text ["(eval 1)" "(load-string \"1\")" "(atom 1)" "(slurp \"x\")"]]
      (is (thrown? Exception (sci/eval-string* ctx text)) text))))

;; @spec MCP-OP-QUERY-124
(deftest depth-scan-refuses-deep-nesting-before-any-read
  ;; Issue #19. Each of these threw a raw StackOverflowError.
  (doseq [[what text] [["vectors" (nest "[" "]" 1900)]
                       ["lists" (nest "(" ")" 1000)]
                       ["carets" (str (str/join (repeat 1900 "^")) "1")]
                       ["quotes" (str (str/join (repeat 1900 "'")) "1")]
                       ["set openers" (nest "#{" "}" 300)]
                       ["fn openers" (nest "#(" ")" 300)]]]
    (let [data (thrown-data #(fence/read-expression text))]
      (is (= :expression-too-deep (:error-type data)) what)
      (is (> (:depth data) 256) what)
      (is (= 256 (:limit data)) what)
      (is (string? (:remedy data)) what))))

;; @spec MCP-OP-QUERY-124
(deftest depth-scan-measures-nesting-and-nothing-else
  (is (= 256 (fence/expression-depth (nest "[" "]" 256))))
  (is (= 257 (fence/expression-depth (nest "[" "]" 257))))
  (is (= 1 (fence/expression-depth "(+ 1 2)")))
  (is (= 2 (fence/expression-depth "(+ 1 [2])")))
  (testing "siblings do not add up"
    (is (= 2 (fence/expression-depth (str "[" (str/join " " (repeat 400 "'a")) "]"))))
    (is (= 2 (fence/expression-depth (str "[" (str/join " " (repeat 400 "^:a b")) "]")))))
  (testing "prefixes count while they wait for their form"
    (is (= 3 (fence/expression-depth "'[@a]")))
    (is (= 1 (fence/expression-depth "#'a")))
    (is (= 1 (fence/expression-depth "~@a"))))
  (testing "strings, comments and character literals do not count"
    (is (= 0 (fence/expression-depth (str "\"" (str/join (repeat 500 "[")) "\""))))
    (is (= 1 (fence/expression-depth (str "(inc 1) ; " (str/join (repeat 500 "["))))))
    (is (= 1 (fence/expression-depth (str "[" (str/join " " (repeat 500 "\\[")) "]"))))
    (is (= 1 (fence/expression-depth "[\"a\\\"[[[[\" 1]")))))

;; @spec MCP-OP-QUERY-124
(deftest an-expression-at-the-bound-is-still-read
  (is (= 256 (loop [f (fence/read-expression (nest "[" "]" 255)) n 1]
               (if (vector? f) (recur (first f) (inc n)) n)))))

;; @spec MCP-OP-QUERY-125
(deftest a-stack-overflow-in-admission-or-the-def-check-is-a-typed-refusal
  ;; Issue #19. The form is built as data, so the reader never sees it.
  (let [deep (reduce (fn [acc _] [acc]) 1 (range 30000))]
    (doseq [[what f] [["admit" #(fence/admit deep #{})]
                      ["check-def" #(fence/check-def deep #{} 64)]]]
      (let [data (thrown-data f)]
        ;; Round 1c, owner decision 2026-10-18: one type for one cause.
        (is (= :expression-too-deep (:error-type data)) what)
        (is (= 256 (:limit data)) what)
        (is (str/includes? (:remedy data) "256") what)
        (is (str/includes? (:remedy data) "Flatten") what)))))

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-126
(deftest another-throwable-in-a-pre-evaluation-step-is-internal-error
  (let [boom (fn [& _] (throw (AssertionError. (str/join (repeat 3000 "x")))))]
    (with-redefs [fence/bound-set boom]
      (doseq [[what f] [["read-expression" #(with-redefs [sci/parse-next boom]
                                              (fence/read-expression "(+ 1 2)"))]
                        ["admit" #(fence/admit '(foo) #{})]
                        ["check-def" #(fence/check-def '(def a 1) #{} 64)]]]
        (let [data (thrown-data f)]
          (is (= :internal-error (:error-type data)) what)
          (is (str/starts-with? (:error data) "java.lang.AssertionError") what)
          (is (<= (count (:error data)) 1100) what)
          (is (string? (:remedy data)) what)
          (is (not (contains? data :trace)) what))))))

;; @spec MCP-OP-QUERY-128
(deftest typed-refusals-of-bound-functions-keep-their-type
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        throws (fn [t] (fn [] (throw (ex-info "x" {:error-type t}))))]
    (doseq [t [:result-too-large :busy :timeout :unknown-namespace :unknown-var
               :pull-too-deep :invalid-expression]]
      (let [data (with-redefs [fence/admit (fn [_ _] nil)]
                   (try (session/eval-in-session s h '(boom) {'boom (throws t)})
                        :no-throw
                        (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (is (= t (:error-type data)) (str t))))
    (testing "an ordinary exception stays an evaluation-error"
      (is (= :evaluation-error
             (:error-type (thrown-data #(ev s h "(inc nil)"))))))))

;; ---------------------------------------------------------------------------
;; Fix round 1c: the def-shape allowlist and per-sub-form evaluation (#24)

(defn- ev-fresh
  "Evaluate `text` in a fresh session. Returns [outcome handle], where outcome is
   the result map or the ex-data of the refusal."
  [text]
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    [(try (ev s h text) (catch clojure.lang.ExceptionInfo e (ex-data e))) h]))

;; @spec MCP-OP-QUERY-130
;; @spec MCP-OP-QUERY-104
(deftest issue-24-attr-map-after-the-arities-cannot-make-a-macro
  ;; Issue #24. Request 1 was ok and the macro ran `intern` in the same request.
  (doseq [[what text]
          [["intern and ns-publics"
            "(do (defn m \"d\" ([&form &env] '(do (intern 'user 'zz 41) (str (ns-publics 'user)))) {:sci/macro true}) (def m [(m)]))"]
           ["eval"
            "(do (defn m \"d\" ([&form &env] '(eval '(+ 1 2))) {:sci/macro true}) (def m [(m)]))"]
           ["read-string, resolve, all-ns"
            "(do (defn m \"d\" ([&form &env] '[(read-string \"(+ 1 2)\") (str (resolve 'skeleton)) (str (all-ns))]) {:sci/macro true}) (def m [(m)]))"]
           ["two arities, attr-map last"
            "(do (defn m ([&form &env] 1) ([&form &env x] 2) {:sci/macro true}) (def m [(m)]))"]
           ["attr-map after the docstring"
            "(do (defn m \"d\" {:sci/macro true} [&form &env] '(eval 1)) (def m [(m)]))"]
           ["macro key"
            "(do (defn m \"d\" ([&form &env] 1) {:macro true}) (def m [(m)]))"]]]
    (let [[data h] (ev-fresh text)]
      (is (= :denied-symbol (:error-type data)) what)
      (is (contains? #{":sci/macro" ":macro"} (:symbol data)) what)
      (is (= [] (real-var-names h)) what)
      (is (= [] (session/handle-defs h)) what))))

;; @spec MCP-OP-QUERY-132
;; @spec MCP-OP-QUERY-122
(deftest issue-24-do-then-def-pattern-is-checked-after-each-sub-form
  ;; The allowed shapes cannot carry the marker, but a computed value can. The
  ;; later `def` replaced the var and hid the marker from the end check.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        calls (atom 0)
        text (str "(do (def m (with-meta (fn [&form &env] '(tick)) {:sci/macro true}))"
                  " (def r [(m)]) (def m 1))")
        data (with-redefs [fence/admit (fn [_ _] nil)]
               (try (session/eval-in-session s h (fence/read-expression text)
                                             {'tick (fn [] (swap! calls inc))})
                    (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :denied-symbol (:error-type data)))
    (is (= ":sci/macro" (:symbol data)))
    (is (= [] (real-var-names h)))
    (is (= [] (session/handle-defs h)))
    (is (zero? @calls) "the macro never expanded: the sub-form that uses it did not run")))

;; @spec MCP-OP-QUERY-132
(deftest sub-forms-after-a-macro-are-not-evaluated
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        calls (atom 0)
        data (with-redefs [fence/admit (fn [_ _] nil)]
               (try (session/eval-in-session
                     s h
                     (fence/read-expression
                      "(do (def m (with-meta (fn [&form &env] 1) {:macro true})) (tick) (tick))")
                     {'tick (fn [] (swap! calls inc))})
                    (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :denied-symbol (:error-type data)))
    (is (zero? @calls))))

;; @spec MCP-OP-QUERY-132
(deftest a-top-level-do-evaluates-its-sub-forms-in-order-and-gives-the-last
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (is (= 3 (:value (ev s h "(do (def a 1) (def b 2) (+ 1 2))"))))
    (is (= ["a" "b"] (session/handle-defs h)))
    (is (= 2 (:value (ev s h "(do 1 2)"))))
    (is (nil? (:value (ev s h "(do)"))))
    (testing "a nested do is split in the same way"
      (is (= 7 (:value (ev s h "(do (do (def c 5) (do (def d 7))) 7)"))))
    (is (= ["a" "b" "c" "d"] (session/handle-defs h))))
    (testing "a final def gives the bound value and its name"
      (let [r (ev s h "(do (def e 9))")]
        (is (= 9 (:value r)))
        (is (= "e" (:def-name r)))))))

;; @spec MCP-OP-QUERY-132
;; @spec MCP-OP-QUERY-113
(deftest a-refused-or-throwing-sub-form-rolls-back-the-whole-request
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        _ (ev s h "(def keep-me 1)")
        data (thrown-data #(ev s h "(do (def a 1) (def keep-me 2) (inc nil) (def z 3))"))]
    (is (= :evaluation-error (:error-type data)))
    (is (= ["keep-me"] (real-var-names h)))
    (is (= ["keep-me"] (session/handle-defs h)))
    (is (= 1 (:value (ev s h "keep-me"))))))

;; @spec MCP-OP-QUERY-130
(deftest the-allowlist-denies-the-def-like-forms-it-does-not-list
  (doseq [[sym text] [["defn-" "(defn- f [] 1)"]
                      ["defmacro" "(defmacro m [] 1)"]
                      ["definline" "(definline f [x] x)"]
                      ["defmulti" "(defmulti f identity)"]
                      ["defmethod" "(defmethod f :a [x] x)"]
                      ["defonce" "(defonce a 1)"]
                      ["deftype" "(deftype T [a])"]
                      ["defrecord" "(defrecord R [a])"]
                      ["defprotocol" "(defprotocol P (f [x]))"]
                      ["intern" "(intern 'user 'zz 1)"]
                      ["alter-meta!" "(alter-meta! (var-get 1) assoc :a 1)"]
                      ["eval" "(eval 1)"]
                      ["read-string" "(read-string \"1\")"]
                      ["resolve" "(resolve 'skeleton)"]
                      ["ns-publics" "(ns-publics 'user)"]
                      ["all-ns" "(all-ns)"]]]
    (let [[data h] (ev-fresh text)]
      (is (= :denied-symbol (:error-type data)) text)
      (is (= sym (:symbol data)) text)
      (is (= [] (real-var-names h)) text))))

;; @spec MCP-OP-QUERY-130
(deftest a-def-or-defn-outside-the-shapes-is-refused-with-def-shape
  (doseq [text ["(def a 1 2)"
                "(def a \"doc\" 1 2)"
                "(def (with-meta 'a {:x 1}) 1)"
                "(defn f)"
                "(defn f \"doc\")"
                "(defn f \"doc\" 1)"
                "(defn f ([x] x) [y] y)"
                "(defn f ([x] x) 5)"
                "(defn f {:a 1} [x] x)"
                "(defn f \"d\" {:a 1} [x] x)"
                "(defn f ([x] x) {:a 1})"
                "(defn f \"d\" ([x] x) ([x y] y) {:doc \"x\"})"
                "(let [x 1] (def ^{:tag 'String} a x))"
                "(fn [] (defn g {:a 1} [] 1))"
                "(if true (def a 1 2) 1)"]]
    (let [[data h] (ev-fresh text)]
      (is (= :invalid-expression (:error-type data)) text)
      (is (= :def-shape (:reason data)) text)
      (is (string? (:message data)) text)
      (is (str/includes? (:remedy data) "defn") text)
      (is (= [] (real-var-names h)) text))))

;; @spec MCP-OP-QUERY-131
(deftest metadata-on-the-name-allows-only-doc-and-private-literals
  (doseq [text ["(def ^{:doc 1} a 1)"
                "(def ^{:private \"x\"} a 1)"
                "(def ^{:private (= 1 1)} a 1)"
                "(def ^{:doc (str \"a\" \"b\")} a 1)"
                "(def ^{:tag 'String} a 1)"
                "(def ^{:arglists '([x])} a 1)"
                "(def ^{:x 1} a 1)"
                "(def ^:dynamic a 1)"
                "(defn ^{:tag 'String} f [x] x)"
                "(defn ^{:private (= 1 1)} f [x] x)"
                "(def ^{:doc \"a\" :other 1} a 1)"]]
    (let [[data h] (ev-fresh text)]
      (is (= :invalid-expression (:error-type data)) text)
      (is (= :def-shape (:reason data)) text)
      (is (= [] (real-var-names h)) text)))
  (testing "the macro markers keep the refusal that names them"
    (doseq [[text marker] [["(def ^:macro a 1)" ":macro"]
                           ["(def ^:sci/macro a 1)" ":sci/macro"]
                           ["(defn ^{:sci/macro true} f [x] x)" ":sci/macro"]]]
      (let [[data _] (ev-fresh text)]
        (is (= :denied-symbol (:error-type data)) text)
        (is (= marker (:symbol data)) text)))))

;; @spec MCP-OP-QUERY-130
;; @spec MCP-OP-QUERY-131
(deftest the-allowlist-still-accepts-the-ordinary-shapes
  (doseq [text ["(def xs (range 5))"
                "(def x \"doc\" 1)"
                "(def x)"
                "(def x \"doc\")"
                "(defn f [x] x)"
                "(defn f \"doc\" [x] x)"
                "(defn f [x] {:a x})"
                "(defn f \"doc\" ([x] x) ([x y] y))"
                "(defn f ([x] x) ([x y] y))"
                "(def ^:private p 1)"
                "(def ^{:doc \"d\" :private true} p 1)"
                "(def ^{:private false} p 1)"
                "(defn ^:private g [x] x)"
                "(let [x 1] (def y x))"]]
    (let [[outcome h] (ev-fresh text)]
      (is (contains? outcome :value) (str text " " (pr-str outcome)))
      (is (seq (real-var-names h)) text))))

;; @spec MCP-OP-QUERY-130
(deftest a-session-can-use-its-defs-across-requests
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")]
    (ev s h "(defn add \"doc\" ([x] (add x 1)) ([x y] (+ x y)))")
    (ev s h "(def ^:private base 10)")
    (is (= 11 (:value (ev s h "(add 10)"))))
    (is (= 15 (:value (ev s h "(add base 5)"))))
    (is (= ["add" "base"] (session/handle-defs h)))))

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-127
(deftest a-stack-overflow-in-the-evaluation-is-expression-too-deep
  ;; Round 1c, owner decision 2026-10-18: one type for one cause.
  (let [[s _] (new-sessions {})
        h (handle-of s "s1" "/r")
        _ (ev s h "(def keep-me 1)")
        _ (ev s h "(defn f [x] (inc (f x)))")
        data (thrown-data #(ev s h "(f 1)"))]
    (is (= :expression-too-deep (:error-type data)))
    (is (str/includes? (:remedy data) "Flatten"))
    (is (= ["f" "keep-me"] (real-var-names h)))))
