(ns clj-surgeon.code-index-judge
  "SPIKE (issue #27, stage B): `judge` and `locate`, bound only when the
   environment variable CLJ_SURGEON_JUDGE is \"1\".

   `judge` asks a cheap model (gemini-3.8-flash on Vertex AI, with gcloud
   application-default credentials) one question about some data. `locate`
   runs the three-round loop of the offline replay that passed its gate
   (https://github.com/markaddleman/clj-surgeon/issues/27#issuecomment-6265307578):
   search, judge, expand with callers, callees and new phrases, judge, and a
   ranked final answer. This namespace is spike code, not a reviewed surface."
  (:require
   [cheshire.core :as json]
   [clj-surgeon.code-index-search :as search-index]
   [clojure.java.shell :as sh]
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.time Duration)))

(def enabled?
  "True when the spike is switched on for this process."
  (= "1" (System/getenv "CLJ_SURGEON_JUDGE")))

(def eval-timeout-ms
  "The evaluation deadline while the spike is on: three model rounds take 30 to 60 s."
  180000)

(def ^:private model-url
  (str "https://aiplatform.googleapis.com/v1/projects/"
       (or (System/getenv "CLJ_SURGEON_JUDGE_PROJECT") "pyze-automation-dev3")
       "/locations/global/publishers/google/models/gemini-3.8-flash:generateContent"))

(def ^:private usage-log (System/getenv "CLJ_SURGEON_JUDGE_LOG"))

(defonce ^:private http (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) (.build))))
(defonce ^:private token-cache (atom nil))

(defn- token []
  (let [{:keys [t at]} @token-cache]
    (if (and t (< (- (System/currentTimeMillis) at) (* 30 60 1000)))
      t
      (let [t (str/trim (:out (sh/sh "gcloud" "auth" "application-default" "print-access-token")))]
        (reset! token-cache {:t t :at (System/currentTimeMillis)})
        t))))

(defn- log-usage! [kind usage]
  (when usage-log
    (spit usage-log (str (pr-str {:at (System/currentTimeMillis) :kind kind
                                  :in (:promptTokenCount usage) :out (:candidatesTokenCount usage)
                                  :think (:thoughtsTokenCount usage)})
                         "\n")
          :append true)))

(defn- call-model
  "One generateContent call; retries a 429 up to 4 times. Returns the text."
  [kind system user]
  (let [body (json/generate-string
              {:systemInstruction {:parts [{:text system}]}
               :contents [{:role "user" :parts [{:text user}]}]
               :generationConfig {:maxOutputTokens 4096 :temperature 0
                                  :thinkingConfig {:thinkingBudget 512}}})]
    (loop [i 0]
      (let [req (-> (HttpRequest/newBuilder (URI. model-url))
                    (.header "Authorization" (str "Bearer " (token)))
                    (.header "Content-Type" "application/json")
                    (.timeout (Duration/ofSeconds 90))
                    (.POST (HttpRequest$BodyPublishers/ofString body)) (.build))
            resp (.send ^HttpClient @http req (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)]
        (cond
          (= 200 status)
          (let [p (json/parse-string (.body resp) true)]
            (log-usage! kind (:usageMetadata p))
            (apply str (keep :text (get-in p [:candidates 0 :content :parts]))))
          (and (= 429 status) (< i 4)) (do (Thread/sleep 15000) (recur (inc i)))
          :else (throw (ex-info (str "judge model call failed with HTTP " status)
                                {:error-type :evaluation-error
                                 :remedy "Send the request again later, or use search and form."})))))))

(def ^:private max-data-chars 150000)

(defn judge
  "Ask the cheap model `question` about `data` (a string, or any value, printed).
   Returns its answer as a string."
  [question data]
  (let [text (if (string? data) data (pr-str data))
        text (subs text 0 (min max-data-chars (count text)))]
    (call-model :judge
                (str "You answer questions about Clojure code for a coding agent. Be brief and exact. "
                     "Quote only the code lines that matter.")
                (str "QUESTION:\n" question "\n\nDATA:\n" text))))

;; ---------------------------------------------------------------- locate

(def ^:private round-system
  (str "You help a coding agent change a Clojure code base. You get an issue, the vars chosen so far, and candidate vars "
       "with their source. Pick up to 8 existing vars that may need to change to fix the issue, most likely first. "
       "Include a var when it is plausible, not only when it is certain. Reply in exactly this form:\n"
       "CHOSEN: qualified/name, qualified/name\nSEARCH: phrase one | phrase two\n"
       "CHOSEN holds only names from the candidates or the chosen list. SEARCH holds at most 3 short phrases of words "
       "likely to appear in code you have not seen yet. Be brief."))

(def ^:private final-system
  (str "You help a coding agent change a Clojure code base. You get an issue, the vars chosen so far, and candidate vars "
       "with their source. Rank up to 8 existing vars that may need to change to fix the issue, most likely first. "
       "Include a var when it is plausible, not only when it is certain. For each, give one line: the qualified name "
       "between ** marks, a reason of at most 15 words, and only the few source lines that matter. Name only vars from "
       "the candidates or the chosen list. Be brief."))

(defn- parse-round [text]
  {:chosen (some->> (re-find #"(?m)^CHOSEN:\s*(.*)$" text) second
                    (re-seq #"[\w.*+!?<>=\-]+/[\w.*+!?<>=\-']+") vec)
   :search (some->> (re-find #"(?m)^SEARCH:\s*(.*)$" text) second (#(str/split % #"\|"))
                    (map str/trim) (remove str/blank?) (take 3) vec)})

(defn- src-record [index q]
  (let [rec (first (get-in index [:vars q]))]
    (when (and rec (not (search-index/test-path? (:file rec)))) rec)))

(defn- hits [index text n]
  (mapv :qname (search-index/ranked-records index text false n)))

(defn- candidates [index chosen phrases]
  (let [nbrs (mapcat #(concat (:calls (src-record index %)) (get-in index [:callers %])) chosen)
        found (mapcat #(hits index % 5) phrases)]
    (->> (concat chosen nbrs found) (filter #(src-record index %)) distinct (take 30) vec)))

(defn- sources [index qs]
  (str/join "\n\n" (for [q qs :let [s (str (:source (src-record index q)))]]
                     (str ";; " q "\n" (subs s 0 (min 4000 (count s)))))))

(defn locate
  "The vars that may need to change for `text` (an issue or a few words), ranked,
   with a reason and the relevant lines each. Three model rounds over the index."
  [index text]
  (let [ask (fn [system chosen cands]
              (call-model :locate system (str "ISSUE:\n" text "\n\nCHOSEN SO FAR: " (str/join ", " chosen)
                                              "\n\nCANDIDATES:\n" (sources index cands))))
        c1 (hits index text 20)
        p1 (parse-round (ask round-system [] c1))
        ch1 (vec (filter (set c1) (:chosen p1)))
        c2 (candidates index ch1 (:search p1))
        p2 (parse-round (ask round-system ch1 c2))
        ch2 (vec (distinct (filter (set (concat ch1 c2)) (:chosen p2))))
        c3 (candidates index ch2 (:search p2))]
    (ask final-system ch2 c3)))
