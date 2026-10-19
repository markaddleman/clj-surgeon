(ns clj-surgeon.code-index-discovery-test
  "Witnesses for workspace discovery, stable reads and SHA-256 (MCP-OP-QUERY-009
   through 017). Discovery tests use temp workspaces under java.io.tmpdir that a
   finally block deletes. The read-stable tests inject stat and read sequences."
  {:lane :fast}
  (:require
   [clj-surgeon.code-index-discovery :as disc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io File IOException)
   (java.nio.file Files LinkOption Path Paths)
   (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Temp workspace helpers
;; ---------------------------------------------------------------------------

(def ^:private no-attrs (make-array FileAttribute 0))

(defn- tmp-dir
  "Create a temp directory under java.io.tmpdir. Return its canonical Path."
  [prefix]
  (let [base (Paths/get (System/getProperty "java.io.tmpdir") (make-array String 0))
        dir  (Files/createTempDirectory base prefix no-attrs)]
    (.toRealPath dir (make-array LinkOption 0))))

(defn- delete-tree!
  "Delete a tree. A symbolic link is deleted itself and never followed."
  [^Path p]
  (when (Files/exists p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (when (Files/isDirectory p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (with-open [s (Files/list p)]
        (doseq [c (vec (iterator-seq (.iterator s)))]
          (delete-tree! c))))
    (Files/delete p)))

(defn- with-dirs
  "Call (f root outside) with two fresh canonical temp dirs. Delete both after."
  [f]
  (let [root    (tmp-dir "ci-disc-root")
        outside (tmp-dir "ci-disc-outside")]
    (try
      (f (str root) (str outside))
      (finally
        (delete-tree! root)
        (delete-tree! outside)))))

(defn- put!
  "Write text to root/rel, creating parent directories."
  [root rel text]
  (let [^File f (File. ^String root ^String rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    f))

(defn- symlink!
  "Create a symbolic link at root/rel that points to target."
  [root rel target]
  (let [^File f (File. ^String root ^String rel)]
    (.mkdirs (.getParentFile f))
    (Files/createSymbolicLink (.toPath f) (Paths/get (str target) (make-array String 0)) no-attrs)))

(defn- paths
  [discovery]
  (mapv :path (:files discovery)))

(defn- clj
  [ns-name]
  (str "(ns " ns-name ")\n"))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-009 build files and source paths
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-009
(deftest deps-edn-paths-select-the-source-files
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\" \"extra\"]}")
      (put! root "src/a/b.clj" (clj "a.b"))
      (put! root "src/a/c.cljs" (clj "a.c"))
      (put! root "src/a/d.cljc" (clj "a.d"))
      (put! root "src/a/notes.txt" "not source")
      (put! root "extra/e.clj" (clj "e"))
      (put! root "other/z.clj" (clj "z"))
      (let [d (disc/discover root {})]
        (is (= ["extra/e.clj" "src/a/b.clj" "src/a/c.cljs" "src/a/d.cljc"] (paths d)))
        (is (true? (:read-complete? d)))
        (is (= [] (:skipped d)))))))

;; @spec MCP-OP-QUERY-009
(deftest deps-edn-without-paths-defaults-to-src
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:deps {}}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "lib/b.clj" (clj "b"))
      (is (= ["src/a.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest bb-edn-paths-select-the-source-files
  (with-dirs
    (fn [root _]
      (put! root "bb.edn" "{:paths [\"scripts\"]}")
      (put! root "scripts/s.clj" (clj "s"))
      (put! root "src/ignored.clj" (clj "ignored"))
      (is (= ["scripts/s.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest project-clj-source-paths-select-the-source-files
  (with-dirs
    (fn [root _]
      (put! root "project.clj" "(defproject my/proj \"0.1.0\" :source-paths [\"lib\"] :dependencies [])")
      (put! root "lib/l.clj" (clj "l"))
      (put! root "src/ignored.clj" (clj "ignored"))
      (is (= ["lib/l.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest project-clj-without-source-paths-defaults-to-src
  (with-dirs
    (fn [root _]
      (put! root "project.clj" "(defproject my/proj \"0.1.0\")")
      (put! root "src/a.clj" (clj "a"))
      (put! root "lib/b.clj" (clj "b"))
      (is (= ["src/a.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest test-and-tests-directories-next-to-a-build-file-are-source-paths
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "test/a_test.clj" (clj "a-test"))
      (put! root "tests/b_test.clj" (clj "b-test"))
      (put! root "spec/c.clj" (clj "c"))
      (is (= ["src/a.clj" "test/a_test.clj" "tests/b_test.clj"]
             (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest a-build-file-in-a-subdirectory-adds-its-own-paths-relative-to-the-root
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "modules/m/deps.edn" "{:paths [\"code\"]}")
      (put! root "modules/m/code/m.clj" (clj "m"))
      (put! root "modules/m/test/m_test.clj" (clj "m-test"))
      (put! root "modules/m/src/not_a_path.clj" (clj "np"))
      (is (= ["modules/m/code/m.clj" "modules/m/test/m_test.clj" "src/a.clj"]
             (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest dot-directories-node-modules-and-target-are-not-searched-for-build-files
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root ".hidden/deps.edn" "{:paths [\"code\"]}")
      (put! root ".hidden/code/h.clj" (clj "h"))
      (put! root "node_modules/n/deps.edn" "{:paths [\"code\"]}")
      (put! root "node_modules/n/code/n.clj" (clj "n"))
      (put! root "target/deps.edn" "{:paths [\"code\"]}")
      (put! root "target/code/t.clj" (clj "t"))
      (is (= ["src/a.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-009
(deftest a-file-entry-has-path-abs-mtime-and-size
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{}")
      (let [f (put! root "src/a.clj" "(ns a)\n")
            d (disc/discover root {})]
        (is (= [{:path "src/a.clj"
                 :abs (str root "/src/a.clj")
                 :mtime (.lastModified f)
                 :size 7}]
               (:files d)))))))

;; @spec MCP-OP-QUERY-009
(deftest a-path-that-two-source-paths-reach-appears-once
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\" \"src/a\"]}")
      (put! root "src/a/x.clj" (clj "a.x"))
      (is (= ["src/a/x.clj"] (paths (disc/discover root {})))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-010 build files are never evaluated
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-010
(deftest deps-edn-with-a-reader-eval-is-not-evaluated
  (with-dirs
    (fn [root outside]
      (let [marker (str outside "/marker-deps")]
        (put! root "deps.edn"
              (str "{:paths [\"src\"] :x #=(spit \"" marker "\" \"evaluated\")}"))
        (put! root "src/a.clj" (clj "a"))
        (put! root "test/t.clj" (clj "t"))
        (let [d (disc/discover root {})]
          (is (false? (.exists (File. ^String marker))))
          ;; An unreadable build file contributes only its test directories.
          (is (= ["test/t.clj"] (paths d))))))))

;; @spec MCP-OP-QUERY-010
(deftest project-clj-with-a-reader-eval-is-not-evaluated
  (with-dirs
    (fn [root outside]
      (let [marker (str outside "/marker-project")]
        (put! root "project.clj"
              (str "(defproject p \"1\" :source-paths [\"lib\"] :x #=(spit \"" marker "\" \"evaluated\"))"))
        (put! root "lib/l.clj" (clj "l"))
        (put! root "test/t.clj" (clj "t"))
        (let [d (disc/discover root {})]
          (is (false? (.exists (File. ^String marker))))
          (is (= ["test/t.clj"] (paths d))))))))

;; @spec MCP-OP-QUERY-010
(deftest bb-edn-with-a-reader-eval-is-not-evaluated
  (with-dirs
    (fn [root outside]
      (let [marker (str outside "/marker-bb")]
        (put! root "bb.edn"
              (str "{:paths [\"scripts\"] :x #=(spit \"" marker "\" \"evaluated\")}"))
        (put! root "scripts/s.clj" (clj "s"))
        (disc/discover root {})
        (is (false? (.exists (File. ^String marker))))))))

;; @spec MCP-OP-QUERY-010
(deftest a-clj-kondo-config-with-a-reader-eval-is-not-evaluated
  (with-dirs
    (fn [root outside]
      (let [marker (str outside "/marker-kondo")]
        (put! root ".clj-kondo/config.edn"
              (str "{:lint-as {a/b clojure.core/def} :x #=(spit \"" marker "\" \"evaluated\")}"))
        (is (= {} (disc/read-lint-as root)))
        (is (false? (.exists (File. ^String marker))))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-011 a source path outside the root
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-011
(deftest a-source-path-with-an-absolute-path-outside-the-root-is-dropped
  (with-dirs
    (fn [root outside]
      (put! root "deps.edn" (str "{:paths [\"src\" \"" outside "\"]}"))
      (put! root "src/a.clj" (clj "a"))
      (put! outside "secret.clj" (clj "secret"))
      (is (= ["src/a.clj"] (paths (disc/discover root {})))))))

;; @spec MCP-OP-QUERY-011
(deftest a-source-path-that-climbs-out-of-the-root-with-dot-dot-is-dropped
  (with-dirs
    (fn [root outside]
      (let [rel (str "../" (.getName (File. ^String outside)))]
        (put! root "deps.edn" (str "{:paths [\"src\" \"" rel "\"]}"))
        (put! root "src/a.clj" (clj "a"))
        (put! outside "secret.clj" (clj "secret"))
        (is (= ["src/a.clj"] (paths (disc/discover root {}))))))))

;; @spec MCP-OP-QUERY-011
(deftest a-source-path-that-is-a-symlink-to-a-directory-outside-the-root-is-dropped
  (with-dirs
    (fn [root outside]
      (put! root "deps.edn" "{:paths [\"src\" \"linked\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! outside "secret.clj" (clj "secret"))
      (symlink! root "linked" outside)
      (let [d (disc/discover root {})]
        (is (= ["src/a.clj"] (paths d)))
        (is (not-any? #(str/includes? (:abs %) "secret") (:files d)))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-012 symlinks inside a source path
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-012
(deftest a-directory-symlink-that-leaves-the-root-is-not-followed
  (with-dirs
    (fn [root outside]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! outside "secret.clj" (clj "secret"))
      (put! outside "deep/secret2.clj" (clj "secret2"))
      (symlink! root "src/escape" outside)
      (let [d (disc/discover root {})]
        (is (= ["src/a.clj"] (paths d)))
        (is (true? (:read-complete? d)))))))

;; @spec MCP-OP-QUERY-012
(deftest a-file-symlink-that-leaves-the-root-is-not-read
  (with-dirs
    (fn [root outside]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (let [target (put! outside "secret.clj" (clj "secret"))]
        (symlink! root "src/leak.clj" (.getPath target))
        (is (= ["src/a.clj"] (paths (disc/discover root {}))))))))

;; @spec MCP-OP-QUERY-012
(deftest a-symlink-that-stays-inside-the-root-is-followed-once
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "shared/s.clj" (clj "s"))
      (symlink! root "src/shared-link" (str root "/shared"))
      (let [ps (paths (disc/discover root {}))]
        (is (= 1 (count (filter #(str/ends-with? % "/s.clj") ps))))
        (is (some #{"src/a.clj"} ps))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-013 the file-count cap
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-013
(deftest reaching-the-file-cap-stops-the-walk-and-records-the-cap
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (doseq [n (range 5)]
        (put! root (str "src/f" n ".clj") (clj (str "f" n))))
      (let [d (disc/discover root {:max-files 3})]
        (is (= 3 (count (:files d))))
        (is (= [{:reason :file-cap :cap 3}] (:skipped d)))
        (is (false? (:read-complete? d)))))))

;; @spec MCP-OP-QUERY-013
(deftest a-workspace-below-the-file-cap-records-no-cap-entry
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "src/b.clj" (clj "b"))
      (let [d (disc/discover root {:max-files 3})]
        (is (= ["src/a.clj" "src/b.clj"] (paths d)))
        (is (= [] (:skipped d)))
        (is (true? (:read-complete? d)))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-014 the file-byte cap
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-014
(deftest a-file-over-the-byte-cap-is-skipped-with-its-size
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/small.clj" (clj "small"))
      (put! root "src/big.clj" (str/join (repeat 101 "x")))
      (let [d (disc/discover root {:max-file-bytes 100})]
        (is (= ["src/small.clj"] (paths d)))
        (is (= [{:file "src/big.clj" :reason :too-large :size 101}] (:skipped d)))
        (is (false? (:read-complete? d)))))))

;; @spec MCP-OP-QUERY-014
(deftest a-file-exactly-at-the-byte-cap-is-kept
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/edge.clj" (str/join (repeat 100 "x")))
      (let [d (disc/discover root {:max-file-bytes 100})]
        (is (= ["src/edge.clj"] (paths d)))
        (is (= 100 (:size (first (:files d)))))
        (is (= [] (:skipped d)))
        (is (true? (:read-complete? d)))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-120 the config-byte cap (issue #11)
;; ---------------------------------------------------------------------------

(def ^:private big-padding
  "A comment line that makes a build file bigger than 100 bytes."
  (str ";; " (str/join (repeat 120 "x")) "\n"))

;; @spec MCP-OP-QUERY-120
(deftest a-build-file-over-the-config-byte-cap-is-not-read-and-is-skipped
  (with-dirs
    (fn [root _]
      (let [text (str big-padding "{:paths [\"lib\"]}")]
        (put! root "deps.edn" text)
        (put! root "lib/l.clj" (clj "l"))
        (put! root "src/s.clj" (clj "s"))
        (put! root "test/t.clj" (clj "t"))
        (let [d (disc/discover root {:max-config-bytes 100})]
          (testing "the build file adds only its test directory"
            (is (= ["test/t.clj"] (paths d))))
          (is (= [{:file "deps.edn" :reason :too-large :size (count text)}] (:skipped d)))
          (is (false? (:read-complete? d))))))))

;; @spec MCP-OP-QUERY-120
(deftest a-build-file-at-the-config-byte-cap-is-read
  (with-dirs
    (fn [root _]
      (let [text "{:paths [\"lib\"]}"]
        (put! root "deps.edn" text)
        (put! root "lib/l.clj" (clj "l"))
        (let [d (disc/discover root {:max-config-bytes (count text)})]
          (is (= ["lib/l.clj"] (paths d)))
          (is (= [] (:skipped d)))
          (is (true? (:read-complete? d))))))))

;; @spec MCP-OP-QUERY-120
(deftest a-build-file-in-a-subdirectory-is-skipped-with-its-relative-path
  (with-dirs
    (fn [root _]
      (let [text (str big-padding "{:paths [\"lib\"]}")]
        (put! root "sub/project.clj" text)
        (put! root "sub/lib/l.clj" (clj "l"))
        (let [d (disc/discover root {:max-config-bytes 100})]
          (is (= [] (paths d)))
          (is (= [{:file "sub/project.clj" :reason :too-large :size (count text)}]
                 (:skipped d))))))))

;; @spec MCP-OP-QUERY-120
(deftest the-config-byte-cap-defaults-to-one-mebibyte
  (with-dirs
    (fn [root _]
      (let [text (str ";; " (str/join (repeat 1048576 "x")) "\n{:paths [\"lib\"]}")]
        (put! root "deps.edn" text)
        (put! root "lib/l.clj" (clj "l"))
        (let [d (disc/discover root {})]
          (is (= [] (paths d)))
          (is (= [{:file "deps.edn" :reason :too-large :size (count text)}] (:skipped d))))))))

;; @spec MCP-OP-QUERY-120
(deftest a-kondo-config-over-the-config-byte-cap-adds-no-lint-as-entry
  (with-dirs
    (fn [root _]
      (let [text (str big-padding "{:lint-as {a/b clojure.core/def}}")]
        (put! root "deps.edn" "{:paths [\"src\"]}")
        (put! root "src/s.clj" (clj "s"))
        (put! root ".clj-kondo/config.edn" text)
        (let [d (disc/discover root {:max-config-bytes 100})]
          (is (= {} (:lint-as d)))
          (is (= ["src/s.clj"] (paths d)))
          (is (= [{:file ".clj-kondo/config.edn" :reason :too-large :size (count text)}]
                 (:skipped d)))
          (is (false? (:read-complete? d))))
        (testing "under the cap the entry applies"
          (let [d (disc/discover root {:max-config-bytes 10000})]
            (is (= '{a/b clojure.core/def} (:lint-as d)))
            (is (true? (:read-complete? d)))))))))

;; @spec MCP-OP-QUERY-120
(deftest read-lint-as-with-a-cap-gives-an-empty-map-for-a-big-config
  (with-dirs
    (fn [root _]
      (put! root ".clj-kondo/config.edn"
            (str big-padding "{:lint-as {a/b clojure.core/def}}"))
      (is (= {} (disc/read-lint-as root 100)))
      (is (= '{a/b clojure.core/def} (disc/read-lint-as root 10000))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-108 the refresh time bound covers discovery (issue #11)
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-108
(deftest discovery-stops-with-a-timeout-when-the-time-bound-has-passed
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (put! root "src/a.clj" (clj "a"))
      (put! root "src/b.clj" (clj "b"))
      (let [e (try (disc/discover root {:expired? (constantly true)})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e))
        (is (= :timeout (:error-type (ex-data e)))))
      (testing "a bound that has not passed changes nothing"
        (is (= ["src/a.clj" "src/b.clj"]
               (paths (disc/discover root {:expired? (constantly false)}))))))))

;; @spec MCP-OP-QUERY-108
(deftest discovery-checks-the-time-bound-before-each-file
  (with-dirs
    (fn [root _]
      (put! root "deps.edn" "{:paths [\"src\"]}")
      (dotimes [i 5] (put! root (str "src/f" i ".clj") (clj (str "f" i))))
      (let [calls (atom 0)
            e (try (disc/discover root {:expired? #(> (swap! calls inc) 3)})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :timeout (:error-type (ex-data e))))
        (is (< @calls 20) "the check stops the walk")))))

;; ---------------------------------------------------------------------------
;; Defect guard
;; ---------------------------------------------------------------------------

(deftest discover-refuses-a-root-that-is-not-a-directory
  (with-dirs
    (fn [root _]
      (let [missing (str root "/nope")
            ex (try (disc/discover missing {}) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= {:error-type :invalid-workspace-root :root missing}
               (select-keys (ex-data ex) [:error-type :root])))))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-015 and 016 stable reads with injected stat and read sequences
;; ---------------------------------------------------------------------------

(defn- script
  "Return {:stat-fn :read-fn :log} where stat-fn pops from stats, read-fn pops
   from reads, and log records :stat and :read calls in order."
  [stats reads]
  (let [stats (atom (vec stats))
        reads (atom (vec reads))
        log   (atom [])
        pop!  (fn [a]
                (let [[x & more] @a]
                  (reset! a (vec more))
                  x))]
    {:log log
     :stat-fn (fn [_] (swap! log conj :stat) (pop! stats))
     :read-fn (fn [_] (swap! log conj :read) (pop! reads))}))

(defn- bytes-of [^String s] (.getBytes s "UTF-8"))

;; @spec MCP-OP-QUERY-015
(deftest a-file-whose-stats-agree-is-read-once
  (let [{:keys [stat-fn read-fn log]}
        (script [{:mtime 5 :size 3} {:mtime 5 :size 3}] [(bytes-of "one")])
        r (disc/read-stable "p" {:stat-fn stat-fn :read-fn read-fn})]
    (is (= [:stat :read :stat] @log))
    (is (true? (:ok r)))
    (is (= 1 (:attempts r)))
    (is (= 5 (:mtime r)))
    (is (= 3 (:size r)))
    (is (= "one" (String. ^bytes (:bytes r) "UTF-8")))
    (is (= "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed" (:sha256 r)))))

;; @spec MCP-OP-QUERY-015
(deftest a-changed-mtime-between-the-stats-triggers-a-second-read
  (let [{:keys [stat-fn read-fn log]}
        (script [{:mtime 5 :size 3} {:mtime 6 :size 3}
                 {:mtime 6 :size 3} {:mtime 6 :size 3}]
                [(bytes-of "one") (bytes-of "two")])
        r (disc/read-stable "p" {:stat-fn stat-fn :read-fn read-fn})]
    (is (= [:stat :read :stat :stat :read :stat] @log))
    (is (true? (:ok r)))
    (is (= 2 (:attempts r)))
    (is (= 6 (:mtime r)))
    (is (= "two" (String. ^bytes (:bytes r) "UTF-8")))
    (testing "the hash is of the bytes that the successful round read"
      (is (= "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3" (:sha256 r))))))

;; @spec MCP-OP-QUERY-015
(deftest a-changed-size-between-the-stats-triggers-a-second-read
  (let [{:keys [stat-fn read-fn log]}
        (script [{:mtime 5 :size 3} {:mtime 5 :size 4}
                 {:mtime 5 :size 4} {:mtime 5 :size 4}]
                [(bytes-of "one") (bytes-of "two!")])
        r (disc/read-stable "p" {:stat-fn stat-fn :read-fn read-fn})]
    (is (= 4 (count (filter #{:stat} @log))))
    (is (= 2 (count (filter #{:read} @log))))
    (is (true? (:ok r)))
    (is (= 2 (:attempts r)))
    (is (= 4 (:size r)))))

;; @spec MCP-OP-QUERY-015
(deftest a-file-that-settles-on-the-third-read-succeeds
  (let [unstable-a [{:mtime 1 :size 1} {:mtime 2 :size 1}]
        unstable-b [{:mtime 2 :size 1} {:mtime 3 :size 1}]
        stable     [{:mtime 3 :size 1} {:mtime 3 :size 1}]
        {:keys [stat-fn read-fn log]}
        (script (concat unstable-a unstable-b stable)
                [(bytes-of "a") (bytes-of "b") (bytes-of "c")])
        r (disc/read-stable "p" {:stat-fn stat-fn :read-fn read-fn})]
    (is (= 6 (count (filter #{:stat} @log))))
    (is (= 3 (count (filter #{:read} @log))))
    (is (true? (:ok r)))
    (is (= 3 (:attempts r)))
    (is (= "c" (String. ^bytes (:bytes r) "UTF-8")))))

;; @spec MCP-OP-QUERY-016
(deftest a-file-that-never-settles-is-unstable-after-three-reads
  (let [stat-count (atom 0)
        read-count (atom 0)
        r (disc/read-stable "p"
                            {:stat-fn (fn [_] {:mtime (swap! stat-count inc) :size 1})
                             :read-fn (fn [_] (swap! read-count inc) (bytes-of "x"))})]
    (is (= {:ok false :reason :unstable :attempts 3} r))
    (is (= 6 @stat-count))
    (is (= 3 @read-count))))

;; @spec MCP-OP-QUERY-016
(deftest max-attempts-sets-the-number-of-rounds
  (let [stat-count (atom 0)
        read-count (atom 0)
        r (disc/read-stable "p"
                            {:max-attempts 2
                             :stat-fn (fn [_] {:mtime (swap! stat-count inc) :size 1})
                             :read-fn (fn [_] (swap! read-count inc) (bytes-of "x"))})]
    (is (= false (:ok r)))
    (is (= :unstable (:reason r)))
    (is (= 2 (:attempts r)))
    (is (= 4 @stat-count))
    (is (= 2 @read-count))))

(deftest a-read-that-throws-is-unreadable-at-once
  (let [stat-count (atom 0)
        r (disc/read-stable "p"
                            {:stat-fn (fn [_] (swap! stat-count inc) {:mtime 1 :size 1})
                             :read-fn (fn [_] (throw (IOException. "disk gone")))})]
    (is (= {:ok false :reason :unreadable :message "disk gone"} r))
    (is (= 1 @stat-count))))

(deftest a-stat-that-throws-is-unreadable-at-once
  (let [read-count (atom 0)
        r (disc/read-stable "p"
                            {:stat-fn (fn [_] (throw (IOException. "no such file")))
                             :read-fn (fn [_] (swap! read-count inc) (bytes-of "x"))})]
    (is (= {:ok false :reason :unreadable :message "no such file"} r))
    (is (= 0 @read-count))))

;; ---------------------------------------------------------------------------
;; MCP-OP-QUERY-017 mtime, size and SHA-256 of the analyzed bytes
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-017
(deftest sha256-of-the-empty-string-is-the-known-digest
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (disc/sha256 ""))))

;; @spec MCP-OP-QUERY-017
(deftest sha256-of-abc-is-the-known-digest-for-a-string-and-for-bytes
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (disc/sha256 "abc")))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (disc/sha256 (bytes-of "abc")))))

;; @spec MCP-OP-QUERY-017
(deftest sha256-of-a-string-uses-its-utf-8-bytes
  (is (= "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c"
         (disc/sha256 "é"))))

;; @spec MCP-OP-QUERY-017
(deftest sha256-is-64-lowercase-hex-characters
  (let [h (disc/sha256 "anything")]
    (is (= 64 (count h)))
    (is (re-matches #"[0-9a-f]{64}" h))))

;; @spec MCP-OP-QUERY-017
(deftest read-stable-on-a-real-file-records-mtime-size-and-hash-of-its-bytes
  (with-dirs
    (fn [root _]
      (let [f (put! root "a.clj" "abc")
            r (disc/read-stable (.getPath f) {})]
        (is (true? (:ok r)))
        (is (= 1 (:attempts r)))
        (is (= 3 (:size r)))
        (is (= (.lastModified f) (:mtime r)))
        (is (= "abc" (String. ^bytes (:bytes r) "UTF-8")))
        (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
               (:sha256 r)))))))
