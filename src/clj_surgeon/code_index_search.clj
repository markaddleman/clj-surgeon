(ns clj-surgeon.code-index-search
  "BM25 search from words to the var records of a code index (issue #27).

   The search index is built in memory from the var records of one index map,
   never from the file system, and is reused for the identical index map.
   The contract is MCP-OP-QUERY-136 to 140."
  (:require
   [clojure.string :as str])
  (:import
   (org.apache.lucene.analysis Analyzer Analyzer$TokenStreamComponents LowerCaseFilter)
   (org.apache.lucene.analysis.core FlattenGraphFilter)
   (org.apache.lucene.analysis.miscellaneous WordDelimiterGraphFilter)
   (org.apache.lucene.analysis.pattern PatternTokenizer)
   (org.apache.lucene.analysis.tokenattributes CharTermAttribute)
   (org.apache.lucene.document Document Field$Store SortedDocValuesField StoredField StringField TextField)
   (org.apache.lucene.index DirectoryReader IndexWriter IndexWriterConfig Term)
   (org.apache.lucene.search BooleanClause$Occur BooleanQuery$Builder BoostQuery
                             IndexSearcher ScoreDoc Sort SortField SortField$Type TermQuery)
   (org.apache.lucene.util BytesRef)
   (org.apache.lucene.store ByteBuffersDirectory)))

;; @spec MCP-OP-QUERY-137
(def ^:private split-pattern
  "Whitespace and the characters ()[]{}\"'`,;#^@~:/\\ separate tokens."
  #"[\s\(\)\[\]\{\}\"'`,;#\^@~:/\\]+")

;; @spec MCP-OP-QUERY-137
(def max-terms
  "The query holds at most this many distinct terms, so that three clauses for
   each term stay under Lucene's default limit of 1024 clauses."
  340)

;; @spec MCP-OP-QUERY-137
(defn analyzer
  "The one analysis for fields and query text: split, then split words on case,
   digits, hyphens, underscores and dots, keep the original token, lowercase.
   No stemming and no stop words."
  ^Analyzer []
  (proxy [Analyzer] []
    (createComponents [_field]
      (let [tokenizer (PatternTokenizer. split-pattern -1)
            flags (bit-or WordDelimiterGraphFilter/GENERATE_WORD_PARTS
                          WordDelimiterGraphFilter/GENERATE_NUMBER_PARTS
                          WordDelimiterGraphFilter/SPLIT_ON_CASE_CHANGE
                          WordDelimiterGraphFilter/SPLIT_ON_NUMERICS
                          WordDelimiterGraphFilter/PRESERVE_ORIGINAL)
            stream (-> (WordDelimiterGraphFilter. tokenizer flags nil)
                       (FlattenGraphFilter.)
                       (LowerCaseFilter.))]
        (Analyzer$TokenStreamComponents. tokenizer stream)))))

(defn terms
  "The terms of `text` under `a`, in order, with repeats."
  [^Analyzer a ^String text]
  (with-open [ts (.tokenStream a "text" text)]
    (let [attr (.addAttribute ts CharTermAttribute)]
      (.reset ts)
      (loop [acc (transient [])]
        (if (.incrementToken ts)
          (recur (conj! acc (str attr)))
          (do (.end ts) (persistent! acc)))))))

;; @spec MCP-OP-QUERY-139
(defn test-path?
  "True when a directory segment of `path` is `test` or `tests` (the rule of
   MCP-OP-QUERY-044)."
  [path]
  (boolean (some #{"test" "tests"} (butlast (str/split (str path) #"/")))))

(defn- first-versions
  "The first version record of each var of the index, sorted by qualified name."
  [index]
  (->> (:vars index)
       (keep (fn [[_ versions]] (first versions)))
       (sort-by :qname)))

;; @spec MCP-OP-QUERY-140
(defn build
  "A search index over the var records of `index`: {:searcher :analyzer :records}.
   :records is the vector of var records, by document number."
  [index]
  (let [a (analyzer)
        dir (ByteBuffersDirectory.)
        records (vec (first-versions index))]
    (with-open [w (IndexWriter. dir (IndexWriterConfig. a))]
      (doseq [[i rec] (map-indexed vector records)]
        (let [d (Document.)]
          (.add d (StoredField. "n" (int i)))
          (.add d (StringField. "test" (str (test-path? (:file rec))) Field$Store/NO))
          (.add d (SortedDocValuesField. "qname" (BytesRef. (str (:qname rec)))))
          (.add d (TextField. "name" (str (:qname rec)) Field$Store/NO))
          (.add d (TextField. "doc" (str (:doc rec)) Field$Store/NO))
          (.add d (TextField. "source" (str (:source rec)) Field$Store/NO))
          (.addDocument w d))))
    {:searcher (IndexSearcher. (DirectoryReader/open dir))
     :analyzer a
     :records records}))

;; @spec MCP-OP-QUERY-140
(def ^:private cache-size 4)

(defonce ^:private cache
  ;; [[index-map search-index] ...], newest first, compared with identical?
  (atom []))

;; @spec MCP-OP-QUERY-140
(defn search-index
  "The search index of `index`, built once for each identical index map. At most
   four are kept."
  [index]
  (or (some (fn [[k v]] (when (identical? k index) v)) @cache)
      (let [built (build index)]
        (swap! cache (fn [entries]
                       (->> (remove (fn [[k _]] (identical? k index)) entries)
                            (cons [index built])
                            (take cache-size)
                            vec)))
        built)))

(def ^:private fields [["name" 2.0] ["doc" 1.0] ["source" 1.0]])

;; @spec MCP-OP-QUERY-137
;; @spec MCP-OP-QUERY-139
(defn ranked-records
  "The var records that match the terms of `text`, best first; equal scores by
   qualified name; at most `limit`. Test files are left out unless `include-tests?`."
  [index text include-tests? limit]
  (let [{:keys [^IndexSearcher searcher analyzer records]} (search-index index)
        qterms (take max-terms (distinct (terms analyzer text)))]
    (if (empty? qterms)
      []
      (let [b (BooleanQuery$Builder.)
            should (BooleanQuery$Builder.)]
        (doseq [t qterms [field boost] fields]
          (.add should (BoostQuery. (TermQuery. (Term. ^String field ^String t)) (float boost))
                BooleanClause$Occur/SHOULD))
        (.add b (.build should) BooleanClause$Occur/MUST)
        (when-not include-tests?
          (.add b (TermQuery. (Term. "test" "true")) BooleanClause$Occur/MUST_NOT))
        (let [sort (Sort. (into-array SortField [SortField/FIELD_SCORE (SortField. "qname" SortField$Type/STRING)]))
              stored (.storedFields searcher)
              hits (.scoreDocs (.search searcher (.build b) (int limit) sort))]
          (mapv (fn [^ScoreDoc h]
                  (records (.intValue (.numericValue (.getField (.document stored (.doc h)) "n")))))
                hits))))))
