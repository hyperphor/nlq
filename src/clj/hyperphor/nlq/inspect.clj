(ns hyperphor.nlq.inspect
  "The object inspector: given an id/FK value clicked in the SQL results grid
   (see frontend.sql-query), look up that entity's full row by its own id
   column."
  (:require [hyperphor.multitool.core :as u]
            [clojure.string :as str]
            [hyperphor.nlq.schema :as schema]
            [hyperphor.nlq.sources.sql :as sql]
            [hyperphor.nlq.config :refer [project-named]]
            [hyperphor.way.data :as wd]))

(defn inspect-row
  "The full row for `kind`'s entity identified by `id` (its own id column's
   value), in `project`. Drops nil/false fields — a wide flat sheet, mostly
   empty/false per row, otherwise makes the inspector unusable."
  [project kind id]
  (let [db     (:db (project-named project))
        table  (or (sql/table-for-kind db kind)
                   ;; Not every kind a ref-kind FK points at has a real
                   ;; backing table in every project — fail clearly here
                   ;; rather than handing qualify-table-name a nil and
                   ;; getting a confusing SQL syntax error from the backend.
                   (throw (ex-info (str "No queryable table for " kind " in " project)
                                    {:project project :kind kind})))
        id-col (schema/db-col kind :id)
        query  (str "SELECT * FROM " (sql/qualify-table-name db table)
                    " WHERE " (sql/quote-ident db id-col)
                    " = " (sql/sql-string-literal id)
                    " LIMIT 1")
        row    (first (sql/query db query))]
    (u/dissoc-if #(let [v (second %)] (or (nil? v) (false? v))) row)))

;;; A column's :kind/:ref-kind (from schema/column-info) says what an id/FK
;;; value *means* semantically, but not whether that kind is actually
;;; queryable in this project right now — table-for-kind returning nil for
;;; some ref-kinds is expected, not a bug (see inspect-row above). Mirrors
;;; frontend.sql-query's `id-column?`/`inspectable-kind`: keep the two in
;;; sync if that logic changes.
(defn- id-column?
  [col-name info]
  (or (= (:field info) :id)
      (str/ends-with? (name col-name) "_id")))

(defn- target-kind
  [col-name info]
  (cond
    (and (:kind info) (id-column? col-name info)) (:kind info)
    (:ref-kind info) (:ref-kind info)))

(defn annotate-inspectable
  "Tags each column's info with :inspectable? — whether `target-kind` (the
   kind a click on it would try to open) has a real backing table in this
   project's db right now. Computed live via the same sql/table-for-kind
   lookup the inspector itself uses at click-time — not a hardcoded list, so
   a column that isn't linkable today becomes so automatically once its
   kind's table shows up, no code change needed here."
  [db columns-info]
  (into {}
        (map (fn [[col-name info]]
               [col-name (assoc info :inspectable?
                                (boolean (some->> (target-kind col-name info)
                                                   (sql/table-for-kind db))))]))
        columns-info))

(defmethod wd/data :sql-inspect
  [{:keys [project kind id]}]
  (let [conf   (project-named project)
        db     (:db conf)
        row    (inspect-row project (keyword kind) id)
        ;; Same schema `generate`'s `endpoint` resolves for this project (its
        ;; :schema config entry, if any) — one notion of "the schema", not a
        ;; separate lookup that could drift from it.
        schema (when (:schema conf) (schema/read-schema (:schema conf)))]
    {:row row
     :columns (annotate-inspectable db (schema/columns-info schema [row]))}))
