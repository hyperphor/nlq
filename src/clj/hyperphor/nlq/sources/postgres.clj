;;; Extracted from OKC's pg-aact branch (see okc's design/pg-aact-split-
;;; plan.md for history); first real use was AACT (aggregate
;;; ClinicalTrials.gov data), but nothing here is AACT-specific — the AACT
;;; data-dictionary loader and hardcoded table list that pg-aact's original
;;; postgres.clj also carried live in the nlq-aact app instead, as callers
;;; of gen-alz-schema.
(ns hyperphor.nlq.sources.postgres
  "Generic Postgres backend for hyperphor.nlq.sources.sql, plus a dev-time
   tool for reverse-engineering an Alzabo schema from a live Postgres
   database's own catalogs (see gen-alz-schema)."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u]
            [hyperphor.nlq.sources.sql :as sql]))

;;; ── Connection ───────────────────────────────────────────────────────────────
;;; `db` is a :db config map (config.edn's :nlq entries) with :host :port
;;; :dbname :user :password :pg-schema — credentials come from config
;;; (typically #env-sourced), never hardcoded here.

;;; No clj-postgresql dependency here — java.jdbc has natively supported the
;;; :dbtype/:host/:port/:dbname/:user/:password map shape since 0.7, so a
;;; helper library that mostly just wraps that isn't pulling its weight for
;;; the one call site (this fn) that would have used it.
(defn- jdbc-spec
  "A plain clojure.java.jdbc db-spec map."
  [db]
  (merge {:dbtype "postgresql"} (select-keys db [:host :port :dbname :user :password])))

(defn pg-schema
  [db]
  (:pg-schema db "public"))

(defmethod sql/query :postgres
  [db sql-string]
  (jdbc/query (jdbc-spec db) [sql-string]))

;;; Postgres table/column names are plain lowercase identifiers here (no
;;; spaces/mixed case to worry about, unlike BigQuery's CSV-imported tables),
;;; but they do need schema-qualifying (eg "ctgov.studies") since the JDBC
;;; connection's search_path may not include it by default.
(defmethod sql/qualify-table-name :postgres
  [db table-name]
  (str (pg-schema db) "." table-name))

;;; ── Table/column listing, for hyperphor.nlq.sources.sql ─────────────────────

(defmethod sql/project-tables :postgres
  [db]
  (->> (jdbc/query (jdbc-spec db)
                   ["select table_name, column_name, data_type
                     from information_schema.columns
                     where table_schema = ?
                     order by table_name, ordinal_position"
                    (pg-schema db)])
       (group-by :table_name)
       (map (fn [[table-name cols]]
              {:table-name table-name
               :columns (map (fn [{:keys [column_name data_type]}]
                               {:name column_name :type data_type})
                             cols)}))))

;;; ── DB → Alzabo schema generation (dev-time only, not run at startup) ───────
;;; Same spirit as schema.clj's gen-doc: a one-shot tool you invoke from the
;;; REPL to produce a schema *file*, not something computed live. Real FK
;;; constraints (pg_constraint, not information_schema — information_schema.
;;; table_constraints only shows constraints on tables the connected role has
;;; non-SELECT privilege on, which a read-only reporting account like AACT's
;;; never does) become each kind's join structure; an optional `dictionary`
;;; map fills in :doc strings for columns whose names aren't self-explanatory.
;;;
;;; NOTE: the generated schema's field keys match Postgres's own bare column
;;; names (eg :nct_id, not a kind-prefixed :nct-id) because most Postgres
;;; sources like AACT don't follow this app's kind_field naming convention
;;; (there's no "studies_" prefix on studies.nct_id) — schema.clj's
;;; sql->alz/db-col round trip, which drives the object inspector and
;;; semantic column icons/grouping for schemas that DO flatten several kinds
;;; into shared tables (eg RADIOHEAD/PRINCE/MAHLER), won't resolve these
;;; columns; schema.clj's direct-field-lookup fallback handles that instead.

(defn relations
  "Real FK constraints in `pg-schema`, as {[table column] foreign-table}."
  [db]
  (->> (jdbc/query (jdbc-spec db)
                   ["select
                       conrelid::regclass::text  as table_name,
                       a.attname                  as column_name,
                       confrelid::regclass::text as foreign_table_name
                     from pg_constraint c
                     join unnest(c.conkey)  with ordinality as ak(attnum, ord) on true
                     join unnest(c.confkey) with ordinality as afk(attnum, ord) on afk.ord = ak.ord
                     join pg_attribute a  on a.attrelid = c.conrelid  and a.attnum = ak.attnum
                     where c.contype = 'f'
                       and c.connamespace = (?::regnamespace)"
                    (pg-schema db)])
       (u/index-by (juxt :table_name :column_name))
       (u/map-values :foreign_table_name)))

;;; TODO eveeryhing down from here should probably be in a separate file and/or integrated with infer.clj


(def ^:private pg-type->alz-type
  {"integer" :long, "bigint" :long, "smallint" :long
   "numeric" :float, "double precision" :float, "real" :float
   "boolean" :boolean
   "date" :instant, "timestamp without time zone" :instant, "timestamp with time zone" :instant})

(defn- alz-type
  [pg-type]
  (get pg-type->alz-type pg-type :string))

;;; ── Enum detection ───────────────────────────────────────────────────────────
;;; Plain Postgres has no real ENUM type here (categorical columns like
;;; phase/intervention_type/overall_status are plain character varying), so
;;; there's no catalog flag to read off directly. Instead: ask the query
;;; planner's own column statistics (pg_stats.n_distinct — populated by
;;; ANALYZE, which every Postgres install runs routinely) which string
;;; columns have a small, fixed set of distinct values. This is a fast
;;; estimate (no table scan), unlike a real `count(distinct col)` per column,
;;; which timed out against AACT's larger tables when tried directly.

(def default-enum-max-distinct 25)

(defn enum-candidate-columns
  "{[table-name column-name] estimated-distinct-count} for every string
   column of `tables` whose planner-estimated cardinality is <= max-distinct
   — likely a closed/categorical value set rather than free text."
  [db tables & {:keys [max-distinct] :or {max-distinct default-enum-max-distinct}}]
  (->> (jdbc/query (jdbc-spec db)
                   ["select s.tablename, s.attname, s.n_distinct
                     from pg_stats s
                     join information_schema.columns c
                       on c.table_schema = s.schemaname and c.table_name = s.tablename
                      and c.column_name = s.attname
                     where s.schemaname = ? and s.tablename = any(?)
                       and c.data_type in ('character varying', 'text')"
                    (pg-schema db) (into-array String tables)])
       (filter (fn [{:keys [n_distinct]}] (and n_distinct (pos? n_distinct) (<= n_distinct max-distinct))))
       (into {} (map (fn [{:keys [tablename attname n_distinct]}] [[tablename attname] n_distinct])))))

(defn- safe-enum-value?
  "Whether v can become an enum keyword key. \"/\" is allowed (eg phase's
   PHASE1/PHASE2) — see enum-keyword for how it's kept safe. Anything else
   irregular (spaces, quotes, ...) still isn't safe, so stays a plain string."
  [v]
  (re-matches #"[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*" v))

;;; A literal "/" in v (eg phase's PHASE1/PHASE2) gets substituted with
;;; U+2044 FRACTION SLASH before calling `keyword` — the real ASCII "/"
;;; would make `(keyword v)` a NAMESPACED keyword once written to and read
;;; back from the schema file (:PHASE1/PHASE2), and `name` on that would
;;; silently drop everything before the "/". The fraction slash isn't
;;; special to the reader, so `name` gets the whole code back intact;
;;; schema/enum-values un-substitutes it back to a real "/" when
;;; reconstructing the stored code.
(defn- enum-keyword
  "v as an enum :values key, with any literal \"/\" made keyword-safe."
  [v]
  (keyword (str/replace v "/" "⁄")))

(defn- humanize-enum-value
  "Word-cap each underscore/space-separated segment; \"/\"-joined compound
   values (eg PHASE1/PHASE2) get humanized on each side, keeping the \"/\"."
  [v]
  (->> (str/split v #"/")
       (map (fn [segment]
              (->> (str/split segment #"[_\s]+")
                   (map str/lower-case)
                   (map str/capitalize)
                   (str/join " "))))
       (str/join "/")))

(defn enum-values-for-column
  "Actual distinct values of table.col. nil if any value can't safely
   become a keyword (see safe-enum-value?), so the caller falls back to a plain string."
  [db table col]
  (let [vs (->> (jdbc/query (jdbc-spec db)
                            [(format "select distinct %s as v from %s.%s where %s is not null"
                                     col (pg-schema db) table col)])
                (map :v))]
    (when (every? safe-enum-value? vs)
      vs)))

;;; Args: `tables` is a subset of live tables to include (a whole real-world
;;; schema can be 70+ tables, too large for one SQL-generation prompt).
;;; `dictionary` is an optional {[table-name column-name] doc-string} map
;;; for field :doc strings (parsed by the caller — eg from AACT's own
;;; pipe-delimited data-dictionary CSV — this fn takes the parsed map, not a
;;; file path, to stay source-agnostic); pass {} if none. `table-docs` is
;;; {table-name doc-string}, by hand. `table-icons` is {table-name
;;; emoji-string} — drives the results grid's icon column like any other
;;; project's kind :icon. `table-labels` is {table-name column-name} — the
;;; kind's own "name" field (eg studies' brief_title), which the results
;;; grid links into the inspector alongside the id/FK columns, using the
;;; row's own id (see hyperphor.nlq.schema/column-info's :label? and
;;; hyperphor.nlq.frontend.sql-query/label-inspect-cell-renderer).
;;; `table-external-link-templates` is {table-name url-template} — a
;;; {{value}}-templated URL (hyperphor.nlq.schema/external-link-template);
;;; when a kind has one, its id/FK columns (never the label column) render
;;; as that external link instead of the in-app inspector, eg AACT studies
;;; -> clinicaltrials.gov.
(defn gen-alz-schema
  "Reverse-engineer an Alzabo schema from `db` (a live Postgres connection)
   for `tables`, with low-cardinality string columns promoted to real Alzabo
   :enums (see enum-candidate-columns). Returns the schema map — call
   (spit path (with-out-str (pprint schema))) to save it."
  [db tables dictionary table-docs table-icons table-labels table-external-link-templates]
  (let [tables (set tables)
        rels (relations db)
        enum-cols (enum-candidate-columns db tables)
        enums (atom {})
        by-table (->> (sql/project-tables db)
                      (filter #(tables (:table-name %)))
                      (u/index-by :table-name))
        field-type (fn [table-name col-name pg-type]
                     (let [fk-target (get rels [table-name col-name])]
                       (cond
                         (and fk-target (tables fk-target)) (keyword fk-target)
                         (contains? enum-cols [table-name col-name])
                         (if-let [values (enum-values-for-column db table-name col-name)]
                           (let [enum-key (keyword (str table-name "." col-name))]
                             (swap! enums assoc enum-key
                                    {:values (into {} (map (fn [v] [(enum-keyword v) (humanize-enum-value v)])) values)})
                             enum-key)
                           (alz-type pg-type))
                         :else (alz-type pg-type))))]
    (cond-> {:title "Generated Postgres schema"
             :version "0.1"
             ;; This schema is one-kind-per-table with bare Postgres column
             ;; names, not the kind_field flattening convention — tells
             ;; hyperphor.nlq.schema/column-info to resolve columns via
             ;; direct-field-lookup instead of (only) sql->alz. See that
             ;; function's docstring for why this must be opt-in.
             :direct-field-lookup? true
             :kinds
             (into {}
                   (map (fn [table-name]
                          (let [{:keys [columns]} (get by-table table-name)]
                            [(keyword table-name)
                             (cond-> {:fields
                                      (into {}
                                            (map (fn [{col-name :name pg-type :type}]
                                                   (let [doc (get dictionary [table-name col-name])]
                                                     [(keyword col-name)
                                                      (cond-> {:type (field-type table-name col-name pg-type)
                                                               :cardinality :one}
                                                        doc (assoc :doc doc))])))
                                            columns)}
                               (get table-docs table-name) (assoc :doc (get table-docs table-name))
                               (get table-icons table-name) (assoc :icon (get table-icons table-name))
                               (get table-labels table-name)
                               (assoc :label (keyword table-name (get table-labels table-name)))
                               (get table-external-link-templates table-name)
                               (assoc :external-link-template (get table-external-link-templates table-name))
                               (some #(= "id" (:name %)) columns) (assoc :unique-id (keyword (str table-name "/id")))
                               ;; No generic way to know a table's true natural key beyond a
                               ;; literal "id" column — a caller whose primary key is named
                               ;; something else (eg AACT studies' nct_id) sets :unique-id
                               ;; itself on the returned schema before saving it.
                               )])))
                   tables)}
      (seq @enums) (assoc :enums @enums))))
