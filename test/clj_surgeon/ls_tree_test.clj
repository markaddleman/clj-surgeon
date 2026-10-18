(ns clj-surgeon.ls-tree-test
  {:lane :fast}
  (:require
   [babashka.fs :as fs]
   [clj-surgeon.core :as core]
   [clj-surgeon.outline :as outline]
   [clj-surgeon.refs :as refs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [rewrite-clj.zip :as z]))

;; ============================================================
;; Pure tests: extract-ns-requires (zloc in, data out)
;; ============================================================

(defn- ns-zloc-from-string
  "Parse source and return the (ns ...) zipper location. No I/O."
  [source]
  (let [zloc (z/of-string source)]
    (some-> zloc (z/find-value z/next 'ns) z/up)))

(deftest test-extract-ns-requires-basic
  (let [ns-z (ns-zloc-from-string
               "(ns my.app
  (:require [clojure.string :as str]
            [clojure.set :as set]))
(defn foo [] :ok)")]
    (is (= ["[clojure.string :as str]"
            "[clojure.set :as set]"]
           (outline/extract-ns-requires ns-z)))))

(deftest test-extract-ns-requires-with-refer
  (let [ns-z (ns-zloc-from-string
               "(ns my.app
  (:require [clojure.test :refer [deftest is testing]]))
(defn foo [] :ok)")]
    (is (= ["[clojure.test :refer [deftest is testing]]"]
           (outline/extract-ns-requires ns-z)))))

(deftest test-extract-ns-requires-cljc-reader-conditional
  (let [ns-z (ns-zloc-from-string
               "(ns my.app
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [goog.string :as gstr])))
(defn foo [] :ok)")]
    (testing "extracts both direct and reader-conditional requires"
      (is (= ["[clojure.string :as str]"
              "[clojure.java.io :as io]"
              "[goog.string :as gstr]"]
             (outline/extract-ns-requires ns-z))))))

(deftest test-extract-ns-requires-cljc-splicing
  (let [ns-z (ns-zloc-from-string
               "(ns my.app
  (:require [clojure.string :as str]
            #?@(:clj [[clojure.java.io :as io]
                       [clojure.edn :as edn]])))
(defn foo [] :ok)")]
    (testing "extracts splicing reader-conditional requires"
      (is (= ["[clojure.string :as str]"
              "[clojure.java.io :as io]"
              "[clojure.edn :as edn]"]
             (outline/extract-ns-requires ns-z))))))

(deftest test-extract-ns-requires-no-require-block
  (let [ns-z (ns-zloc-from-string "(ns my.empty)")]
    (is (nil? (outline/extract-ns-requires ns-z)))))

(deftest test-extract-ns-requires-nil-input
  (is (nil? (outline/extract-ns-requires nil))))

(deftest test-extract-ns-requires-no-ns-form
  (let [ns-z (ns-zloc-from-string "(def x 1)")]
    (is (nil? (outline/extract-ns-requires ns-z)))))

;; ============================================================
;; Pure tests: source-paths-from-config (data in, data out)
;; ============================================================

(deftest test-source-paths-deps-edn
  (testing "reads :paths from deps.edn"
    (is (= ["src"] (core/source-paths-from-config "deps.edn" {:paths ["src"]})))
    (is (= ["src" "test"] (core/source-paths-from-config "deps.edn" {:paths ["src" "test"]}))))
  (testing "defaults to [\"src\"] when :paths missing"
    (is (= ["src"] (core/source-paths-from-config "deps.edn" {})))
    (is (= ["src"] (core/source-paths-from-config "deps.edn" {:deps {}})))))

(deftest test-source-paths-bb-edn
  (is (= ["src" "scripts"] (core/source-paths-from-config "bb.edn" {:paths ["src" "scripts"]})))
  (is (= ["src"] (core/source-paths-from-config "bb.edn" {}))))

(deftest test-source-paths-project-clj
  (is (= ["src/clj"]
         (core/source-paths-from-config "project.clj"
                                        '(defproject my-app "1.0" :source-paths ["src/clj"]))))
  (testing "defaults to [\"src\"] when :source-paths missing"
    (is (= ["src"]
           (core/source-paths-from-config "project.clj"
                                          '(defproject my-app "1.0" :dependencies []))))))

(deftest test-source-paths-unknown-file
  (is (= ["src"] (core/source-paths-from-config "build.gradle" {}))))

;; ============================================================
;; Pure tests: filter-projects-by-hits (data in, data out)
;; ============================================================

(deftest test-filter-by-hits-build-file-match
  (testing "when deps.edn matches, all source files are included"
    (let [projects [{:name "email-fetch"
                     :root "/r/email-fetch"
                     :files ["/r/email-fetch/src/a.clj"
                             "/r/email-fetch/src/b.clj"]}]
          hits #{"/r/email-fetch/deps.edn"}]
      (is (= projects (core/filter-projects-by-hits projects hits))))))

(deftest test-filter-by-hits-source-file-match
  (testing "when only source files match, only those are included"
    (let [projects [{:name "app"
                     :root "/r/app"
                     :files ["/r/app/src/email.clj"
                             "/r/app/src/util.clj"
                             "/r/app/src/core.clj"]}]
          hits #{"/r/app/src/email.clj"}
          result (core/filter-projects-by-hits projects hits)]
      (is (= 1 (count result)))
      (is (= ["/r/app/src/email.clj"] (:files (first result)))))))

(deftest test-filter-by-hits-no-match
  (testing "projects with no matches are excluded"
    (let [projects [{:name "unrelated"
                     :root "/r/unrelated"
                     :files ["/r/unrelated/src/core.clj"]}]
          hits #{"/r/other/src/foo.clj"}]
      (is (empty? (core/filter-projects-by-hits projects hits))))))

(deftest test-filter-by-hits-mixed
  (testing "mix of build-file match and source-file match across projects"
    (let [projects [{:name "email-lib"
                     :root "/r/email-lib"
                     :files ["/r/email-lib/src/a.clj" "/r/email-lib/src/b.clj"]}
                    {:name "app"
                     :root "/r/app"
                     :files ["/r/app/src/handler.clj" "/r/app/src/mailer.clj"]}
                    {:name "unrelated"
                     :root "/r/unrelated"
                     :files ["/r/unrelated/src/core.clj"]}]
          hits #{"/r/email-lib/deps.edn" "/r/app/src/mailer.clj"}
          result (core/filter-projects-by-hits projects hits)]
      (is (= 2 (count result)))
      ;; email-lib: build file matched → all files included
      (is (= ["/r/email-lib/src/a.clj" "/r/email-lib/src/b.clj"]
             (:files (first result))))
      ;; app: only source match → just mailer.clj
      (is (= ["/r/app/src/mailer.clj"]
             (:files (second result)))))))

;; ============================================================
;; Pure tests: format-file-text (data in, string out)
;; ============================================================

(deftest test-format-file-text-basic
  (let [result (core/format-file-text
                 {:ns 'my.app
                  :lines 50
                  :form-count 2
                  :requires ["[clojure.string :as str]"]
                  :forms [{:type 'defn :name 'greet :args "[name]" :line 5 :end-line 10}
                          {:type 'def :name 'version :line 12 :end-line 12}]}
                 "src/my/app.clj")]
    (is (str/includes? result "src/my/app.clj  50 lines, 2 forms"))
    (is (str/includes? result "ns: my.app"))
    (is (str/includes? result "requires: [clojure.string :as str]"))
    (is (str/includes? result "5-10: defn greet [name]"))
    (is (str/includes? result "12: def version"))))

(deftest test-format-file-text-no-requires
  (let [result (core/format-file-text
                 {:ns 'my.bare :lines 5 :form-count 1
                  :requires []
                  :forms [{:type 'def :name 'x :line 3 :end-line 3}]}
                 "bare.clj")]
    (is (not (str/includes? result "requires:")))
    (is (str/includes? result "3: def x"))))

(deftest test-format-file-text-error
  (let [result (core/format-file-text
                 {:lines 0 :form-count 0 :error "Unexpected EOF"}
                 "broken.clj")]
    (is (str/includes? result "⚠ Unexpected EOF"))))

(deftest test-format-file-text-single-line-form
  (testing "form where line == end-line shows just the line number, not a range"
    (let [result (core/format-file-text
                   {:ns 'x :lines 5 :form-count 1 :requires []
                    :forms [{:type 'def :name 'x :line 3 :end-line 3}]}
                   "x.clj")]
      (is (str/includes? result "3: def x"))
      (is (not (str/includes? result "3-3:"))))))

;; ============================================================
;; Pure tests: format-ls-tree-text (data in, string out)
;; ============================================================

(deftest test-format-ls-tree-text-single-project
  (let [projects [{:name "myapp"
                   :root "/tmp/myapp"
                   :outlines [["/tmp/myapp/src/core.clj"
                               {:ns 'myapp.core :lines 20 :form-count 2
                                :requires ["[clojure.string :as str]"]
                                :forms [{:type 'defn :name 'greet :args "[x]" :line 3 :end-line 8}
                                        {:type 'def :name 'version :line 10 :end-line 10}]}]]}]
        result (core/format-ls-tree-text projects "/tmp")]
    (is (str/includes? result "greet"))
    (is (str/includes? result "version"))
    (is (str/includes? result "total: 1 files, 2 forms"))))

(deftest test-format-ls-tree-text-multi-project-headers
  (let [projects [{:name "alpha"
                   :root "/r/alpha"
                   :outlines [["/r/alpha/src/a.clj"
                               {:ns 'alpha.core :lines 10 :form-count 1
                                :requires [] :forms [{:type 'defn :name 'a-fn :line 3 :end-line 5}]}]]}
                  {:name "beta"
                   :root "/r/beta"
                   :outlines [["/r/beta/src/b.clj"
                               {:ns 'beta.core :lines 15 :form-count 2
                                :requires [] :forms [{:type 'defn :name 'b-fn :line 3 :end-line 5}
                                                     {:type 'def :name 'b-val :line 7 :end-line 7}]}]]}]
        result (core/format-ls-tree-text projects "/r")]
    (is (str/includes? result "── alpha (1 files, 1 forms)"))
    (is (str/includes? result "── beta (1 files, 2 forms)"))
    (is (str/includes? result "total: 2 files, 3 forms"))))

;; ============================================================
;; Pure tests: format-ls-tree-edn (data in, data out)
;; ============================================================

(deftest test-format-ls-tree-edn
  (let [projects [{:name "proj"
                   :root "/tmp/proj"
                   :outlines [["/tmp/proj/src/core.clj"
                               {:ns 'proj.core :lines 20 :form-count 1
                                :requires ["[clojure.string :as str]"]
                                :forms [{:type 'defn :name 'f :args "[x]" :line 3 :end-line 5}]
                                :forward-refs []}]]}]
        result (core/format-ls-tree-edn projects "/tmp")]
    (is (vector? result))
    ;; @spec MCP-OP-MEM-005 — one entry per file, plus the unconditional
    ;; `:resources` receipt. These projects were assembled by hand rather than
    ;; by `outline-all-files`, so no meter was installed and the scan cost is
    ;; honestly zero.
    (is (= 2 (count result)))
    (is (= {:resources {:scan_ms 0.0 :bytes_scanned 0}} (:receipt (last result))))
    (let [entry (first result)]
      (is (= 'proj.core (:ns entry)))
      (is (str/includes? (:file entry) "core.clj"))
      (is (= 1 (:form-count entry)))
      (is (nil? (:forward-refs entry)) "forward-refs should be stripped"))))

;; ============================================================
;; Integration smoke tests (minimal I/O — verify end-to-end wiring)
;; ============================================================

(defn- create-temp-project!
  [parent-dir project-name deps-edn-content source-files]
  (let [project-dir (str parent-dir "/" project-name)
        src-dir (str project-dir "/src")]
    (fs/create-dirs src-dir)
    (spit (str project-dir "/deps.edn") deps-edn-content)
    (doseq [[rel-path content] source-files]
      (let [full-path (str src-dir "/" rel-path)]
        (fs/create-dirs (fs/parent full-path))
        (spit full-path content)))
    project-dir))

(deftest test-integration-single-project
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "ls-tree-test"}))]
    (try
      (create-temp-project! tmp-dir "myapp"
                            "{:paths [\"src\"]}"
                            {"myapp/core.clj"
                             "(ns myapp.core
  (:require [clojure.string :as str]))

(defn greet [name]
  (str \"Hello \" name))

(def version \"1.0\")"})
      (let [result (core/run-ls-tree {:dir tmp-dir})]
        (is (string? result))
        (is (str/includes? result "greet"))
        (is (str/includes? result "[clojure.string :as str]"))
        (is (str/includes? result "total: 1 files, 2 forms")))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-integration-no-deps-edn-fallback
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "ls-tree-test"}))]
    (try
      (spit (str tmp-dir "/loose.clj") "(ns loose)\n(defn loose-fn [] :loose)")
      (let [result (core/run-ls-tree {:dir tmp-dir})]
        (is (str/includes? result "loose-fn")))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest test-integration-no-deps-edn-grep-fallback
  (testing "grep fast-path finds loose .clj files without a build file"
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "ls-tree-grep-loose"}))]
      (try
        (spit (str tmp-dir "/loose.clj")
              "(ns loose)\n(defn postgres-query [] :query)")
        (let [result (core/run-ls-tree {:dir tmp-dir :grep "postgres"})]
          (is (some? result) "loose file matching grep should be found")
          (is (str/includes? result "postgres-query")))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest test-integration-parse-error-non-fatal
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "ls-tree-test"}))]
    (try
      (create-temp-project! tmp-dir "mixed"
                            "{:paths [\"src\"]}"
                            {"good.clj" "(ns good)\n(defn g [] :ok)"
                             "bad.clj" "(defn unclosed ["})
      (let [result (core/run-ls-tree {:dir tmp-dir})]
        (is (str/includes? result "g"))
        (is (or (str/includes? result "⚠")
                (str/includes? result "bad.clj"))))
      (finally
        (fs/delete-tree tmp-dir)))))

;; ============================================================
;; :refs -- pure
;; ============================================================

(deftest refs-parse-sym-splits-namespace-and-name
  (is (= {:ns "my.ns" :name "my-fn"} (refs/parse-sym "my.ns/my-fn")))
  (is (= {:ns "my.ns" :name "my-fn"} (refs/parse-sym "#'my.ns/my-fn")))
  (is (= {:ns "a.b" :name "file->zloc"} (refs/parse-sym "a.b/file->zloc")))
  (is (nil? (refs/parse-sym "my-fn")))
  (is (nil? (refs/parse-sym "my.ns/")))
  (is (nil? (refs/parse-sym nil))))

(deftest refs-ns-of-source-reads-the-first-ns-form
  (is (= "a.b" (refs/ns-of-source "(ns a.b (:require [x :as y]))\n(defn f [])")))
  (is (= "a.b" (refs/ns-of-source ";; header\n(ns ^:foo a.b)\n")))
  (is (nil? (refs/ns-of-source "(defn f [])"))))

(def ^:private refs-analysis
  {:var-definitions [{:ns 'app.lib :name 'target :filename "src/app/lib.clj" :row 2}]
   :var-usages
   [{:to 'app.lib :name 'target :filename "src/a.clj" :row 3 :col 1 :name-row 3 :name-col 2
     :from 'a :from-var 'f :alias 'l :arity 1}
    ;; a function passed as an argument: kondo reports a value and a synthetic call
    {:to 'app.lib :name 'target :filename "src/a.clj" :row 6 :col 17 :name-row 6 :name-col 17
     :from 'a :from-var 'g :alias 'l}
    {:to 'app.lib :name 'target :filename "src/a.clj" :row 6 :col 17
     :from 'a :from-var 'g :alias 'l :arity 1}
    {:to 'app.lib :name 'target :filename "src/a.clj" :row 1 :col 36 :refer true :from 'a}
    ;; .cljc: one entry for each language at the same position
    {:to 'app.lib :name 'target :filename "src/c.cljc" :row 2 :col 5 :name-row 2 :name-col 6
     :from 'c :from-var 'h :arity 1 :lang :clj}
    {:to 'app.lib :name 'target :filename "src/c.cljc" :row 2 :col 5 :name-row 2 :name-col 6
     :from 'c :from-var 'h :arity 1 :lang :cljs}
    ;; other vars and other namespaces are not references
    {:to 'app.lib :name 'other :filename "src/a.clj" :row 9 :col 1 :from 'a :arity 0}
    {:to 'other.lib :name 'target :filename "src/a.clj" :row 9 :col 1 :from 'a :arity 0}]})

(deftest refs-usages-become-one-ref-for-each-position
  (let [rs (refs/usages->refs refs-analysis "app.lib" "target")]
    (is (= [["src/a.clj" 1 :refer "(ns)"]
            ["src/a.clj" 3 :alias-call "f"]
            ["src/a.clj" 6 :value "g"]
            ["src/c.cljc" 2 :call "h"]]
           (map (juxt :file :line :kind :form) rs)))))

(deftest refs-definition-is-found-in-the-analysis
  (is (= {:file "src/app/lib.clj" :line 2} (refs/definition refs-analysis "app.lib" "target")))
  (is (nil? (refs/definition refs-analysis "app.lib" "nope"))))

(deftest refs-compact-format-has-one-line-per-ref-and-a-total
  (let [rs [{:file "a.clj" :line 3 :ns "a" :form "f" :kind :call}]]
    (is (= "a.clj:3 a/f call\n1 reference to x/y in 1 file; defined at x.clj:1"
           (refs/format-compact rs {:sym "x/y" :definition {:file "x.clj" :line 1} :dir "/d"} nil)))
    (is (= "0 references to x/y in 0 files; no definition found under /d"
           (refs/format-compact [] {:sym "x/y" :dir "/d"} nil)))
    (is (str/includes? (refs/format-compact rs {:sym "x/y" :dir "/d"} {["a.clj" 3] "(f 1)"})
                       "\n    (f 1)\n"))))

;; ============================================================
;; :refs -- fixture project, real clj-kondo
;; ============================================================

(def ^:private refs-fixture-files
  {"deps.edn" "{:paths [\"src\"]}\n"
   "src/app/lib.clj"
   (str "(ns app.lib)\n"
        "(defn target [x] x)\n"
        "(defn own-caller [] (target 1))\n"
        "(defn shadow [target] (target 2))\n")
   "src/app/user.clj"
   (str "(ns app.user\n"
        "  (:require [app.lib :as l :refer [target]]))\n"
        "(defn a [] (l/target 1))\n"
        "(defn b [] (app.lib/target 2))\n"
        "(defn c [] (target 3))\n"
        "(defn d [] (map l/target [1]))\n")
   "src/app/x.cljc"
   (str "(ns app.x (:require [app.lib :as lib]))\n"
        "#?(:clj (defn f [] (lib/target 1)))\n"
        "(defn g [] (lib/target 2))\n")
   "src/app/unrelated.clj" "(ns app.unrelated)\n(defn target [x] x)\n(defn z [] (target 1))\n"
   "test/app/lib_test.clj"
   (str "(ns app.lib-test (:require [app.lib :as l]))\n"
        "(defn t [] (l/target 1))\n")})

(defn- refs-with-fixture [f]
  (let [dir (fs/create-temp-dir {:prefix "refs-fixture"})]
    (try
      (doseq [[path text] refs-fixture-files]
        (let [p (fs/path dir path)]
          (fs/create-dirs (fs/parent p))
          (spit (str p) text)))
      (f (str dir))
      (finally (fs/delete-tree dir)))))

(defn- refs-edn [dir opts]
  (core/run-refs (merge {:dir dir :format :edn} opts)))

(deftest refs-fixture-covers-every-kind-and-skips-the-shadowing-local
  (refs-with-fixture
    (fn [dir]
      (let [rs (refs-edn dir {:sym "app.lib/target"})
            by (fn [file] (map (juxt :line :kind :form) (filter #(= file (:file %)) rs)))]
        (testing "unqualified call in the own namespace; the shadowing local is not listed"
          (is (= [[3 :call "own-caller"]] (by "src/app/lib.clj"))))
        (testing "refer, alias call, qualified call, referred call, value use"
          (is (= [[2 :refer "(ns)"] [3 :alias-call "a"] [4 :call "b"]
                  [5 :call "c"] [6 :value "d"]]
                 (by "src/app/user.clj"))))
        (testing ".cljc is reported once for each position"
          (is (= [[2 :alias-call "f"] [3 :alias-call "g"]] (by "src/app/x.cljc"))))
        (testing "tests are searched by default"
          (is (= [[2 :alias-call "t"]] (by "test/app/lib_test.clj"))))
        (testing "a var of the same name in another namespace is not a reference"
          (is (empty? (by "src/app/unrelated.clj"))))))))

(deftest refs-fixture-include-tests-false-leaves-test-files-out
  (refs-with-fixture
    (fn [dir]
      (let [rs (refs-edn dir {:sym "app.lib/target" :include-tests false})]
        (is (seq rs))
        (is (not-any? #(str/starts-with? (:file %) "test/") rs))))))

(deftest refs-fixture-file-and-form-name-the-same-var-as-sym
  (refs-with-fixture
    (fn [dir]
      (is (= (refs-edn dir {:sym "app.lib/target"})
             (refs-edn dir {:file (str dir "/src/app/lib.clj") :form "target"}))))))

(deftest refs-fixture-compact-output-and-context
  (refs-with-fixture
    (fn [dir]
      (let [text (core/run-refs {:dir dir :sym "app.lib/target" :context true})]
        (is (str/includes? text "src/app/user.clj:3 app.user/a alias-call\n    (defn a [] (l/target 1))\n"))
        (is (str/includes? text "src/app/user.clj:2 app.user/(ns) refer"))
        (is (str/ends-with? text "9 references to app.lib/target in 4 files; defined at src/app/lib.clj:2")))
      (let [text (core/run-refs {:dir dir :sym "app.lib/target"})]
        (is (not (str/includes? text "(defn a")))))))

(deftest refs-refusals-are-typed
  (refs-with-fixture
    (fn [dir]
      (is (= :refs-sym-not-qualified (:error-type (core/run-refs {:dir dir :sym "target"}))))
      (is (= :refs-target-missing (:error-type (core/run-refs {:dir dir}))))
      (is (= :refs-file-missing (:error-type (core/run-refs {:dir dir :file "/nope.clj" :form "f"}))))
      (is (= :workspace-root-not-a-directory
             (:error-type (core/run-refs {:dir (str dir "/missing") :sym "a.b/c"})))))))

(deftest refs-is-a-registered-read-only-op
  (let [entry (get core/ops-registry :refs)]
    (is (= :read (:category entry)))
    (is (seq (:desc entry)))
    (is (seq (:examples entry)))
    (is (contains? (:args entry) :sym))
    (is (str/includes? (core/format-op-help :refs entry) ":sym"))))
