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
   [clojure.test :refer [deftest is testing]]))

(def bound-names ["namespaces" "skeleton" "form" "refs" "pull" "help"])

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
  (let [data (thrown-data #(session/run-with-timeout
                            100 (fn [] (loop [] (recur)))))]
    (is (= :timeout (:error-type data)))
    (is (= 100 (:limit-ms data)))))

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
