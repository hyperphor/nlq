(ns hyperphor.nlq.sources.sql
  "Backend-agnostic SQL abstraction. Each backend implements `project-tables`
   (list tables/columns) and `query` (run a SELECT), dispatched on
   (:provider db); DDL assembly is shared here. See sources.bigquery and
   sources.cirro for two real implementations."
  (:require [hyperphor.multitool.core :as u]
            [hyperphor.nlq.schema :as schema]))

(defmulti project-tables
  "List a project's queryable tables as [{:table-name :columns}], where :columns
   is a seq of {:name :type} maps, typed from the backend's own SQL tables
   (BigQuery's column types, Cirro sheets' dataType, etc). Dispatched on (:provider db)."
  :provider)

(defmulti query
  "Run a SQL SELECT against the backend named by (:provider db). Returns a seq of maps."
  (fn [db _sql] (:provider db)))

;;; ── Dialect quoting ──────────────────────────────────────────────────────────
;;; Backends whose table/column names are always valid bare SQL identifiers
;;; need no quoting; backends with arbitrary names (eg tables imported from
;;; CSV, with spaces etc) need to say so.

(defmulti quote-ident
  "Return ident (a table or column name), quoted per (:provider db)'s dialect
   if that dialect requires it. Defaults to no quoting."
  (fn [db _ident] (:provider db)))

(defmethod quote-ident :default [_db ident] ident)

(defmulti qualify-table-name
  "Return the table name to embed in DDL/SQL text, quoted and qualified
   (eg with project/dataset) per (:provider db)'s dialect. Defaults to the
   bare table name."
  (fn [db _table-name] (:provider db)))

(defmethod qualify-table-name :default [_db table-name] table-name)

;;; ── DDL generation ───────────────────────────────────────────────────────────

(defn alz-enum-type
  "If col is recognized in `schema` as an enum field, return its DDL
   enum type string (e.g. \"ENUM (female, male, unknown)\"); otherwise nil.
   Overrides a backend's raw column type, since bare types like STRING lose
   the enum values the LLM benefits from seeing — secret sauce for generation."
  [schema col]
  (when-let [[kind field] (schema/sql->alz schema col)]
    (schema/enum-ddl-type schema kind field)))

(defn table-ddl
  [db schema table-name columns]
  (let [column-defs
        (apply str
               (map (fn [{col-name :name backend-type :type}]
                      (let [type (or (alz-enum-type schema col-name) backend-type)
                            name (quote-ident db col-name)]
                        (u/tx "{{name}} {{type}},\n"))) ;has extra comma at end but don't think we care
                    columns))
        table-name (qualify-table-name db table-name)]
    (u/tx "CREATE TABLE {{table-name}} {
{{column-defs}}
};
")))

(u/defn-memoized project-ddl
  "Generate DDL for all of a project's tables, for use as LLM context. `db` is a
   :db config map (from config.edn's :nlq entries), with :provider selecting the
   backend. `schema` is that project's own Alzabo schema (for enum-type injection
   via alz-enum-type) — pass whatever `generate`'s `alz-schema` already resolved,
   not a separate/hardcoded one."
  [db schema]
  (let [tables (project-tables db)
        selected-tables (if (:tables db)
                          (filter #(contains? (set (:tables db)) (:table-name %))
                                  tables)
                          tables)]
    (apply str (map (fn [{:keys [table-name columns]}] (table-ddl db schema table-name columns))
                    selected-tables))))

;;; TODO human readable summary
(defn project-tables-human
  [db]
  )

;;; ── Single-entity lookup (for the object inspector) ─────────────────────────

(u/defn-memoized table-for-kind
  "The table containing `kind`'s own id column (eg subject_id for :subject).
   Memoized like project-ddl, since project-tables is a live API call.
   Assumes at most one table per project has that column. Schema-independent
   — the kind_field naming convention (schema/db-col) needs only the kind's
   name, not a full schema."
  [db kind]
  (let [id-col (schema/db-col kind :id)]
    (->> (project-tables db)
         (filter (fn [{:keys [columns]}] (some #(= (:name %) id-col) columns)))
         first
         :table-name)))

(defn sql-string-literal
  "Safely quote `s` as a SQL string literal. `query`'s multimethods take a raw
   SQL string with no bind-parameter API, so this is the injection defense
   for a value (eg an inspector lookup's id) going into generated SQL text.
   Real ids can contain all sorts of punctuation an allow-list won't
   anticipate (eg a variant id like \"GRCh37:chr1:+:11889411:11889411/A/C\"),
   so reject only what actually matters for this attack — a quote character
   that would let the value break out of the string literal — rather than
   restricting to a conservative charset that ends up blocking legitimate
   values too."
  [s]
  (when (re-find #"['\"]" s)
    (throw (ex-info "Unsafe value for SQL literal" {:value s})))
  (str "'" s "'"))
