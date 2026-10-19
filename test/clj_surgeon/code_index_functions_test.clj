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
(deftest bound-symbols-are-exactly-the-seven-functions-in-order
  (is (= '[search namespaces skeleton form refs pull help] (fns/bound-symbols))))

;; @spec MCP-OP-QUERY-036
(deftest bindings-hold-exactly-the-seven-bound-symbols-as-functions
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
    (let [records (set (mapcat identity (vals (:vars idx))))]
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
    (is (= [{:kind :var :qname "a.b/f" :ns "a.b" :name "f" :platform :clj :file "src/a/b.clj"
             :start 4 :end 7 :arglists ["[x]" "[x y]"] :private? false
             :calls ["a.b/g" "c.d.e/h"] :fx ["swap!"] :throws? false
             :intent "Does f."}
            {:kind :var :qname "a.b/g" :ns "a.b" :name "g" :platform :clj :file "src/a/b.clj"
             :start 9 :end 12 :arglists ["[x y]"] :private? true
             :calls ["z.external/q"] :fx [] :throws? true}]
           sk))
    (is (= {:kind :skeleton :ns "a.b" :doc "The a.b namespace."} (meta sk)))))

;; @spec MCP-OP-QUERY-039
(deftest skeleton-accepts-a-string-and-leaves-the-doc-out-when-there-is-none
  (let [sk (fns/skeleton idx "a.b-test")]
    (is (= [{:kind :var :qname "a.b-test/t" :ns "a.b-test" :name "t" :platform :clj
             :file "test/a/b_test.clj" :start 4 :end 4 :arglists ["[]"]
             :private? false :calls ["a.b/f"] :fx [] :throws? false}]
           sk))
    (is (= {:kind :skeleton :ns "a.b-test"} (meta sk)))))

;; @spec MCP-OP-QUERY-040
(deftest skeleton-var-map-has-no-source-and-no-doc-keys
  (is (= #{:kind :qname :ns :name :platform :file :start :end :arglists :private? :calls :fx
           :throws? :intent}
         (set (keys (first (fns/skeleton idx 'a.b))))))
  (is (= #{:kind :qname :ns :name :platform :file :start :end :arglists :private? :calls :fx
           :throws?}
         (set (keys (second (fns/skeleton idx 'a.b)))))))

;; @spec MCP-OP-QUERY-041
(deftest skeleton-carries-the-fx-names-of-the-var-record
  (is (= ["swap!"] (:fx (first (fns/skeleton idx 'a.b))))))

;; --- 042 ---

;; @spec MCP-OP-QUERY-042
(deftest form-returns-one-map-for-a-single-symbol
  (is (= [{:kind :form :qname "c.d.e/h" :platform :clj :file "src/c/d/e.clj" :start 3 :end 3
           :source "(defn h [x] (a.b/f x))"}]
         (fns/form idx 'c.d.e/h))))

;; @spec MCP-OP-QUERY-042
(deftest form-returns-maps-in-the-order-given-for-symbols-and-strings
  (let [r (fns/form idx ["c.d.e/h" 'a.b/g])]
    (is (= ["c.d.e/h" "a.b/g"] (mapv :qname r)))
    (is (= {:kind :form :qname "a.b/g" :platform :clj :file "src/a/b.clj" :start 9 :end 12
            :source (:source (first (get (:vars idx) "a.b/g")))}
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
(deftest help-lists-seven-blocks-in-bound-symbol-order
  (let [blocks (help-blocks)]
    (is (= 7 (count blocks)))
    (is (= (map str (fns/bound-symbols))
           (map #(second (re-find #"^\((\S+)" %)) blocks)))
    (doseq [b blocks]
      (is (str/starts-with? b "("))
      (is (str/starts-with? (last (str/split-lines b)) "  Example: ")))))

;; @spec MCP-OP-QUERY-046
(deftest help-with-a-name-returns-that-block-only
  (let [blocks (help-blocks)
        form-block (nth blocks 3)]
    (is (= form-block (fns/help 'form)))
    (is (= form-block (fns/help "form")))
    (is (= (nth blocks 5) (fns/help 'pull)))))

;; @spec MCP-OP-QUERY-046
(deftest help-of-an-unknown-name-returns-a-string-without-throwing
  (is (= "no such function: bogus. Functions: search, namespaces, skeleton, form, refs, pull, help"
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
                       {:var/qname "c.d.e/h"
                        :var/_calls [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}]}]}
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
                       :var/calls [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}]}]}
         (pull/pull idx [:var/qname {:var/calls 3}] [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-052
(deftest pull-cycle-cut-returns-the-id-map-only-even-with-more-attributes
  (let [r (pull/pull idx [:var/qname :var/private? {:var/calls 2}] [:var/qname "a.b/f"])
        h (second (:var/calls r))]
    (is (= "c.d.e/h" (:var/qname h)))
    (is (= [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}] (:var/calls h)))))

;; @spec MCP-OP-QUERY-052
;; @spec MCP-OP-QUERY-096
(deftest pull-cuts-a-cycle-through-an-explicit-join-too
  ;; Changed in fix round 1 (issue #8): the path check applies to every join.
  (is (= {:var/qname "a.b/f"
          :var/calls [{:var/qname "a.b/g"
                       :var/calls [{:var/qname "z.external/q" :var/external? true}]}
                      {:var/qname "c.d.e/h"
                       :var/calls [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}]}]}
         (pull/pull idx [:var/qname {:var/calls [:var/qname {:var/calls [:var/qname :var/private?]}]}]
                    [:var/qname "a.b/f"]))))

;; @spec MCP-OP-QUERY-052
;; @spec MCP-OP-QUERY-096
(deftest pull-marks-a-cycle-with-the-var-it-returns-to
  (testing "the map holds the id and the cycle key, whatever the sub-pattern asks"
    (let [r (pull/pull idx [:var/qname :var/private? {:var/calls [:var/name :var/private?
                                                                 {:var/calls [:var/name :var/private?]}]}]
                       [:var/qname "a.b/f"])
          h (second (:var/calls r))]
      (is (= [{:var/qname "a.b/f" :pull/cycle-to "a.b/f"}] (:var/calls h)))))
  (testing "a var that calls itself closes a cycle on the root"
    (let [self {:vars {"s/loop" {:qname "s/loop" :calls ["s/loop"]}}
                :namespaces {} :callers {} :uses-by-target {}}]
      (is (= {:var/qname "s/loop"
              :var/calls [{:var/qname "s/loop" :pull/cycle-to "s/loop"}]}
             (pull/pull self [:var/qname {:var/calls [:var/qname]}] [:var/qname "s/loop"])))))
  (testing "a cycle that does not pass the root names the var it returns to"
    (let [g {:vars {"c/a" {:qname "c/a" :calls ["c/b"]}
                    "c/b" {:qname "c/b" :calls ["c/c"]}
                    "c/c" {:qname "c/c" :calls ["c/b"]}}
             :namespaces {} :callers {} :uses-by-target {}}]
      (is (= {:var/qname "c/a"
              :var/calls [{:var/qname "c/b"
                           :var/calls [{:var/qname "c/c"
                                        :var/calls [{:var/qname "c/b" :pull/cycle-to "c/b"}]}]}]}
             (pull/pull g [:var/qname {:var/calls [:var/qname {:var/calls [:var/qname {:var/calls [:var/qname]}]}]}]
                        [:var/qname "c/a"])))
      (is (= {:var/qname "c/a"
              :var/calls [{:var/qname "c/b"
                           :var/calls [{:var/qname "c/c"
                                        :var/calls [{:var/qname "c/b" :pull/cycle-to "c/b"}]}]}]}
             (pull/pull g [:var/qname {:var/calls 3}] [:var/qname "c/a"]))))))

;; --- 111 and 112 (issue #8) ---

(defn- nest
  "A pull pattern with `n` explicit joins, one inside the other."
  [n]
  (reduce (fn [inner _] [:var/qname {:var/calls inner}]) [:var/qname] (range n)))

(defn- depth-of
  [pattern]
  ((requiring-resolve 'clj-surgeon.code-index-pull/pattern-depth) pattern))

(defn- layered-index
  "An index of `layers` layers of `width` vars. Each var calls all vars of the
   next layer. With `wrap?` the last layer calls the first one."
  [layers width wrap?]
  (let [q (fn [l i] (str "g/n" l "_" i))
        callees (fn [l] (cond
                          (< l (dec layers)) (mapv #(q (inc l) %) (range width))
                          wrap? (mapv #(q 0 %) (range width))
                          :else []))]
    {:vars (into {} (for [l (range layers) i (range width)]
                      [(q l i) {:qname (q l i) :calls (callees l)}]))
     :namespaces {} :callers {} :uses-by-target {}}))

;; @spec MCP-OP-QUERY-111
(deftest pattern-depth-counts-explicit-joins-as-one-and-numeric-joins-as-n
  (is (= 0 (depth-of [:var/qname])))
  (is (= 1 (depth-of [:var/qname {:var/calls [:var/qname]}])))
  (is (= 3 (depth-of [:var/qname {:var/calls 3}])))
  (is (= 3 (depth-of (nest 3))))
  (is (= 4 (depth-of (nest 4))))
  (is (= 3 (depth-of [{:var/calls [{:var/calls 2}]}])))
  (is (= 4 (depth-of [{:var/calls [{:var/calls 3}]}])))
  (testing "the greatest path wins"
    (is (= 3 (depth-of [{:var/calls [:var/qname]} {:var/_calls [{:var/calls [{:var/calls [:var/qname]}]}]}])))))

;; @spec MCP-OP-QUERY-051
;; @spec MCP-OP-QUERY-111
(deftest pull-refuses-nested-explicit-joins-deeper-than-three
  (testing "three nested joins pass"
    (is (map? (pull/pull idx (nest 3) [:var/qname "a.b/f"]))))
  (doseq [n [4 5 6 14 150]]
    (let [pattern (nest n)
          d (thrown-data #(pull/pull idx pattern [:var/qname "a.b/f"]))]
      (is (= :pull-too-deep (:error-type d)) (str n " nested joins"))
      (is (= n (:depth d)))
      (is (= pattern (:pattern d))))))

;; @spec MCP-OP-QUERY-051
;; @spec MCP-OP-QUERY-111
(deftest pull-refuses-a-numeric-join-nested-in-an-explicit-join-when-the-sum-is-over-three
  (is (map? (pull/pull idx [{:var/calls [{:var/calls 2}]}] [:var/qname "a.b/f"])))
  (let [d (thrown-data #(pull/pull idx [{:var/calls [{:var/calls 3}]}] [:var/qname "a.b/f"]))]
    (is (= :pull-too-deep (:error-type d)))
    (is (= 4 (:depth d)))))

;; @spec MCP-OP-QUERY-111
(deftest pull-computes-the-depth-before-it-visits-the-index
  (let [visited (atom 0)
        spy (reify clojure.lang.ILookup
              (valAt [_ _] (swap! visited inc) nil)
              (valAt [_ _ nf] (swap! visited inc) nf))]
    (is (= :pull-too-deep (:error-type (thrown-data #(pull/pull spy (nest 6) [:var/qname "a.b/f"])))))
    (is (zero? @visited))))

;; @spec MCP-OP-QUERY-112
(deftest pull-refuses-a-pull-over-the-node-budget-with-result-too-large
  (let [big (layered-index 4 20 false)
        d (thrown-data #(pull/pull big [:var/qname {:var/calls 3}] [:var/qname "g/n0_0"]))]
    (is (= :result-too-large (:error-type d)))
    (is (= 2000 (:limit d)))
    (is (= :pull-nodes (:reason d)))))

;; @spec MCP-OP-QUERY-112
(deftest pull-stops-at-the-node-budget-without-building-the-tree
  (let [big (layered-index 4 20 false)
        polled (atom 0)
        d (thrown-data #(pull/pull big [:var/qname {:var/calls 3}] [:var/qname "g/n0_0"]
                                   {:max-pull-nodes 50 :interrupt-fn (fn [] (swap! polled inc))}))]
    (is (= :result-too-large (:error-type d)))
    (is (= 50 (:limit d)))
    (is (<= @polled 51) "the pull builds no entity past the budget")))

;; @spec MCP-OP-QUERY-112
(deftest pull-counts-one-node-for-each-entity-cycle-maps-included
  (let [g (layered-index 3 2 true)
        pattern [:var/qname {:var/calls 3}]
        eid [:var/qname "g/n0_0"]]
    ;; root 1 + 2 + 4 + 8 (the last 8 are layer 0: the root is a cycle map)
    (is (map? (pull/pull g pattern eid {:max-pull-nodes 15})))
    (is (= :result-too-large (:error-type (thrown-data #(pull/pull g pattern eid {:max-pull-nodes 14})))))))

;; @spec MCP-OP-QUERY-112
(deftest pull-refuses-a-wide-join-before-it-builds-any-child
  (let [wide {:vars (into {"w/root" {:qname "w/root" :calls (mapv #(str "w/c" %) (range 100000))}}
                          (for [i (range 100000)] [(str "w/c" i) {:qname (str "w/c" i)}]))
              :namespaces {} :callers {} :uses-by-target {}}
        d (thrown-data #(pull/pull wide [:var/qname {:var/calls [:var/qname]}] [:var/qname "w/root"]))]
    (is (= :result-too-large (:error-type d)))))

;; @spec MCP-OP-QUERY-112
(deftest pull-within-the-budget-is-not-refused
  (let [g (layered-index 4 6 false)
        r (pull/pull g [:var/qname {:var/calls 3}] [:var/qname "g/n0_0"])]
    (is (= 6 (count (:var/calls r))))))

;; @spec MCP-OP-QUERY-107
(deftest pull-polls-the-interrupt-fn-for-each-entity
  (let [calls (atom 0)
        g (layered-index 3 6 false)
        d (thrown-data #(pull/pull g [:var/qname {:var/calls 3}] [:var/qname "g/n0_0"]
                                   {:interrupt-fn (fn [] (when (> (swap! calls inc) 10)
                                                           (throw (ex-info "t" {:error-type :timeout}))))}))]
    (is (= :timeout (:error-type d)))
    (is (= 11 @calls))))

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

;; --- C1: the platform versions of a .cljc var (099, 100) ---

(defn- cljc-var
  [name platform start end & {:as more}]
  (merge {:qname (str "k.c/" name) :ns "k.c" :name name :platform platform
          :file "src/k/c.cljc" :start start :end end :kind "clojure.core/defn"
          :private? false :arglists ["[x]"] :source (str "(defn " name " [x] x)")
          :calls [] :fx [] :uses-kw [] :reads-kw [] :throws? false}
         more))

(def ^:private cljc-file
  "A .cljc file: `both` has a :clj and a :cljs version (the :cljs one is defined
   first in the file), `same` has one :cljc version."
  {:path "src/k/c.cljc" :mtime 1 :size 10 :sha256 "d" :ns "k.c"
   :vars [(cljc-var "both" :cljs 3 3 :source "(defn both [x] (js/f x))" :calls [])
          (cljc-var "both" :clj 5 5 :source "(defn both [x] (inc x))" :calls ["z.e/q"])
          (cljc-var "same" :cljc 7 7)]
   :uses []})

(def ^:private cljc-idx
  (code-index/apply-ingest code-index/empty-index {:upsert [cljc-file]}))

;; @spec MCP-OP-QUERY-039
;; @spec MCP-OP-QUERY-100
(deftest skeleton-returns-each-platform-version-of-a-var-with-the-clj-version-first
  (let [sk (fns/skeleton cljc-idx 'k.c)]
    (is (= [["k.c/both" :clj 5] ["k.c/both" :cljs 3] ["k.c/same" :cljc 7]]
           (mapv (juxt :qname :platform :start) sk))
        "one var map for each version; :clj before :cljs; vars in order of their start")
    (is (= ["z.e/q"] (:calls (first sk))) "each version keeps its own analysis")
    (is (= 2 (:vars (first (fns/namespaces cljc-idx))))
        "namespaces counts vars, not versions")))

;; @spec MCP-OP-QUERY-042
;; @spec MCP-OP-QUERY-100
(deftest form-returns-each-platform-version-of-a-var-with-its-own-source
  (let [r (fns/form cljc-idx ['k.c/both 'k.c/same])]
    (is (= [["k.c/both" :clj 5 "(defn both [x] (inc x))"]
            ["k.c/both" :cljs 3 "(defn both [x] (js/f x))"]
            ["k.c/same" :cljc 7 "(defn same [x] x)"]]
           (mapv (juxt :qname :platform :start :source) r)))
    (is (every? #(= :form (:kind %)) r))))

;; @spec MCP-OP-QUERY-042
(deftest form-refuses-a-var-that-no-version-defines
  (is (= :unknown-var (:error-type (thrown-data #(fns/form cljc-idx 'k.c/none))))))

;; @spec MCP-OP-QUERY-033
;; @spec MCP-OP-QUERY-100
(deftest bindings-record-the-file-of-a-var-that-has-two-versions
  (let [touched (atom #{})
        b (fns/bindings cljc-idx touched)]
    ((get b 'form) 'k.c/both)
    ((get b 'pull) [:var/qname] [:var/qname "k.c/both"])
    (is (= #{"src/k/c.cljc"} @touched))))

;; --- C1: pull follows the versions of a .cljc var (100) ---

(def ^:private cljc-join-idx
  "A .cljc file: `both` has :clj and :cljs versions, `top` is one :cljc version
   that calls it, `twice` has a :clj and a :cljs version that each call it, and
   `jsonly` is a :cljs version that `twice` also calls."
  (code-index/apply-ingest
   code-index/empty-index
   {:upsert
    [{:path "src/k/c.cljc" :mtime 1 :size 10 :sha256 "j" :ns "k.c"
      :vars [(cljc-var "both" :cljs 3 3 :source "(defn both [x] (js/f x))")
             (cljc-var "both" :clj 5 5 :source "(defn both [x] (inc x))")
             (cljc-var "top" :cljc 7 7 :calls ["k.c/both"])
             (cljc-var "twice" :cljs 9 9 :calls ["k.c/both" "k.c/jsonly"])
             (cljc-var "twice" :clj 11 11 :calls ["k.c/both"])
             (cljc-var "jsonly" :cljs 13 13)]
      :uses []}]}))

;; @spec MCP-OP-QUERY-100
(deftest pull-join-from-a-cljc-version-reaches-every-version-of-the-target
  (let [r (pull/pull cljc-join-idx [:var/qname {:var/calls [:var/qname :var/platform :var/source]}]
                     [:var/qname "k.c/top"])]
    (is (= [{:var/qname "k.c/both" :var/platform :clj :var/source "(defn both [x] (inc x))"}
            {:var/qname "k.c/both" :var/platform :cljs :var/source "(defn both [x] (js/f x))"}]
           (:var/calls r))
        ":cljc source reaches both versions, :clj first, each with its own source")))

;; @spec MCP-OP-QUERY-100
(deftest pull-root-with-two-versions-returns-a-vector-and-joins-stay-on-the-platform
  (let [r (pull/pull cljc-join-idx [:var/qname :var/platform {:var/calls [:var/qname :var/platform]}]
                     [:var/qname "k.c/twice"])]
    (is (vector? r))
    (is (= [:clj :cljs] (mapv :var/platform r)))
    (is (= [[{:var/qname "k.c/both" :var/platform :clj}]
            [{:var/qname "k.c/both" :var/platform :cljs}
             {:var/qname "k.c/jsonly" :var/platform :cljs}]]
           (mapv :var/calls r))
        "a :clj source reaches the :clj target only; a :cljs source the :cljs ones")))

;; @spec MCP-OP-QUERY-100
(deftest pull-ns-and-keyword-joins-follow-versions
  (let [r (pull/pull cljc-join-idx [:ns/name {:ns/_in-ns [:var/qname :var/platform]}]
                     [:ns/name "k.c"])]
    (is (= [["k.c/both" :clj] ["k.c/both" :cljs] ["k.c/top" :cljc]
            ["k.c/twice" :clj] ["k.c/twice" :cljs] ["k.c/jsonly" :cljs]]
           (mapv (juxt :var/qname :var/platform) (:ns/_in-ns r)))
        "every version, in file order")))

;; @spec MCP-OP-QUERY-100
(deftest pull-callers-join-reaches-the-applicable-versions
  (let [r (pull/pull cljc-join-idx [:var/qname :var/platform {:var/_calls 2}]
                     [:var/qname "k.c/both"])]
    (is (= [:clj :cljs] (mapv :var/platform r)))
    (is (= [["k.c/top" :cljc] ["k.c/twice" :clj]]
           (mapv (juxt :var/qname :var/platform) (:var/_calls (first r)))))))

;; ---------------------------------------------------------------------------
;; Wiring of the pull bounds through `bindings` (MCP-OP-QUERY-107, 112, 129)
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-129
;; @spec MCP-OP-QUERY-112
(deftest bound-pull-takes-the-node-budget-from-the-options
  (let [pull-fn (get (fns/bindings idx (atom #{}) {:max-pull-nodes 1}) 'pull)
        data (thrown-data #(pull-fn [:var/qname {:var/calls [:var/qname]}]
                                    [:var/qname "a.b/f"]))]
    (is (= :result-too-large (:error-type data)))
    (is (= :pull-nodes (:reason data)))
    (is (string? (:remedy data))))
  (testing "the same pull inside the budget returns data"
    (let [pull-fn (get (fns/bindings idx (atom #{}) {:max-pull-nodes 10}) 'pull)]
      (is (= 2 (count (:var/calls (pull-fn [:var/qname {:var/calls [:var/qname]}]
                                           [:var/qname "a.b/f"]))))))))

;; @spec MCP-OP-QUERY-129
;; @spec MCP-OP-QUERY-107
(deftest bound-pull-calls-the-interrupt-fn-and-lets-its-throw-stop-the-pull
  (let [calls (atom 0)
        pull-fn (get (fns/bindings idx (atom #{}) {:interrupt-fn #(swap! calls inc)}) 'pull)]
    (pull-fn [:var/qname {:var/calls [:var/qname]}] [:var/qname "a.b/f"])
    (is (pos? @calls) "the pull checks the hook for each entity"))
  (let [stop (fn [] (throw (ex-info "stop" {:error-type :timeout})))
        pull-fn (get (fns/bindings idx (atom #{}) {:interrupt-fn stop}) 'pull)]
    (is (= :timeout (:error-type (thrown-data #(pull-fn [:var/qname {:var/calls [:var/qname]}]
                                                        [:var/qname "a.b/f"])))))))

;; @spec MCP-OP-QUERY-129
(deftest bindings-without-options-keep-the-default-budget
  (let [pull-fn (get (fns/bindings idx (atom #{})) 'pull)]
    (is (= "a.b/f" (:var/qname (pull-fn [:var/qname] [:var/qname "a.b/f"]))))))

;; --- 133 ---

(defn- with-reads-kw
  "idx where the var versions of each qname in `qnames` read the keyword name `kw`."
  [kw qnames]
  (reduce (fn [i q]
            (update-in i [:vars q]
                       (fn [v] (if (map? v)
                                 (update v :reads-kw (fnil conj []) kw)
                                 (mapv #(update % :reads-kw (fnil conj []) kw) v)))))
          idx qnames))

;; @spec MCP-OP-QUERY-133
(deftest pull-reverse-reads-kw-join-returns-readers-sorted-by-qname
  (let [i (with-reads-kw "scope" ["a.b/g" "a.b/f"])]
    (is (= {:kw/name "scope" :var/_reads-kw [{:var/qname "a.b/f"} {:var/qname "a.b/g"}]}
           (pull/pull i [:kw/name {:var/_reads-kw [:var/qname]}] [:kw/name "scope"])))
    (testing "a keyword that nobody reads gives no join"
      (is (= {:kw/name "nope"}
             (pull/pull i [:kw/name {:var/_reads-kw [:var/qname]}] [:kw/name "nope"]))))))

;; @spec MCP-OP-QUERY-133
(deftest pull-reverse-uses-kw-join-returns-users-on-the-fixture-index
  (is (= {:kw/name "x/k" :var/_uses-kw [{:var/qname "a.b/g"}]}
         (pull/pull idx [:kw/name {:var/_uses-kw [:var/qname]}] [:kw/name "x/k"]))))

;; ---------------------------------------------------------------------------
;; search (issue #27)
;; ---------------------------------------------------------------------------

(defn- hit-names [result] (mapv :name result))

;; @spec MCP-OP-QUERY-137
;; @spec MCP-OP-QUERY-138
(deftest search-ranks-vars-by-the-words-of-their-name-doc-and-source
  (testing "words from the source"
    (is (= "a.b/f" (first (hit-names (fns/search idx "swap state conj"))))))
  (testing "a word inside a hyphenated token matches (ex-info gives ex and info)"
    (is (= ["a.b/g"] (hit-names (fns/search idx "info")))))
  (testing "the docstring counts"
    (is (= ["a.b/f"] (hit-names (fns/search idx "Does")))))
  (testing "matching is case-insensitive and has no stemming"
    (is (= ["a.b/f"] (hit-names (fns/search idx "SWAP"))))
    (is (= [] (hit-names (fns/search idx "swapping")))))
  (testing "a name hit outranks a source-only hit (weight 2)"
    (is (= "c.d.e/h" (first (hit-names (fns/search idx "h x")))))))

;; @spec MCP-OP-QUERY-137
(deftest search-splits-camel-case-and-keeps-the-whole-token
  (let [file {:path "src/p/q.clj" :mtime 1 :size 1 :sha256 (apply str (repeat 64 "d"))
              :ns "p.q" :uses []
              :vars [{:qname "p.q/load-page" :ns "p.q" :name "load-page" :platform :clj
                      :file "src/p/q.clj" :start 1 :end 2 :kind "clojure.core/defn" :private? false
                      :arglists ["[m]"] :source "(defn load-page [m] (get m \"pyLoadActivityParameters\"))"
                      :calls [] :fx [] :uses-kw [] :reads-kw [] :throws? false}]}
        ix (code-index/apply-ingest code-index/empty-index {:upsert [file]})]
    (is (= ["p.q/load-page"] (hit-names (fns/search ix "pyLoadActivityParameters"))) "whole token")
    (is (= ["p.q/load-page"] (hit-names (fns/search ix "activity"))) "camel-case part")
    (is (= ["p.q/load-page"] (hit-names (fns/search ix "page"))) "kebab-case part of the name")))

;; @spec MCP-OP-QUERY-138
(deftest search-returns-skeleton-var-maps-with-the-qualified-name
  (let [result (fns/search idx "swap")
        [hit] result
        skeleton-f (first (filter #(= "a.b/f" (:qname %)) (fns/skeleton idx 'a.b)))]
    (is (vector? result))
    (is (= {:kind :search :query "swap"} (meta result)))
    (is (= (assoc skeleton-f :name "a.b/f") hit))
    (is (plain-data? result))))

;; @spec MCP-OP-QUERY-138
(deftest search-with-no-match-is-an-empty-vector
  (is (= [] (fns/search idx "nothingmatchesthis"))))

;; @spec MCP-OP-QUERY-136
(deftest search-limit-caps-the-result
  (is (= 3 (count (fns/search idx "x"))))
  (is (= 1 (count (fns/search idx "x" {:limit 1}))))
  (is (= 3 (count (fns/search idx "x" {:limit 50})))))

;; @spec MCP-OP-QUERY-139
(deftest search-leaves-out-test-files-unless-asked
  (is (= [] (hit-names (fns/search idx "map"))) "only a.b-test/t has map")
  (is (= ["a.b-test/t"] (hit-names (fns/search idx "map" {:include-tests true})))))

;; @spec MCP-OP-QUERY-136
(deftest search-refuses-bad-input-with-a-remedy
  (doseq [args [[""] ["   "] [nil] ['swap] [(apply str (repeat 4001 "a"))]
                ["x" {:limit 0}] ["x" {:limit 51}] ["x" {:limit "3"}]
                ["x" {:include-tests "yes"}] ["x" {:limt 3}] ["x" 3]]]
    (let [data (thrown-data #(apply fns/search idx args))]
      (testing (pr-str args)
        (is (= :invalid-expression (:error-type data)))
        (is (string? (:remedy data)))))))

;; @spec MCP-OP-QUERY-140
(deftest search-reuses-the-search-index-of-an-identical-index-map
  (is (identical? (fns/search-index idx) (fns/search-index idx)))
  (let [other (code-index/apply-ingest code-index/empty-index {:upsert [fx/a-b-file]})]
    (is (not (identical? (fns/search-index idx) (fns/search-index other))))))

;; @spec MCP-OP-QUERY-136
;; @spec MCP-OP-QUERY-139
(deftest the-bound-search-records-the-files-of-its-hits
  (let [touched (atom #{})
        b (fns/bindings idx touched)]
    (is (= ["a.b/f"] (hit-names ((get b 'search) "swap"))))
    (is (= #{"src/a/b.clj"} @touched))))
