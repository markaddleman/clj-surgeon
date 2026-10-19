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
    (is (= [(first (:vars fx/a-b-file))] (get-in idx [:vars "a.b/f"])))
    (is (= [(second (:vars fx/a-b-file))] (get-in idx [:vars "a.b/g"])))))

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

;; @spec MCP-OP-QUERY-101
(deftest a-namespace-lives-in-one-file-the-smaller-path-is-indexed
  ;; The "smaller path wins" rule for one qname is gone (decision C1). A
  ;; namespace in two files keeps the smaller path; the other file is skipped.
  (let [lib-var (assoc (first (:vars fx/a-b-file)) :file "lib/a/b.clj")
        lib-file (assoc fx/a-b-file :path "lib/a/b.clj" :size 77
                        :vars [lib-var] :uses [])]
    (testing "ingest order does not matter"
      (doseq [order [[fx/a-b-file lib-file] [lib-file fx/a-b-file]]]
        (let [idx (ci/apply-ingest ci/empty-index {:upsert order})]
          (is (= [lib-var] (get-in idx [:vars "a.b/f"])))
          (is (= "lib/a/b.clj" (get-in idx [:namespaces "a.b" :file])))
          (is (= #{"lib/a/b.clj"} (set (keys (:files idx)))))
          (is (= [{:file a-b-path :reason :duplicate-namespace :size 233}]
                 (:skipped idx)))
          (is (false? (:read-complete? idx))))))
    (testing "a file that arrives later with a smaller path evicts the indexed file"
      (let [base (ci/apply-ingest ci/empty-index {:upsert [fx/a-b-file]})
            idx (ci/apply-ingest base {:upsert [lib-file]})]
        (is (= #{"lib/a/b.clj"} (set (keys (:files idx)))))
        (is (= [{:file a-b-path :reason :duplicate-namespace :size 233}]
               (:skipped idx)))))
    (testing "a skipped list given by the caller keeps its entries and gains the duplicate"
      (let [given {:file "x.clj" :reason :unstable :size 1}
            idx (ci/apply-ingest ci/empty-index
                                 {:upsert [fx/a-b-file lib-file] :skipped [given]})]
        (is (= [given {:file a-b-path :reason :duplicate-namespace :size 233}]
               (:skipped idx)))))))

;; @spec MCP-OP-QUERY-100
(deftest a-var-with-two-platform-versions-keeps-both-records-clj-first
  (let [clj-v (assoc (first (:vars fx/c-d-e-file))
                     :platform :clj :arglists ["[x]"] :file "src/c/d/e.cljc")
        cljs-v (assoc clj-v :platform :cljs :arglists ["[x y]"] :start 5 :end 5)
        cljc-file (assoc fx/c-d-e-file :path "src/c/d/e.cljc" :vars [clj-v cljs-v])]
    (testing "both orders of the version records give the same vector"
      (doseq [vs [[clj-v cljs-v] [cljs-v clj-v]]]
        (let [idx (ci/apply-ingest ci/empty-index {:upsert [(assoc cljc-file :vars vs)]})]
          (is (= [clj-v cljs-v] (get-in idx [:vars "c.d.e/h"])))
          (is (= ["c.d.e/h"] (get-in idx [:files "src/c/d/e.cljc" :vars]))
              "the file entry lists the qname once"))))
    (testing "removing the file removes both records"
      (let [idx (ci/apply-ingest ci/empty-index {:upsert [cljc-file]})
            gone (ci/apply-ingest idx {:remove ["src/c/d/e.cljc"]})]
        (is (= {} (:vars gone)))
        (is (= {} (:namespaces gone)))))
    (testing "a replaced file drops the versions it no longer has"
      (let [idx (ci/apply-ingest ci/empty-index {:upsert [cljc-file]})
            one (ci/apply-ingest idx {:upsert [(assoc cljc-file :vars [(assoc clj-v :platform :cljc)])]})]
        (is (= [(assoc clj-v :platform :cljc)] (get-in one [:vars "c.d.e/h"])))))))

;; @spec MCP-OP-QUERY-119
(deftest plan-reads-stops-when-the-bytes-read-reach-the-total-cap
  (let [files [{:path "src/c.clj" :size 40}
               {:path "src/a.clj" :size 60}
               {:path "src/b.clj" :size 30}
               {:path "src/d.clj" :size 10}]]
    (testing "files are taken in order of path; the file that reaches the cap is read"
      (is (= {:read ["src/a.clj" "src/b.clj"]
              :skipped [{:file "src/c.clj" :reason :total-bytes :size 40}
                        {:file "src/d.clj" :reason :total-bytes :size 10}]}
             (ci/plan-reads files 70))))
    (testing "a total below the cap reads every file"
      (is (= {:read ["src/a.clj" "src/b.clj" "src/c.clj" "src/d.clj"] :skipped []}
             (ci/plan-reads files 1000))))
    (testing "the bytes read reach the cap exactly, so the next file is skipped"
      (is (= {:read ["src/a.clj" "src/b.clj"]
              :skipped [{:file "src/c.clj" :reason :total-bytes :size 40}
                        {:file "src/d.clj" :reason :total-bytes :size 10}]}
             (ci/plan-reads files 90))))
    (testing "no file at all"
      (is (= {:read [] :skipped []} (ci/plan-reads [] 10))))))

;; @spec MCP-OP-QUERY-119
;; @spec MCP-OP-QUERY-120
(deftest default-bounds-hold-the-ingest-bounds
  (is (= 134217728 (:max-total-bytes ci/default-bounds)))
  (is (= 1048576 (:max-config-bytes ci/default-bounds)))
  (is (= 60000 (:refresh-timeout-ms ci/default-bounds))))

;; @spec MCP-OP-QUERY-124
(deftest default-bounds-hold-the-expression-depth-cap
  (is (= 256 (:max-expression-depth ci/default-bounds))))

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
