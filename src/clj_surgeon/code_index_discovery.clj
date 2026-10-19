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
(def ^:private default-max-config-bytes 1048576)
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

(defn- check-deadline!
  "Throw `:timeout` when the refresh time bound has passed. `expired?` is a
   zero-argument function or nil."
  [expired?]
  (when (and expired? (expired?))
    (throw (ex-info "the refresh time bound passed during discovery"
                    {:error-type :timeout :phase :discovery}))))

;; @spec MCP-OP-QUERY-120
(defn- read-first-form
  "Read the first form of the file with clojure.edn. Nil when it cannot be read.
   Reads at most `max-bytes` bytes: a file that holds more is not parsed and
   gives `{:too-large size}`, with `size` the length of the file. Never
   evaluates: `#=` is an unknown tag to the edn reader and throws."
  [^File file max-bytes]
  (try
    (let [^bytes data (with-open [in (Files/newInputStream (.toPath file) (make-array java.nio.file.OpenOption 0))]
                        (.readNBytes in (int (inc max-bytes))))]
      (if (> (alength data) max-bytes)
        {:too-large (.length file)}
        {:form (edn/read-string (String. data "UTF-8"))}))
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
  "The relative source-path strings that the build files of one directory give,
   and the build files that were too large to read:
   `{:paths [...] :skipped [{:file :reason :size}]}`. A build file that cannot
   be read contributes nothing here. The test and tests directories are added
   by the caller."
  [^Path root ^File dir max-config-bytes expired?]
  (reduce
   (fn [acc fname]
     (let [f (File. dir ^String fname)]
       (check-deadline! expired?)
       (if-not (.isFile f)
         acc
         (let [read (read-first-form f max-config-bytes)]
           (if-let [size (:too-large read)]
             (update acc :skipped conj
                     {:file (str/join "/" (map str (.relativize root (.toPath f))))
                      :reason :too-large
                      :size size})
             (update acc :paths into (some->> read :form (source-paths-of fname))))))))
   {:paths [] :skipped []}
   build-file-names))

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
   root by realpath, and the build files that were too large to read:
   `{:dirs [...] :skipped [...]}`. Source paths that do not exist or lie outside
   are dropped."
  [^Path root ^File root-file max-config-bytes expired?]
  (let [per-dir (mapv
                 (fn [^File dir]
                   (let [{:keys [paths skipped]}
                         (build-dir-source-paths root dir max-config-bytes expired?)
                         tests (filter #(.isDirectory (File. dir ^String %)) ["test" "tests"])]
                     {:dirs (map (fn [p] (.normalize (.resolve (.toPath dir) ^String p)))
                                 (distinct (concat paths tests)))
                      :skipped skipped}))
                 (build-directories root-file))]
    {:dirs (->> (mapcat :dirs per-dir)
                (filter (fn [^Path p]
                          (let [real (real-path p)]
                            (and (inside-root? root real) (real-directory? real)))))
                distinct
                vec)
     :skipped (vec (mapcat :skipped per-dir))}))

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
  [^Path root source-dirs max-files max-file-bytes expired?]
  (let [files         (volatile! [])
        skipped       (volatile! [])
        seen-files    (volatile! #{})
        seen-dirs     (volatile! #{})
        found         (volatile! 0)
        capped?       (volatile! false)
        visit-file
        (fn [^File f ^Path lexical]
          (check-deadline! expired?)
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
;; @spec MCP-OP-QUERY-120
(defn- read-lint-as*
  "`{:lint-as map :skipped [...]}` from `<root>/.clj-kondo/config.edn`. A config
   over `max-config-bytes` is not read: it adds no entry and is listed as
   `:too-large`."
  [root max-config-bytes]
  (let [file (File. (str root) ".clj-kondo/config.edn")]
    (if-not (.isFile file)
      {:lint-as {} :skipped []}
      (let [read (read-first-form file max-config-bytes)]
        (if-let [size (:too-large read)]
          {:lint-as {}
           :skipped [{:file ".clj-kondo/config.edn" :reason :too-large :size size}]}
          (let [lint-as (some-> read :form (get :lint-as))]
            {:lint-as (if (map? lint-as) lint-as {}) :skipped []}))))))

;; @spec MCP-OP-QUERY-019
(defn read-lint-as
  "Read `<root>/.clj-kondo/config.edn` with clojure.edn and return its
   `:lint-as` map. Everything else, `:hooks` included, is dropped. A missing or
   unreadable file, a file over the config-byte cap (default 1 MiB), or a
   `:lint-as` that is not a map, gives {}. Nothing is evaluated."
  ([root] (read-lint-as root default-max-config-bytes))
  ([root max-config-bytes]
   (:lint-as (read-lint-as* root max-config-bytes))))

;; @spec MCP-OP-QUERY-009
(defn discover
  "Discover the source files of the workspace at `root`.

   `root` is an absolute directory. `opts` is the bounds map; this function
   uses `:max-files`, `:max-file-bytes`, `:max-config-bytes` (default 1 MiB, for
   a build file and the kondo config) and `:expired?`, a zero-argument function
   that discovery calls before each build file and each source file; when it
   returns true, discovery throws `:timeout`. Returns
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
          max-config     (or (:max-config-bytes opts) default-max-config-bytes)
          expired?       (:expired? opts)
          {:keys [dirs] build-skipped :skipped}
          (source-roots root-real (.toFile root-real) max-config expired?)
          {:keys [files] walk-skipped :skipped}
          (walk-sources root-real dirs max-files max-file-bytes expired?)
          {:keys [lint-as] config-skipped :skipped}
          (read-lint-as* (str root-real) max-config)
          skipped        (-> (vec build-skipped)
                             (into config-skipped)
                             (into walk-skipped))]
      {:files          files
       :skipped        skipped
       :read-complete? (empty? skipped)
       :lint-as        lint-as})))

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
