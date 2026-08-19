;;; Cases are {:nl ... :results {...}}, optionally with an :expected
;;; canonical query to compare against — :datomic-style query types do via
;;; α-equivalence (see `queries-equivalent?`); :sql/:sparql don't, since two
;;; query strings can be semantically identical and textually unrecognizable.
(ns hyperphor.nlq.evals
  "Eval harness for NL->query generation quality — runs eval cases against a
   real project, scoring on whether the query ran and its results matched."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hyperphor.nlq.generate :as generate]
            [hyperphor.nlq.config :as nlqc]
            [hyperphor.multitool.core :as u]
            clojure.data))

;;; ── α-Equivalence, for query types with a canonical comparable form ────────
;;;
;;; Two Datomic-shaped queries are α-equivalent if they are identical under
;;; consistent renaming of logic variables (symbols starting with ?). We also
;;; treat :where clause order as insignificant. Useful for any query type
;;; whose generated form is a Clojure data structure over logic variables,
;;; not just Datomic specifically.

(defn- logic-var?
  [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- collect-vars-ordered
  "Walk form, returning logic variables in order of first appearance."
  [form]
  (let [seen     (atom [])
        seen-set (atom #{})]
    (walk/postwalk
     (fn [x]
       (when (and (logic-var? x) (not (contains? @seen-set x)))
         (swap! seen conj x)
         (swap! seen-set conj x))
       x)
     form)
    @seen))

(defn canonicalize-query
  "Rename logic variables to ?v0, ?v1, … by order of first appearance,
   then sort :where clauses lexicographically for order-insensitive comparison."
  [query]
  (when query
    (let [vars    (collect-vars-ordered query)
          var-map (zipmap vars (map #(symbol (str "?v" %)) (range)))
          renamed (walk/postwalk #(get var-map % %) query)]
      (update renamed :where #(when % (vec (sort-by pr-str %)))))))

(defn queries-equivalent?
  "True if q1 and q2 are α-equivalent (same up to variable renaming
   and :where clause ordering)."
  [q1 q2]
  (= (canonicalize-query q1) (canonicalize-query q2)))

;;; ── Result checking ────────────────────────────────────────────────────────

(defn check-results
  "Check `actual` results against `expected`'s :count/:min-count/:max-count/
   :not-empty spec. Returns {:pass? bool :failures [...]}."
  [expected actual]
  (if (nil? expected)
    {:pass? true :skipped true}
    (let [n        (count actual)
          failures (cond-> []
                     (and (:count expected) (not= (:count expected) n))
                     (conj {:check :count :expected (:count expected) :actual n})

                     (and (:min-count expected) (< n (:min-count expected)))
                     (conj {:check :min-count :expected (str ">=" (:min-count expected)) :actual n})

                     (and (:max-count expected) (> n (:max-count expected)))
                     (conj {:check :max-count :expected (str "<=" (:max-count expected)) :actual n})

                     (and (:not-empty expected) (empty? actual))
                     (conj {:check :not-empty :actual "empty"}))]
      {:pass? (empty? failures) :failures failures})))

;;; ── Single eval runner ─────────────────────────────────────────────────────

;;; :sql/:sparql (and any other type using llm-util/extract-code) return
;;; [type code text]; a :datomic-style extension using extract-clojure
;;; instead returns [code text] already, so it's handled specially below.
(defn- generate-for-eval
  "Call `generate/generate` for `query-type`, normalizing its return shape to
   [code text]."
  [query-type nl]
  (if (= query-type :datomic)
    (generate/generate :datomic nl)
    (let [[_type code text] (generate/generate query-type nl)]
      [code text])))

;;; `project` binds generate/*project-conf* to that nlq-config entry for the
;;; call's duration — required for :sql/:sparql (generation and running the
;;; query both need the project's DB/schema). `provider`/`model` override
;;; the project's configured LLM, for cross-model comparisons (see
;;; `cross-check`). Only query types with a canonical comparable form (the
;;; α-equivalence section above) get a :query-match? check against an
;;; :expected query; :sql/:sparql just run the generated query and check
;;; the case's :results spec. Also logs the run via `generate/record` (a
;;; no-op unless a log target is configured), with the case's :tests
;;; description (if any) carried in the log's :user_reason column so eval
;;; rows are distinguishable there.
(defn run-eval
  "Run a single eval case (`query-type` defaults to :sql). Returns a result
   map: :nl :query-type :expected :generated :gen-error :parse-error
   :run-error :query-match? :result-check :result-count :pass? :elapsed-ms."
  [{:keys [nl expected results tests]}
   & {:keys [query-type project provider model]
      :or {query-type :sql}}]
  (binding [generate/*project-conf*
            (cond-> (some-> project nlqc/project-named)
              (or provider model)
              (update :llm merge (cond-> {}
                                    provider (assoc :provider provider)
                                    model    (assoc :model model))))
            generate/*last-llm-call* (atom nil)
            generate/*query-type* query-type]
    (let [start (System/currentTimeMillis)
          canonical-mode? (some? expected)

          ;; Step 1: call the LLM
          [generated-query gen-text gen-error]
          (try
            (let [[q t] (generate-for-eval query-type nl)]
              [q t nil])
            (catch Exception e
              [nil nil e]))

          ;; Step 2: if query is nil the LLM response had no valid code block
          parse-error (when (and (nil? generated-query) (nil? gen-error))
                        (or gen-text "No code block in LLM response"))

          ;; Step 3: α-equivalence check against expected query, if this case has one
          query-match? (when (and canonical-mode? generated-query)
                         (queries-equivalent? expected generated-query))

          ;; Step 4: run a query to test results — prefer the generated
          ;; query, falling back to the expected one (if any) if generation
          ;; failed
          query-to-run (or generated-query expected)

          [actual-results run-error]
          (if query-to-run
            (try
              [(generate/run-query query-to-run) nil]
              (catch Exception e
                ;; If the generated query is broken, also try the expected query
                (if (and canonical-mode? (not= query-to-run expected))
                  (try
                    [(generate/run-query expected) nil]
                    (catch Exception _
                      [nil (ex-message e)]))
                  [nil (ex-message e)])))
            [nil nil])

          ;; Step 5: check result spec
          result-check (check-results results actual-results)
          elapsed      (- (System/currentTimeMillis) start)]

      (generate/record project
                        {:nl nl
                         :query generated-query
                         :text gen-text
                         :results actual-results
                         :error (or gen-error parse-error run-error)
                         :user-reason (or tests "eval")}
                        @generate/*last-llm-call*)

      {:nl           nl
       :query-type   query-type
       :elapsed-ms   elapsed
       :expected     (when canonical-mode? expected)
       :generated    generated-query
       :gen-error    gen-error
       :parse-error  parse-error
       :run-error    run-error
       :query-match? query-match?
       :result-check result-check
       :result-count (when actual-results (count actual-results))
       :pass?        (and (nil? gen-error)
                          (nil? parse-error)
                          (nil? run-error)
                          (or (not canonical-mode?) query-match?)
                          (:pass? result-check))})))

;;; ── Batch runner ───────────────────────────────────────────────────────────

(defn run-evals
  "Run `:eval-cases` (required, no default set ships here) via `run-eval`
   and print a summary report. Returns the result seq."
  [& {:keys [eval-cases query-type project provider model]
      :or {query-type :sql}}]
  (println (str "\n=== NLQ Eval Run — " (count eval-cases) " cases, " (name query-type)
               (when project (str ", " project))
               (when model (str ", " model))
               " ===\n"))
  (let [results (mapv #(run-eval % :query-type query-type :project project
                                 :provider provider :model model)
                      eval-cases)
        passed  (filter :pass? results)]
    (doseq [{:keys [nl pass? query-match? gen-error parse-error run-error
                    result-check elapsed-ms]} results]
      (println (str (if pass? "✓" "✗") "  " nl))
      (when gen-error   (println (str "    GEN ERROR:   " gen-error)))
      (when parse-error (println (str "    PARSE ERROR: " parse-error)))
      (when run-error   (println (str "    RUN ERROR:   " run-error)))
      (when (false? query-match?)
        (println "    QUERY MISMATCH (expected ≠ generated)"))
      (doseq [f (:failures result-check)]
        (println (str "    RESULT FAIL:  " f)))
      (println (str "    (" elapsed-ms " ms)")))
    (println (str "\nPassed: " (count passed) "/" (count results) "\n"))
    results))

;;; ── Cross-model comparison ───────────────────────────────────────────────

(defn- config-label
  [{:keys [provider model]}]
  (str (some-> provider name) "/" model))

(defn cross-check
  "Run `eval-cases` against each of `configs` ({:provider :model} maps,
   either key optional, falling back to the project's LLM) and print a
   pass/fail matrix. Returns a seq of {:provider :model :results [...]}."
  [& {:keys [eval-cases configs query-type project]
      :or {query-type :sql
           configs [{:provider :openai :model "gpt-4o"}
                    {:provider :openai :model "gpt-4o-mini"}]}}]
  (let [eval-cases  (vec eval-cases)
        col-results (for [{:keys [provider model] :as config} configs]
                      (assoc config :results
                             (mapv #(run-eval % :query-type query-type :project project
                                              :provider provider :model model)
                                   eval-cases)))]
    (println (str "\n=== Cross-check — " (count eval-cases) " cases × "
                 (count configs) " configs ===\n"))
    (doseq [[i config] (map-indexed vector col-results)]
      (println (str "  [" i "] " (config-label config))))
    (println)
    (doseq [[i {:keys [nl]}] (map-indexed vector eval-cases)]
      (println (str i ". " nl "   "
                    (str/join "  " (map (fn [{:keys [results]}]
                                          (if (:pass? (nth results i)) "✓" "✗"))
                                        col-results)))))
    (println)
    (doseq [config col-results]
      (println (str "  " (config-label config) ": "
                    (count (filter :pass? (:results config))) "/"
                    (count (:results config)) " passed")))
    (println)
    col-results))

(defn print-diff
  "Pretty-print a result's generated query — a canonicalized diff against
   :expected if it has one, otherwise the generated query as-is."
  [{:keys [nl expected generated]}]
  (println "NL:       " nl)
  (if (nil? expected)
    (println "Generated:" generated)
    (do
      (println "Expected: " (pr-str (canonicalize-query expected)))
      (println "Generated:" (pr-str (canonicalize-query generated)))
      (println "Diff" (clojure.data/diff expected generated))))) ;TODO refine

(defn examples-check
  "Cross-check `project-name`'s own configured :examples as eval cases,
   against its own configured LLM — a quick sanity pass over the few-shot
   examples themselves."
  [project-name]
  (let [project (nlqc/project-named project-name)]
    (cross-check :project project-name
                 :query-type :sql
                 :eval-cases (:examples project)
                 :configs [(:llm project)])))
