(ns clj-surgeon.code-index-discovery
  "Workspace discovery for the code index: which source files exist, which
   `:lint-as` entries apply, and how to read one file so that its bytes, its
   mtime, its size and its SHA-256 describe the same version of the file.

   Fence rules. Build files and the kondo config are read with `clojure.edn`
   only and never evaluated. Every path is confined to the workspace root
   after `toRealPath`. No subprocess runs here.

   The build-file rules mirror `clj-surgeon.core/source-paths-from-config`.
   This namespace does not require `clj-surgeon.core`: that namespace holds
   the CLI and shells out, and the index must stay free of both."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.nio.file Files LinkOption Path Paths)
   (java.security MessageDigest)))

;; ---------------------------------------------------------------------------
;; Constants and path helpers
;; ---------------------------------------------------------------------------

(def ^:private default-max-files 10000)
(def ^:private default-max-file-bytes 2097152)
(def ^:private max-build-depth 3)
(def ^:private skipped-directory-names #{"node_modules" "target"})
(def ^:private build-file-names ["deps.edn" "bb.edn" "project.clj"])
(def ^:private source-extensions [".clj" ".cljs" ".cljc"])

(def ^:private follow (make-array LinkOption 0))
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- ^Path as-path
  [s]
  (Paths/get (str s) (make-array String 0)))

(defn- real-path
  "The real path, or nil when it does not resolve."
  ^Path [^Path p]
  (try (.toRealPath p follow) (catch Throwable _ nil)))

(defn- inside-root?
  [^Path root ^Path real]
  (and (some? real) (.startsWith real root)))

(defn- real-directory?
  [^Path p]
  (try (Files/isDirectory p follow) (catch Throwable _ false)))

(defn- sorted-children
  "The children of a directory, sorted by name. Nil entries are never returned."
  [^File dir]
  (let [children (.listFiles dir)]
    (if (nil? children)
      []
      (sort-by #(.getName ^File %) (vec children)))))

(defn- source-name?
  [^String name]
  (boolean (some #(str/ends-with? name %) source-extensions)))

;; ---------------------------------------------------------------------------
;; Build files
;; ---------------------------------------------------------------------------

(defn- read-first-form
  "Read the first form of the file with clojure.edn. Nil when it cannot be read.
   Never evaluates: `#=` is an unknown tag to the edn reader and throws."
  [^File file]
  (try
    (let [form (edn/read-string (slurp file))]
      {:form form})
    (catch Throwable _ nil)))

(defn- strings-only
  [coll]
  (when (sequential? coll)
    (filterv string? coll)))

(defn- source-paths-of
  "Mirror of `clj-surgeon.core/source-paths-from-config`, hardened for content
   that is read from an untrusted repository. Returns a vector of strings, or
   nil when the content has the wrong shape."
  [filename content]
  (case filename
    ("deps.edn" "bb.edn")
    (cond
      (nil? content) ["src"]
      (map? content) (or (strings-only (:paths content)) ["src"])
      :else nil)

    "project.clj"
    (when (and (seq? content) (>= (count content) 3))
      (let [kvs (drop 3 content)
            m   (into {} (map vec) (partition-all 2 kvs))]
        (or (strings-only (get m :source-paths)) ["src"])))

    nil))

(defn- build-dir-source-paths
  "The relative source-path strings that the build files of one directory give.
   A build file that cannot be read contributes nothing here. The test and
   tests directories are added by the caller."
  [^File dir]
  (vec
   (mapcat
    (fn [fname]
      (let [f (File. dir ^String fname)]
        (when (.isFile f)
          (some->> (read-first-form f)
                   :form
                   (source-paths-of fname)))))
    build-file-names)))

(defn- has-build-file?
  [^File dir]
  (some #(.isFile (File. dir ^String %)) build-file-names))

(defn- build-directories
  "Directories (as Files, lexical paths) at depth <= 3 below the root, root
   included, that hold a build file. Hidden directories, node_modules and
   target are skipped. A symbolic link to a directory is not descended."
  [^File root]
  (letfn [(walk [^File dir depth]
            (concat
             (when (has-build-file? dir) [dir])
             (when (< depth max-build-depth)
               (mapcat
                (fn [^File child]
                  (let [n (.getName child)]
                    (when (and (Files/isDirectory (.toPath child) nofollow)
                               (not (str/starts-with? n "."))
                               (not (contains? skipped-directory-names n)))
                      (walk child (inc depth)))))
                (sorted-children dir)))))]
    (vec (walk root 0))))

(defn- source-roots
  "Lexical, normalized directory paths that are source paths, confined to the
   root by realpath. Source paths that do not exist or lie outside are dropped."
  [^Path root ^File root-file]
  (->> (build-directories root-file)
       (mapcat
        (fn [^File dir]
          (let [declared (build-dir-source-paths dir)
                tests    (filter #(.isDirectory (File. dir ^String %)) ["test" "tests"])]
            (map (fn [p] (.normalize (.resolve (.toPath dir) ^String p)))
                 (distinct (concat declared tests))))))
       (filter (fn [^Path p]
                 (let [real (real-path p)]
                   (and (inside-root? root real) (real-directory? real)))))
       distinct
       vec))

;; ---------------------------------------------------------------------------
;; Walk
;; ---------------------------------------------------------------------------

(defn- relative-string
  [^Path root ^Path p]
  (str/join "/" (map str (.relativize root p))))

(defn- mtime-millis
  [^Path p]
  (.toMillis (Files/getLastModifiedTime p follow)))

;; @spec MCP-OP-QUERY-009
;; @spec MCP-OP-QUERY-011
;; @spec MCP-OP-QUERY-012
;; @spec MCP-OP-QUERY-013
;; @spec MCP-OP-QUERY-014
(defn- walk-sources
  "Walk the source roots. Returns {:files [...] :skipped [...]}. Every source
   file found counts toward the cap. A link whose realpath leaves the root is
   not followed and not read. A real directory or file is visited once."
  [^Path root source-dirs max-files max-file-bytes]
  (let [files         (volatile! [])
        skipped       (volatile! [])
        seen-files    (volatile! #{})
        seen-dirs     (volatile! #{})
        found         (volatile! 0)
        capped?       (volatile! false)
        visit-file
        (fn [^File f ^Path lexical]
          (let [real (real-path lexical)]
            (when (and (inside-root? root real)
                       (not (contains? @seen-files (str real)))
                       (Files/isRegularFile real follow))
              (vswap! seen-files conj (str real))
              (if (>= @found max-files)
                (vreset! capped? true)
                (let [size (Files/size real)
                      rel  (relative-string root lexical)]
                  (vswap! found inc)
                  (if (> size max-file-bytes)
                    (vswap! skipped conj {:file rel :reason :too-large :size size})
                    (vswap! files conj {:path  rel
                                        :abs   (str (.resolve root rel))
                                        :mtime (mtime-millis real)
                                        :size  size})))))))]
    (letfn [(walk-dir [^Path lexical]
              (let [real (real-path lexical)]
                (when (and (inside-root? root real)
                           (real-directory? real)
                           (not (contains? @seen-dirs (str real))))
                  (vswap! seen-dirs conj (str real))
                  (doseq [^File child (sorted-children (.toFile lexical))
                          :while (not @capped?)]
                    (let [cp (.toPath child)]
                      (if (real-directory? cp)
                        (walk-dir cp)
                        (when (source-name? (.getName child))
                          (visit-file child cp))))))))]
      (doseq [d source-dirs
              :while (not @capped?)]
        (walk-dir d)))
    {:files   (vec (sort-by :path @files))
     :skipped (cond-> @skipped
                @capped? (conj {:reason :file-cap :cap max-files}))}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

;; @spec MCP-OP-QUERY-019
(defn read-lint-as
  "Read `<root>/.clj-kondo/config.edn` with clojure.edn and return its
   `:lint-as` map. Everything else, `:hooks` included, is dropped. A missing or
   unreadable file, or a `:lint-as` that is not a map, gives {}. Nothing is
   evaluated."
  [root]
  (let [file (File. (str root) ".clj-kondo/config.edn")]
    (if-not (.isFile file)
      {}
      (let [lint-as (some-> (read-first-form file) :form (get :lint-as))]
        (if (map? lint-as) lint-as {})))))

;; @spec MCP-OP-QUERY-009
(defn discover
  "Discover the source files of the workspace at `root`.

   `root` is an absolute directory. `opts` is the bounds map; this function
   uses `:max-files` and `:max-file-bytes`. Returns
   `{:files [{:path :abs :mtime :size}] :skipped [...] :read-complete? bool
     :lint-as {sym sym}}`. Throws `:invalid-workspace-root` when `root` is not an
   existing directory."
  [root opts]
  (let [root-real (when (and (string? root) (real-directory? (as-path root)))
                    (real-path (as-path root)))]
    (when (nil? root-real)
      (throw (ex-info (str "workspace root is not a directory: " root)
                      {:error-type :invalid-workspace-root :root root})))
    (let [max-files      (or (:max-files opts) default-max-files)
          max-file-bytes (or (:max-file-bytes opts) default-max-file-bytes)
          dirs           (source-roots root-real (.toFile root-real))
          {:keys [files skipped]} (walk-sources root-real dirs max-files max-file-bytes)]
      {:files          files
       :skipped        skipped
       :read-complete? (empty? skipped)
       :lint-as        (read-lint-as (str root-real))})))

;; @spec MCP-OP-QUERY-017
(defn sha256
  "SHA-256 of a byte array or of the UTF-8 bytes of a string, as 64 lowercase
   hex characters."
  [x]
  (let [^bytes data   (if (string? x) (.getBytes ^String x "UTF-8") x)
        digest (.digest (doto (MessageDigest/getInstance "SHA-256")
                          (.update data)))]
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) digest))))

(defn- default-stat
  [path]
  (let [p (as-path path)]
    {:mtime (mtime-millis p)
     :size  (Files/size p)}))

(defn- default-read
  [path]
  (Files/readAllBytes (as-path path)))

(defn- same-stat?
  [a b]
  (and (= (:mtime a) (:mtime b)) (= (:size a) (:size b))))

;; @spec MCP-OP-QUERY-015
;; @spec MCP-OP-QUERY-016
;; @spec MCP-OP-QUERY-017
(defn read-stable
  "Read a file so that its bytes and its stat belong together.

   A round stats the file, reads it, and stats it again. When the two stats
   agree in mtime and size the round succeeds. Otherwise another round runs, up
   to `:max-attempts` (default 3). Returns
   `{:ok true :bytes :mtime :size :sha256 :attempts}`,
   `{:ok false :reason :unstable :attempts n}` or, when `:stat-fn` or `:read-fn`
   throws, `{:ok false :reason :unreadable :message m}`. Does not throw."
  [path {:keys [stat-fn read-fn max-attempts]
         :or   {stat-fn default-stat read-fn default-read max-attempts 3}}]
  (try
    (loop [attempt 1]
      (let [s1    (stat-fn path)
            bytes (read-fn path)
            s2    (stat-fn path)]
        (cond
          (same-stat? s1 s2)
          {:ok       true
           :bytes    bytes
           :mtime    (:mtime s2)
           :size     (:size s2)
           :sha256   (sha256 bytes)
           :attempts attempt}

          (< attempt max-attempts) (recur (inc attempt))
          :else {:ok false :reason :unstable :attempts attempt})))
    (catch Exception e
      {:ok false :reason :unreadable :message (ex-message e)})))
