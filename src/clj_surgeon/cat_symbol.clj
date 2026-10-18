(ns clj-surgeon.cat-symbol
  "Read exact top-level forms by qualified symbol (`my.ns/my-fn`).

   The namespace is mapped to a file by the Clojure path rule
   (`my.ns-x` -> `my/ns_x.clj`, `.cljc` or `.cljs`) over the files that
   project discovery found. No file is parsed to find a namespace. The read
   itself is `show-form/show-file` or `show-form/show-files`, so the output and
   the guards are the ones of `:cat :file F :form NAME`."
  (:require
   [clj-surgeon.forms :as forms]
   [clj-surgeon.outline :as outline]
   [clj-surgeon.show-form :as show-form]
   [clojure.string :as str]))

(def ^:private max-candidates 8)

(defn- ->symbol
  "A qualified symbol from a symbol or a string, else nil."
  [x]
  (let [s (cond (symbol? x) x
                (and (string? x) (not (str/blank? x))) (symbol x)
                :else nil)]
    (when (and s (namespace s) (seq (name s))) s)))

(defn- requested-symbols
  "Pure: the vector of requested raw values, or an error map."
  [{:keys [sym syms] :as opts}]
  (cond
    (and (contains? opts :sym) (contains? opts :syms))
    {:error "Supply :sym or :syms, not both"
     :error-type :conflicting-symbol-selectors}

    (contains? opts :syms)
    (if (and (vector? syms) (seq syms))
      {:raw syms}
      {:error ":syms must be a nonempty EDN vector of qualified symbols"
       :error-type :invalid-syms})

    :else {:raw [sym]}))

(defn- ns->suffix [ns-name]
  (str "/" (-> ns-name (str/replace "-" "_") (str/replace "." "/"))))

(defn- file-suffix-match?
  [suffix file]
  (let [f (str file)]
    (some #(str/ends-with? f (str suffix %)) [".clj" ".cljc" ".cljs"])))

(defn- levenshtein [^String a ^String b]
  (let [n (count b)]
    (loop [i 0, prev (vec (range (inc n)))]
      (if (= i (count a))
        (peek prev)
        (recur (inc i)
               (reduce (fn [row j]
                         (conj row
                               (min (inc (peek row))
                                    (inc (nth prev (inc j)))
                                    (+ (nth prev j)
                                       (if (= (nth a i) (nth b j)) 0 1)))))
                       [(inc i)]
                       (range n)))))))

(defn- file->ns-guess
  "The namespace spelling of `file`, keeping as many trailing path segments as
   the requested namespace has."
  [file k]
  (let [base (str/replace (str file) #"\.clj[cs]?$" "")
        segs (remove str/blank? (str/split base #"/"))]
    (->> segs (take-last k)
         (map #(str/replace % "_" "-"))
         (str/join "."))))

(defn- close-namespaces [ns-name files]
  (let [k (count (str/split ns-name #"\."))
        last-seg (last (str/split ns-name #"\."))]
    (->> files
         (map #(file->ns-guess % k))
         distinct
         (map (fn [guess] [guess (levenshtein ns-name guess)]))
         (filter (fn [[guess d]]
                   (or (<= d 3)
                       (str/includes? guess last-seg)
                       (str/includes? last-seg (last (str/split guess #"\."))))))
         (sort-by (juxt second first))
         (map first)
         (take max-candidates)
         vec)))

(defn- locate-namespace
  "Pure: {:file F} or an error map for `ns-name` over `files`."
  [ns-name files]
  (let [suffix (ns->suffix ns-name)
        hits (->> files (filter #(file-suffix-match? suffix %)) distinct sort vec)]
    (case (count hits)
      1 {:file (first hits)}
      0 (let [near (close-namespaces ns-name files)]
          (cond-> {:error (str "Namespace not found under :dir: " ns-name
                               (if (seq near)
                                 (str ". Close names: " (str/join ", " near))
                                 ""))
                   :error-type :namespace-not-found
                   :namespace ns-name}
            (seq near) (assoc :candidates near)))
      {:error (str "Namespace " ns-name " matches " (count hits)
                   " files; pass :file to choose one")
       :error-type :ambiguous-namespace
       :namespace ns-name
       :files hits})))

(defn- top-level-names
  [file]
  (->> (outline/top-level-form-records
         file (slurp file) (or @forms/project-aliases {}))
       (keep :name)
       distinct
       (map str)
       vec))

(defn- name-candidates [wanted names]
  (let [w (str wanted)]
    (->> names
         (sort-by (fn [n] [(if (str/includes? n w) 0 1) (levenshtein w n) n]))
         (take 20)
         vec)))

(defn- resolve-one
  "Resolve one raw value to {:sym :ns :name :file} or an error map."
  [raw files]
  (if-let [s (->symbol raw)]
    (let [located (locate-namespace (namespace s) files)]
      (if (:error located)
        (assoc located :symbol s)
        (let [file (:file located)
              names (top-level-names file)]
          (if (some #{(name s)} names)
            {:symbol s :file file :name (symbol (name s))}
            {:error (str "Name not found in " (namespace s) ": " (name s)
                         ". Forms of the namespace (closest first): "
                         (str/join ", " (name-candidates (name s) names)))
             :error-type :symbol-not-found
             :symbol s
             :file file
             :candidates (name-candidates (name s) names)}))))
    {:error (str "Not a qualified symbol (expected my.ns/my-fn): " (pr-str raw))
     :error-type :invalid-symbol
     :value (pr-str raw)}))

(defn show
  "Read the forms named by `:sym` / `:syms` from `files`.

   `files` are the source files that project discovery found under :dir.
   Several symbols of one file are one `:forms` read; symbols over several
   files are one all-or-nothing cross-file read."
  [opts files]
  (let [requested (requested-symbols opts)]
    (if (:error requested)
      requested
      (let [resolved (mapv #(resolve-one % files) (:raw requested))
            failures (filterv :error resolved)]
        (cond
          (seq failures)
          (if (= 1 (count failures))
            (first failures)
            {:error (str (count failures) " symbols could not be resolved; no source is returned")
             :error-type :symbol-resolution-failed
             :failures failures})

          (not= (count resolved) (count (distinct (map :symbol resolved))))
          {:error "Duplicate symbols requested"
           :error-type :duplicate-symbols}

          :else
          (let [by-file (->> resolved (group-by :file))
                order (distinct (map :file resolved))
                platform (select-keys opts [:platform])]
            (if (= 1 (count order))
              (let [names (mapv :name resolved)
                    file (first order)]
                (show-form/show-file
                  (merge platform {:file file}
                         (if (= 1 (count names))
                           {:form (first names)}
                           {:forms names}))))
              (show-form/show-files
                (merge platform
                       {:reads (mapv (fn [file]
                                       (merge platform
                                              {:file file
                                               :forms (mapv :name (by-file file))}))
                                     order)
                        :expect {:file-count (count order)
                                 :form-count (count resolved)}})))))))))
