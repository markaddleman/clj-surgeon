(ns clj-surgeon.code-index-performance-test
  "Wall-clock witnesses for the code index (MCP-OP-QUERY-089 and 090).

   The workspace is generated: 700 namespaces of five vars each, under
   java.io.tmpdir. Each namespace calls the last var of the one before it, so
   the call graph is one long chain. The measurements use the real ingest."
  {:lane :battery}
  (:require
   [clj-surgeon.code-index-session :as session]
   [clj-surgeon.mcp-query-clojure :as q]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private ns-count 700)
(def ^:private vars-per-ns 5)

(def ^:dynamic *root* nil)

(defn- ns-text
  [i]
  (let [prev (dec i)]
    (str "(ns perf.n" i "\n"
         "  \"Synthetic namespace " i ".\""
         (when (pos? i) (str "\n  (:require [perf.n" prev " :as prev])"))
         ")\n\n"
         (apply str
                (for [v (range vars-per-ns)]
                  (str "(defn f" v "\n"
                       "  \"Var " v " of namespace " i ". More text.\"\n"
                       "  [x]\n"
                       (cond
                         (and (zero? v) (pos? i)) (str "  (prev/f" (dec vars-per-ns) " x))\n\n")
                         (pos? v) (str "  (f" (dec v) " (inc x)))\n\n")
                         :else "  (inc x))\n\n")))))))

(defn- generate!
  [root]
  (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
  (doseq [i (range ns-count)]
    (let [f (io/file root (str "src/perf/n" i ".clj"))]
      (io/make-parents f)
      (spit f (ns-text i)))))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete ^java.io.File f)))

(defn- workspace-fixture
  [f]
  (let [root (.getCanonicalPath
              (.toFile (Files/createTempDirectory
                        "clj-surgeon-perf" (make-array FileAttribute 0))))]
    (try
      (generate! root)
      (binding [*root* root] (f))
      (finally (delete-tree! root)))))

(use-fixtures :once workspace-fixture)

(defn- fresh-config
  []
  {:project-root *root*
   :code-index/registry (atom {})
   :code-index/sessions (session/sessions {})})

(defn- timed
  "Run (f), return [result elapsed-ms]."
  [f]
  (let [start (System/nanoTime)
        result (f)]
    [result (/ (- (System/nanoTime) start) 1e6)]))

(defn- best-of
  "The fastest of n runs of (f) in ms, and the last result."
  [n f]
  (let [runs (repeatedly n #(timed f))]
    [(first (last runs)) (apply min (map second runs))]))

(defn- used-heap-bytes
  []
  (System/gc)
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

;; @spec MCP-OP-QUERY-090
(deftest a-cold-build-of-700-namespaces-finishes-within-30-seconds-under-1-gb
  (let [cfg (fresh-config)
        [result ms] (timed #(q/execute-request! cfg {"expression" "(count (namespaces))"} {}))
        heap (used-heap-bytes)]
    (is (true? (:ok result)) (pr-str (select-keys result [:error_type :error])))
    (is (= (str ns-count) (:value result)))
    (is (= {:files ns-count :vars (* ns-count vars-per-ns) :skipped 0}
           (select-keys (:index result) [:files :vars :skipped])))
    (is (true? (:read_complete result)))
    (is (< ms 30000) (str "cold build took " ms " ms"))
    (is (< (:built_ms (:index result)) 30000))
    (is (< heap (* 1024 1024 1024)) (str "used heap " heap " bytes"))))

;; @spec MCP-OP-QUERY-089
(deftest warm-requests-on-700-namespaces-meet-the-latency-bounds
  (let [cfg (fresh-config)
        cold (q/execute-request! cfg {"expression" "1"} {})
        run (fn [expression]
              (best-of 5 #(q/execute-request! cfg {"expression" expression} {})))]
    (is (true? (:ok cold)))
    (testing "skeleton"
      (let [[result ms] (run "(skeleton 'perf.n350)")]
        (is (true? (:ok result)))
        (is (= [] (:reingested result)))
        (is (< ms 100) (str "skeleton took " ms " ms"))))
    (testing "form"
      (let [[result ms] (run "(form 'perf.n350/f2)")]
        (is (true? (:ok result)))
        (is (< ms 100) (str "form took " ms " ms"))))
    (testing "refs"
      (let [[result ms] (run "(refs 'perf.n349/f4)")]
        (is (true? (:ok result)))
        (is (= "src/perf/n350.clj:8 perf.n350/f0 alias-call\ntotal 1" (:value result)))
        (is (< ms 100) (str "refs took " ms " ms"))))
    (testing "a typical pull"
      (let [[result ms] (run "(pull '[:var/qname {:var/calls [:var/qname]} {:var/_calls [:var/qname]}] [:var/qname \"perf.n350/f0\"])")]
        (is (true? (:ok result)))
        (is (< ms 500) (str "pull took " ms " ms"))))))
