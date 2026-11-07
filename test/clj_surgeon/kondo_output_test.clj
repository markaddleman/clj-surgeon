(ns clj-surgeon.kondo-output-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clj-surgeon.binding-rename :as binding-rename]
   [clj-surgeon.forward-refs :as fwd]
   [clj-surgeon.mcp-process :as process-env]))

;; A large namespace gives a large analysis: markdown's shared.cljc (3837
;; lines) makes 3 MB of clj-kondo JSON. A cut stdout must never reach the
;; parser as "invalid JSON"; the limit must hold real files, and a cut that
;; still happens is its own error. clj-kondo exits 2 on warnings and 3 on
;; errors with a complete analysis, for the binding analysis too.

(def ^:private forward-ref-analysis
  "{\"analysis\":{\"var-definitions\":[],\"var-usages\":[]}}")

(def ^:private binding-analysis
  "{:analysis {:locals [] :local-usages []}}")

(defn- kondo-result [out exit & {:keys [truncated?] :or {truncated? false}}]
  {:admission {:status :admitted} :finished? true :exit exit :out out :err ""
   :out-truncated truncated?})

(defn- error-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:error-type (ex-data e)))))

(def ^:private min-limit (* 64 1024 1024))

(deftest forward-refs-limit-holds-a-large-analysis
  (let [seen (atom nil)]
    (with-redefs [process-env/run-bounded! (fn [opts] (reset! seen opts)
                                             (kondo-result forward-ref-analysis 2))]
      (fwd/detect-forward-refs "a.clj" 'a.b))
    (is (<= min-limit (:visible-byte-limit @seen)))))

(deftest forward-refs-cut-output-is-its-own-error
  (with-redefs [process-env/run-bounded! (fn [_] (kondo-result "{\"analysis\":{\"var-def" 3 :truncated? true))]
    (is (= :forward-reference-analysis-truncated
           (error-type #(fwd/detect-forward-refs "a.clj" 'a.b))))))

(deftest binding-analysis-limit-holds-a-large-analysis
  (let [seen (atom nil)]
    (with-redefs [process-env/run-bounded! (fn [opts] (reset! seen opts)
                                             (kondo-result binding-analysis 0))]
      (binding-rename/analyze-source "a.clj" "(ns a.b)"))
    (is (<= min-limit (:visible-byte-limit @seen)))))

(deftest binding-analysis-survives-kondo-findings-exit-codes
  (testing "exit 0, 2 and 3 are a finished analysis"
    (doseq [exit [0 2 3]]
      (with-redefs [process-env/run-bounded! (fn [_] (kondo-result binding-analysis exit))]
        (is (= {:locals [] :local-usages []}
               (binding-rename/analyze-source "a.clj" "(ns a.b)"))
            (str "kondo exit " exit)))))
  (testing "other exits still refuse"
    (doseq [exit [1 64]]
      (with-redefs [process-env/run-bounded! (fn [_] (kondo-result binding-analysis exit))]
        (is (= :binding-analysis-failed
               (error-type #(binding-rename/analyze-source "a.clj" "(ns a.b)")))
            (str "kondo exit " exit))))))

(deftest binding-analysis-cut-output-is-its-own-error
  (with-redefs [process-env/run-bounded! (fn [_] (kondo-result "{:analysis {:loc" 2 :truncated? true))]
    (is (= :binding-analysis-truncated
           (error-type #(binding-rename/analyze-source "a.clj" "(ns a.b)"))))))
