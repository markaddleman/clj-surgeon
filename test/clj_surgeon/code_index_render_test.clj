(ns clj-surgeon.code-index-render-test
  "Pure witnesses for the code index renderer (MCP-OP-QUERY-056 to 066, and
   the rendering half of 074 and 085).

   Every input is a literal. The var maps follow the skeleton shape of
   docs/intent/code-index/code-index-api.md, and they match the shared
   fixtures `a.b/f`, `a.b/g` and `c.d.e/h`."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index-fixtures :as fx]
   [clj-surgeon.code-index-render :as r]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def var-f
  {:kind :var :qname "a.b/f" :ns "a.b" :name "f" :file "src/a/b.clj"
   :start 4 :end 7 :arglists ["[x]" "[x y]"] :private? false
   :calls ["a.b/g" "c.d.e/h"] :fx ["swap!"] :throws? false
   :intent "Does f."})

(def var-g
  {:kind :var :qname "a.b/g" :ns "a.b" :name "g" :file "src/a/b.clj"
   :start 9 :end 12 :arglists ["[x y]"] :private? true
   :calls ["z.external/q"] :fx [] :throws? true})

(def var-h
  {:kind :var :qname "c.d.e/h" :ns "c.d.e" :name "h" :file "src/c/d/e.clj"
   :start 3 :end 3 :arglists ["[x]"] :private? false
   :calls ["a.b/f"] :fx [] :throws? false})

(def line-f "λ f([x] | [x y]) | calls e/h,g | fx swap! — Does f.")
(def line-g "λ g([x y]) | calls external/q | throws | private")
(def line-h "λ h([x]) | calls b/f")

(def skeleton-meta
  {:kind :skeleton :ns "a.b" :doc "The a.b namespace."})

(defn- form-map
  [var-record]
  {:kind :form
   :qname (:qname var-record)
   :file (:file var-record)
   :start (:start var-record)
   :end (:end var-record)
   :source (:source var-record)})

(def form-f (form-map (first (:vars fx/a-b-file))))
(def form-h (form-map (first (:vars fx/c-d-e-file))))

(defn- pull-result
  [m]
  (with-meta m {:kind :pull}))

;; @spec MCP-OP-QUERY-024
(deftest first-sentence-keeps-the-terminator-and-stops-at-the-first-one
  (is (= "Does f." (r/first-sentence "Does f. Then more.")))
  (is (= "A multi line doc?" (r/first-sentence "A\n  multi   line doc? Yes.")))
  (is (= "Wow!" (r/first-sentence "Wow! Next.")))
  (is (= "v1.2 is fine" (r/first-sentence "v1.2 is fine")))
  (is (= "No terminator here" (r/first-sentence "  No terminator   here ")))
  (is (= "Ends at the end." (r/first-sentence "Ends at the end."))))

;; @spec MCP-OP-QUERY-024
(deftest first-sentence-returns-nil-for-nil-blank-and-non-strings
  (is (nil? (r/first-sentence nil)))
  (is (nil? (r/first-sentence "")))
  (is (nil? (r/first-sentence " \n\t ")))
  (is (nil? (r/first-sentence 42))))

;; @spec MCP-OP-QUERY-024
(deftest first-sentence-cuts-to-200-characters-without-an-ellipsis
  (let [long-text (apply str (repeat 300 "a"))
        result (r/first-sentence long-text)]
    (is (= 200 (count result)))
    (is (= (apply str (repeat 200 "a")) result)))
  (let [result (r/first-sentence (str (apply str (repeat 250 "b")) ". Next."))]
    (is (= (apply str (repeat 200 "b")) result))))

;; @spec MCP-OP-QUERY-059
(deftest var-line-writes-name-arglists-calls-effects-and-intent
  (is (= line-f (r/var-line var-f))))

;; @spec MCP-OP-QUERY-059
(deftest var-line-writes-throws-and-private-after-calls
  (is (= line-g (r/var-line var-g))))

;; @spec MCP-OP-QUERY-059
(deftest var-line-leaves-out-every-clause-that-has-no-value
  (testing "empty arglists render as ()"
    (is (= "λ conf()"
           (r/var-line {:name "conf" :ns "a.b" :arglists []
                        :calls [] :fx [] :throws? false :private? false}))))
  (testing "absent keys give the same line"
    (is (= "λ conf()" (r/var-line {:name "conf" :ns "a.b"}))))
  (testing "a blank intent gives no dash clause"
    (is (= "λ k([x])"
           (r/var-line {:name "k" :ns "a.b" :arglists ["[x]"] :intent "  "}))))
  (testing "fx alone"
    (is (= "λ k([x]) | fx println,swap!"
           (r/var-line {:name "k" :ns "a.b" :arglists ["[x]"]
                        :fx ["println" "swap!"]})))))

;; @spec MCP-OP-QUERY-059
(deftest var-line-puts-the-intent-last-after-private
  (is (= "λ p([]) | private — Hidden helper."
         (r/var-line {:name "p" :ns "a.b" :arglists ["[]"] :private? true
                      :intent "Hidden helper."}))))

;; @spec MCP-OP-QUERY-060
(deftest var-line-writes-a-call-to-another-namespace-with-its-last-segment
  (is (= line-h (r/var-line var-h))))

;; @spec MCP-OP-QUERY-060
(deftest var-line-sorts-the-written-call-names-not-the-qnames
  (is (= "λ m([]) | calls alpha/z,b,zed/a"
         (r/var-line {:name "m" :ns "a.b" :arglists ["[]"]
                      :calls ["x.zed/a" "a.b/b" "x.alpha/z"]}))))

;; @spec MCP-OP-QUERY-060
(deftest var-line-writes-each-called-name-once
  (is (= "λ m([]) | calls e/h"
         (r/var-line {:name "m" :ns "a.b" :arglists ["[]"]
                      :calls ["p.e/h" "q.e/h"]}))))

;; @spec MCP-OP-QUERY-058
(deftest ns-line-writes-the-name-and-the-first-sentence-of-the-doc
  (is (= "ns a.b — The a.b namespace."
         (r/ns-line {:ns "a.b" :doc "The a.b namespace. More text."})))
  (is (= "ns a.b" (r/ns-line {:ns "a.b"}))))

;; @spec MCP-OP-QUERY-058
(deftest render-writes-one-var-line-for-a-var-map
  (is (= line-f (r/render var-f))))

;; @spec MCP-OP-QUERY-058
(deftest render-writes-one-var-line-for-each-var-of-a-plain-vector
  (is (= (str line-f "\n" line-g)
         (r/render [var-f var-g])))
  (is (= (str line-f "\n" line-g)
         (r/render (list var-f var-g))))
  (is (= (str line-f "\n" line-g)
         (r/render (map identity [var-f var-g])))))

;; @spec MCP-OP-QUERY-058
(deftest render-starts-a-skeleton-with-the-namespace-line
  (is (= (str "ns a.b — The a.b namespace.\n" line-f "\n" line-g)
         (r/render (with-meta [var-f var-g] skeleton-meta)))))

;; @spec MCP-OP-QUERY-058
(deftest render-of-a-filtered-skeleton-has-var-lines-only
  (let [skeleton (with-meta [var-f var-g] skeleton-meta)
        filtered (filter :private? skeleton)]
    (is (= line-g (r/render filtered)))))

;; @spec MCP-OP-QUERY-058
(deftest render-of-an-empty-skeleton-is-the-namespace-line-only
  (is (= "ns a.b — The a.b namespace."
         (r/render (with-meta [] skeleton-meta))))
  (is (= "ns a.b"
         (r/render (with-meta [] {:kind :skeleton :ns "a.b"})))))

;; @spec MCP-OP-QUERY-061
(deftest form-block-writes-the-header-and-the-unchanged-source
  (is (= (str ";; a.b/f  src/a/b.clj:4-7\n" (:source form-f))
         (r/form-block form-f)))
  (is (= ";; c.d.e/h  src/c/d/e.clj:3-3\n(defn h [x] (a.b/f x))"
         (r/form-block form-h))))

;; @spec MCP-OP-QUERY-061
(deftest render-writes-the-block-for-a-form-map
  (is (= ";; c.d.e/h  src/c/d/e.clj:3-3\n(defn h [x] (a.b/f x))"
         (r/render form-h))))

;; @spec MCP-OP-QUERY-061
(deftest render-separates-form-blocks-with-a-blank-line
  (is (= (str (r/form-block form-f) "\n\n" (r/form-block form-h))
         (r/render [form-f form-h])))
  (is (not (str/ends-with? (r/render [form-f form-h]) "\n"))))

;; @spec MCP-OP-QUERY-062
(deftest use-line-writes-file-line-owner-and-use-kind
  (is (= "src/a/b.clj:7 a.b/f call"
         (r/use-line {:kind :use :file "src/a/b.clj" :line 7 :ns "a.b"
                      :from "f" :use-kind :call})))
  (is (= "test/a/b_test.clj:4 a.b-test/t alias-call"
         (r/use-line {:kind :use :file "test/a/b_test.clj" :line 4
                      :ns "a.b-test" :from "t" :use-kind :alias-call}))))

;; @spec MCP-OP-QUERY-062
(deftest use-line-writes-no-slash-when-the-use-is-outside-a-var
  (is (= "src/a/b.clj:2 a.b refer"
         (r/use-line {:kind :use :file "src/a/b.clj" :line 2 :ns "a.b"
                      :from nil :use-kind :refer}))))

;; @spec MCP-OP-QUERY-062
(deftest render-ends-a-vector-of-uses-with-a-total-line
  (let [uses [{:kind :use :file "src/a/b.clj" :line 6 :ns "a.b" :from "f"
               :use-kind :call}
              {:kind :use :file "test/a/b_test.clj" :line 4 :ns "a.b-test"
               :from "t" :use-kind :value}]]
    (is (= (str "src/a/b.clj:6 a.b/f call\n"
                "test/a/b_test.clj:4 a.b-test/t value\n"
                "total 2")
           (r/render uses)))
    (is (= "src/a/b.clj:6 a.b/f call\ntotal 1"
           (r/render (subvec uses 0 1))))))

;; @spec MCP-OP-QUERY-063
(deftest namespace-line-writes-the-count-and-the-first-sentence
  (is (= "ns a.b (2 vars) — The a.b namespace."
         (r/namespace-line {:kind :ns :ns "a.b" :file "src/a/b.clj" :vars 2
                            :doc "The a.b namespace. More."})))
  (is (= "ns c.d.e (1 vars)"
         (r/namespace-line {:kind :ns :ns "c.d.e" :file "src/c/d/e.clj"
                            :vars 1}))))

;; @spec MCP-OP-QUERY-063
(deftest render-writes-one-line-for-each-namespace-map
  (let [a {:kind :ns :ns "a.b" :file "src/a/b.clj" :vars 2
           :doc "The a.b namespace."}
        c {:kind :ns :ns "c.d.e" :file "src/c/d/e.clj" :vars 1}]
    (is (= "ns a.b (2 vars) — The a.b namespace." (r/render a)))
    (is (= "ns a.b (2 vars) — The a.b namespace.\nns c.d.e (1 vars)"
           (r/render [a c])))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-puts-scalar-attributes-in-the-fixed-order
  (testing "kind, start-end, arglists, then the intent"
    (is (= "a.b/f clojure.core/defn 4-7 [x] | [x y] — Does f."
           (r/render (pull-result
                      {:var/qname "a.b/f" :var/kind "clojure.core/defn"
                       :var/start 4 :var/end 7
                       :var/arglists ["[x]" "[x y]"]
                       :var/intent "Does f."})))))
  (testing "private comes after the kind and before the lines"
    (is (= "a.b/g clojure.core/defn- private 9-12 [x y]"
           (r/render (pull-result
                      {:var/qname "a.b/g" :var/kind "clojure.core/defn-"
                       :var/private? true :var/start 9 :var/end 12
                       :var/arglists ["[x y]"]})))))
  (testing "external comes first"
    (is (= "z.external/q external"
           (r/render (pull-result
                      {:var/qname "z.external/q" :var/external? true})))))
  (testing "a var with an id only"
    (is (= "a.b/f" (r/render (pull-result {:var/qname "a.b/f"}))))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-leaves-out-the-attributes-it-does-not-show
  (is (= "a.b/f"
         (r/render (pull-result
                    {:var/qname "a.b/f" :var/name "f" :var/ns "a.b"
                     :var/file "src/a/b.clj" :var/throws? false
                     :var/fx ["swap!"]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-uses-the-doc-when-the-var-has-no-intent
  (is (= "a.b/g clojure.core/defn- — Does g. Then more."
         (r/render (pull-result
                    {:var/qname "a.b/g" :var/kind "clojure.core/defn-"
                     :var/doc "Does g.\n  Then   more."}))))
  (testing "the intent wins over the doc"
    (is (= "a.b/f — Does f."
           (r/render (pull-result
                      {:var/qname "a.b/f" :var/intent "Does f."
                       :var/doc "Does f. Then more."}))))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-writes-a-namespace-and-a-keyword-entity
  (is (= "a.b — The a.b namespace."
         (r/render (pull-result
                    {:ns/name "a.b" :ns/doc "The a.b namespace. More."
                     :ns/file "src/a/b.clj"}))))
  (is (= "c.d.e" (r/render (pull-result {:ns/name "c.d.e"}))))
  (is (= "scope" (r/render (pull-result {:kw/name "scope"})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-writes-an-id-only-join-as-one-line
  (is (= "a.b/f\n  calls: a.b/g, c.d.e/h"
         (r/render (pull-result
                    {:var/qname "a.b/f"
                     :var/calls [{:var/qname "a.b/g"}
                                 {:var/qname "c.d.e/h"}]}))))
  (is (= "a.b/g\n  called by: a.b/f"
         (r/render (pull-result
                    {:var/qname "a.b/g"
                     :var/_calls [{:var/qname "a.b/f"}]}))))
  (is (= "a.b/f\n  uses: scope"
         (r/render (pull-result
                    {:var/qname "a.b/f" :var/uses-kw [{:kw/name "scope"}]}))))
  (is (= "a.b/f\n  reads: db"
         (r/render (pull-result
                    {:var/qname "a.b/f" :var/reads-kw [{:kw/name "db"}]}))))
  (is (= "a.b/f\n  in: a.b"
         (r/render (pull-result
                    {:var/qname "a.b/f" :var/in-ns [{:ns/name "a.b"}]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-writes-the-reverse-joins-of-keywords-and-namespaces
  (is (= "scope\n  used by: a.b/f, a.b/g"
         (r/render (pull-result
                    {:kw/name "scope"
                     :var/_uses-kw [{:var/qname "a.b/f"}
                                    {:var/qname "a.b/g"}]}))))
  (is (= "db\n  read by: a.b/f"
         (r/render (pull-result
                    {:kw/name "db" :var/_reads-kw [{:var/qname "a.b/f"}]}))))
  (is (= "a.b\n  vars: a.b/f, a.b/g"
         (r/render (pull-result
                    {:ns/name "a.b"
                     :ns/_in-ns [{:var/qname "a.b/f"}
                                 {:var/qname "a.b/g"}]}))))
  (is (= "a.b\n  vars: a.b/f"
         (r/render (pull-result
                    {:ns/name "a.b"
                     :var/_in-ns [{:var/qname "a.b/f"}]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-puts-joins-in-the-fixed-order
  (is (= (str "a.b/f\n"
              "  calls: a.b/g\n"
              "  called by: c.d.e/h\n"
              "  uses: scope\n"
              "  reads: db\n"
              "  in: a.b")
         (r/render (pull-result
                    {:var/in-ns [{:ns/name "a.b"}]
                     :var/reads-kw [{:kw/name "db"}]
                     :var/uses-kw [{:kw/name "scope"}]
                     :var/_calls [{:var/qname "c.d.e/h"}]
                     :var/calls [{:var/qname "a.b/g"}]
                     :var/qname "a.b/f"})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-gives-no-line-for-an-empty-join
  (is (= "a.b/f"
         (r/render (pull-result {:var/qname "a.b/f" :var/calls []})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-indents-a-richer-join-under-its-parent
  (is (= (str "a.b/f\n"
              "  calls:\n"
              "    a.b/g clojure.core/defn- private 9-12 [x y]\n"
              "    z.external/q external")
         (r/render (pull-result
                    {:var/qname "a.b/f"
                     :var/calls [{:var/qname "a.b/g"
                                  :var/kind "clojure.core/defn-"
                                  :var/private? true
                                  :var/start 9 :var/end 12
                                  :var/arglists ["[x y]"]}
                                 {:var/qname "z.external/q"
                                  :var/external? true}]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-indents-nested-joins-two-spaces-for-each-level
  (is (= (str "a.b/f\n"
              "  calls:\n"
              "    c.d.e/h\n"
              "      calls: a.b/f")
         (r/render (pull-result
                    {:var/qname "a.b/f"
                     :var/calls [{:var/qname "c.d.e/h"
                                  :var/calls [{:var/qname "a.b/f"}]}]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-writes-the-source-as-an-indented-code-block
  (is (= (str "c.d.e/h\n"
              "  ```clojure\n"
              "  (defn h [x] (a.b/f x))\n"
              "  ```")
         (r/render (pull-result
                    {:var/qname "c.d.e/h"
                     :var/source "(defn h [x] (a.b/f x))"}))))
  (testing "every source line takes the indent, and the joins follow the block"
    (is (= (str "a.b/g\n"
                "  ```clojure\n"
                "  (defn- g\n"
                "    [x y])\n"
                "  ```\n"
                "  calls: z.external/q")
           (r/render (pull-result
                      {:var/qname "a.b/g"
                       :var/source "(defn- g\n  [x y])"
                       :var/calls [{:var/qname "z.external/q"}]}))))))

;; @spec MCP-OP-QUERY-064
(deftest render-of-a-pull-puts-the-source-of-a-nested-entity-two-spaces-deeper
  (is (= (str "a.b/f\n"
              "  calls:\n"
              "    c.d.e/h\n"
              "      ```clojure\n"
              "      (defn h [x] (a.b/f x))\n"
              "      ```")
         (r/render (pull-result
                    {:var/qname "a.b/f"
                     :var/calls [{:var/qname "c.d.e/h"
                                  :var/source "(defn h [x] (a.b/f x))"}]})))))

;; @spec MCP-OP-QUERY-064
(deftest render-joins-the-lines-of-a-vector-of-pull-results
  (is (= "a.b/f\n  calls: a.b/g\na.b/g"
         (r/render [(pull-result {:var/qname "a.b/f"
                                  :var/calls [{:var/qname "a.b/g"}]})
                    (pull-result {:var/qname "a.b/g"})]))))

;; @spec MCP-OP-QUERY-057
(deftest render-publishes-a-string-unchanged
  (is (= "plain text" (r/render "plain text")))
  (is (= "line one\n  line two\n" (r/render "line one\n  line two\n")))
  (is (= "" (r/render "")))
  (is (= "\"quoted\"" (r/render "\"quoted\""))))

;; @spec MCP-OP-QUERY-065
(deftest render-uses-pr-str-for-any-other-value
  (is (= "42" (r/render 42)))
  (is (= "nil" (r/render nil)))
  (is (= ":x" (r/render :x)))
  (is (= "true" (r/render true)))
  (is (= "[]" (r/render [])))
  (is (= "{:a 1}" (r/render {:a 1})))
  (is (= "[1 2 3]" (r/render [1 2 3])))
  (is (= "#{1}" (r/render #{1})))
  (is (= "{:kind :other}" (r/render {:kind :other}))))

;; @spec MCP-OP-QUERY-065
(deftest render-uses-pr-str-for-a-mixed-vector
  (let [mixed [var-f form-f]]
    (is (= (pr-str mixed) (r/render mixed))))
  (let [mixed [var-f "a string"]]
    (is (= (pr-str mixed) (r/render mixed)))))

;; @spec MCP-OP-QUERY-066
(deftest render-gives-the-same-text-for-equal-inputs
  (let [build (fn []
                (with-meta [(assoc var-f :calls ["c.d.e/h" "a.b/g"])
                            var-g]
                  skeleton-meta))
        expected (str "ns a.b — The a.b namespace.\n" line-f "\n" line-g)]
    (is (= expected (r/render (build))))
    (is (= (r/render (build)) (r/render (build))))))

;; @spec MCP-OP-QUERY-066
(deftest render-gives-the-same-text-for-two-calls-on-one-value
  (let [pulled (pull-result {:var/qname "a.b/f"
                             :var/calls [{:var/qname "a.b/g"}]})
        forms [form-f form-h]]
    (is (= (r/render pulled) (r/render pulled)))
    (is (= (r/render forms) (r/render forms)))
    (is (= (r/render fx/all-files) (r/render fx/all-files)))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-gives-the-name-and-the-kind-and-count-of-a-collection
  (is (= "#'user/xs\nvector of 3" (r/render-def "xs" [1 2 3])))
  (is (= "#'user/m\nmap of 2" (r/render-def "m" {:a 1 :b 2})))
  (is (= "#'user/s\nset of 1" (r/render-def "s" #{:a})))
  (is (= "#'user/l\nlist of 4" (r/render-def "l" '(1 2 3 4))))
  (is (= "#'user/e\nvector of 0" (r/render-def "e" []))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-counts-a-lazy-seq
  (is (= "#'user/q\nseq of 5" (r/render-def "q" (map inc (range 5)))))
  (is (= "#'user/q\nseq of 0" (r/render-def "q" (map inc [])))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-counts-a-seq-up-to-10000-items
  (is (= "#'user/q\nseq of 10000" (r/render-def "q" (map inc (range 10000)))))
  (is (= "#'user/q\nseq of 10000+" (r/render-def "q" (map inc (range 10001)))))
  (is (= "#'user/q\nseq of 10000+" (r/render-def "q" (map inc (range 50000))))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-does-not-realize-an-endless-seq
  (is (= "#'user/q\nseq of 10000+" (r/render-def "q" (iterate inc 0)))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-gives-the-length-of-a-string
  (is (= "#'user/t\nstring of 5" (r/render-def "t" "hello")))
  (is (= "#'user/t\nstring of 0" (r/render-def "t" ""))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-gives-the-kind-word-of-a-scalar
  (is (= "#'user/n\nnumber" (r/render-def "n" 42)))
  (is (= "#'user/n\nnumber" (r/render-def "n" 1.5)))
  (is (= "#'user/b\nboolean" (r/render-def "b" true)))
  (is (= "#'user/b\nboolean" (r/render-def "b" false)))
  (is (= "#'user/k\nkeyword" (r/render-def "k" :k)))
  (is (= "#'user/y\nsymbol" (r/render-def "y" 'sym)))
  (is (= "#'user/z\nnil" (r/render-def "z" nil))))

;; @spec MCP-OP-QUERY-074
(deftest render-def-gives-value-for-anything-else
  (is (= "#'user/f\nvalue" (r/render-def "f" (fn [] 1)))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-returns-the-whole-text-when-it-fits
  (is (= {:text "hello" :over-limit? false :chars 5}
         (r/render-bounded "hello" 100)))
  (is (= {:text "hello" :over-limit? false :chars 5}
         (r/render-bounded "hello" 5)))
  (is (= {:text "" :over-limit? false :chars 0}
         (r/render-bounded "" 0))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-cuts-the-text-at-the-limit
  (is (= {:text "hell" :over-limit? true :chars 4}
         (r/render-bounded "hello" 4)))
  (is (= {:text "" :over-limit? true :chars 0}
         (r/render-bounded "hello" 0))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-text-is-a-prefix-of-the-full-rendering
  (let [value (with-meta [var-f var-g] skeleton-meta)
        full (r/render value)
        result (r/render-bounded value 30)]
    (is (= (subs full 0 30) (:text result)))
    (is (= 30 (:chars result)))
    (is (true? (:over-limit? result))))
  (let [value [form-f form-h]
        full (r/render value)]
    (is (= {:text full :over-limit? false :chars (count full)}
           (r/render-bounded value (count full))))
    (is (= {:text (subs full 0 (dec (count full))) :over-limit? true
            :chars (dec (count full))}
           (r/render-bounded value (dec (count full)))))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-stops-on-an-endless-lazy-seq-at-the-limit
  (let [result (r/render-bounded (range) 100)]
    (is (true? (:over-limit? result)))
    (is (= 100 (:chars result)))
    (is (= 100 (count (:text result))))
    (is (str/starts-with? (:text result) "(0 1 2 3 4 5"))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-never-realizes-a-lazy-value-far-past-the-limit
  (let [realized (atom 0)
        endless (letfn [(from [i]
                          (lazy-seq
                           (swap! realized inc)
                           (cons i (from (inc i)))))]
                  (from 0))
        result (r/render-bounded endless 20)]
    (is (true? (:over-limit? result)))
    (is (= 20 (:chars result)))
    (is (= "(0 1 2 3 4 5 6 7 8 9" (:text result)))
    (is (< @realized 100))))

;; --- 097 (decision C17) ---

;; @spec MCP-OP-QUERY-097
(deftest render-of-a-pull-names-the-var-a-cycle-returns-to-and-the-var-that-closes-it
  (is (= (str "a.b/f\n"
              "  calls:\n"
              "    c.d.e/h\n"
              "      calls:\n"
              "        a.b/f cycle: returns to a.b/f (closed by c.d.e/h)")
         (r/render (pull-result
                    {:var/qname "a.b/f"
                     :var/calls [{:var/qname "c.d.e/h"
                                  :var/calls [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}]}]})))))

;; @spec MCP-OP-QUERY-097
(deftest render-of-a-pull-never-writes-a-cycle-map-as-a-plain-id
  (testing "a join of cycle maps only"
    (is (= (str "c/b\n"
                "  calls:\n"
                "    c/b cycle: returns to c/b (closed by c/b)")
           (r/render (pull-result {:var/qname "c/b"
                                   :var/calls [{:var/qname "c/b" :pull/cycle-to "c/b"}]})))))
  (testing "a join that mixes a cycle map with ids"
    (is (= (str "c/a\n"
                "  calls:\n"
                "    c/x\n"
                "    c/y cycle: returns to c/y (closed by c/a)")
           (r/render (pull-result {:var/qname "c/a"
                                   :var/calls [{:var/qname "c/x"}
                                               {:var/qname "c/y" :pull/cycle-to "c/y"}]})))))
  (testing "a reverse join"
    (is (= (str "a.b/f\n"
                "  called by:\n"
                "    c.d.e/h\n"
                "      called by:\n"
                "        a.b/f cycle: returns to a.b/f (closed by c.d.e/h)")
           (r/render (pull-result
                      {:var/qname "a.b/f"
                       :var/_calls [{:var/qname "c.d.e/h"
                                     :var/_calls [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}]}]}))))))

;; --- 085, 118 (issue #10) ---

(defn- counted
  "An unchunked endless lazy seq of `(f i)` that counts the cells it realizes
   in `realized`."
  [realized f]
  (letfn [(from [i]
            (lazy-seq
             (swap! realized inc)
             (cons (f i) (from (inc i)))))]
    (from 0)))

(defn- use-map [i] {:kind :use :file "f" :line i :ns "a" :from "b" :use-kind :call})
(defn- form-at [i] {:kind :form :qname (str "a/f" i) :file "f" :start i :end i :source "(x)"})
(defn- ns-at [i] {:kind :ns :ns (str "n" i) :vars i})

(defn- valve
  "An interrupt fn that fails a test instead of hanging it."
  []
  (let [n (atom 0)]
    (fn [] (when (> (swap! n inc) 2000000)
             (throw (ex-info "valve" {:error-type :valve}))))))

;; @spec MCP-OP-QUERY-085
;; @spec MCP-OP-QUERY-118
(deftest render-bounded-never-realizes-a-sequence-of-any-kind-past-the-limit
  (doseq [[label f wrap] [["use" use-map identity]
                          ["var" (fn [i] (assoc var-f :name (str "f" i))) identity]
                          ["form" form-at identity]
                          ["ns" ns-at identity]
                          ["skeleton" (fn [i] (assoc var-f :name (str "f" i)))
                           #(with-meta % skeleton-meta)]
                          ["pull" (fn [i] (pull-result {:var/qname (str "a/f" i)})) identity]]]
    (let [realized (atom 0)
          limit 100
          result (r/render-bounded (wrap (counted realized f)) limit (valve))]
      (is (true? (:over-limit? result)) label)
      (is (= limit (:chars result)) label)
      (is (<= @realized (inc limit)) (str label " realized " @realized)))))

;; @spec MCP-OP-QUERY-118
(deftest render-bounded-of-a-long-finite-sequence-realizes-limit-plus-one-elements
  (let [realized (atom 0)
        xs (take 4000000 (counted realized use-map))
        result (r/render-bounded xs 500 (valve))]
    (is (true? (:over-limit? result)))
    (is (<= @realized 501))))

;; @spec MCP-OP-QUERY-118
(deftest render-bounded-decides-the-kind-from-the-realized-elements
  (let [realized (atom 0)
        mixed (cons var-f (counted realized (fn [_] "text")))
        result (r/render-bounded mixed 60 (valve))]
    (is (true? (:over-limit? result)))
    (is (str/starts-with? (:text result) "({"))
    (is (<= @realized 61))))

;; @spec MCP-OP-QUERY-085
(deftest render-bounded-text-of-a-streamed-sequence-is-a-prefix-of-the-full-rendering
  (doseq [value [(mapv use-map (range 30))
                 (mapv form-at (range 30))
                 (mapv ns-at (range 30))
                 (with-meta (mapv (fn [i] (assoc var-f :name (str "f" i))) (range 30)) skeleton-meta)
                 (mapv (fn [i] (pull-result {:var/qname (str "a/f" i)})) (range 30))]]
    (let [full (r/render value)]
      (doseq [limit [0 1 17 (dec (count full)) (count full) (inc (count full))]]
        (let [result (r/render-bounded value limit)]
          (is (= (subs full 0 (min limit (count full))) (:text result)))
          (is (= (> (count full) limit) (:over-limit? result))))))))

;; @spec MCP-OP-QUERY-107
(deftest render-bounded-polls-the-interrupt-fn-for-each-element
  (let [calls (atom 0)
        d (try (r/render-bounded (map use-map (range)) 100000
                                 (fn [] (when (> (swap! calls inc) 50)
                                          (throw (ex-info "stop" {:error-type :timeout})))))
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :timeout (:error-type d)))
    (is (= 51 @calls))))

;; @spec MCP-OP-QUERY-107
(deftest render-bounded-stops-on-the-thread-interrupt-flag-when-it-has-no-interrupt-fn
  (let [d (try (.interrupt (Thread/currentThread))
               (r/render-bounded (map use-map (range 1000)) 100000)
               (catch clojure.lang.ExceptionInfo e (ex-data e))
               (finally (Thread/interrupted)))]
    (is (= :timeout (:error-type d)))))
