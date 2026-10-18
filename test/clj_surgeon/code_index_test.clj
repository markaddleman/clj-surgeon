(ns clj-surgeon.code-index-test
  "Pure witnesses for the code index: apply-ingest, stale-plan and the
   single-writer ingest lock. All inputs are the literal fixtures."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index :as ci]
   [clj-surgeon.code-index-fixtures :as fx]
   [clojure.test :refer [deftest is testing]]))

(def ^:private a-b-path "src/a/b.clj")
(def ^:private c-d-e-path "src/c/d/e.clj")
(def ^:private test-path "test/a/b_test.clj")

(defn- full-index
  []
  (ci/apply-ingest ci/empty-index {:upsert fx/all-files}))

(defn- permutations
  [xs]
  (if (<= (count xs) 1)
    [(vec xs)]
    (vec (for [x xs
               p (permutations (remove #(identical? x %) xs))]
           (into [x] p)))))

(defn- await-or
  "Deref a future for at most ms milliseconds, else return ::blocked."
  [fut ms]
  (deref fut ms ::blocked))

;; @spec MCP-OP-QUERY-025
(deftest empty-index-is-the-documented-empty-map
  (is (= {:files {} :namespaces {} :vars {} :callers {} :kw-users {}
          :uses-by-target {} :skipped [] :read-complete? true :built-ms 0}
         ci/empty-index)))

;; @spec MCP-OP-QUERY-025
;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-adds-file-entries-in-source-order
  (let [idx (full-index)]
    (is (= #{a-b-path c-d-e-path test-path} (set (keys (:files idx)))))
    (is (= {:mtime 1792000000000
            :size 233
            :sha256 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            :ns "a.b"
            :vars ["a.b/f" "a.b/g"]
            :uses (:uses fx/a-b-file)}
           (get-in idx [:files a-b-path])))
    (is (= {:mtime 1792000001000
            :size 40
            :sha256 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            :ns "c.d.e"
            :vars ["c.d.e/h"]
            :uses (:uses fx/c-d-e-file)}
           (get-in idx [:files c-d-e-path])))
    (is (= #{"a.b/f" "a.b/g" "c.d.e/h" "a.b-test/t"} (set (keys (:vars idx)))))
    (is (= (first (:vars fx/a-b-file)) (get-in idx [:vars "a.b/f"])))
    (is (= (second (:vars fx/a-b-file)) (get-in idx [:vars "a.b/g"])))))

;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-records-one-namespace-entry-per-file
  (is (= {"a.b" {:name "a.b" :file a-b-path :doc "The a.b namespace."}
          "c.d.e" {:name "c.d.e" :file c-d-e-path}
          "a.b-test" {:name "a.b-test" :file test-path}}
         (:namespaces (full-index)))))

;; @spec MCP-OP-QUERY-027
(deftest apply-ingest-builds-the-callers-index-including-external-callees
  (is (= {"a.b/g" #{"a.b/f"}
          "c.d.e/h" #{"a.b/f"}
          "z.external/q" #{"a.b/g"}
          "a.b/f" #{"c.d.e/h" "a.b-test/t"}}
         (:callers (full-index)))))

;; @spec MCP-OP-QUERY-027
(deftest apply-ingest-builds-the-keyword-users-index
  (is (= {"scope" #{"a.b/f" "a.b/g"}
          "x/k" #{"a.b/g"}}
         (:kw-users (full-index)))))

;; @spec MCP-OP-QUERY-027
(deftest apply-ingest-builds-uses-by-target-sorted-by-file-line-from-kind
  (let [use-a-b-f-from-cde (first (:uses fx/c-d-e-file))
        [alias-use value-use] (:uses fx/a-b-test-file)
        [g6 g7 h7 q12] (:uses fx/a-b-file)]
    (is (= {"a.b/f" [use-a-b-f-from-cde alias-use value-use]
            "a.b/g" [g6 g7]
            "c.d.e/h" [h7]
            "z.external/q" [q12]}
           (:uses-by-target (full-index))))))

;; @spec MCP-OP-QUERY-025
(deftest apply-ingest-sets-skipped-built-ms-and-read-complete
  (let [skipped [{:file "src/big.clj" :reason :too-large :size 3000000}]
        idx (ci/apply-ingest ci/empty-index
                             {:upsert [fx/a-b-file]
                              :skipped skipped
                              :built-ms 812})]
    (is (= skipped (:skipped idx)))
    (is (= false (:read-complete? idx)))
    (is (= 812 (:built-ms idx))))
  (testing "no skipped and no built-ms keys keep the defaults"
    (let [idx (ci/apply-ingest ci/empty-index {:upsert [fx/a-b-file]})]
      (is (= [] (:skipped idx)))
      (is (= true (:read-complete? idx)))
      (is (= 0 (:built-ms idx))))))

;; @spec MCP-OP-QUERY-025
(deftest apply-ingest-skipped-key-replaces-the-previous-skipped-list
  (let [with-skip (ci/apply-ingest ci/empty-index
                                   {:skipped [{:file "x.clj" :reason :unstable :size 1}]})
        cleared (ci/apply-ingest with-skip {:skipped []})]
    (is (= false (:read-complete? with-skip)))
    (is (= [] (:skipped cleared)))
    (is (= true (:read-complete? cleared)))))

;; @spec MCP-OP-QUERY-025
(deftest apply-ingest-with-an-empty-ingest-returns-an-equal-index
  (let [idx (full-index)]
    (is (= idx (ci/apply-ingest idx {})))))

;; @spec MCP-OP-QUERY-025
(deftest apply-ingest-does-not-change-its-input-index
  (let [idx (full-index)
        before (pr-str idx)]
    (ci/apply-ingest idx {:remove [test-path] :upsert [(assoc fx/c-d-e-file :mtime 1)]})
    (is (= before (pr-str idx)))))

;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-replaces-what-a-changed-file-had-before
  (let [shrunk (assoc fx/a-b-file
                      :size 100
                      :vars [(assoc (first (:vars fx/a-b-file))
                                    :calls [] :uses-kw [])]
                      :uses [])
        idx (ci/apply-ingest (full-index) {:upsert [shrunk]})]
    (is (= ["a.b/f"] (get-in idx [:files a-b-path :vars])))
    (is (= 100 (get-in idx [:files a-b-path :size])))
    (is (nil? (get-in idx [:vars "a.b/g"])))
    (is (= {"a.b/f" #{"c.d.e/h" "a.b-test/t"}} (:callers idx)))
    (is (= {} (:kw-users idx)))
    (is (= #{"a.b/f"} (set (keys (:uses-by-target idx)))))))

;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-removes-file-vars-and-namespace-of-a-removed-path
  (let [idx (ci/apply-ingest (full-index) {:remove [test-path]})]
    (is (= #{a-b-path c-d-e-path} (set (keys (:files idx)))))
    (is (nil? (get-in idx [:vars "a.b-test/t"])))
    (is (nil? (get-in idx [:namespaces "a.b-test"])))
    (is (= #{"a.b/f" "a.b/g" "c.d.e/h"} (set (keys (:vars idx)))))
    (is (= #{"c.d.e/h"} (get-in idx [:callers "a.b/f"])))
    (is (= [(first (:uses fx/c-d-e-file))]
           (get-in idx [:uses-by-target "a.b/f"])))))

;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-ignores-a-removed-path-the-index-does-not-hold
  (let [idx (full-index)]
    (is (= idx (ci/apply-ingest idx {:remove ["src/never/seen.clj"]})))))

;; @spec MCP-OP-QUERY-026
(deftest apply-ingest-upsert-wins-over-remove-of-the-same-path
  (let [idx (ci/apply-ingest (full-index)
                             {:remove [c-d-e-path] :upsert [fx/c-d-e-file]})]
    (is (= (full-index) idx))))

;; @spec MCP-OP-QUERY-027
(deftest apply-ingest-lets-the-smaller-path-win-for-a-qname-defined-twice
  (let [lib-var (assoc (first (:vars fx/a-b-file))
                       :file "lib/a/b.clj" :source "(defn f [x] :lib)")
        lib-file (assoc fx/a-b-file
                        :path "lib/a/b.clj"
                        :vars [lib-var]
                        :uses [])
        expected-winner lib-var]
    (testing "ingest order does not matter"
      (doseq [order [[fx/a-b-file lib-file] [lib-file fx/a-b-file]]]
        (let [idx (ci/apply-ingest ci/empty-index {:upsert order})]
          (is (= expected-winner (get-in idx [:vars "a.b/f"])))
          (is (= "lib/a/b.clj" (get-in idx [:namespaces "a.b" :file]))))))))

;; @spec MCP-OP-QUERY-028
(deftest apply-ingest-gives-the-same-index-for-every-upsert-order
  (let [results (map #(ci/apply-ingest ci/empty-index {:upsert %})
                     (permutations fx/all-files))]
    (is (= 6 (count results)))
    (is (apply = results))
    (is (= (full-index) (first results)))))

;; @spec MCP-OP-QUERY-028
(deftest apply-ingest-order-independence-holds-over-a-nonempty-index
  (let [base (ci/apply-ingest ci/empty-index {:upsert [fx/a-b-file]})
        changed-cde (assoc fx/c-d-e-file :mtime 5)
        a (ci/apply-ingest base {:upsert [changed-cde fx/a-b-test-file]})
        b (ci/apply-ingest base {:upsert [fx/a-b-test-file changed-cde]})]
    (is (= a b))))

;; @spec MCP-OP-QUERY-028
(deftest incremental-ingest-equals-batch-ingest
  (let [step (-> ci/empty-index
                 (ci/apply-ingest {:upsert [fx/a-b-test-file]})
                 (ci/apply-ingest {:upsert [fx/c-d-e-file]})
                 (ci/apply-ingest {:upsert [fx/a-b-file]}))]
    (is (= (full-index) step))))

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-031
(deftest stale-plan-of-an-unchanged-workspace-is-empty
  (let [discovered (mapv #(select-keys % [:path :mtime :size]) fx/all-files)]
    (is (= {:ingest [] :remove []} (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-lists-a-file-whose-mtime-changed
  (let [discovered [{:path a-b-path :mtime 1792000009999 :size 233}
                    {:path c-d-e-path :mtime 1792000001000 :size 40}
                    {:path test-path :mtime 1792000002000 :size 90}]]
    (is (= {:ingest [a-b-path] :remove []}
           (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-lists-a-file-whose-size-changed
  (let [discovered [{:path a-b-path :mtime 1792000000000 :size 233}
                    {:path c-d-e-path :mtime 1792000001000 :size 41}
                    {:path test-path :mtime 1792000002000 :size 90}]]
    (is (= {:ingest [c-d-e-path] :remove []}
           (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-lists-a-new-file
  (let [discovered (conj (mapv #(select-keys % [:path :mtime :size]) fx/all-files)
                         {:path "src/new/n.clj" :mtime 1 :size 2})]
    (is (= {:ingest ["src/new/n.clj"] :remove []}
           (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-lists-a-removed-file
  (let [discovered [{:path a-b-path :mtime 1792000000000 :size 233}
                    {:path test-path :mtime 1792000002000 :size 90}]]
    (is (= {:ingest [] :remove [c-d-e-path]}
           (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-sorts-paths-and-combines-all-three-kinds
  (let [discovered [{:path "src/z/new.clj" :mtime 1 :size 1}
                    {:path "src/a/new.clj" :mtime 1 :size 1}
                    {:path a-b-path :mtime 7 :size 233}
                    {:path c-d-e-path :mtime 1792000001000 :size 40}]]
    (is (= {:ingest [a-b-path "src/a/new.clj" "src/z/new.clj"]
            :remove [test-path]}
           (ci/stale-plan (full-index) discovered)))))

;; @spec MCP-OP-QUERY-030
(deftest stale-plan-of-an-empty-index-ingests-every-discovered-file
  (is (= {:ingest [a-b-path c-d-e-path]
          :remove []}
         (ci/stale-plan ci/empty-index
                        (list {:path c-d-e-path :mtime 1 :size 1}
                              {:path a-b-path :mtime 2 :size 2})))))

;; @spec MCP-OP-QUERY-031
(deftest stale-plan-treats-a-previously-skipped-path-now-present-as-new
  (let [idx (ci/apply-ingest ci/empty-index
                             {:skipped [{:file "src/big.clj" :reason :too-large :size 3000000}]})]
    (is (= {:ingest ["src/big.clj"] :remove []}
           (ci/stale-plan idx [{:path "src/big.clj" :mtime 1 :size 100}])))))

;; @spec MCP-OP-QUERY-029
(deftest new-entry-has-exactly-index-lock-and-cold-flag
  (let [e (ci/new-entry)]
    (is (= #{:index :lock :cold?} (set (keys e))))
    (is (= ci/empty-index @(:index e)))
    (is (= true @(:cold? e)))
    (is (instance? java.util.concurrent.locks.ReentrantLock (:lock e)))))

;; @spec MCP-OP-QUERY-029
(deftest with-ingest-lock-returns-the-value-of-f-and-releases-the-lock
  (let [e (ci/new-entry)]
    (is (= :result (ci/with-ingest-lock e (fn [] :result))))
    (is (not (.isLocked ^java.util.concurrent.locks.ReentrantLock (:lock e))))))

;; @spec MCP-OP-QUERY-029
(deftest with-ingest-lock-releases-the-lock-when-f-throws
  (let [e (ci/new-entry)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (ci/with-ingest-lock e (fn [] (throw (ex-info "boom" {}))))))
    (is (not (.isLocked ^java.util.concurrent.locks.ReentrantLock (:lock e))))
    (is (= :again (ci/with-ingest-lock e (fn [] :again))))))

;; @spec MCP-OP-QUERY-029
(deftest with-ingest-lock-is-reentrant-on-one-thread
  (let [e (ci/new-entry)]
    (is (= :inner (ci/with-ingest-lock e (fn [] (ci/with-ingest-lock e (fn [] :inner))))))))

;; @spec MCP-OP-QUERY-029
(deftest a-second-ingest-of-the-same-entry-waits-for-a-slow-first-ingest
  (let [e (ci/new-entry)
        events (atom [])
        entered (promise)
        release (promise)
        slow-ingest (fn []
                      (swap! events conj :first-start)
                      (deliver entered true)
                      @release
                      (reset! (:index e) (ci/apply-ingest @(:index e) {:upsert [fx/a-b-file]}))
                      (swap! events conj :first-end)
                      :first)
        fast-ingest (fn []
                      (swap! events conj :second-start)
                      (reset! (:index e) (ci/apply-ingest @(:index e) {:upsert [fx/c-d-e-file]}))
                      :second)
        first-f (future (ci/with-ingest-lock e slow-ingest))]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (let [second-f (future (ci/with-ingest-lock e fast-ingest))]
        (testing "second ingest has not started while the first holds the lock"
          (is (= ::blocked (await-or second-f 300)))
          (is (= [:first-start] @events)))
        (testing "reads of the index never take the lock"
          (is (= ci/empty-index @(:index e))))
        (deliver release true)
        (is (= :first (await-or first-f 5000)))
        (is (= :second (await-or second-f 5000)))
        (is (= [:first-start :first-end :second-start] @events))
        (is (= #{a-b-path c-d-e-path} (set (keys (:files @(:index e)))))))
      (finally
        (deliver release true)))))

;; @spec MCP-OP-QUERY-029
(deftest an-ingest-of-a-different-entry-is-not-blocked
  (let [e1 (ci/new-entry)
        e2 (ci/new-entry)
        entered (promise)
        release (promise)
        slow (future (ci/with-ingest-lock e1 (fn [] (deliver entered true) @release :slow)))]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (let [other (future (ci/with-ingest-lock e2 (fn [] :other)))]
        (is (= :other (await-or other 2000))))
      (finally
        (deliver release true)))
    (is (= :slow (await-or slow 5000)))))
