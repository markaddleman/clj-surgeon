(ns clj-surgeon.mcp-query-clojure-test
  "End-to-end witnesses for the query_clojure tool (MCP-OP-QUERY-001 to 008,
   030 to 035, 072 to 074, 084, 086 to 088).

   Every test builds a small workspace under java.io.tmpdir and calls
   `mcp-query-clojure/execute-request!` with a fresh registry and a fresh
   sessions object, so no test shares state with another."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index-discovery :as discovery]
   [clj-surgeon.code-index-fence :as fence]
   [clj-surgeon.code-index-ingest :as ingest]
   [clj-surgeon.code-index-pull :as pull]
   [clj-surgeon.code-index-render :as render]
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
       (every? #{:ok :operation :error_type :error :next_call :remedy :problems :workspace_root}
               (keys result))))

(defn- problem-shape-ok?
  "True when `p` has error_type, error and exactly one of next_call and remedy (091)."
  [p]
  (and (string? (:error_type p))
       (string? (:error p))
       (= 1 (count (filter #(contains? p %) [:next_call :remedy])))
       (every? #{:error_type :error :next_call :remedy} (keys p))))

(defn- first-problem-copied?
  "True when the top-level error_type, error and continuation are those of the
   first problem (004)."
  [result]
  (let [p (first (:problems result))]
    (and (seq (:problems result))
         (every? problem-shape-ok? (:problems result))
         (= (select-keys p [:error_type :error :next_call :remedy])
            (select-keys result [:error_type :error :next_call :remedy])))))

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
;; @spec MCP-OP-QUERY-135
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
                :index {:files 2 :vars 3 :skipped 0 :built_ms 812}
                :read_complete true
                :skipped []
                :workspace_root root}
               result))
        (is (= {:files [{:path "src/demo/core.clj"
                         :sha256 (sha-of root "src/demo/core.clj")}]
                :reingested []}
               (select-keys (meta result) [:files :reingested]))
            "the file receipts are metadata, not keys of the reply")))))

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
                   "hi\n;; 2 chars · "))
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
  (:reingested (meta result)))

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
                 (:files (meta second-run))))
          (is (not= (:sha256 (first (:files (meta first-run))))
                    (:sha256 (first (:files (meta second-run)))))))
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
          (is (= [] (:files (meta (run cfg "(str \"a\" \"b\")"))))))
        (testing "skeleton of one namespace lists that namespace's file only"
          (is (= [core] (:files (meta (run cfg "(skeleton 'demo.core)"))))))
        (testing "namespaces lists every file, sorted by path"
          (is (= [core other] (:files (meta (run cfg "(namespaces)"))))))
        (testing "refs lists the files of the uses, not the file of the target"
          (is (= [other] (:files (meta (run cfg "(refs 'demo.core/run)"))))))
        (testing "form lists the file of the form"
          (is (= [core] (:files (meta (run cfg "(form 'demo.core/run)"))))))))))

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

;; @spec MCP-OP-QUERY-134
;; @spec MCP-OP-QUERY-117
(deftest the-item-bound-of-the-config-reaches-the-sessions-object
  ;; Issue #26. sessions-of passed only three of the four session bounds.
  ;; No :code-index/sessions here, so the tool builds its own sessions object.
  (with-workspace
    (fn [root]
      (with-redefs-fn {#'q/global-sessions (atom nil)}
        #(let [cfg (dissoc (config root {:max-def-items 10}) :code-index/sessions)
               refused (run cfg "(def big (vec (range 50)))" {:session-id "s1"})]
           (is (refusal-shape-ok? refused))
           (is (= "session-full" (:error_type refused)))
           (is (= "3" (:value (run cfg "(+ 1 2)" {:session-id "s1"})))
               "a request within the bound still runs"))))))

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

;; ---------------------------------------------------------------------------
;; C25 (091 to 095): a refusal reports every problem of the request
;; ---------------------------------------------------------------------------

(defn- error-types
  [result]
  (mapv :error_type (:problems result)))

;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-091
(deftest a-refusal-with-one-problem-has-a-list-of-one
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (doseq [expression ["(form 'demo.core/nope)" "(+ 1" "(skeleton 'zzz)" "(nth [1] 5)"]]
          (let [result (run cfg expression)]
            (testing expression
              (is (refusal-shape-ok? result))
              (is (= 1 (count (:problems result))))
              (is (first-problem-copied? result)))))))))

;; @spec MCP-OP-QUERY-091
;; @spec MCP-OP-QUERY-093
(deftest each-distinct-denied-symbol-is-its-own-problem-in-order-of-first-occurrence
  (with-workspace
    (fn [root]
      (let [touched (atom 0)
            opts {:discover-fn (fn [_ _] (swap! touched inc) (throw (ex-info "disk" {})))}
            result (q/execute-request!
                    (config root)
                    {"expression" "(do (slurp \"a\") (spit \"b\" 1) (slurp \"c\") (eval 1))"}
                    opts)]
        (is (refusal-shape-ok? result))
        (is (= ["denied-symbol" "denied-symbol" "denied-symbol"] (error-types result)))
        (is (= ["slurp" "spit" "eval"]
               (mapv #(re-find #"slurp|spit|eval" (:error %)) (:problems result))))
        (is (first-problem-copied? result))
        (is (every? #(str/includes? (:remedy %) "skeleton") (:problems result))
            "the remedy names the bound functions")
        (is (zero? @touched) "the fence refusals cost no disk read")))))

;; @spec MCP-OP-QUERY-092
;; @spec MCP-OP-QUERY-094
(deftest the-def-check-reports-each-refused-name-and-the-session-limit
  (with-workspace
    (fn [root]
      (testing "two refused names"
        (let [result (run (config root) "(do (def skeleton 1) (def map 2))")]
          (is (= ["denied-symbol" "denied-symbol"] (error-types result)))
          (is (first-problem-copied? result))))
      (testing "a denied symbol of admission, then the session limit of the def check"
        (let [result (run (config root {:max-defs 1}) "(do (slurp \"x\") (def a 1) (def b 2))")]
          (is (= ["denied-symbol" "session-full"] (error-types result)))
          (is (str/includes? (:error (first (:problems result))) "slurp"))
          (is (every? problem-shape-ok? (:problems result))))))))

;; @spec MCP-OP-QUERY-092
(deftest a-request-with-bad-fields-reports-one-problem-for-each-field
  (with-workspace
    (fn [root]
      (let [touched (atom 0)
            opts {:discover-fn (fn [_ _] (swap! touched inc) (throw (ex-info "disk" {})))}
            result (q/execute-request! (config root)
                                       {"expression" 7 "workspace_root" 3 "foo" 1 "bar" 2}
                                       opts)]
        (is (refusal-shape-ok? result))
        (is (= ["invalid-request" "invalid-request" "invalid-request" "invalid-request"]
               (error-types result)))
        (is (= ["bar" "foo"]
               (mapv #(re-find #"foo|bar" (:error %)) (take 2 (:problems result))))
            "unknown fields first, sorted by name, each its own problem")
        (is (first-problem-copied? result))
        (is (zero? @touched))))))

;; @spec MCP-OP-QUERY-092
(deftest a-root-problem-does-not-stop-the-checks-of-the-expression
  (with-workspace
    (fn [root]
      (let [result (q/execute-request!
                    (config root)
                    {"expression" "(slurp \"x\")" "workspace_root" "relative/dir"}
                    {})]
        (is (refusal-shape-ok? result))
        (is (= ["invalid-workspace-root" "denied-symbol"] (error-types result)))
        (is (first-problem-copied? result)))
      (let [result (q/execute-request!
                    (config root)
                    {"expression" "(+ 1" "workspace_root" "relative/dir"}
                    {})]
        (is (= ["invalid-workspace-root" "invalid-expression"] (error-types result))))
      (testing "the length check, the root check and the other checks all run"
        (let [result (q/execute-request!
                      (config root {:max-expression-chars 10})
                      {"expression" "(slurp \"abcdefgh\")" "workspace_root" "relative/dir"}
                      {})]
          (is (= ["expression-too-large" "invalid-workspace-root"] (error-types result))
              "the reading and admission need an expression within the bound"))))))

;; @spec MCP-OP-QUERY-092
;; @spec MCP-OP-QUERY-102
(deftest a-malformed-special-form-is-an-invalid-expression-problem
  (with-workspace
    (fn [root]
      (let [result (run (config root) "(let* [a] a)")]
        (is (refusal-shape-ok? result))
        (is (= ["invalid-expression"] (error-types result)))
        (is (string? (:remedy result)))))))

;; @spec MCP-OP-QUERY-005
;; @spec MCP-OP-QUERY-091
(deftest no-problem-has-a-next-call-that-equals-the-request
  (with-workspace
    (fn [root]
      (doseq [params [{"expression" "(do (slurp 1) (spit 2 3))"}
                      {"expression" "(skeleton 'demo.cor)" "workspace_root" root}
                      {"expression" 9 "foo" 1}]]
        (let [result (q/execute-request! (config root) params {})
              normalized (into {} (map (fn [[k v]] [(keyword k) v])) params)]
          (testing (pr-str params)
            (is (refusal-shape-ok? result))
            (doseq [p (:problems result)]
              (is (problem-shape-ok? p))
              (when-let [call (:next_call p)]
                (is (not= (dissoc call :tool) normalized))))))))))

;; @spec MCP-OP-QUERY-095
(deftest a-problem-of-the-refresh-or-the-evaluation-is-alone
  (with-workspace
    (fn [root]
      (let [cfg (config root)]
        (doseq [[expression error-type] [["(form 'demo.core/nope)" "unknown-var"]
                                         ["(skeleton 'zzz)" "unknown-namespace"]
                                         ["(nth [1] 5)" "evaluation-error"]
                                         [pull-deep "pull-too-deep"]]]
          (let [result (run cfg expression)]
            (testing expression
              (is (= [error-type] (error-types result)))
              (is (first-problem-copied? result)))))
        (let [refused (run (config root) "(namespaces)"
                           {:ingest-fn (fn [_] (throw (ex-info "boom" {})))})]
          (is (= ["index-unavailable"] (error-types refused)))
          (is (first-problem-copied? refused)))))))

;; ---------------------------------------------------------------------------
;; Issue 11 (108): the refresh runs under a time bound
;; ---------------------------------------------------------------------------

(defn- ticking
  "A clock and an ingest function that moves it: every ingest takes `step` ms."
  [step]
  (let [t (atom 0)
        paths (atom [])]
    {:now-ms (fn [] @t)
     :ingest-fn (fn [input]
                  (swap! paths conj (:path input))
                  (swap! t + step)
                  (ingest/file-result input))
     :paths paths}))

(defn- add-sources!
  [root]
  (put! root "src/demo/third.clj" "(ns demo.third)\n\n(defn three [] 3)\n")
  (put! root "src/demo/fourth.clj" "(ns demo.fourth)\n\n(defn four [] 4)\n"))

;; @spec MCP-OP-QUERY-108
(deftest a-cold-build-over-the-refresh-bound-keeps-the-finished-files-and-asks-for-a-new-call
  (with-workspace
    (fn [root]
      (add-sources! root)
      (let [cfg (config root {:refresh-timeout-ms 1500 :pool-size 1})
            {:keys [now-ms ingest-fn paths]} (ticking 1000)
            refused (run cfg "(count (namespaces))" {:now-ms now-ms :ingest-fn ingest-fn})]
        (is (refusal-shape-ok? refused))
        (is (= ["timeout"] (error-types refused)))
        (is (str/includes? (:remedy refused) "again") "the remedy tells the agent to call again")
        (is (= 2 (count @paths)) "the check before each file stopped the third read")
        (is (= 2 (count (:files (some-> (get @(:code-index/registry cfg) root) :index deref))))
            "the files that it finished are in the index")
        (testing "the next call finishes the build, and nothing is read twice"
          (let [ok (run cfg "(count (namespaces))" {:now-ms now-ms :ingest-fn ingest-fn})]
            (is (true? (:ok ok)))
            (is (= "4" (:value ok)))
            (is (= 4 (count @paths)))
            (is (= 4 (count (distinct @paths))))))))))

;; @spec MCP-OP-QUERY-108
(deftest a-warm-refresh-over-the-bound-keeps-the-finished-files-and-asks-for-a-new-call
  (with-workspace
    (fn [root]
      (add-sources! root)
      (let [cfg (config root {:refresh-timeout-ms 1500})
            warm (run cfg "(count (namespaces))")]
        (is (true? (:ok warm)))
        (doseq [f ["core" "other" "third"]]
          (put! root (str "src/demo/" f ".clj")
                (str (slurp (io/file root (str "src/demo/" f ".clj"))) "\n;; changed\n")
                (+ t0 90000)))
        (let [{:keys [now-ms ingest-fn paths]} (ticking 1000)
              refused (run cfg "(count (namespaces))" {:now-ms now-ms :ingest-fn ingest-fn})]
          (is (= ["timeout"] (error-types refused)))
          (is (= 2 (count @paths)))
          (let [ok (run cfg "(count (namespaces))")]
            (is (true? (:ok ok)))
            (is (= 1 (count (reingested ok))) "only the file that the first call did not finish")))))))

;; @spec MCP-OP-QUERY-108
(deftest a-discovery-that-passes-the-bound-is-a-timeout-refusal
  (with-workspace
    (fn [root]
      (let [seen (atom nil)
            refused (run (config root {:refresh-timeout-ms 5000}) "1"
                         {:discover-fn (fn [_ bounds]
                                         (reset! seen bounds)
                                         (throw (ex-info "late" {:error-type :timeout})))})]
        (is (fn? (:expired? @seen)) "discovery receives the expiry check")
        (is (= ["timeout"] (error-types refused)))
        (is (str/includes? (:remedy refused) "again"))))))

;; @spec MCP-OP-QUERY-108
(deftest a-refresh-inside-the-bound-is-not-refused
  (with-workspace
    (fn [root]
      (let [{:keys [now-ms ingest-fn paths]} (ticking 10)
            ok (run (config root {:refresh-timeout-ms 1500}) "(count (namespaces))"
                    {:now-ms now-ms :ingest-fn ingest-fn})]
        (is (true? (:ok ok)))
        (is (= 2 (count @paths)))))))

;; @spec MCP-OP-QUERY-004
;; @spec MCP-OP-QUERY-091
(deftest caller-text-in-every-problem-is-canonical
  (with-workspace
    (fn [root]
      (let [result (q/execute-request! (config root)
                                       {"expression" "1" "bad\n✓ forged" 1 "x\ny" 2}
                                       {})]
        (is (= 2 (count (:problems result))))
        (is (every? #(not (str/includes? (str (:error %) (:remedy %)) "\n")) (:problems result))
            "a newline in a field name cannot forge a line in a problem")
        (is (first-problem-copied? result))))))

;; ---------------------------------------------------------------------------
;; Fix round 1b: the bounds of 107, 109, 112 and 119 reach the work (129)
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-119
;; @spec MCP-OP-QUERY-129
(deftest the-total-byte-cap-skips-the-files-past-it-and-the-next-call-reads-them
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-total-bytes 1 :pool-size 1})
            first-call (run cfg "(count (namespaces))")]
        (is (true? (:ok first-call)))
        (is (= "1" (:value first-call)) "only the first file in path order was read")
        (is (false? (:read_complete first-call)))
        (is (= [{:file "src/demo/other.clj" :reason "total-bytes"
                 :size (count (.getBytes ^String other-text "UTF-8"))}]
               (:skipped first-call)))
        (is (= 1 (get-in first-call [:index :skipped])))
        (testing "the next call reads what the cap left out"
          (let [second-call (run cfg "(count (namespaces))")]
            (is (= "2" (:value second-call)))
            (is (true? (:read_complete second-call)))
            (is (= [] (:skipped second-call)))))))))

;; @spec MCP-OP-QUERY-119
(deftest a-cap-above-the-bytes-of-the-files-skips-nothing
  (with-workspace
    (fn [root]
      (let [ok (run (config root {:max-total-bytes 1000000}) "(count (namespaces))")]
        (is (= "2" (:value ok)))
        (is (true? (:read_complete ok)))))))

(defn- add-fan!
  [root]
  (put! root "src/demo/fan.clj"
        (str "(ns demo.fan)\n"
             "(defn a [] 1)\n(defn b [] 2)\n(defn c [] 3)\n"
             "(defn all [] (a) (b) (c))\n")))

(def ^:private fan-pull
  "(pull '[:var/qname {:var/calls [:var/qname]}] [:var/qname \"demo.fan/all\"])")

;; @spec MCP-OP-QUERY-112
;; @spec MCP-OP-QUERY-128
;; @spec MCP-OP-QUERY-129
(deftest a-pull-over-the-node-budget-is-result-too-large-with-a-remedy
  (with-workspace
    (fn [root]
      (add-fan! root)
      (let [refused (run (config root {:max-pull-nodes 2}) fan-pull)]
        (is (refusal-shape-ok? refused))
        (is (= ["result-too-large"] (error-types refused)))
        (is (string? (:remedy refused)))
        (is (str/includes? (:remedy refused) "2") "the remedy names the budget"))
      (testing "inside the budget the same pull answers"
        (let [ok (run (config root {:max-pull-nodes 10}) fan-pull)]
          (is (true? (:ok ok)))
          (is (str/includes? (str (:value ok)) "demo.fan/a")))))))

;; @spec MCP-OP-QUERY-107
;; @spec MCP-OP-QUERY-129
(deftest the-bound-pull-gets-the-node-budget-and-the-deadline-check
  (with-workspace
    (fn [root]
      (let [seen (atom nil)
            ok (with-redefs [pull/pull (fn [_ _ _ opts] (reset! seen opts) {:var/qname "x"})]
                 (run (config root {:max-pull-nodes 77})
                      "(pull [:var/qname] [:var/qname \"demo.core/run\"])"))]
        (is (true? (:ok ok)))
        (is (= 77 (:max-pull-nodes @seen)))
        (is (fn? (:interrupt-fn @seen)))
        (testing "the hook is the deadline check of the session"
          (is (nil? ((:interrupt-fn @seen))) "no deadline on this thread: no throw")
          (let [data (try (binding [session/*deadline* {:at (- (System/nanoTime) 1000) :limit-ms 5}]
                            ((:interrupt-fn @seen))
                            ::no-throw)
                          (catch Throwable t (ex-data t)))]
            (is (= :timeout (:error-type data)))))))))

;; @spec MCP-OP-QUERY-107
;; @spec MCP-OP-QUERY-129
(deftest the-renderer-gets-the-deadline-check
  (with-workspace
    (fn [root]
      (let [seen (atom nil)
            original render/render-bounded
            ok (with-redefs [render/render-bounded (fn [value limit interrupt-fn]
                                                     (reset! seen interrupt-fn)
                                                     (original value limit interrupt-fn))]
                 (run (config root) "(namespaces)"))]
        (is (true? (:ok ok)))
        (is (fn? @seen))
        (let [data (try (binding [session/*deadline* {:at (- (System/nanoTime) 1000) :limit-ms 5}]
                          (@seen)
                          ::no-throw)
                        (catch Throwable t (ex-data t)))]
          (is (= :timeout (:error-type data))))))))

;; @spec MCP-OP-QUERY-109
;; @spec MCP-OP-QUERY-129
(deftest the-worker-run-gets-the-stop-grace-and-the-worker-cap-from-the-bounds
  (with-workspace
    (fn [root]
      (let [original session/run-with-timeout
            seen-by (fn [bounds]
                      (let [seen (atom nil)]
                        (with-redefs [session/run-with-timeout
                                      (fn [ms f opts] (reset! seen opts) (original ms f opts))]
                          (run (config root bounds) "1"))
                        @seen))]
        (is (= {:grace-ms 1000 :worker-cap 2} (seen-by {})) "the defaults of the bounds")
        (is (= {:grace-ms 55 :worker-cap 7}
               (seen-by {:stop-grace-ms 55 :max-live-workers 7})))))))

;; @spec MCP-OP-QUERY-109
;; @spec MCP-OP-QUERY-128
(deftest a-request-that-meets-the-worker-cap-is-busy-with-a-remedy
  (with-workspace
    (fn [root]
      (let [refused (run (config root {:max-live-workers 0}) "1")]
        (is (refusal-shape-ok? refused))
        (is (= ["busy"] (error-types refused)))
        (is (string? (:remedy refused)))))))

;; ---------------------------------------------------------------------------
;; Issue #19: no Throwable leaves a step before the worker (124 to 127)
;; ---------------------------------------------------------------------------

(defn- nested
  [open close n]
  (str (str/join (repeat n open)) (str/join (repeat n close))))

;; @spec MCP-OP-QUERY-124
(deftest a-deeply-nested-expression-is-expression-too-deep-through-the-tool
  ;; Issue #19. Each of these threw a raw StackOverflowError out of the reader.
  (with-workspace
    (fn [root]
      (doseq [[what text] [["vectors" (nested "[" "]" 1900)]
                           ["lists" (nested "(" ")" 1000)]
                           ["quotes" (str (str/join (repeat 1900 "'")) "1")]]]
        (let [result (run (config root) text)]
          (is (refusal-shape-ok? result) what)
          (is (= ["expression-too-deep"] (error-types result)) what)
          (is (str/includes? (:remedy result) "256") (str what ": the remedy gives the bound"))
          (is (not (contains? result :workspace_root)) what))))))

;; @spec MCP-OP-QUERY-124
(deftest the-depth-bound-comes-from-the-effective-bounds
  (with-workspace
    (fn [root]
      (let [cfg (config root {:max-expression-depth 3})
            refused (run cfg "[[[[1]]]]")]
        (is (= ["expression-too-deep"] (error-types refused)))
        (is (str/includes? (:remedy refused) "3"))
        (is (str/includes? (:remedy refused) "4") "the remedy gives the depth found"))
      (testing "an expression at the bound is read"
        (is (true? (:ok (run (config root {:max-expression-depth 3}) "[[[1]]]"))))))))

;; @spec MCP-OP-QUERY-124
;; @spec MCP-OP-QUERY-092
(deftest the-length-check-comes-before-the-depth-check
  (with-workspace
    (fn [root]
      (let [result (run (config root) (nested "[" "]" 2500))]
        (is (= ["expression-too-large"] (error-types result)))))))

;; @spec MCP-OP-QUERY-125
(deftest a-stack-overflow-in-admission-is-a-too-deep-refusal
  (with-workspace
    (fn [root]
      (let [result (with-redefs [fence/admit (fn [_ _] (throw (StackOverflowError.)))]
                     (run (config root) "(inc 1)"))]
        (is (refusal-shape-ok? result))
        (is (= ["expression-too-deep"] (error-types result)))
        (is (str/includes? (str/lower-case (:remedy result)) "flatten"))))))

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-083
(deftest a-stack-overflow-in-the-evaluation-is-expression-too-deep
  (with-workspace
    (fn [root]
      (let [result (run (config root) "((fn f [] (inc (f))))")]
        (is (refusal-shape-ok? result))
        (is (= ["expression-too-deep"] (error-types result)))
        (is (str/includes? (str/lower-case (:remedy result)) "flatten"))))))

;; @spec MCP-OP-QUERY-125
;; @spec MCP-OP-QUERY-126
(deftest another-throwable-in-a-step-is-internal-error-and-the-other-steps-still-run
  (with-workspace
    (fn [root]
      (let [boom (fn [& _] (throw (AssertionError. (str/join (repeat 3000 "x")))))
            result (with-redefs [fence/check-def boom]
                     (run (config root) "(eval 1)"))
            [denied internal] (:problems result)]
        (is (every? problem-shape-ok? (:problems result)))
        (is (= ["denied-symbol" "internal-error"] (error-types result))
            "admission still ran; the def check is a problem of its own step")
        (is (str/starts-with? (:error internal) "java.lang.AssertionError"))
        (is (<= (count (:error internal)) 1100))
        (is (str/includes? (:remedy internal) "again"))
        (is (not (str/includes? (pr-str result) "at clj_surgeon")) "no stack trace")
        (is (= "denied-symbol" (:error_type denied)))))))

;; @spec MCP-OP-QUERY-125
(deftest a-throwable-in-the-reading-step-is-internal-error
  (with-workspace
    (fn [root]
      (let [result (with-redefs [fence/read-expression (fn [& _] (throw (AssertionError. "read broke")))]
                     (run (config root) "(inc 1)"))]
        (is (= ["internal-error"] (error-types result)))
        (is (str/includes? (:error result) "read broke"))))))

;; @spec MCP-OP-QUERY-125
(deftest a-throwable-outside-the-steps-is-internal-error-and-never-leaves
  (with-workspace
    (fn [root]
      (testing "the session checkout"
        (let [result (with-redefs [session/checkout! (fn [& _] (throw (AssertionError. "checkout broke")))]
                       (run (config root) "(inc 1)"))]
          (is (refusal-shape-ok? result))
          (is (= ["internal-error"] (error-types result)))))
      (testing "the outer backstop, for a stack overflow"
        (let [result (with-redefs-fn {#'q/bounds-of (fn [_] (throw (StackOverflowError.)))}
                       #(run (config root) "(inc 1)"))]
          (is (refusal-shape-ok? result))
          (is (= ["expression-too-deep"] (error-types result)))))
      (testing "the outer backstop, for another Error"
        (let [result (with-redefs-fn {#'q/bounds-of (fn [_] (throw (OutOfMemoryError. "heap")))}
                       #(run (config root) "(inc 1)"))]
          (is (= ["internal-error"] (error-types result)))
          (is (str/includes? (:error result) "OutOfMemoryError")))))))

;; @spec MCP-OP-QUERY-127
(deftest invoke-contains-any-throwable-when-it-is-given-an-operation
  (let [published (atom [])
        callback (fn [content error? result]
                   (swap! published conj {:content content :error? error? :result result}))
        base {:operation "query-clojure"
              :summarize q/summary
              :callback callback}]
    (testing "execute throws"
      (reset! published [])
      (let [body (mcp-operation/invoke! (assoc base :execute #(throw (AssertionError. "execute broke"))))
            {:keys [error? result]} (first @published)]
        (is (= 1 (count @published)))
        (is (true? error?))
        (is (false? (:ok result)))
        (is (= "query-clojure" (:operation result)))
        (is (= "internal-error" (:error_type result)))
        (is (str/includes? (:error result) "execute broke"))
        (is (string? (:remedy result)))
        (is (number? (:elapsed_ms result)))
        (is (string? body))))
    (testing "execute overflows the stack"
      (reset! published [])
      (mcp-operation/invoke! (assoc base :execute #(throw (StackOverflowError.))))
      (is (= "expression-too-deep" (get-in (first @published) [:result :error_type])))
      (is (string? (get-in (first @published) [:result :remedy]))))
    (testing "summarize throws"
      (reset! published [])
      (mcp-operation/invoke! (assoc base
                                    :execute (constantly {:ok true :operation "query-clojure" :value "1"
                                                          :value_chars 1 :files [] :reingested []})
                                    :summarize (fn [r] (if (:ok r)
                                                         (throw (AssertionError. "summary broke"))
                                                         (q/summary r)))))
      (is (= "internal-error" (get-in (last @published) [:result :error_type]))))
    (testing "the callback throws once"
      (reset! published [])
      (let [calls (atom 0)
            body (mcp-operation/invoke!
                  (assoc base
                         :execute (constantly {:ok true :operation "query-clojure" :value "1"
                                               :value_chars 1 :files [] :reingested []})
                         :callback (fn [content error? result]
                                     (when (= 1 (swap! calls inc))
                                       (throw (AssertionError. "callback broke")))
                                     (swap! published conj {:content content :error? error? :result result}))))]
        (is (string? body))
        (is (= "internal-error" (get-in (last @published) [:result :error_type])))))))

;; @spec MCP-OP-QUERY-127
(deftest the-handler-publishes-a-refusal-when-the-execution-throws
  (with-workspace
    (fn [root]
      (let [published (atom [])]
        (try
          (q/init! (config root))
          (with-redefs [q/execute-request! (fn [& _] (throw (AssertionError. "boom")))]
            (q/handle-query-clojure {:session-id "s1"} {"expression" "1"}
                                    (fn [content error? result]
                                      (swap! published conj {:content content :error? error? :result result}))))
          (let [{:keys [content error? result]} (first @published)]
            (is (true? error?))
            (is (= "internal-error" (:error_type result)))
            (is (= "query-clojure" (:operation result)))
            (is (number? (:elapsed_ms result)))
            (is (str/starts-with? (first content) "query_clojure refused · internal-error · ")))
          (finally (q/init! nil)))))))

;; @spec MCP-OP-QUERY-141
(deftest a-warm-refresh-with-no-change-keeps-the-identical-index-map
  (with-workspace
    (fn [root]
      (let [cfg (config root)
            index-now #(some-> (get @(:code-index/registry cfg) root) :index deref)]
        (run cfg "1")
        (let [before (index-now)]
          (run cfg "(skeleton 'demo.core)")
          (is (identical? before (index-now)) "nothing changed, so the same map")
          (put! root "src/demo/core.clj" (str core-text "\n;; changed\n") (+ t0 120000))
          (run cfg "1")
          (is (not (identical? before (index-now))) "a change gives a new map"))))))
