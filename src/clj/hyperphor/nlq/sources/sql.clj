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

;;; Two conventions are checked, since different projects' schemas use
;;; different ones: schemas that flatten several kinds' fields into shared
;;; tables reverse-map a column back to [kind field] via the kind_field
;;; naming convention (schema/sql->alz); schemas generated with one kind
;;; per table (eg AACT, see hyperphor.nlq.sources.postgres/gen-alz-schema)
;;; instead look up table-name/col directly as `schema`'s own kind/field.
(defn alz-enum-type
  "If table-name/col is recognized as an enum field, return its DDL enum
   type string (e.g. \"ENUM (female, male, unknown)\"); otherwise nil —
   overrides a backend's raw column type, since bare types like STRING lose
   the enum values the LLM benefits from seeing."
  [schema table-name col]
  (or (when-let [[kind field] (schema/sql->alz schema col)]
        (schema/enum-ddl-type schema kind field))
      (schema/enum-ddl-type schema (keyword table-name) (keyword col))))

(defn table-ddl
  [db schema table-name columns]
  (let [column-defs
        (apply str
               (map (fn [{col-name :name backend-type :type}]
                      (let [type (or (alz-enum-type schema table-name col-name) backend-type)
                            name (quote-ident db col-name)]
                        (u/tx "{{name}} {{type}},\n"))) ;has extra comma at end but don't think we care
                    columns))
        table-name (qualify-table-name db table-name)]
    (u/tx "CREATE TABLE {{table-name}} {
{{column-defs}}
};
")))

(u/defn-memoized project-ddl
  "Generate DDL for all of a project's tables, for use as LLM context. `schema`
   is that project's own Alzabo schema, for enum-type injection via alz-enum-type."
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

;;; Tries the kind_field id-column search FIRST, falling back to a same-
;;; named-table short-circuit (for AACT-style one-kind-per-table schemas,
;;; which have no kind_field id column to find) only when that search finds
;;; nothing. Order matters: checking the short-circuit first would risk a
;;; false-positive match against a kind_field project that happens to also
;;; have a table literally named after some kind — trying the real
;;; convention first means the fallback can only fire when that convention
;;; has nothing to offer, so it can't change behavior for any project where
;;; it currently works.
(u/defn-memoized table-for-kind
  "The table containing `kind`'s own id column (eg subject_id for :subject).
   Assumes at most one table per project has that column."
  [db kind]
  (let [tables (project-tables db)
        id-col (schema/db-col kind :id)]
    (or (->> tables
             (filter (fn [{:keys [columns]}] (some #(= (:name %) id-col) columns)))
             first
             :table-name)
        (some #(when (= (:table-name %) (name kind)) (:table-name %)) tables))))

(defn sql-string-literal
  "Safely quote `s` as a SQL string literal, with check againsat injection attacks."
  [s]
  (when (re-find #"['\"]" s)
    (throw (ex-info "Unsafe value for SQL literal" {:value s})))
  (str "'" s "'"))
