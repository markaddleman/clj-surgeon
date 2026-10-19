(ns clj-surgeon.spike-edit-test
  "SPIKE (issue #27, stage C): `edit_forms` and `run_tests`, registered only
   when the environment variable CLJ_SURGEON_EDIT_TEST is \"1\".

   `edit_forms` applies a batch of exact text edits, each scoped to one
   top-level form, checks that every changed file parses, and then writes all
   files. It needs no Read before an edit. `run_tests` runs named test
   namespaces with clojure.test in a JVM on the project's :test classpath.
   Pre-registration: https://github.com/markaddleman/clj-surgeon/issues/27#issuecomment-6266206465
   This namespace is spike code, not a reviewed surface."
  (:require
   [cheshire.core :as json]
   [clj-surgeon.mcp-operation :as mcp-operation]
   [clj-surgeon.mcp-runtime :as runtime]
   [clj-surgeon.outline :as outline]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [rewrite-clj.parser :as parser])
  (:import
   (java.io File)
   (java.nio.file Files Path StandardCopyOption)))

(def enabled?
  "True when the spike tools are switched on for this process."
  (= "1" (System/getenv "CLJ_SURGEON_EDIT_TEST")))

(defn- root ^File []
  (.getCanonicalFile (io/file (or (:project-root @runtime/tool-config) "."))))

(defn- inside-root ^File [path]
  (let [r (root)
        f (.getCanonicalFile (if (.isAbsolute (io/file path)) (io/file path) (io/file r path)))]
    (when-not (str/starts-with? (str f) (str r File/separator))
      (throw (ex-info (str "file is outside the workspace: " path) {:error-type :invalid-request})))
    f))

;; ---------------------------------------------------------------- edit_forms

(defn- form-span
  "[start end] character offsets of the one top-level form named `form-name` in `text`."
  [file text form-name]
  (let [forms (filter #(= (str form-name) (str (:name %))) (:forms (outline/outline-source file text)))
        lines (str/split text #"\n" -1)
        offset (fn [line] (reduce + (map #(inc (count %)) (take (dec line) lines))))]
    (case (count forms)
      0 {:problem (str "no top-level form named " form-name " in " file)}
      1 (let [{:keys [line end-line]} (first forms)]
          {:span [(offset line) (min (count text) (+ (offset end-line) (count (nth lines (dec end-line) ""))))]})
      {:problem (str (count forms) " top-level forms are named " form-name " in " file "; give a more exact name")})))

(defn- apply-edit
  "Apply one edit to `text`. Returns {:text} or {:problem :form-source}."
  [file text {:keys [form old new insert_after append] :as edit}]
  (cond
    (some? append) {:text (str (str/trimr text) "\n\n" append "\n")}
    (some? insert_after)
    (let [{:keys [span problem]} (form-span file text insert_after)]
      (if problem {:problem problem}
          (let [[_ e] span] {:text (str (subs text 0 e) "\n\n" (:text edit) (subs text e))})))
    :else
    (let [{:keys [span problem]} (form-span file text form)]
      (if problem
        {:problem problem}
        (let [[s e] span
              src (subs text s e)
              n (count (re-seq (re-pattern (java.util.regex.Pattern/quote (str old))) src))]
          (case n
            1 {:text (str (subs text 0 s) (str/replace-first src old new) (subs text e))}
            0 {:problem (str "old text not found in form " form " of " file) :form-source src}
            {:problem (str "old text occurs " n " times in form " form " of " file "; give a longer old text")
             :form-source src}))))))

(defn- parses? [text]
  (try (parser/parse-string-all text) true (catch Exception _ false)))

(defn edit-forms
  "Check every edit, then write every changed file. Returns a result map."
  [{:keys [edits]}]
  (if-not (and (sequential? edits) (seq edits))
    {:ok false :error_type "invalid-request" :error "edits must be a non-empty array"
     :remedy "Send {\"edits\": [{\"file\": \"src/a/b.clj\", \"form\": \"f\", \"old\": \"...\", \"new\": \"...\"}]}."}
    (let [by-file (group-by #(str (get % :file)) (map #(update-keys % keyword) edits))
          results (for [[path es] by-file]
                    (let [f (inside-root path)
                          before (slurp f)
                          step (reduce (fn [{:keys [text problems]} e]
                                         (let [r (apply-edit path text e)]
                                           (if (:problem r)
                                             {:text text :problems (conj problems (select-keys r [:problem :form-source]))}
                                             {:text (:text r) :problems problems})))
                                       {:text before :problems []} es)]
                      (cond-> {:file path :f f :text (:text step) :problems (:problems step) :edits (count es)}
                        (and (empty? (:problems step)) (not (parses? (:text step))))
                        (update :problems conj {:problem (str path " would not parse after these edits")}))))
          problems (mapcat :problems results)]
      (if (seq problems)
        {:ok false :error_type "edit-refused" :error "No file was written."
         :problems (vec problems)
         :remedy "Fix the edits named in problems and send the whole batch again. :form-source holds the current source of the form."}
        (do (doseq [{:keys [^File f text]} results]
              (let [tmp (File/createTempFile "edit-forms" ".clj" (.getParentFile f))]
                (spit tmp text)
                (Files/move (.toPath tmp) (.toPath f) (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))
            {:ok true :applied (reduce + (map :edits results))
             :files (mapv :file results) :parses true})))))

;; ---------------------------------------------------------------- run_tests

(defonce ^:private classpaths (atom {}))

(defn- test-jvm-opts
  "The :jvm-opts of the :test alias in deps.edn, limited to --add-modules, -D and -X flags."
  [^File r]
  (let [deps (try (edn/read-string (slurp (io/file r "deps.edn"))) (catch Exception _ {}))]
    (vec (filter #(re-matches #"(--add-modules=\S+|-D\S+|-X\S+)" (str %))
                 (get-in deps [:aliases :test :jvm-opts])))))

(defn- classpath [^File r]
  (or (@classpaths (str r))
      (let [{:keys [exit out err]} (sh/sh "clojure" "-Spath" "-A:test" :dir (str r))]
        (when-not (zero? exit)
          (throw (ex-info (str "could not build the :test classpath: " (subs err 0 (min 400 (count err))))
                          {:error-type :test-setup})))
        (let [cp (str/trim out)] (swap! classpaths assoc (str r) cp) cp))))

(defn- summary-of [out]
  (let [ran (re-find #"Ran \d+ tests containing \d+ assertions\.\s*\d+ failures, \d+ errors\." out)
        blocks (re-seq #"(?s)\n(?:FAIL|ERROR) in .*?(?=\n(?:FAIL|ERROR) in |\nRan \d+ tests|\z)" out)
        detail (str/join "" blocks)]
    (str (or ran "no clojure.test summary line (see output)") "\n"
         (subs detail 0 (min 4000 (count detail)))
         (when-not ran (str "\n" (subs out (max 0 (- (count out) 2000))))))))

(defn run-tests
  "Run the test namespaces `namespaces` with clojure.test. Returns a result map."
  [{:keys [namespaces]}]
  (if-not (and (sequential? namespaces) (seq namespaces)
               (every? #(re-matches #"[\w.\-*+!?]+" (str %)) namespaces))
    {:ok false :error_type "invalid-request" :error "namespaces must be a non-empty array of namespace names"
     :remedy "Send {\"namespaces\": [\"a.b-test\"]}."}
    (let [r (root)
          nss (str/join " " (map #(str "'" %) namespaces))
          code (str "(require 'clojure.test) (apply require [" nss "]) "
                    "(let [s (apply clojure.test/run-tests [" nss "])] "
                    "(shutdown-agents) (System/exit (if (zero? (+ (:fail s) (:error s))) 0 1)))")
          cmd (concat ["java"] (test-jvm-opts r) ["-cp" (classpath r) "clojure.main" "-e" code])
          {:keys [exit out err]} (apply sh/sh (concat cmd [:dir (str r)]))
          text (str out "\n" err)]
      {:ok true :passed (zero? exit) :namespaces (vec namespaces) :summary (summary-of text)})))

;; ---------------------------------------------------------------- tools

(defn- handler [operation f summarize]
  (fn [_exchange params callback]
    (mcp-operation/invoke!
     {:operation operation
      :execute (fn [] (try (f (update-keys (or params {}) keyword))
                           (catch clojure.lang.ExceptionInfo e
                             {:ok false :error_type (name (or (:error-type (ex-data e)) :invalid-request))
                              :error (ex-message e)})))
      :summarize summarize
      :callback callback})))

(def edit-forms-tool
  {:id :edit-forms
   :name "edit_forms"
   :outcome-classes #{:committed :typed-refusal}
   :description (str "Edit Clojure files without reading them first. One call applies a batch of edits across files: "
                     "{file, form, old, new} replaces the one exact occurrence of old inside the named top-level form "
                     "(the name as written, for example \"resolve-service\"); {file, insert_after, text} inserts text "
                     "after a form; {file, append} adds text at the end of a file. All edits are checked first, every "
                     "changed file must parse, then all files are written. A refusal writes nothing and returns the "
                     "current source of the form. Use query_clojure form to see the source you edit.")
   :schema {:type "object"
            :properties {"edits" {:type "array"
                                  :items {:type "object"
                                          :properties {"file" {:type "string"} "form" {:type "string"}
                                                       "old" {:type "string"} "new" {:type "string"}
                                                       "insert_after" {:type "string"} "text" {:type "string"}
                                                       "append" {:type "string"}}
                                          :required ["file"]}}}
            :required ["edits"]}
   :structured? true
   :tool-fn (handler "edit-forms" edit-forms
                     (fn [r] (if (:ok r)
                               (str "applied " (:applied r) " edit(s) to " (str/join ", " (:files r)) "; every file parses")
                               (str "edit_forms refused: " (:error r) "\n" (json/generate-string (:problems r))))))})

(def run-tests-tool
  {:id :run-tests
   :name "run_tests"
   :outcome-classes #{:receipt :typed-refusal}
   :description (str "Run Clojure test namespaces with clojure.test on the project's :test classpath and get a short "
                     "summary: the counts, then the FAIL and ERROR text (at most 4,000 characters). Only the named "
                     "namespaces load, so an unrelated broken test file does not stop the run. "
                     "Example: {\"namespaces\": [\"a.b-test\", \"a.c-test\"]}.")
   :schema {:type "object"
            :properties {"namespaces" {:type "array" :items {:type "string"}}}
            :required ["namespaces"]}
   :structured? true
   :tool-fn (handler "run-tests" run-tests
                     (fn [r] (if (:ok r) (str (if (:passed r) "PASS " "FAIL ") (:summary r))
                                 (str "run_tests refused: " (:error r)))))})

(defn tools [] (if enabled? [edit-forms-tool run-tests-tool] []))
