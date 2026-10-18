(ns clj-surgeon.state-home-admission-test
  {:lane :fast}
  (:require
   [clj-surgeon.core :as core]
   [clj-surgeon.probe-state :as state]
   [clj-surgeon.receipt-artifacts :as artifacts]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;; @spec STATE-HOME-009
;; @spec STATE-HOME-010
(deftest configured-root-cartesian-matrix
  ;; Packet 68bacdec: enumerate admission here, without 72 cold Make images.
  (let [container (Files/createTempDirectory "state-admission-" (make-array FileAttribute 0))
        rows (atom [])]
    (try
      (doseq [source ["CLJ_SURGEON_STATE_HOME" "XDG_STATE_HOME" "user.home"]
              shape [:direct :symlink :relative :empty]
              envelope [:default :narrow]
              location [:inside :outside :outside-envelope]]
        (let [cell (Files/createDirectory (.resolve container (str (count @rows))) (make-array FileAttribute 0))
              runtime (.resolve cell "runtime")
              checkout (.resolve runtime "checkout")
              home (.resolve runtime "home")
              _ (doseq [p [runtime checkout home]] (Files/createDirectory p (make-array FileAttribute 0)))
              selected (case location :inside checkout :outside (.resolve runtime "external")
                             :outside-envelope (.resolve cell "disallowed"))
              link (.resolve runtime "link")
              _ (when (= shape :symlink) (Files/createSymbolicLink link selected (make-array FileAttribute 0)))
              value (case shape :empty "" :relative (or (not-empty (str (.relativize checkout selected))) ".")
                          :symlink (str link) :direct (str selected))
              env (if (= source "user.home") {} {source value})
              home-value (if (= source "user.home") value (str home))
              root (state/state-root env home-value)
              ;; Relative roots are interpreted from the fixture checkout, the
              ;; real Make child's cwd. No mutation of global user.dir is needed.
              requested (if (.isAbsolute (io/file root)) root (str (io/file (str checkout) root)))
              base (if (= shape :empty) (if (= source "user.home") checkout home) selected)
              expected (str (cond
                              (or (= source "user.home") (= shape :empty)) (.resolve base ".local/state/clj-surgeon")
                              (= source "XDG_STATE_HOME") (.resolve base "clj-surgeon")
                              :else base))
              kind (cond
                     (or (and (= shape :empty) (= source "user.home"))
                         (and (not= shape :empty) (= location :inside))) :state-root-inside-workspace
                     (or (= envelope :narrow)
                         (and (not= shape :empty) (= location :outside-envelope))) :state-root-outside-envelope)
              result (binding [artifacts/*destination-envelope*
                               (artifacts/destination-envelope
                                 [(str (if (= envelope :narrow) (.resolve runtime "allowed") runtime))]
                                 (if (= envelope :narrow) :launcher :policy-default))]
                       (try {:canonical-path (state/admit-state-root! (str checkout) requested)}
                            (catch clojure.lang.ExceptionInfo e (ex-data e))))
              row [source shape envelope location]]
          (swap! rows conj row)
          (is (= kind (:error-type result)) (pr-str [row result]))
          (is (= expected (:canonical-path result)) (pr-str [row result]))))
      (is (= 72 (count @rows) (count (set @rows))))
      (finally
        (with-open [paths (Files/walk container (make-array java.nio.file.FileVisitOption 0))]
          (doseq [path (reverse (sort (iterator-seq (.iterator paths))))]
            (Files/delete path)))))))

(defn- with-failing-passwd-home
  "Run `f` with a fresh launcher envelope and a passwd lookup that throws, the
   way it throws inside the macOS Claude Code sandbox (dscl is blocked)."
  [f]
  (let [artifacts-ns (the-ns 'clj-surgeon.receipt-artifacts)
        var-of #(ns-resolve artifacts-ns %)]
    (with-redefs-fn
      {(var-of 'launcher-envelope) (atom nil)
       (var-of 'passwd-home) #(throw (ex-info "Cannot determine invoking user's passwd home"
                                              {:error-type :invalid-operation-context
                                               :attempts []}))}
      #(binding [artifacts/*destination-envelope* nil]
         (f)))))

(deftest read-only-operation-does-not-need-the-passwd-home
  ;; The launcher initializes the envelope for EVERY operation. That must not
  ;; look up the passwd home: a read-only op writes nothing.
  (with-failing-passwd-home
    (fn []
      (let [out (with-out-str
                  (core/-main ":op" ":ls" ":file" "src/clj_surgeon/receipt_artifacts.clj"))]
        (is (not (str/includes? out ":error")) out)
        (is (str/includes? out "passwd-home") out)))))

(deftest write-envelope-still-refuses-without-the-passwd-home
  ;; Security intent: a write never falls back to $HOME.
  (with-failing-passwd-home
    (fn []
      (is (nil? (artifacts/initialize-envelope! "/work")))
      (let [data (try (artifacts/current-envelope) nil
                      (catch Exception e (ex-data e)))]
        (is (= :invalid-operation-context (:error-type data)))))))
