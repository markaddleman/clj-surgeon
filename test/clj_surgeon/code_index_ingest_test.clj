(ns clj-surgeon.code-index-ingest-test
  "Fast witnesses for ingest of one file: clj-kondo runs in process over
   small literal sources, through `code-index-ingest/file-result`. No file is
   read or written."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index-ingest :as ingest]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private fake-sha
  "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

(defn- analyze
  "Run file-result on a literal source text."
  ([text] (analyze text nil))
  ([text lint-as]
   (ingest/file-result
    (cond-> {:path "src/a/b.clj"
             :text text
             :mtime 1792000000000
             :size (count text)
             :sha256 fake-sha}
      lint-as (assoc :lint-as lint-as)))))

(defn- var-of
  [result name]
  (first (filter #(= name (:name %)) (:vars result))))

(defn- intent-of-doc
  "Intent of a var `f` whose docstring is `doc` (no double quotes in doc)."
  [doc]
  (:intent (var-of (analyze (str "(ns a.b)\n\n(defn f\n  \"" doc "\"\n  [x]\n  x)\n"))
                   "f")))

;; @spec MCP-OP-QUERY-018
(deftest kondo-config-sets-analysis-for-arglists-and-keywords-but-not-locals
  (let [cfg (ingest/kondo-config nil)]
    (is (true? (get-in cfg [:analysis :arglists])))
    (is (true? (get-in cfg [:analysis :keywords])))
    (is (false? (get-in cfg [:analysis :locals])))))

;; @spec MCP-OP-QUERY-018
(deftest kondo-config-turns-every-linter-off
  (let [cfg (ingest/kondo-config nil)]
    (is (seq (:linters cfg)) "the linter map is not empty")
    (is (every? #(= :off (:level %)) (vals (:linters cfg))))))

;; @spec MCP-OP-QUERY-018
;; @spec MCP-OP-QUERY-019
(deftest kondo-config-has-no-hooks-whatever-the-input
  (is (empty? (:hooks (ingest/kondo-config nil))))
  (is (empty? (:hooks (ingest/kondo-config '{a.macro/defthing clojure.core/def})))))

;; @spec MCP-OP-QUERY-019
(deftest kondo-config-passes-lint-as-through
  (let [m '{a.macro/defthing clojure.core/def}]
    (is (= m (:lint-as (ingest/kondo-config m))))
    (is (= {} (:lint-as (ingest/kondo-config nil))))
    (is (= {} (:lint-as (ingest/kondo-config {}))))))

;; @spec MCP-OP-QUERY-019
(deftest file-result-applies-lint-as-so-a-macro-defines-a-var
  (let [text (str "(ns a.b (:require [my.macro :refer [defthing]]))\n"
                  "\n"
                  "(defthing thing 1)\n")
        with (analyze text '{my.macro/defthing clojure.core/def})
        without (analyze text)]
    (is (= ["a.b/thing"] (mapv :qname (:vars with))))
    (is (= "clojure.core/def" (:kind (var-of with "thing"))))
    (is (empty? (:vars without))
        "without :lint-as the unknown macro defines no var")))

;; @spec MCP-OP-QUERY-020
(deftest file-result-copies-the-input-metadata-and-names-the-namespace
  (let [text "(ns a.b \"The a.b namespace.\")\n\n(defn f [x] x)\n"
        r (analyze text)]
    (is (= "src/a/b.clj" (:path r)))
    (is (= 1792000000000 (:mtime r)))
    (is (= (count text) (:size r)))
    (is (= fake-sha (:sha256 r)))
    (is (= "a.b" (:ns r)))
    (is (= "The a.b namespace." (:ns-doc r)))))

;; @spec MCP-OP-QUERY-020
(deftest file-result-leaves-ns-doc-absent-without-a-docstring
  (let [r (analyze "(ns a.b)\n\n(defn f [x] x)\n")]
    (is (= "a.b" (:ns r)))
    (is (not (contains? r :ns-doc)))))

;; @spec MCP-OP-QUERY-020
(deftest file-result-makes-one-full-record-per-top-level-var-in-source-order
  (let [text (str "(ns a.b)\n"
                  "\n"
                  "(defn f\n"
                  "  \"Does f.\"\n"
                  "  ([x] (g x 1))\n"
                  "  ([x y] (g x y)))\n"
                  "\n"
                  "(defn- g [x y] [x y])\n"
                  "\n"
                  "(def answer 42)\n")
        r (analyze text)
        f (var-of r "f")
        g (var-of r "g")
        answer (var-of r "answer")]
    (is (= ["a.b/f" "a.b/g" "a.b/answer"] (mapv :qname (:vars r))))
    (is (= {:qname "a.b/f" :ns "a.b" :name "f" :platform :clj :file "src/a/b.clj"
            :start 3 :end 6
            :kind "clojure.core/defn" :private? false
            :arglists ["[x]" "[x y]"]
            :doc "Does f." :intent "Does f."
            :calls ["a.b/g"] :fx [] :uses-kw [] :reads-kw []
            :throws? false}
           (dissoc f :source)))
    (is (= "clojure.core/defn-" (:kind g)))
    (is (true? (:private? g)))
    (is (= ["[x y]"] (:arglists g)))
    (is (= "clojure.core/def" (:kind answer)))
    (is (= [] (:arglists answer)) "a var that is not a function has no arglists")
    (is (= 10 (:start answer) (:end answer)))))

;; @spec MCP-OP-QUERY-020
;; @spec MCP-OP-QUERY-024
(deftest file-result-leaves-doc-and-intent-absent-without-a-docstring
  (let [g (var-of (analyze "(ns a.b)\n\n(defn g [x] x)\n") "g")]
    (is (some? g))
    (is (not (contains? g :doc)))
    (is (not (contains? g :intent)))))

;; @spec MCP-OP-QUERY-021
(deftest source-is-the-exact-lines-of-the-top-level-form
  (let [text (str "(ns a.b)\n"
                  "\n"
                  ";; a comment above f\n"
                  "(defn f\n"
                  "  \"Doc.\"\n"
                  "  [x]\n"
                  "  (inc   x))\n"
                  "\n"
                  "(defn g [y] y)\n")
        r (analyze text)]
    (is (= (str "(defn f\n"
                "  \"Doc.\"\n"
                "  [x]\n"
                "  (inc   x))")
           (:source (var-of r "f")))
        "inner spacing is kept, the comment above and the blank line below are not")
    (is (= [4 7] ((juxt :start :end) (var-of r "f"))))
    (is (= "(defn g [y] y)" (:source (var-of r "g"))))
    (is (= [9 9] ((juxt :start :end) (var-of r "g"))))))

;; @spec MCP-OP-QUERY-021
(deftest source-has-no-trailing-newline-for-the-last-form
  (let [r (analyze "(ns a.b)\n\n(defn g [y] y)\n\n")]
    (is (= "(defn g [y] y)" (:source (var-of r "g"))))))

;; @spec MCP-OP-QUERY-022
(deftest calls-hold-qualified-sorted-distinct-names-without-clojure-vars
  (let [text (str "(ns a.b (:require [c.d :as cd]))\n"
                  "\n"
                  "(defn g [x] x)\n"
                  "\n"
                  "(defn f [x]\n"
                  "  (str (inc x) (g x) (cd/z x) (cd/z x) (cd/a x) (g x)))\n")
        f (var-of (analyze text) "f")]
    (is (= ["a.b/g" "c.d/a" "c.d/z"] (:calls f)))
    (is (not-any? #(str/starts-with? % "clojure.") (:calls f)))))

;; @spec MCP-OP-QUERY-022
(deftest calls-are-empty-when-a-form-uses-only-clojure-core
  (let [f (var-of (analyze "(ns a.b)\n\n(defn f [x] (map inc (filter odd? x)))\n") "f")]
    (is (= [] (:calls f)))))

;; @spec MCP-OP-QUERY-022
(deftest calls-keep-a-call-to-the-var-itself
  (let [f (var-of (analyze "(ns a.b)\n\n(defn f [x] (if x (f nil) 1))\n") "f")]
    (is (= ["a.b/f"] (:calls f)))))

;; @spec MCP-OP-QUERY-022
(deftest file-uses-leave-out-clojure-targets
  (let [r (analyze (str "(ns a.b)\n\n(defn g [x] x)\n\n"
                        "(defn f [x] (str (g x)))\n"))]
    (is (= [["a.b/f" "a.b/g" :call]]
           (mapv (juxt :from :target :kind) (:uses r))))
    (is (every? #(= "src/a/b.clj" (:file %)) (:uses r)))
    (is (= [5] (distinct (map :line (:uses r)))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-true-for-a-throw-call-head
  (let [r (analyze "(ns a.b)\n\n(defn g [] (throw (Exception. \"x\")))\n")]
    (is (true? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-true-for-an-ex-info-call-head
  (let [r (analyze "(ns a.b)\n\n(defn g [] (ex-info \"x\" {}))\n")]
    (is (true? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-false-when-throw-occurs-only-in-a-string
  (let [r (analyze "(ns a.b)\n\n(defn g [] \"(throw x) and (ex-info y)\")\n")]
    (is (false? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-false-when-throw-occurs-only-in-a-comment
  (let [r (analyze (str "(ns a.b)\n\n"
                        "(defn g []\n"
                        "  ;; (throw x)\n"
                        "  1)\n"))]
    (is (false? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-false-when-throw-occurs-only-in-a-quoted-form
  (let [r (analyze "(ns a.b)\n\n(defn g [] '(throw x))\n")]
    (is (false? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-false-for-a-name-that-only-starts-with-throw
  (let [r (analyze "(ns a.b)\n\n(defn g [] (throw-it 1))\n")]
    (is (false? (:throws? (var-of r "g"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-is-decided-for-each-var-apart
  (let [r (analyze (str "(ns a.b)\n\n"
                        "(defn bad [] (throw (ex-info \"x\" {})))\n\n"
                        "(defn ok [] 1)\n"))]
    (is (true? (:throws? (var-of r "bad"))))
    (is (false? (:throws? (var-of r "ok"))))))

;; @spec MCP-OP-QUERY-023
(deftest throws-predicate-follows-the-call-head-rule
  (is (true? (ingest/throws? "(defn g [] (throw (ex-info \"x\" {})))")))
  (is (true? (ingest/throws? "(defn g [] (ex-info \"x\" {}))")))
  (is (false? (ingest/throws? "(defn g [] (throw-it 1))")))
  (is (false? (ingest/throws? "(defn g [] \"(throw\")")))
  (is (false? (ingest/throws? "(defn g [] ; (throw x)\n 1)")))
  (is (false? (ingest/throws? "(defn g [] (clojure.core/throw x))"))))

;; @spec MCP-OP-QUERY-024
(deftest intent-follows-the-first-sentence-rule-table
  (doseq [[doc expected]
          [["Does f. Then more." "Does f."]
           ["Is it? Yes." "Is it?"]
           ["Stop! Now." "Stop!"]
           ["Does f." "Does f."]
           ["Does f" "Does f"]
           ["v1.2 is fine" "v1.2 is fine"]
           ["Reads v1.2 files. Fast." "Reads v1.2 files."]
           ["A\n  multi   line doc? Yes." "A multi line doc?"]
           ["  padded text.  " "padded text."]]]
    (testing (pr-str doc)
      (is (= expected (intent-of-doc doc))))))

;; @spec MCP-OP-QUERY-024
(deftest intent-is-cut-to-200-characters-without-a-terminator
  (let [long-doc (apply str (repeat 300 "x"))
        intent (intent-of-doc long-doc)]
    (is (= 200 (count intent)))
    (is (= (apply str (repeat 200 "x")) intent))))

;; @spec MCP-OP-QUERY-024
(deftest intent-is-cut-to-200-characters-when-the-first-sentence-is-longer
  (let [long-doc (str (apply str (repeat 250 "y")) ". Next.")
        intent (intent-of-doc long-doc)]
    (is (= 200 (count intent)))
    (is (= (apply str (repeat 200 "y")) intent))))

;; @spec MCP-OP-QUERY-024
(deftest doc-keeps-the-full-docstring-while-intent-keeps-the-first-sentence
  (let [f (var-of (analyze "(ns a.b)\n\n(defn f \"Does f. Then more.\" [x] x)\n") "f")]
    (is (= "Does f. Then more." (:doc f)))
    (is (= "Does f." (:intent f)))))

;; @spec MCP-OP-QUERY-041
(deftest fx-holds-clojure-core-effect-names-that-calls-leaves-out
  (let [text (str "(ns a.b)\n\n"
                  "(defn f [state]\n"
                  "  (swap! state inc))\n")
        f (var-of (analyze text) "f")]
    (is (= ["swap!"] (:fx f)))
    (is (= [] (:calls f)) "clojure.core uses stay out of :calls")))

;; @spec MCP-OP-QUERY-041
(deftest fx-is-sorted-and-distinct
  (let [text (str "(ns a.b)\n\n"
                  "(defn f [state]\n"
                  "  (println 1)\n"
                  "  (swap! state inc)\n"
                  "  (spit \"x\" \"y\")\n"
                  "  (println 2)\n"
                  "  (reset! state 0))\n")
        f (var-of (analyze text) "f")]
    (is (= ["println" "reset!" "spit" "swap!"] (:fx f)))))

;; @spec MCP-OP-QUERY-041
(deftest fx-leaves-out-names-that-are-not-in-the-effect-list
  (let [f (var-of (analyze "(ns a.b)\n\n(defn f [x] (map inc (conj x 1)))\n") "f")]
    (is (= [] (:fx f)))))

;; @spec MCP-OP-QUERY-041
(deftest fx-holds-names-of-vars-from-other-namespaces
  (let [text (str "(ns a.b (:require [clojure.java.shell :as shell]))\n\n"
                  "(defn f [] (shell/sh \"ls\"))\n")
        f (var-of (analyze text) "f")]
    (is (= ["sh"] (:fx f)))))

;; @spec MCP-OP-QUERY-041
(deftest fx-names-set-holds-the-fixed-effect-list
  (is (= #{"swap!" "reset!" "spit" "transact" "transact!" "sh" "shell" "process"
           "delete-file" "send" "put!" ">!" "future" "println"}
         ingest/fx-names)))

;; @spec MCP-OP-QUERY-045
(deftest a-symbol-in-a-string-a-comment-or-a-quoted-form-is-not-a-use
  (let [text (str "(ns a.b)\n\n"
                  "(defn g [x] x)\n\n"
                  "(defn f [x]\n"
                  "  ;; (g 1)\n"
                  "  (list \"(g 2)\" '(g 3) 'g x))\n")
        r (analyze text)
        f (var-of r "f")]
    (is (= [] (:calls f)))
    (is (empty? (filter #(= "a.b/g" (:target %)) (:uses r))))))

;; @spec MCP-OP-QUERY-045
(deftest a-real-call-next-to-quoted-text-is-still-a-use
  (let [text (str "(ns a.b)\n\n"
                  "(defn g [x] x)\n\n"
                  "(defn f [x]\n"
                  "  (list '(g 3) (g x)))\n")
        r (analyze text)]
    (is (= ["a.b/g"] (:calls (var-of r "f"))))
    (is (= [[6 "a.b/f" "a.b/g" :call]]
           (mapv (juxt :line :from :target :kind) (:uses r))))))

;; @spec MCP-OP-QUERY-020
(deftest file-result-does-not-throw-on-a-syntax-error
  (let [r (analyze "(ns a.b)\n\n(defn f [x] (inc x)\n")]
    (is (map? r))
    (is (= "src/a/b.clj" (:path r)))))

;; ---------------------------------------------------------------------------
;; Platform of a var (MCP-OP-QUERY-098, 099; decision C1, issue #3)
;; ---------------------------------------------------------------------------

(defn- analyze-at
  "Run file-result on a literal source text at `path`."
  [path text]
  (ingest/file-result {:path path :text text :mtime 1792000000000
                       :size (count text) :sha256 fake-sha}))

(def ^:private cljc-text
  (str "(ns a.b)\n"                                         ; 1
       "\n"                                                 ; 2
       "(defn same [x] (inc x))\n"                          ; 3
       "\n"                                                 ; 4
       "#?(:clj (defn diff [x] (str x))\n"                  ; 5
       "   :cljs (defn diff [x y] (js/foo x y)))\n"         ; 6
       "\n"                                                 ; 7
       "#?(:clj (defn only-clj [] 1))\n"                    ; 8
       "\n"                                                 ; 9
       "#?(:cljs (defn only-cljs [] 2))\n"))                ; 10

(defn- versions
  [result name]
  (filter #(= name (:name %)) (:vars result)))

;; @spec MCP-OP-QUERY-098
(deftest a-clj-file-gives-the-clj-platform-and-a-cljs-file-the-cljs-platform
  (let [text "(ns a.b)\n\n(defn f [x] x)\n"]
    (is (= [:clj] (mapv :platform (:vars (analyze-at "src/a/b.clj" text)))))
    (is (= [:cljs] (mapv :platform (:vars (analyze-at "src/a/b.cljs" text)))))))

;; @spec MCP-OP-QUERY-099
(deftest a-cljc-var-with-equal-analysis-on-both-platforms-gives-one-cljc-record
  (let [r (analyze-at "src/a/b.cljc" cljc-text)
        [same :as vs] (versions r "same")]
    (is (= 1 (count vs)))
    (is (= :cljc (:platform same)))
    (is (= [3 3] [(:start same) (:end same)]))
    (is (= "(defn same [x] (inc x))" (:source same)))))

;; @spec MCP-OP-QUERY-099
(deftest a-cljc-var-with-different-analysis-gives-a-clj-and-a-cljs-record
  (let [r (analyze-at "src/a/b.cljc" cljc-text)
        [clj-v cljs-v :as vs] (versions r "diff")]
    (is (= 2 (count vs)))
    (is (= [:clj :cljs] (mapv :platform vs)) "the clj version comes first")
    (is (= ["[x]"] (:arglists clj-v)))
    (is (= ["[x y]"] (:arglists cljs-v)))
    (testing "start, end and source are those of the defining form of the branch"
      (is (= [5 5] [(:start clj-v) (:end clj-v)]))
      (is (= [6 6] [(:start cljs-v) (:end cljs-v)]))
      (is (str/includes? (:source clj-v) "(str x)"))
      (is (str/includes? (:source cljs-v) "(js/foo x y)")))))

;; @spec MCP-OP-QUERY-099
(deftest a-cljc-var-that-one-platform-defines-gives-one-record-of-that-platform
  (let [r (analyze-at "src/a/b.cljc" cljc-text)]
    (is (= [:clj] (mapv :platform (versions r "only-clj"))))
    (is (= [:cljs] (mapv :platform (versions r "only-cljs"))))))

;; @spec MCP-OP-QUERY-099
(deftest cljc-records-keep-source-order-with-clj-before-cljs
  (let [r (analyze-at "src/a/b.cljc" cljc-text)]
    (is (= [["same" :cljc] ["diff" :clj] ["diff" :cljs] ["only-clj" :clj] ["only-cljs" :cljs]]
           (mapv (juxt :name :platform) (:vars r))))))

;; @spec MCP-OP-QUERY-099
(deftest a-cljc-var-that-differs-only-by-the-core-namespace-of-its-macro-is-one-record
  ;; kondo reports clojure.core/defn on :clj and cljs.core/defn on :cljs for the
  ;; same form; that is not a difference of the var.
  (let [r (analyze-at "src/a/b.cljc" "(ns a.b)\n\n(defn f [x] x)\n")]
    (is (= [:cljc] (mapv :platform (:vars r))))
    (is (= "clojure.core/defn" (:kind (first (:vars r)))))))

;; @spec MCP-OP-QUERY-099
(deftest the-first-definition-of-a-qname-and-platform-wins-in-one-file
  (let [r (analyze-at "src/a/b.clj" "(ns a.b)\n\n(defn f [x] 1)\n\n(defn f [x y] 2)\n")
        [v :as vs] (:vars r)]
    (is (= 1 (count vs)))
    (is (= 3 (:start v)))))
