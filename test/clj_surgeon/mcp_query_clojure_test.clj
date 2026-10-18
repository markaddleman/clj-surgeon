(ns clj-surgeon.mcp-query-clojure-test
  "End-to-end witnesses for the query_clojure tool (MCP-OP-QUERY-001 to 008,
   030 to 035, 072 to 074, 084, 086 to 088).

   Every test builds a small workspace under java.io.tmpdir and calls
   `mcp-query-clojure/execute-request!` with a fresh registry and a fresh
   sessions object, so no test shares state with another."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index-discovery :as discovery]
   [clj-surgeon.code-index-ingest :as ingest]
   [clj-surgeon.code-index-session :as session]
   [clj-surgeon.mcp-operation :as mcp-operation]
   [clj-surgeon.mcp-query-clojure :as q]
   [clj-surgeon.mcp-tool :as mcp-tool]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Workspace helpers
;; ---------------------------------------------------------------------------

(def ^:private core-text
  (str "(ns demo.core\n"
       "  \"Demo core.\")\n"
       "\n"
       "(defn helper\n"
       "  \"Helps. More.\"\n"
       "  [x]\n"
       "  (inc x))\n"
       "\n"
       "(defn run\n"
       "  [x]\n"
       "  (helper x))\n"))

(def ^:private other-text
  (str "(ns demo.other\n"
       "  (:require [demo.core :as core]))\n"
       "\n"
       "(defn two [] (core/run 2))\n"))

(def ^:private t0 1792000000000)

(defn- make-root
  []
  (.getCanonicalPath
   (.toFile (Files/createTempDirectory
             "clj-surgeon-qc-test" (make-array FileAttribute 0)))))

(defn- delete-tree!
  [^String root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete ^java.io.File f)))

(defn- put!
  ([root rel text] (put! root rel text t0))
  ([root rel text mtime]
   (let [f (io/file root rel)]
     (io/make-parents f)
     (spit f text)
     (.setLastModified f mtime))))

(defn- sha-of
  [root rel]
  (discovery/sha256 (Files/readAllBytes (.toPath (io/file root rel)))))

(defn- with-workspace
  "Build the standard two-namespace workspace, call (f root), delete the tree."
  [f]
  (let [root (make-root)]
    (try
      (put! root "deps.edn" "{:paths [\"src\"]}\n")
      (put! root "src/demo/core.clj" core-text)
      (put! root "src/demo/other.clj" other-text)
      (f root)
      (finally (delete-tree! root)))))

(defn- config
  ([root] (config root {}))
  ([root bounds]
   {:project-root root
    :code-index/bounds bounds
    :code-index/registry (atom {})
    :code-index/sessions (session/sessions (select-keys bounds [:max-defs]))}))

(defn- run
  ([cfg expression] (run cfg expression {}))
  ([cfg expression opts]
   (q/execute-request! cfg {"expression" expression} opts)))

(defn- clock-812
  "A :now-ms that reads 0 for the first call and 812 for every later call."
  []
  (let [n (atom -1)]
    (fn [] (if (zero? (swap! n inc)) 0 812))))

(def ^:private skeleton-core
  (str "ns demo.core — Demo core.\n"
       "λ helper([x]) — Helps.\n"
       "λ run([x]) | calls helper"))

(defn- refusal-shape-ok?
  [result]
  (and (false? (:ok result))
       (= "query-clojure" (:operation result))
       (string? (:error_type result))
       (string? (:error result))
       (= 1 (count (filter #(contains? result %) [:next_call :remedy])))
       (every? #{:ok :operation :error_type :error :next_call :remedy :workspace_root}
               (keys result))))

(defn- snapshot
  [root]
  (into (sorted-map)
        (for [^java.io.File f (file-seq (io/file root))]
          [(subs (.getPath f) (count root))
           (if (.isDirectory f)
             {:dir true :mtime (.lastModified f)}
             {:size (.length f)
              :mtime (.lastModified f)
              :sha (discovery/sha256 (Files/readAllBytes (.toPath f)))})])))

;; ---------------------------------------------------------------------------
;; 001
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-001
(deftest the-full-profile-publishes-query-clojure-as-a-read-only-tool
  (let [tool (first (filter #(= "query_clojure" (:name %))
                            (mcp-tool/tools-for-profile :full)))]
    (is (some? tool) "the :full profile lists query_clojure")
    (is (= #{:read-success :typed-refusal} (:outcome-classes tool)))
    (is (true? (get-in tool [:annotations :read-only])))
    (is (true? (get-in tool [:annotations :idempotent])))
    (is (false? (get-in tool [:annotations :open-world])))
    (is (false? (get-in tool [:annotations :destructive])))
    (is (= q/query-clojure-tool tool))))

;; @spec MCP-OP-QUERY-001
(deftest the-edit-profile-does-not-publish-query-clojure
  (is (not-any? #(= "query_clojure" (:name %))
                (mcp-tool/tools-for-profile :edit))))

;; @spec MCP-OP-QUERY-001
;; @spec MCP-OP-QUERY-002
(deftest the-input-schema-requires-expression-and-closes-the-field-set
  (is (= {:type "object"
          :additionalProperties false
          :properties {"expression" {:type "string"}
                       "workspace_root" {:type "string"}}
          :required ["expression"]}
         q/query-clojure-schema))
  (is (= q/query-clojure-schema (:schema q/query-clojure-tool))))

;; ---------------------------------------------------------------------------
;; 002, 004
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-002
;; @spec MCP-OP-QUERY-004
(deftest a-request-with-a-bad-shape-is-refused-before-workspace-or-disk
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            touched (atom 0)
            opts {:discover-fn (fn [_ _] (swap! touched inc) (throw (ex-info "disk" {})))
                  :read-fn (fn [_] (swap! touched inc) (throw (ex-info "disk" {})))}]
        (doseq [[label params]
                [["an extra field" {"expression" "1" "foo" 1}]
                 ["an extra field next to workspace_root"
                  {"expression" "1" "workspace_root" root "extra" true}]
                 ["no expression" {}]
                 ["a numeric expression" {"expression" 7}]
                 ["a numeric workspace_root" {"expression" "1" "workspace_root" 3}]
                 ["params that are not a map" ["expression" "1"]]]]
          (testing label
            (let [result (q/execute-request! cfg params opts)]
              (is (refusal-shape-ok? result))
              (is (= "invalid-request" (:error_type result)))
              (is (string? (:remedy result)))
              (is (not (contains? result :next_call)))
              (is (not (contains? result :workspace_root))
                  "refused before the workspace is resolved"))))
        (is (zero? @touched) "no discovery and no file read happened")))))

;; ---------------------------------------------------------------------------
;; 003
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-003
;; @spec MCP-OP-QUERY-033
;; @spec MCP-OP-QUERY-034
(deftest a-success-carries-every-field-of-the-contract
  (with-workspace
    (fn [root]
      (let [result (run (config root) "(skeleton 'demo.core)"
                        {:now-ms (clock-812)})]
        (is (= {:ok true
                :operation "query-clojure"
                :value skeleton-core
                :value_chars (count skeleton-core)
                :session {:defs [] :reset false}
                :files [{:path "src/demo/core.clj"
                         :sha256 (sha-of root "src/demo/core.clj")}]
                :index {:files 2 :vars 3 :skipped 0 :built_ms 812}
                :reingested []
                :read_complete true
                :skipped []
                :workspace_root root}
               result))))))

;; ---------------------------------------------------------------------------
;; 004, 005
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-004
(deftest a-refusal-has-exactly-one-continuation-and-no-extra-keys
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            unknown (run cfg "(form 'demo.core/nope)")
            invalid (run cfg "(+ 1")]
        (is (refusal-shape-ok? unknown))
        (is (= "unknown-var" (:error_type unknown)))
        (is (= root (:workspace_root unknown)) "present once the root is routed")
        (is (= {:tool "query_clojure" :expression "(skeleton 'demo.core)"}
               (:next_call unknown))
            "the request had no workspace_root, so the next_call has none")
        (is (not (contains? unknown :remedy)))
        (is (refusal-shape-ok? invalid))
        (is (= "invalid-expression" (:error_type invalid)))
        (is (string? (:remedy invalid)))
        (is (not (contains? invalid :next_call)))))))

(def ^:private pull-deep
  "(pull '[:var/qname {:var/calls 9}] [:var/qname \"demo.core/run\"])")

;; @spec MCP-OP-QUERY-005
(deftest no-next-call-equals-the-call-that-produced-it
  (with-workspace
    (fn [root]
      (let [requests
            [{:expression "(skeleton 'demo.cor)"}
             {:expression "(skeleton 'zzz)" :workspace_root root}
             {:expression "(form 'demo.core/nope)"}
             {:expression "(form 'demo.core/nope)" :workspace_root root}
             {:expression pull-deep}
             {:expression pull-deep :workspace_root root}
             {:expression "(form '[demo.core/helper demo.core/run])" :bounds {:max-result-chars 30}}
             {:expression "(refs 'demo.core/helper)" :bounds {:max-result-chars 30}}
             {:expression "(refs 'demo.core/helper {:include-tests false})"
              :bounds {:max-result-chars 30}}
             {:expression "(skeleton 'demo.core)" :bounds {:max-skeleton-chars 20}}
             {:expression "(slurp \"x\")"}
             {:expression "(+ 1"}
             {:expression "1 2"}]]
        (doseq [{:keys [bounds] :as request} requests]
          (let [params (dissoc request :bounds)
                cfg (config root (or bounds {}))
                result (q/execute-request! cfg params {})]
            (testing (pr-str request)
              (is (refusal-shape-ok? result))
              (when-let [next-call (:next_call result)]
                (is (not= (dissoc next-call :tool) params))
                (is (= "query_clojure" (:tool next-call)))))))))))

;; @spec MCP-OP-QUERY-005
;; @spec MCP-OP-QUERY-054
;; @spec MCP-OP-QUERY-055
;; @spec MCP-OP-QUERY-051
(deftest next-calls-narrow-the-request-and-keep-the-request-workspace-root
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            ns-result (run cfg "(skeleton 'demo.cor)")
            ns-rooted (q/execute-request!
                       cfg {"expression" "(skeleton 'zzz)" "workspace_root" root} {})
            deep (run cfg pull-deep)]
        (is (= {:tool "query_clojure" :expression "(skeleton 'demo.core)"}
               (:next_call ns-result)))
        (is (= "unknown-namespace" (:error_type ns-result)))
        (is (= {:tool "query_clojure"
                :expression "(skeleton 'demo.core)"
                :workspace_root root}
               (:next_call ns-rooted)))
        (is (= "pull-too-deep" (:error_type deep)))
        (is (= {:tool "query_clojure"
                :expression "(pull '[:var/qname {:var/calls 1}] [:var/qname \"demo.core/run\"])"}
               (:next_call deep)))))))

;; ---------------------------------------------------------------------------
;; 006
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-006
(deftest the-handler-publishes-one-summary-and-the-result-with-elapsed-ms
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            published (atom [])
            callback (fn [content error? result]
                       (swap! published conj {:content content :error? error? :result result}))]
        (try
          (q/init! cfg)
          (let [expected (q/execute-request! cfg {"expression" "\"hi\""}
                                             {:session-id "s1"})
                _ (q/handle-query-clojure {:session-id "s1"} {"expression" "\"hi\""} callback)
                _ (q/handle-query-clojure {:session-id "s1"} {"expression" "(+ 1"} callback)
                [ok bad] @published]
            (testing "success"
              (is (false? (:error? ok)))
              (is (number? (get-in ok [:result :elapsed_ms])))
              (is (<= 0 (get-in ok [:result :elapsed_ms])))
              (is (= expected (dissoc (:result ok) :elapsed_ms))
                  "elapsed_ms is the only field that the finalizer adds")
              (is (= [(q/summary (:result ok))] (:content ok)))
              (is (str/starts-with?
                   (first (:content ok))
                   "hi\n;; 2 chars · 0 file(s) · 0 re-ingested · "))
              (is (str/ends-with?
                   (first (:content ok))
                   (mcp-operation/format-elapsed-ms (get-in ok [:result :elapsed_ms])))))
            (testing "refusal"
              (is (true? (:error? bad)))
              (is (= "invalid-expression" (get-in bad [:result :error_type])))
              (is (number? (get-in bad [:result :elapsed_ms])))
              (is (= [(q/summary (:result bad))] (:content bad)))
              (is (str/starts-with?
                   (first (:content bad))
                   "query_clojure refused · invalid-expression · "))
              (is (str/ends-with? (first (:content bad)) "\nnothing was written"))
              (is (str/includes? (first (:content bad)) (get-in bad [:result :error])))))
          (finally (q/init! nil)))))))

;; @spec MCP-OP-QUERY-006
(deftest the-handler-refuses-before-init
  (let [published (atom nil)]
    (q/init! nil)
    (q/handle-query-clojure {} {"expression" "1"}
                            (fn [content error? result]
                              (reset! published {:content content :error? error? :result result})))
    (is (true? (:error? @published)))
    (is (= "server-not-initialized" (get-in @published [:result :error_type])))
    (is (string? (get-in @published [:result :remedy])))
    (is (number? (get-in @published [:result :elapsed_ms])))))

;; ---------------------------------------------------------------------------
;; 007
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-007
(deftest handling-requests-leaves-the-workspace-files-unchanged
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            before (snapshot root)]
        (is (:ok (run cfg "(skeleton 'demo.core)")))
        (is (:ok (run cfg "(def kept [1 2 3])" {:session-id "s1"})))
        (is (:ok (run cfg "(refs 'demo.core/helper)")))
        (is (false? (:ok (run cfg "(form 'demo.core/nope)"))))
        (is (false? (:ok (run cfg "(slurp \"deps.edn\")"))))
        (is (false? (:ok (q/execute-request! cfg {"expression" "1" "x" 1} {}))))
        (is (= before (snapshot root))
            "no file was created, changed or deleted, and no mtime moved")))))

;; ---------------------------------------------------------------------------
;; 008
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-008
(deftest an-absent-workspace-root-uses-the-server-project-root
  (with-workspace
    (fn [root]
      (let [result (run (config root) "(count (namespaces))")]
        (is (true? (:ok result)))
        (is (= "2" (:value result)))
        (is (= root (:workspace_root result)))))))

;; @spec MCP-OP-QUERY-008
(deftest a-present-workspace-root-selects-that-workspace
  (with-workspace
    (fn [root]
      (let [other (make-root)]
        (try
          (put! other "deps.edn" "{:paths [\"src\"]}\n")
          (put! other "src/solo/one.clj" "(ns solo.one)\n\n(defn only [] 1)\n")
          (let [cfg (config root)
                result (q/execute-request!
                        cfg {"expression" "(namespaces)" "workspace_root" other} {})]
            (is (true? (:ok result)))
            (is (= other (:workspace_root result)))
            (is (= "ns solo.one (1 vars)" (:value result))))
          (finally (delete-tree! other)))))))

;; @spec MCP-OP-QUERY-008
;; @spec MCP-OP-QUERY-004
(deftest a-workspace-root-that-is-not-a-canonical-directory-is-refused
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (doseq [[label value]
                [["a relative path" "src"]
                 ["a path that does not exist" (str root "/no-such-dir")]
                 ["a file, not a directory" (str root "/deps.edn")]
                 ["a non-canonical path" (str root "/src/..")]]]
          (testing label
            (let [result (q/execute-request!
                          cfg {"expression" "(namespaces)" "workspace_root" value} {})]
              (is (refusal-shape-ok? result))
              (is (= "invalid-workspace-root" (:error_type result)))
              (is (string? (:remedy result)))
              (is (not (contains? result :next_call))))))))))

;; ---------------------------------------------------------------------------
;; 030, 031, 032, 033
;; ---------------------------------------------------------------------------

(defn- reingested
  [result]
  (:reingested result))

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-031
;; @spec MCP-OP-QUERY-033
(deftest a-file-with-a-newer-mtime-is-reingested-before-the-expression-runs
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            first-run (run cfg "(skeleton 'demo.core)")
            changed (str/replace core-text "Helps. More." "Aids.  More.")]
        (is (= skeleton-core (:value first-run)))
        (is (= [] (reingested first-run)))
        (is (= (count core-text) (count changed)) "same size: only the mtime differs")
        (put! root "src/demo/core.clj" changed (+ t0 60000))
        (let [second-run (run cfg "(skeleton 'demo.core)")]
          (is (true? (:ok second-run)))
          (is (= ["src/demo/core.clj"] (reingested second-run)))
          (is (= (str/replace skeleton-core "Helps." "Aids.") (:value second-run))
              "the new docstring shows in the same request that found the change")
          (is (= [{:path "src/demo/core.clj" :sha256 (sha-of root "src/demo/core.clj")}]
                 (:files second-run)))
          (is (not= (:sha256 (first (:files first-run)))
                    (:sha256 (first (:files second-run))))))
        (let [third-run (run cfg "(skeleton 'demo.core)")]
          (is (= [] (reingested third-run)) "nothing changed since the last request")
          (is (= (str/replace skeleton-core "Helps." "Aids.") (:value third-run))))))))

;; @spec MCP-OP-QUERY-031
(deftest a-file-with-a-different-size-and-the-same-mtime-is-reingested
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (run cfg "(skeleton 'demo.core)")
        (put! root "src/demo/core.clj"
              (str core-text "\n(defn extra [] 1)\n")
              t0)
        (let [result (run cfg "(skeleton 'demo.core)")]
          (is (= ["src/demo/core.clj"] (reingested result)))
          (is (str/ends-with? (:value result) "\nλ extra([])"))
          (is (= {:files 2 :vars 4 :skipped 0} (select-keys (:index result) [:files :vars :skipped]))))))))

;; @spec MCP-OP-QUERY-031
(deftest a-new-file-and-a-removed-file-are-listed-in-reingested
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (run cfg "(namespaces)")
        (put! root "src/demo/third.clj" "(ns demo.third)\n\n(defn three [] 3)\n")
        (let [added (run cfg "(namespaces)")]
          (is (= ["src/demo/third.clj"] (reingested added)))
          (is (= (str "ns demo.core (2 vars) — Demo core.\n"
                      "ns demo.other (1 vars)\n"
                      "ns demo.third (1 vars)")
                 (:value added))))
        (.delete (io/file root "src/demo/other.clj"))
        (let [removed (run cfg "(namespaces)")]
          (is (= ["src/demo/other.clj"] (reingested removed)))
          (is (= (str "ns demo.core (2 vars) — Demo core.\n"
                      "ns demo.third (1 vars)")
                 (:value removed)))
          (is (= {:files 2 :vars 3} (select-keys (:index removed) [:files :vars]))))))))

;; @spec MCP-OP-QUERY-030
;; @spec MCP-OP-QUERY-032
(deftest the-stale-check-runs-even-when-the-expression-does-not-touch-the-index
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (run cfg "1")
        (put! root "src/demo/core.clj"
              (str/replace core-text "Helps. More." "Aids.  More.")
              (+ t0 1000))
        (is (= ["src/demo/core.clj"] (reingested (run cfg "\"unrelated\""))))))))

;; @spec MCP-OP-QUERY-033
(deftest files-lists-only-the-files-of-which-a-bound-function-returned-data
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            core {:path "src/demo/core.clj" :sha256 (sha-of root "src/demo/core.clj")}
            other {:path "src/demo/other.clj" :sha256 (sha-of root "src/demo/other.clj")}]
        (testing "a value that is not from a bound function lists no file"
          (is (= [] (:files (run cfg "(str \"a\" \"b\")")))))
        (testing "skeleton of one namespace lists that namespace's file only"
          (is (= [core] (:files (run cfg "(skeleton 'demo.core)")))))
        (testing "namespaces lists every file, sorted by path"
          (is (= [core other] (:files (run cfg "(namespaces)")))))
        (testing "refs lists the files of the uses, not the file of the target"
          (is (= [other] (:files (run cfg "(refs 'demo.core/run)")))))
        (testing "form lists the file of the form"
          (is (= [core] (:files (run cfg "(form 'demo.core/run)")))))))))

;; ---------------------------------------------------------------------------
;; 034, 035
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-034
;; @spec MCP-OP-QUERY-030
(deftest the-cold-build-ingests-every-file-once-and-reports-built-ms
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            calls (atom [])
            counting (fn [input] (swap! calls conj (:path input)) (ingest/file-result input))
            cold (run cfg "(count (namespaces))" {:ingest-fn counting :now-ms (clock-812)})
            warm (run cfg "(count (namespaces))" {:ingest-fn counting :now-ms (constantly 99999)})]
        (is (= ["src/demo/core.clj" "src/demo/other.clj"] (sort @calls))
            "two files, each ingested once over the two requests")
        (is (= 812 (get-in cold [:index :built_ms])))
        (is (= [] (reingested cold)) "the cold build is reported by built_ms")
        (is (= 812 (get-in warm [:index :built_ms])) "a warm request keeps the build time")
        (is (= [] (reingested warm)))))))

;; @spec MCP-OP-QUERY-035
(deftest a-failing-cold-build-is-refused-with-the-cause-and-leaves-no-index
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            failing (fn [_] (throw (ex-info "boom" {})))
            refused (run cfg "(namespaces)" {:ingest-fn failing})]
        (is (refusal-shape-ok? refused))
        (is (= "index-unavailable" (:error_type refused)))
        (is (str/includes? (:remedy refused) "boom"))
        (is (not (contains? refused :next_call)))
        (is (empty? (:files (some-> (get @(:code-index/registry cfg) root) :index deref))))
        (testing "a later request with a working ingest builds the index"
          (let [ok (run cfg "(count (namespaces))")]
            (is (true? (:ok ok)))
            (is (= "2" (:value ok)))))))))

;; @spec MCP-OP-QUERY-035
;; @spec MCP-OP-QUERY-031
(deftest a-failing-reingest-is-refused-and-the-file-stays-stale
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (run cfg "(namespaces)")
        (put! root "src/demo/core.clj"
              (str/replace core-text "Helps. More." "Aids.  More.")
              (+ t0 5000))
        (let [refused (run cfg "(skeleton 'demo.core)"
                           {:ingest-fn (fn [_] (throw (ex-info "disk gone" {})))})]
          (is (= "index-unavailable" (:error_type refused)))
          (is (str/includes? (:remedy refused) "disk gone")))
        (let [retry (run cfg "(skeleton 'demo.core)")]
          (is (true? (:ok retry)))
          (is (= ["src/demo/core.clj"] (reingested retry))
              "the failed re-ingest changed nothing, so the file is still stale")
          (is (= (str/replace skeleton-core "Helps." "Aids.") (:value retry))))))))

;; ---------------------------------------------------------------------------
;; 072, 073, 074
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-072
;; @spec MCP-OP-QUERY-074
(deftest a-def-renders-the-shape-and-lists-the-name-in-session-defs
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            opts {:session-id "s1"}
            r1 (run cfg "(def v [1 2 3])" opts)
            r2 (run cfg "(def m {:a 1 :b 2})" opts)
            r3 (run cfg "(def s \"abc\")" opts)]
        (is (= "#'user/v\nvector of 3" (:value r1)))
        (is (= {:defs ["v"] :reset false} (:session r1)))
        (is (= "#'user/m\nmap of 2" (:value r2)))
        (is (= {:defs ["m" "v"] :reset false} (:session r2)))
        (is (= "#'user/s\nstring of 3" (:value r3)))
        (is (= {:defs ["m" "s" "v"] :reset false} (:session r3)))))))

;; @spec MCP-OP-QUERY-072
(deftest session-defs-follow-the-session-across-requests
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            _ (run cfg "(def v [1 2 3])" {:session-id "s1"})
            later (run cfg "(count v)" {:session-id "s1"})
            other (run cfg "(count v)" {:session-id "s2"})
            fresh (run cfg "(count v)")]
        (is (= "3" (:value later)))
        (is (= {:defs ["v"] :reset false} (:session later)))
        (is (= "denied-symbol" (:error_type other)) "s2 never def'd v")
        (is (= "denied-symbol" (:error_type fresh)) "no session id: a fresh context")
        (is (= {:defs [] :reset false} (:session (run cfg "1"))))))))

;; @spec MCP-OP-QUERY-073
(deftest a-def-past-the-name-limit-is-refused-and-leaves-the-context
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-defs 2})
            opts {:session-id "s1"}]
        (run cfg "(def a 1)" opts)
        (run cfg "(def b 2)" opts)
        (let [refused (run cfg "(def c 3)" opts)]
          (is (refusal-shape-ok? refused))
          (is (= "session-full" (:error_type refused)))
          (is (string? (:remedy refused)))
          (is (not (contains? refused :next_call))))
        (let [after (run cfg "(+ a b)" opts)]
          (is (= "3" (:value after)))
          (is (= {:defs ["a" "b"] :reset false} (:session after))
              "the refused def left the context as it was"))
        (testing "a redefinition does not add a name"
          (let [redef (run cfg "(def a 10)" opts)]
            (is (true? (:ok redef)))
            (is (= ["a" "b"] (get-in redef [:session :defs])))))))))

;; ---------------------------------------------------------------------------
;; 084, 086, 087, 088
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-084
;; @spec MCP-OP-QUERY-088
(deftest an-expression-over-the-bound-is-refused-before-any-read
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-expression-chars 10})
            touched (atom 0)
            opts {:discover-fn (fn [_ _] (swap! touched inc) (throw (ex-info "disk" {})))}
            refused (q/execute-request! cfg {"expression" "(+ 1 2 345)"} opts)]
        (is (refusal-shape-ok? refused))
        (is (= "expression-too-large" (:error_type refused)))
        (is (string? (:remedy refused)))
        (is (not (contains? refused :next_call)))
        (is (not (contains? refused :workspace_root)))
        (is (zero? @touched))
        (testing "an expression of exactly the bound is read"
          (let [ok (run cfg "(+ 1 2 34)")]
            (is (= 10 (count "(+ 1 2 34)")))
            (is (true? (:ok ok)))
            (is (= "37" (:value ok)))))))))

;; @spec MCP-OP-QUERY-086
;; @spec MCP-OP-QUERY-088
(deftest a-value-over-the-result-bound-is-refused-with-a-narrower-next-call
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-result-chars 50})
            forms (q/execute-request!
                   cfg {"expression" "(form '[demo.core/helper demo.core/run])"
                        "workspace_root" root} {})]
        (is (refusal-shape-ok? forms))
        (is (= "result-too-large" (:error_type forms)))
        (is (= {:tool "query_clojure"
                :expression "(skeleton 'demo.core)"
                :workspace_root root}
               (:next_call forms)))
        (is (not (contains? forms :remedy)))
        (testing "the same request under the default bound succeeds"
          (is (true? (:ok (run (config root) "(form '[demo.core/helper demo.core/run])")))))))))

;; @spec MCP-OP-QUERY-086
(deftest a-refs-value-over-the-result-bound-narrows-to-no-tests
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-result-chars 30})
            refs (run cfg "(refs 'demo.core/helper)")
            narrowed (run cfg "(refs 'demo.core/helper {:include-tests false})")]
        (is (= "result-too-large" (:error_type refs)))
        (is (= {:tool "query_clojure"
                :expression "(refs 'demo.core/helper {:include-tests false})"}
               (:next_call refs)))
        (testing "no narrower call exists when tests are already left out"
          (is (= "result-too-large" (:error_type narrowed)))
          (is (string? (:remedy narrowed)))
          (is (not (contains? narrowed :next_call))))))))

;; @spec MCP-OP-QUERY-086
(deftest a-single-form-over-the-result-bound-has-a-remedy-and-no-next-call
  (with-workspace
    (fn [root]
      (let [result (run (config root {:max-result-chars 20}) "(form 'demo.core/run)")]
        (is (= "result-too-large" (:error_type result)))
        (is (string? (:remedy result)))
        (is (not (contains? result :next_call)))))))

;; @spec MCP-OP-QUERY-087
;; @spec MCP-OP-QUERY-086
(deftest a-skeleton-is-bound-by-the-transport-ceiling-not-the-result-bound
  (with-workspace
    (fn [root]
      (testing "over the ceiling: refused with a remedy"
        (let [refused (run (config root {:max-skeleton-chars 20}) "(skeleton 'demo.core)")]
          (is (refusal-shape-ok? refused))
          (is (= "result-too-large" (:error_type refused)))
          (is (string? (:remedy refused)))
          (is (not (contains? refused :next_call)))))
      (testing "under the ceiling but over the result bound: published"
        (let [ok (run (config root {:max-result-chars 10 :max-skeleton-chars 1000})
                      "(skeleton 'demo.core)")]
          (is (true? (:ok ok)))
          (is (= skeleton-core (:value ok)))))
      (testing "a rendering of exactly the ceiling is published"
        (let [n (count skeleton-core)
              ok (run (config root {:max-skeleton-chars n}) "(skeleton 'demo.core)")]
          (is (true? (:ok ok))))))))

;; @spec MCP-OP-QUERY-088
(deftest the-error-ceiling-comes-from-the-same-configuration-map
  (with-workspace
    (fn [root]
      (let [small (run (config root {:max-error-chars 12}) "(nth [1 2] 5)")
            large (run (config root) "(nth [1 2] 5)")]
        (is (= "evaluation-error" (:error_type small)))
        (is (<= (count (:error small)) 12))
        (is (= "evaluation-error" (:error_type large)))
        (is (< 12 (count (:error large))) "the default ceiling is far above 12")))))

;; ---------------------------------------------------------------------------
;; Coverage gaps closed after the test workflow (056, 079, 081)

;; @spec MCP-OP-QUERY-056
(deftest only-the-final-value-is-rendered-into-value
  (with-workspace
    (fn [root]
      (let [result (run (config root) "(do (skeleton 'demo.other) \"done\")")]
        (is (:ok result))
        (is (= "done" (:value result))
            "the skeleton evaluated before the final form leaves no text in value")))))

;; @spec MCP-OP-QUERY-079
(deftest the-context-resolves-no-host-class
  (with-workspace
    (fn [root]
      (doseq [expression ["java.lang.System"
                          "(java.io.File. \"x\")"
                          "(Class/forName \"java.lang.Runtime\")"
                          "(.exec (Runtime/getRuntime) \"true\")"]]
        (let [result (run (config root) expression)]
          (is (= false (:ok result)) expression)
          (is (= "denied-symbol" (:error_type result)) expression))))))

;; @spec MCP-OP-QUERY-081
(deftest the-functions-look-names-up-in-the-index-and-never-open-a-path
  (with-workspace
    (fn [root]
      (doseq [[expression error-type] [["(skeleton \"../../../../etc/passwd\")" "unknown-namespace"]
                                       ["(skeleton \"/etc/passwd\")" "unknown-namespace"]
                                       ["(form \"etc/passwd\")" "unknown-var"]
                                       ["(refs \"deps.edn/paths\")" "unknown-var"]]]
        (let [result (run (config root) expression)]
          (is (= false (:ok result)) expression)
          (is (= error-type (:error_type result)) expression)
          (is (not (str/includes? (str result) "root:"))
              "no byte of a file outside the index reaches the result"))))))
