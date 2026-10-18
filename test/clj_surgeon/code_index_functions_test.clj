(ns clj-surgeon.code-index-functions-test
  "Pure witnesses for the six bound query functions and `pull`, run over an
   index built from the literal fixtures with `code-index/apply-ingest`.

   Contract: docs/intent/code-index/code-index-api.md."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index :as code-index]
   [clj-surgeon.code-index-fixtures :as fx]
   [clj-surgeon.code-index-functions :as fns]
   [clj-surgeon.code-index-pull :as pull]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (clojure.lang ExceptionInfo)))

(def idx
  (code-index/apply-ingest code-index/empty-index {:upsert fx/all-files}))

(def empty-idx code-index/empty-index)

(defn- thrown-data
  "Ex-data of the ExceptionInfo that (f) throws, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch ExceptionInfo e (ex-data e))))

(defn- plain-data?
  "True when every node of x is a map, vector, string, number, boolean,
   keyword or nil."
  [x]
  (cond
    (nil? x) true
    (or (string? x) (number? x) (boolean? x) (keyword? x)) true
    (map? x) (and (instance? clojure.lang.IPersistentMap x)
                  (every? (fn [[k v]] (and (plain-data? k) (plain-data? v))) x))
    (vector? x) (every? plain-data? x)
    :else false))

(defn- help-blocks [] (str/split (fns/help) #"\n\n"))

;; --- 036 ---

;; @spec MCP-OP-QUERY-036
(deftest bound-symbols-are-exactly-the-six-functions-in-order
  (is (= '[namespaces skeleton form refs pull help] (fns/bound-symbols))))

;; @spec MCP-OP-QUERY-036
(deftest bindings-hold-exactly-the-six-bound-symbols-as-functions
  (let [b (fns/bindings idx (atom #{}))]
    (is (= (set (fns/bound-symbols)) (set (keys b))))
    (is (every? ifn? (vals b)))))

;; --- 037 ---

;; @spec MCP-OP-QUERY-037
(deftest every-function-returns-plain-data-only
  (testing "walk each result"
    (is (plain-data? (fns/namespaces idx)))
    (is (plain-data? (fns/skeleton idx 'a.b)))
    (is (plain-data? (fns/form idx ['a.b/f "c.d.e/h"])))
    (is (plain-data? (fns/refs idx 'a.b/f)))
    (is (plain-data? (fns/pull idx [:var/qname {:var/calls 3} {:var/in-ns [:ns/name :ns/doc]}]
                               [:var/qname "a.b/f"])))
    (is (plain-data? (fns/help))))
  (testing "no result is the index or a var record of it"
    (is (not-any? #(identical? % idx) (fns/namespaces idx)))
    (let [records (set (vals (:vars idx)))]
      (is (not-any? #(contains? records %) (fns/skeleton idx 'a.b)))
      (is (not-any? #(contains? records %) (fns/form idx ['a.b/f]))))))

;; @spec MCP-OP-QUERY-037
(deftest bound-functions-leave-the-index-unchanged
  (let [before idx]
    (fns/namespaces idx)
    (fns/skeleton idx 'a.b)
    (fns/pull idx [:var/qname {:var/calls 3}] [:var/qname "a.b/f"])
    (is (= before idx))))

;; --- 038 ---

;; @spec MCP-OP-QUERY-038
(deftest namespaces-returns-one-sorted-map-for-each-namespace
  (is (= [{:kind :ns :ns "a.b" :file "src/a/b.clj" :vars 2 :doc "The a.b namespace."}
          {:kind :ns :ns "a.b-test" :file "test/a/b_test.clj" :vars 1}
          {:kind :ns :ns "c.d.e" :file "src/c/d/e.clj" :vars 1}]
         (fns/namespaces idx))))

;; @spec MCP-OP-QUERY-038
(deftest namespaces-of-the-empty-index-is-an-empty-vector
  (is (= [] (fns/namespaces empty-idx))))

;; --- 039, 040 ---

;; @spec MCP-OP-QUERY-039
;; @spec MCP-OP-QUERY-040
(deftest skeleton-returns-exact-var-maps-in-start-order-with-metadata
  (let [sk (fns/skeleton idx 'a.b)]
    (is (vector? sk))
    (is (= [{:kind :var :qname "a.b/f" :ns "a.b" :name "f" :file "src/a/b.clj"
             :start 4 :end 7 :arglists ["[x]" "[x y]"] :private? false
             :calls ["a.b/g" "c.d.e/h"] :fx ["swap!"] :throws? false
             :intent "Does f."}
            {:kind :var :qname "a.b/g" :ns "a.b" :name "g" :file "src/a/b.clj"
             :start 9 :end 12 :arglists ["[x y]"] :private? true
             :calls ["z.external/q"] :fx [] :throws? true}]
           sk))
    (is (= {:kind :skeleton :ns "a.b" :doc "The a.b namespace."} (meta sk)))))

;; @spec MCP-OP-QUERY-039
(deftest skeleton-accepts-a-string-and-leaves-the-doc-out-when-there-is-none
  (let [sk (fns/skeleton idx "a.b-test")]
    (is (= [{:kind :var :qname "a.b-test/t" :ns "a.b-test" :name "t"
             :file "test/a/b_test.clj" :start 4 :end 4 :arglists ["[]"]
             :private? false :calls ["a.b/f"] :fx [] :throws? false}]
           sk))
    (is (= {:kind :skeleton :ns "a.b-test"} (meta sk)))))

;; @spec MCP-OP-QUERY-040
(deftest skeleton-var-map-has-no-source-and-no-doc-keys
  (is (= #{:kind :qname :ns :name :file :start :end :arglists :private? :calls :fx
           :throws? :intent}
         (set (keys (first (fns/skeleton idx 'a.b))))))
  (is (= #{:kind :qname :ns :name :file :start :end :arglists :private? :calls :fx
           :throws?}
         (set (keys (second (fns/skeleton idx 'a.b)))))))

;; @spec MCP-OP-QUERY-041
(deftest skeleton-carries-the-fx-names-of-the-var-record
  (is (= ["swap!"] (:fx (first (fns/skeleton idx 'a.b))))))

;; --- 042 ---

;; @spec MCP-OP-QUERY-042
(deftest form-returns-one-map-for-a-single-symbol
  (is (= [{:kind :form :qname "c.d.e/h" :file "src/c/d/e.clj" :start 3 :end 3
           :source "(defn h [x] (a.b/f x))"}]
         (fns/form idx 'c.d.e/h))))

;; @spec MCP-OP-QUERY-042
(deftest form-returns-maps-in-the-order-given-for-symbols-and-strings
  (let [r (fns/form idx ["c.d.e/h" 'a.b/g])]
    (is (= ["c.d.e/h" "a.b/g"] (mapv :qname r)))
    (is (= {:kind :form :qname "a.b/g" :file "src/a/b.clj" :start 9 :end 12
            :source (:source (get (:vars idx) "a.b/g"))}
           (second r)))
    (is (= "(defn- g\n  [x y]\n  (when (nil? x) (throw (ex-info \"bad\" {:scope y :x/k 1})))\n  (z.external/q x))"
           (:source (second r))))))

;; @spec MCP-OP-QUERY-042
(deftest form-refuses-an-unqualified-symbol-or-a-non-symbol-element
  (is (= :invalid-expression (:error-type (thrown-data #(fns/form idx 'f)))))
  (is (= :invalid-expression (:error-type (thrown-data #(fns/form idx 5)))))
  (is (= :invalid-expression (:error-type (thrown-data #(fns/form idx ['a.b/f 5]))))))

;; --- 043 ---

;; @spec MCP-OP-QUERY-043
(deftest refs-returns-sorted-use-maps-with-all-use-kinds
  (is (= [{:kind :use :file "src/c/d/e.clj" :line 3 :ns "c.d.e" :from "h" :use-kind :call}
          {:kind :use :file "test/a/b_test.clj" :line 4 :ns "a.b-test" :from "t"
           :use-kind :alias-call}
          {:kind :use :file "test/a/b_test.clj" :line 4 :ns "a.b-test" :from "t"
           :use-kind :value}]
         (fns/refs idx 'a.b/f))))

;; @spec MCP-OP-QUERY-043
(deftest refs-accepts-a-string-and-sorts-by-line
  (is (= [{:kind :use :file "src/a/b.clj" :line 6 :ns "a.b" :from "f" :use-kind :call}
          {:kind :use :file "src/a/b.clj" :line 7 :ns "a.b" :from "f" :use-kind :call}]
         (fns/refs idx "a.b/g"))))

;; @spec MCP-OP-QUERY-043
;; @spec MCP-OP-QUERY-055
(deftest refs-of-an-external-var-works
  (is (= [{:kind :use :file "src/a/b.clj" :line 12 :ns "a.b" :from "g" :use-kind :call}]
         (fns/refs idx 'z.external/q))))

;; --- 044 ---

;; @spec MCP-OP-QUERY-044
(deftest refs-with-include-tests-false-leaves-out-test-directory-uses
  (is (= [{:kind :use :file "src/c/d/e.clj" :line 3 :ns "c.d.e" :from "h" :use-kind :call}]
         (fns/refs idx 'a.b/f {:include-tests false}))))

;; @spec MCP-OP-QUERY-044
(deftest refs-with-include-tests-true-or-unknown-opts-keeps-all-uses
  (let [all (fns/refs idx 'a.b/f)]
    (is (= 3 (count all)))
    (is (= all (fns/refs idx 'a.b/f {:include-tests true})))
    (is (= all (fns/refs idx 'a.b/f {})))
    (is (= all (fns/refs idx 'a.b/f {:other 1})))))

;; @spec MCP-OP-QUERY-044
(deftest refs-refuses-a-non-map-opts
  (is (= :invalid-expression (:error-type (thrown-data #(fns/refs idx 'a.b/f true)))))
  (is (= :invalid-expression (:error-type (thrown-data #(fns/refs idx 'a.b/f [:include-tests false]))))))

;; --- 046 ---

;; @spec MCP-OP-QUERY-046
(deftest help-lists-six-blocks-in-bound-symbol-order
  (let [blocks (help-blocks)]
    (is (= 6 (count blocks)))
    (is (= (map str (fns/bound-symbols))
           (map #(second (re-find #"^\((\S+)" %)) blocks)))
    (doseq [b blocks]
      (is (str/starts-with? b "("))
      (is (str/starts-with? (last (str/split-lines b)) "  Example: ")))))

;; @spec MCP-OP-QUERY-046
(deftest help-with-a-name-returns-that-block-only
  (let [blocks (help-blocks)
        form-block (nth blocks 2)]
    (is (= form-block (fns/help 'form)))
    (is (= form-block (fns/help "form")))
    (is (= (nth blocks 4) (fns/help 'pull)))))

;; @spec MCP-OP-QUERY-046
(deftest help-of-an-unknown-name-returns-a-string-without-throwing
  (is (= "no such function: bogus. Functions: namespaces, skeleton, form, refs, pull, help"
         (fns/help 'bogus))))

;; --- 047 ---

;; @spec MCP-OP-QUERY-047
(deftest pull-accepts-the-three-eid-forms
  (is (= {:var/qname "a.b/f"} (pull/pull idx [:var/qname] [:var/qname "a.b/f"])))
  (is (= {:ns/name "a.b"} (pull/pull idx [:ns/name] [:ns/name "a.b"])))
  (is (= {:kw/name "scope"} (pull/pull idx [:kw/name] [:kw/name "scope"])))
  (testing "a symbol value is converted to its string"
    (is (= {:var/qname "a.b/f"} (pull/pull idx [:var/qname] [:var/qname 'a.b/f])))
    (is (= {:ns/name "a.b"} (pull/pull idx [:ns/name] [:ns/name 'a.b])))))

;; @spec MCP-OP-QUERY-047
(deftest pull-refuses-every-other-eid-form
  (doseq [bad ["a.b/f" nil 5 [] [:var/qname] [:var/qname "a.b/f" "x"]
               [:foo/bar "x"] [:var/qname 5] [:var/qname nil] {:var/qname "a.b/f"}
               '(:var/qname "a.b/f")]]
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx [:var/qname] bad))))
        (pr-str bad))))

;; @spec MCP-OP-QUERY-047
(deftest pull-refuses-a-bad-pattern
  (testing "pattern is not a vector"
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx '(:var/qname) [:var/qname "a.b/f"]))))))
  (testing "wildcard"
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx '[*] [:var/qname "a.b/f"]))))))
  (testing "element is neither keyword nor join map"
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx ["x"] [:var/qname "a.b/f"])))))
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx [5] [:var/qname "a.b/f"]))))))
  (testing "join map with two keys"
    (is (= :invalid-expression
           (:error-type
            (thrown-data #(pull/pull idx [{:var/calls [:var/qname] :var/_calls [:var/qname]}]
                                     [:var/qname "a.b/f"])))))))

;; --- 048 ---

;; @spec MCP-OP-QUERY-048
(deftest pull-returns-the-attributes-of-a-var-and-marks-the-result-as-pull
  (let [r (pull/pull idx [:var/qname :var/name :var/ns :var/file :var/start :var/end
                          :var/kind :var/private? :var/arglists :var/doc :var/intent
                          :var/source :var/throws? :var/fx]
                     [:var/qname "a.b/f"])]
    (is (= {:var/qname "a.b/f" :var/name "f" :var/ns "a.b" :var/file "src/a/b.clj"
            :var/start 4 :var/end 7 :var/kind "clojure.core/defn" :var/private? false
            :var/arglists ["[x]" "[x y]"] :var/doc "Does f. Then more."
            :var/intent "Does f."
            :var/source (:source (first (fns/form idx 'a.b/f)))
            :var/throws? false :var/fx ["swap!"]}
           r))
    (is (= {:kind :pull} (meta r)))))

;; @spec MCP-OP-QUERY-048
(deftest pull-leaves-out-absent-attributes-and-keeps-false
  (testing "a.b/g has no doc and no intent"
    (is (= {:var/qname "a.b/g" :var/private? true :var/throws? true
            :var/kind "clojure.core/defn-"}
           (pull/pull idx [:var/qname :var/doc :var/intent :var/private? :var/throws? :var/kind]
                      [:var/qname "a.b/g"]))))
  (testing "false is a value and stays"
    (is (= {:var/private? false :var/throws? false}
           (pull/pull idx [:var/private? :var/throws?] [:var/qname "a.b/f"]))))
  (testing "attributes of another entity type are left out without a refusal"
    (is (= {:var/qname "a.b/f"}
           (pull/pull idx [:var/qname :ns/name :kw/name :ns/doc] [:var/qname "a.b/f"])))
    (is (= {:ns/name "a.b"}
           (pull/pull idx [:ns/name :var/qname] [:ns/name "a.b"])))))

;; @spec MCP-OP-QUERY-048
(deftest pull-returns-the-attributes-of-a-namespace
  (is (= {:ns/name "a.b" :ns/doc "The a.b namespace." :ns/file "src/a/b.clj"}
         (pull/pull idx [:ns/name :ns/doc :ns/file] [:ns/name "a.b"])))
  (is (= {:ns/name "c.d.e" :ns/file "src/c/d/e.clj"}
         (pull/pull idx [:ns/name :ns/doc :ns/file] [:ns/name "c.d.e"]))))

;; @spec MCP-OP-QUERY-048
(deftest pull-of-a-keyword-nobody-uses-is-not-an-error
  (is (= {:kw/name "nope"}
         (pull/pull idx [:kw/name {:var/_uses-kw [:var/qname]}] [:kw/name "nope"]))))

;; @spec MCP-OP-QUERY-048
(deftest pull-result-is-plain-data
  (is (plain-data? (pull/pull idx [:var/qname {:var/_calls 2} {:var/uses-kw [:kw/name]}]
                              [:var/qname "a.b/f"]))))

;; --- 049 ---

;; @spec MCP-OP-QUERY-049
(deftest pull-calls-join-returns-callees-sorted-by-qname
  (is (= {:var/qname "a.b/f"
          :var/calls [{:var/qname "a.b/g" :var/private? true}
                      {:var/qname "c.d.e/h" :var/private? false}]}
         (pull/pull idx [:var/qname {:var/calls [:var/qname :var/private?]}]
                    [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-049
(deftest pull-reverse-calls-join-returns-callers-sorted-by-qname
  (is (= {:var/qname "a.b/f"
          :var/_calls [{:var/qname "a.b-test/t"} {:var/qname "c.d.e/h"}]}
         (pull/pull idx [:var/qname {:var/_calls [:var/qname]}] [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-049
(deftest pull-keyword-joins-return-keyword-entities
  (is (= {:var/qname "a.b/g" :var/uses-kw [{:kw/name "scope"} {:kw/name "x/k"}]}
         (pull/pull idx [:var/qname {:var/uses-kw [:kw/name]}] [:var/qname "a.b/g"])))
  (is (= {:kw/name "scope" :var/_uses-kw [{:var/qname "a.b/f"} {:var/qname "a.b/g"}]}
         (pull/pull idx [:kw/name {:var/_uses-kw [:var/qname]}] [:kw/name "scope"])))
  (testing "an empty join is left out"
    (is (= {:var/qname "a.b/f"}
           (pull/pull idx [:var/qname {:var/reads-kw [:kw/name]}] [:var/qname "a.b/f"])))))

;; @spec MCP-OP-QUERY-049
(deftest pull-in-ns-join-is-a-vector-of-one-namespace
  (is (= {:var/qname "c.d.e/h" :var/in-ns [{:ns/name "c.d.e" :ns/file "src/c/d/e.clj"}]}
         (pull/pull idx [:var/qname {:var/in-ns [:ns/name :ns/file]}]
                    [:var/qname "c.d.e/h"]))))

;; @spec MCP-OP-QUERY-049
(deftest pull-namespace-joins-return-its-vars-in-source-order
  (is (= {:ns/name "a.b" :ns/_in-ns [{:var/qname "a.b/f"} {:var/qname "a.b/g"}]}
         (pull/pull idx [:ns/name {:ns/_in-ns [:var/qname]}] [:ns/name "a.b"])))
  (is (= {:ns/name "a.b" :var/_in-ns [{:var/qname "a.b/f" :var/start 4}
                                      {:var/qname "a.b/g" :var/start 9}]}
         (pull/pull idx [:ns/name {:var/_in-ns [:var/qname :var/start]}] [:ns/name "a.b"]))))

;; @spec MCP-OP-QUERY-049
(deftest pull-leaves-out-a-join-that-does-not-apply-to-the-entity-type
  (is (= {:ns/name "a.b"}
         (pull/pull idx [:ns/name {:var/calls [:var/qname]}] [:ns/name "a.b"])))
  (is (= {:var/qname "a.b/f"}
         (pull/pull idx [:var/qname {:ns/_in-ns [:var/qname]}] [:var/qname "a.b/f"]))))

;; --- 050 ---

;; @spec MCP-OP-QUERY-050
(deftest pull-numeric-recursion-follows-n-hops-and-stops-at-zero
  (testing "one hop: the joined entities have no further calls"
    (is (= {:var/qname "a.b/f"
            :var/calls [{:var/qname "a.b/g"} {:var/qname "c.d.e/h"}]}
           (pull/pull idx [:var/qname {:var/calls 1}] [:var/qname "a.b/f"]))))
  (testing "two hops, parent pattern applies at each level"
    (is (= {:var/qname "a.b/g" :var/private? true
            :var/calls [{:var/qname "z.external/q" :var/external? true}]}
           (pull/pull idx [:var/qname :var/private? {:var/calls 2}] [:var/qname "a.b/g"])))))

;; @spec MCP-OP-QUERY-050
(deftest pull-numeric-recursion-works-on-reverse-joins
  (is (= {:var/qname "a.b/f"
          :var/_calls [{:var/qname "a.b-test/t"}
                       {:var/qname "c.d.e/h" :var/_calls [{:var/qname "a.b/f"}]}]}
         (pull/pull idx [:var/qname {:var/_calls 2}] [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-050
(deftest pull-refuses-a-bad-recursion-number
  (doseq [n [0 -1 1.5 "2" :x nil]]
    (is (= :invalid-expression
           (:error-type (thrown-data #(pull/pull idx [:var/qname {:var/calls n}]
                                                 [:var/qname "a.b/f"]))))
        (pr-str n))))

;; --- 051 ---

;; @spec MCP-OP-QUERY-051
(deftest pull-accepts-the-maximum-depth-of-three
  (is (= :ok (try (pull/pull idx [:var/qname {:var/calls 3}] [:var/qname "a.b/f"]) :ok
                  (catch ExceptionInfo _ :refused)))))

;; @spec MCP-OP-QUERY-051
(deftest pull-refuses-a-depth-over-three-with-pull-too-deep
  (let [pattern [:var/qname {:var/calls 4}]
        eid [:var/qname "a.b/f"]
        d (thrown-data #(pull/pull idx pattern eid))]
    (is (= :pull-too-deep (:error-type d)))
    (is (= 4 (:depth d)))
    (is (= pattern (:pattern d)))
    (is (= eid (:eid d)))))

;; @spec MCP-OP-QUERY-051
(deftest pull-refuses-the-ellipsis-with-pull-too-deep
  (let [pattern '[:var/qname {:var/calls ...}]
        eid [:var/qname "a.b/f"]
        d (thrown-data #(pull/pull idx pattern eid))]
    (is (= :pull-too-deep (:error-type d)))
    (is (= "..." (str (:depth d))))
    (is (= pattern (:pattern d)))
    (is (= eid (:eid d)))))

;; --- 052 ---

;; @spec MCP-OP-QUERY-052
(deftest pull-cuts-a-cycle-at-the-var-already-on-the-path
  (is (= {:var/qname "a.b/f"
          :var/calls [{:var/qname "a.b/g"
                       :var/calls [{:var/qname "z.external/q" :var/external? true}]}
                      {:var/qname "c.d.e/h"
                       :var/calls [{:var/qname "a.b/f"}]}]}
         (pull/pull idx [:var/qname {:var/calls 3}] [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-052
(deftest pull-cycle-cut-returns-the-id-map-only-even-with-more-attributes
  (let [r (pull/pull idx [:var/qname :var/private? {:var/calls 2}] [:var/qname "a.b/f"])
        h (second (:var/calls r))]
    (is (= "c.d.e/h" (:var/qname h)))
    (is (= [{:var/qname "a.b/f"}] (:var/calls h)))))

;; @spec MCP-OP-QUERY-052
(deftest pull-cut-applies-to-recursive-joins-only
  (testing "a plain join back to the root is fully expanded"
    (is (= {:var/qname "a.b/f"
            :var/calls [{:var/qname "a.b/g"
                         :var/calls [{:var/qname "z.external/q" :var/external? true}]}
                        {:var/qname "c.d.e/h" :var/calls [{:var/qname "a.b/f" :var/private? false}]}]}
           (pull/pull idx [:var/qname {:var/calls [:var/qname {:var/calls [:var/qname :var/private?]}]}]
                      [:var/qname "a.b/f"])))))

;; --- 053 ---

;; @spec MCP-OP-QUERY-053
(deftest pull-returns-an-external-callee-as-id-and-external-flag
  (is (= {:var/qname "a.b/g" :var/private? true
          :var/calls [{:var/qname "z.external/q" :var/external? true}]}
         (pull/pull idx [:var/qname :var/private? {:var/calls [:var/qname]}]
                    [:var/qname "a.b/g"]))))

;; @spec MCP-OP-QUERY-053
(deftest pull-external-callee-keeps-its-id-and-flag-whatever-the-sub-pattern
  (is (= {:var/calls [{:var/name "q" :var/qname "z.external/q" :var/external? true}]}
         (pull/pull idx [{:var/calls [:var/name]}] [:var/qname "a.b/g"]))))

;; @spec MCP-OP-QUERY-053
(deftest pull-of-an-external-var-has-name-ns-and-callers-only
  (is (= {:var/qname "z.external/q" :var/name "q" :var/ns "z.external"
          :var/external? true :var/_calls [{:var/qname "a.b/g"}]}
         (pull/pull idx [:var/qname :var/name :var/ns :var/external? :var/file :var/kind
                         {:var/_calls [:var/qname]}]
                    [:var/qname "z.external/q"]))))

;; @spec MCP-OP-QUERY-053
(deftest pull-flags-no-external-on-a-var-with-a-record
  (is (= {:var/qname "a.b/f"}
         (pull/pull idx [:var/qname :var/external?] [:var/qname "a.b/f"]))))

;; --- 054 ---

;; @spec MCP-OP-QUERY-054
(deftest skeleton-of-an-unknown-namespace-names-the-nearest-ones
  (let [d (thrown-data #(fns/skeleton idx 'a.c))]
    (is (= :unknown-namespace (:error-type d)))
    (is (= "a.c" (:name d)))
    (is (= ["a.b" "c.d.e" "a.b-test"] (:nearest d)))))

;; @spec MCP-OP-QUERY-054
(deftest unknown-namespace-nearest-is-empty-for-an-empty-index
  (let [d (thrown-data #(fns/skeleton empty-idx 'x.y))]
    (is (= :unknown-namespace (:error-type d)))
    (is (= [] (:nearest d)))))

;; @spec MCP-OP-QUERY-054
(deftest pull-of-an-unknown-namespace-throws-unknown-namespace
  (let [d (thrown-data #(pull/pull idx [:ns/name] [:ns/name "a.c"]))]
    (is (= :unknown-namespace (:error-type d)))
    (is (= "a.c" (:name d)))
    (is (= ["a.b" "c.d.e" "a.b-test"] (:nearest d)))))

;; @spec MCP-OP-QUERY-054
(deftest unknown-namespace-nearest-holds-at-most-five-names
  (let [many (code-index/apply-ingest
              empty-idx
              {:upsert (for [i (range 8)]
                         {:path (str "src/n" i ".clj") :mtime 1 :size 1
                          :sha256 (apply str (repeat 64 "d")) :ns (str "n" i)
                          :vars [] :uses []})})
        d (thrown-data #(fns/skeleton many 'zz))]
    (is (= 5 (count (:nearest d))))
    (is (= ["n0" "n1" "n2" "n3" "n4"] (:nearest d)))))

;; --- 055 ---

;; @spec MCP-OP-QUERY-055
(deftest form-of-an-unknown-var-throws-unknown-var-with-namespace-info
  (let [d (thrown-data #(fns/form idx 'a.b/zzz))]
    (is (= :unknown-var (:error-type d)))
    (is (= "a.b/zzz" (:qname d)))
    (is (= "a.b" (:ns d)))
    (is (= true (:ns-known? d)))))

;; @spec MCP-OP-QUERY-055
(deftest form-of-an-unknown-namespace-var-says-the-namespace-is-not-known
  (let [d (thrown-data #(fns/form idx 'q.r/zzz))]
    (is (= :unknown-var (:error-type d)))
    (is (= "q.r" (:ns d)))
    (is (not (:ns-known? d)))))

;; @spec MCP-OP-QUERY-055
(deftest form-treats-an-external-var-as-unknown
  (is (= :unknown-var (:error-type (thrown-data #(fns/form idx 'z.external/q))))))

;; @spec MCP-OP-QUERY-055
(deftest refs-of-an-unknown-var-throws-unknown-var
  (let [d (thrown-data #(fns/refs idx 'a.b/zzz))]
    (is (= :unknown-var (:error-type d)))
    (is (= "a.b/zzz" (:qname d)))
    (is (= true (:ns-known? d)))))

;; @spec MCP-OP-QUERY-055
(deftest pull-of-an-unknown-var-throws-unknown-var
  (let [d (thrown-data #(pull/pull idx [:var/qname] [:var/qname "a.b/zzz"]))]
    (is (= :unknown-var (:error-type d)))
    (is (= "a.b/zzz" (:qname d)))
    (is (= "a.b" (:ns d)))
    (is (= true (:ns-known? d)))))

;; --- touched files (bindings seam, spec 033 is witnessed elsewhere) ---

;; @spec MCP-OP-QUERY-036
(deftest bindings-record-the-files-of-the-data-each-function-returns
  (let [touch (fn [sym & args]
                (let [t (atom #{})]
                  (apply (get (fns/bindings idx t) sym) args)
                  @t))]
    (is (= #{"src/a/b.clj"} (touch 'skeleton 'a.b)))
    (is (= #{"src/a/b.clj" "src/c/d/e.clj" "test/a/b_test.clj"} (touch 'namespaces)))
    (is (= #{"src/c/d/e.clj"} (touch 'form 'c.d.e/h)))
    (is (= #{"src/c/d/e.clj" "test/a/b_test.clj"} (touch 'refs 'a.b/f)))
    (is (= #{"src/a/b.clj" "src/c/d/e.clj"}
           (touch 'pull [:var/qname {:var/calls 2}] [:var/qname "a.b/f"])))
    (is (= #{} (touch 'help)))))
