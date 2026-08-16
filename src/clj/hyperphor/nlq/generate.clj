(ns hyperphor.nlq.generate
  "NL -> query -> results, for :sql and :sparql query types. A consuming app
   adds its own query types (eg :datomic) by extending the `generate`/
   `run-query`/`example-queries` multimethods with its own defmethods for a
   new dispatch keyword — the same way sources.bigquery/sources.cirro extend
   sources.sql's `query`/`project-tables` multimethods. Nothing here has a
   hard dependency on any one query type beyond :sql/:sparql.

   Extracted from ParkerICI/okc's org.parkerici.okc.nlq.generate — see that
   repo's design/hyperphorization.md. Notably NOT carried over: the
   :datomic query type (a hard dependency on a specific Datomic connection
   module, not a generic capability) stays in okc as a small local
   extension of these multimethods."
  (:require [hyperphor.multitool.core :as u]
            [hyperphor.multitool.cljcore :as ju]
            [hyperphor.ellellem.core :as llm]
            [hyperphor.ellellem.util :as llm-util]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hyperphor.nlq.schema :as schema]
            [hyperphor.nlq.sources.sql :as sql]
            [hyperphor.nlq.sources.sparql :as sparql]
            [hyperphor.nlq.sources.bigquery :as bq]
            [hyperphor.nlq.inspect :as inspect]
            [hyperphor.nlq.config :as nlqc]
            [hyperphor.way.config :as config]
            [hyperphor.way.data :as wd]
            [environ.core :as env]
            [clojure.java.shell :as shell]))

;;; Per-request project config — bound in `endpoint` before any generate/run-query calls.
(def ^:dynamic *project-conf* nil)

;;; Per-request capture of the resolved model/provider/prompt from the last
;;; `llm-complete` call, for logging. Bound to a fresh atom in `endpoint`/
;;; `viz-endpoint`; nil (and thus a no-op) outside a request.
(def ^:dynamic *last-llm-call* nil)

;;; For REPL use mainly
(defmacro with-project
  [project-name & body]
  `(binding [*project-conf* (nlqc/project-named ~project-name)]
     ~@body))

;;; ── Example selectors ────────────────────────────────────────────────────────

(defmulti example-queries
  "Return few-shot examples relevant to the given query type, with the other key stripped."
  identity)

(defmethod example-queries :sql [_]
  (->> (:examples *project-conf*)
       (filter :sql)
       (map #(dissoc % :datomic))))

(defmethod example-queries :sparql [_]
  (->> (:examples *project-conf*)
       (filter :sparql)
       (map #(dissoc % :sql :datomic))))

;;; TODO is this right? Vega examples are unrelated to project? Seems to work though
(defmethod example-queries :vega [_]
  (filter :vega (:examples *project-conf*)))

;;; ── LLM call ─────────────────────────────────────────────────────────────────

(defn llm-complete
  "Call the LLM and return the assistant text."
  [messages & {:keys [model provider system]}]
  (let [provider (or provider (get-in *project-conf* [:llm :provider]) :openai)
        model    (or model    (get-in *project-conf* [:llm :model])    "gpt-4o")
        start-ns (System/nanoTime)
        result   (llm/complete {:provider provider
                                :model model
                                :messages (mapv (fn [{:keys [role content]}]
                                                  {:role role :content content})
                                                messages)
                                :system system
                                :max-tokens 2000})
        duration-ms (long (/ (- (System/nanoTime) start-ns) 1e6))]
    (when *last-llm-call*
      (reset! *last-llm-call* {:model model :provider provider :messages messages :system system
                               :duration-ms duration-ms}))
    (:content result)))

;;; ── Schema resolution ────────────────────────────────────────────────────────

(defn alz-schema
  "The current project's own Alzabo schema, or nil if it doesn't declare one
   (:schema is optional — a project with no hand-authored schema still works,
   just without the Alzabo-schema/semantic-column context in the prompt/UI).
   The single source of truth for \"the schema\" everywhere in this ns —
   generate :sql's prompt, project-ddl's enum injection, and endpoint's
   columns-info annotation all reuse this exact value rather than each
   resolving their own."
  []
  (when (:schema *project-conf*)
    (schema/read-schema (:schema *project-conf*))))

;;; ── Generation multimethods ──────────────────────────────────────────────────

(defmulti generate
  "Translate a natural-language query to code. Returns [type code text] where
   code is the parsed/extracted result and text is any non-code surrounding
   text."
  (fn [query-type _nl] query-type))

(defmethod generate :sql [_ nl]
  (let [{:keys [llm db sql-dialect]} *project-conf*
        schema  (alz-schema)
        ddl     (sql/project-ddl db schema)  ;TODO this might grow too large esp with separate assay-level tables.
        system  (get llm :system "You are an expert in relational databases and SQL.")]
    (-> (llm-complete
         [{:role :user
           :content (str "Example queries. Do not translate these, use them as a guide: " (pr-str (example-queries :sql)))}
          (when schema
            {:role :user
             :content (str "This is an Alzabo schema:  " schema)})
          {:role :user
           :content (str "Use this SQL DDL definition. Table names and columns must come from here: " ddl)}
          {:role :user
           :content (str "SQL dialect is " sql-dialect)} ; eg documented at https://trino.io/docs/current/sql/select.html
          ;; Note: putting this at the end is more efficient (LLMs will cache the constant prefix, maybe)
          {:role :user
           :content (str "Please translate this query to an SQL SELECT statement. Include the SQL code in a code block: " nl)}]
         :system system)
        llm-util/extract-code)))

(defmethod generate :sparql [_ nl]
  (let [{:keys [llm]} *project-conf*
        system (get llm :system
                    (str "You are an expert in SPARQL and knowledge graphs. Write SPARQL SELECT "
                        "queries against the configured endpoint. "
                        "To get human-readable labels (not just entity URIs), add a "
                        "`SERVICE wikibase:label { bd:serviceParam wikibase:language \"[AUTO_LANGUAGE],en\" }` "
                        "clause (if the endpoint supports it, eg Wikidata) and select the `...Label` "
                        "variables it binds. "
                        "To resolve a named entity (eg an item given by name), do NOT match a single "
                        "`?x rdfs:label \"Name\"@en` triple -- many entities (proper names especially) "
                        "are labeled with other/no language tags, so that silently matches nothing for "
                        "some names and not others, unpredictably. Instead bind every plausible language "
                        "tag with VALUES: `VALUES ?name { \"Name\"@en \"Name\"@mul } ?x rdfs:label ?name .` "
                        "-- still fast (the literal is still looked up directly, VALUES doesn't force a "
                        "scan) and reliable regardless of which tag the entity actually uses. See the "
                        "examples. (This only applies to *resolving* a known name to its entity -- "
                        "binding `?xLabel` via the label service, or via `rdfs:label` for a text search "
                        "like `CONTAINS(?label, \"...\")` over unknown entities, is fine and often the "
                        "only option.)"))]
    (-> (llm-complete
         [{:role :user
           :content (str "Example queries. Do not translate these, use them as a guide: " (pr-str (example-queries :sparql)))}
          (when-let [schema (alz-schema)]
            {:role :user
             :content (str "This is an Alzabo schema describing the relevant ontology -- each field's "
                          ":doc names the actual predicate/class it corresponds to: " schema)})
          {:role :user
           :content (str "Please translate this query to a SPARQL SELECT statement. Include the SPARQL code in a code block: " nl)}]
         :system system)
        llm-util/extract-code)))

;;; ── Canned-query shortcut ────────────────────────────────────────────────────

(defmulti generate-or-canned
  "Return [code text]. Uses a canned example if available, otherwise calls `generate`."
  (fn [query-type _nl] query-type))

;;; Was a hand-rolled `memoized` macro that created its cache atom via `(intern
;;; *ns* cache (atom {}))` run as a macroexpansion-time side effect. That only
;;; happens once, in the JVM that AOT-compiles the uberjar; the deployed JVM
;;; never re-runs it, so the compiled reference to that var resolves to a
;;; freshly auto-interned *unbound* var instead — hence a deployed-only
;;; ClassCastException (Var$Unbound cast to Future) when `@cache` was derefed.
;;; `u/defn-memoized` doesn't have this problem: its cache atom is created by
;;; the `(def ...)` form itself, which — unlike a bare `intern` call — is real
;;; compiled code that runs again at class-load time in every JVM.
(u/defn-memoized generate-sql-cached
  [project-name nl]
  (let [[_type code text] (generate :sql nl)]
    [code text]))

(defmethod generate-or-canned :sql [_ nl]
  (generate-sql-cached (:name *project-conf*) nl))

(u/defn-memoized generate-sparql-cached
  [project-name nl]
  (let [[_type code text] (generate :sparql nl)]
    [code text]))

(defmethod generate-or-canned :sparql [_ nl]
  (generate-sparql-cached (:name *project-conf*) nl))

;;; ── Query execution ──────────────────────────────────────────────────────────

;;; run-query dispatches purely on this dynamic var, not on the query's own
;;; shape — `endpoint` binds it to whatever query-type it was called with,
;;; for the duration of the call, so :sql/:sparql (this ns) and any consuming
;;; app's own extension (eg :datomic, dispatched on a Clojure map rather than
;;; a query-language string) all route correctly automatically. Any code
;;; calling `run-query` directly, outside an `endpoint` call (eg an eval
;;; harness or a REPL helper), must bind this itself first. nil (the
;;; default, unbound) falls through to :sql.
(def ^:dynamic *query-type* nil)

(defmulti run-query (fn [_q] (or *query-type* :sql)))

(defmethod run-query :sql [sql-query]
  (sql/query (:db *project-conf*) sql-query))

(defmethod run-query :sparql [sparql-query]
  (sparql/query (:db *project-conf*) sparql-query))

;;; ── with-ex macro ────────────────────────────────────────────────────────────
;;; A little DSL for "run these steps in order, short-circuit to an :error key
;;; on the first exception, keep whatever partial results came before it."

(defn walk-remap
  [map form]
  (clojure.walk/postwalk #(get map % %) form))

(defn- with-ex-clause
  [acc-sym vars [var form]]
  (let [var-map (zipmap vars (map (fn [v] `(get @~acc-sym ~v)) vars))
        trans-form (walk-remap var-map form)
        res-sym (gensym "res")]
    (if (sequential? var)
      `(let [~res-sym ~trans-form]
         ~@(map (fn [var i] (with-ex-clause acc-sym vars [var `(nth ~res-sym ~i)]))
                var (range)))
      `(swap! ~acc-sym assoc ~var ~trans-form))))

(defmacro with-ex
  [& body]
  (let [clauses (partition 2 body)
        vars (set (flatten (map first clauses)))
        acc-sym (gensym "acc")]
    `(let [~acc-sym (atom {})]
       (try
         ~@(map (partial with-ex-clause acc-sym vars) clauses)
         @~acc-sym
         (catch Exception e#
           (assoc @~acc-sym :error e#))))))

;;; Cache of the most recent NLQ query's response — read by a viz endpoint
;;; for the :results it visualizes. Set only by `endpoint`/`requery-endpoint`.
(def last-query-response (atom nil))

;;; ── Logging (optional) ───────────────────────────────────────────────────────
;;; Logs every NL->query call to BigQuery, if (and only if) the consuming app
;;; configures a log target — `(config/config :nlq-log)` => {:bq-project
;;; :bq-dataset :bq-table}. Absent (the default): logging is a no-op, so a
;;; project with no BigQuery credentials at all (eg a public-SPARQL-only demo
;;; site) works fine without them. Uses sources.bigquery, brought over from
;;; okc as a real sql/query :bigquery implementation — this is the one place
;;; in this ns that reaches for it directly, for logging rather than querying.

(u/def-lazy githash
  "Heroku (or any host setting SOURCE_VERSION) wins; falls back to a local
   git call in dev."
  (or (env/env :source-version)
      (try
        (let [rev (str/trim (:out (shell/sh "git" "rev-parse" "HEAD")))]
          (when (seq rev) rev))
        (catch Exception _ nil))))

(defn- log-target
  []
  (config/config :nlq-log))

(defn- log-table
  [{:keys [bq-project bq-dataset bq-table]}]
  (bq/table-named (bq/dataset-named bq-project bq-dataset) bq-table))

(defn record
  "Log an NLQ or vis-query response to BigQuery, if a log target is
   configured (see `log-target`) — a silent no-op otherwise. `llm-call` is
   the map captured by `llm-complete` via `*last-llm-call*` (nil for canned
   queries, which never call the LLM). Vis-query response-objects carry
   :viz-spec/:viz-text instead of :query/:text/:results — normalize those
   onto the same log columns rather than adding vis-only columns.
   :user_reason carries \"viz\" for those rows, or an explicit :user-reason
   from response-object, so they're distinguishable from regular NL->query
   rows without splitting :project."
  [project response-object & [llm-call]]
  (when-let [{:keys [bq-project] :as target} (log-target)]
    (let [{:keys [nl query text results error viz-spec viz-text user-reason]} response-object
          to-save {:nl nl
                   :query (str (or query viz-spec))
                   :text (or text viz-text)
                   :results (pr-str (take 2 results))
                   :error (when error (print-str error))
                   :datetime (bq/bq-time (ju/now))
                   :project project
                   :model (:model llm-call)
                   :provider (some-> (:provider llm-call) name)
                   :prompt (some-> (:messages llm-call) pr-str)
                   :duration_ms (:duration-ms llm-call)
                   :user_reason (or user-reason (when (or viz-spec viz-text) "viz"))
                   :githash @githash}]
      (bq/add-row bq-project (log-table target) (u/map-keys name to-save)))))

(defn recent
  "The `n` most recent NLQ log rows, newest first. nil if no log target is
   configured."
  [& [n]]
  (when-let [{:keys [bq-project bq-dataset bq-table]} (log-target)]
    (let [n (or n 10)]
      (bq/query bq-project
                (u/tx "select * from `{{bq-project}}.{{bq-dataset}}.{{bq-table}}` order by datetime desc limit {{n}}")))))

(defn all-log-rows
  "The full NLQ log dataset, newest first. nil if no log target is configured."
  []
  (when-let [{:keys [bq-project bq-dataset bq-table]} (log-target)]
    (bq/query bq-project
              (u/tx "select * from `{{bq-project}}.{{bq-dataset}}.{{bq-table}}` order by datetime desc"))))

(defmethod wd/data :nlq-log-full [_] (all-log-rows))

;;; ──  NLQ endpoint ─────────────────────────────────────────────────────

(defn endpoint
  [project query-type nl]
  (binding [*project-conf* (nlqc/project-named project)
            *last-llm-call* (atom nil)
            *query-type* query-type]
    (let [response-object
          (with-ex
            :nl nl
            [:query :text] (generate-or-canned query-type :nl)
            :results (run-query :query))
          response-object (assoc response-object :columns
                                 ((requiring-resolve 'hyperphor.nlq.inspect/annotate-inspectable)
                                  (:db *project-conf*)
                                  (schema/columns-info (alz-schema) (:results response-object))))]
      (reset! last-query-response response-object)
      (record project response-object @*last-llm-call*)
      (update response-object :error #(when % (print-str %))))))

(defn requery-endpoint
  [query-type query-text]
  (binding [*query-type* query-type]
    (let [response-object (with-ex
                            :query query-text
                            :results (run-query :query))]
      (reset! last-query-response response-object)
      (update response-object :error #(when % (print-str %))))))
