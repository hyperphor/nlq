(ns hyperphor.nlq.sources.bigquery
  (:require [hyperphor.multitool.core :as u]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hyperphor.nlq.sources.sql :as sql]
            )
  (:import [com.google.cloud.bigquery BigQuery BigQueryOptions
            TableId
            InsertAllRequest
            Schema Field StandardSQLTypeName StandardTableDefinition TableInfo
            FieldValue
            BigQuery$DatasetListOption
            BigQuery$DatasetOption
            BigQuery$TableListOption
            BigQuery$TableOption
            BigQuery$JobOption
            QueryJobConfiguration
            ])
  )

;;; Local authentication is via ~/.config/gcloud/application_default_credentials.json , set by gcloud cli:
;;;   gcloud auth application-default login
;;; See https://cloud.google.com/docs/authentication/provide-credentials-adc

;;; Unpage gcs results
(defn unpage
  [thing]
  (-> thing
      (.iterateAll)
      (.iterator)
      iterator-seq))

(u/defn-memoized service
  [project]
  (-> (BigQueryOptions/newBuilder)
      (.setProjectId project)
      (.build)
      (.getService)))

(defn datasets
  [project]
  (unpage (.listDatasets (service project)
                         (make-array BigQuery$DatasetListOption 0))))

;;; Not sure how general these are across GCS...maybe elevate

;;; NOT the identifier
;;; eg "Backup demo-0307-h Dataset"
(defn fname
  [thing]
  (.getFriendlyName thing))

;; eg "pici-internal:bruce_external.feature_table"
(defn id
  [thing]
  (.getGeneratedId thing))

(defn tables
  [ds]
  (-> (.list ds (make-array BigQuery$TableListOption 0))
                (.iterateAll)
                (.iterator)
                iterator-seq))

;;; Note: this won't work on bare tables, hence the -max version below
(defn table-schema
  [t]
  (->> t
       .getDefinition
       .getSchema
       .getFields
       (map #(-> %
                 bean
                 u/clean-map
                 (u/fsbl u/map-values str)
                 (dissoc :class)))))

(defn table-schema-max
  [project t]
  (let [bt (.getTable (service project) (.getTableId t) (make-array BigQuery$TableOption 0))]
    (table-schema bt)))

(defn table-name
  [t]
  (->> t
       .getTableId
       .getTable))

(defn dataset-name
  [ds]
  (-> ds
      .getDatasetId
      .getDataset))

(defn list-matching
  [str]
  (filter #(re-matches (re-pattern str)
                       (table-name %))
          tables))

;;; prob better way to do this
(defn table-named
  [ds n]
  (u/some-thing #(= (table-name %) n) (tables ds)))

(defn dataset-named
  [project n]
  (u/some-thing #(= (dataset-name %) n) (datasets project)))

(defn get-value
  [field thing & [repeat]]
  (if (instance? FieldValue thing)
    (cond (nil? (.getValue thing)) nil
          (and (not repeat) (= "REPEATED" (str (.getMode field))))
          (map #(get-value field % true) (.getValue thing))

          (= "FLOAT" (.name (.getType field))) 
          (.getDoubleValue thing)
          (= "INTEGER" (.name (.getType field))) 
          (.getLongValue thing)
          ;; TODO fill this out
          :else 
          (.getValue thing))
    thing))


;;; Table is provided in select.
(defn query
  [project sql]
  (let [config (.build (QueryJobConfiguration/newBuilder sql))
        results (.query (service project) config (make-array BigQuery$JobOption 0))
        fields (->> results
                    .getSchema
                    .getFields)
        field-names (->> fields
                         (map #(keyword (.getName %))))
        rows (-> results
                 .getValues
                 .iterator
                 iterator-seq)]
    (map (fn [row] (zipmap field-names (map get-value fields row)))
         rows)))

(defmethod sql/query :bigquery [db sql]
  (query (:project db) sql))

;;; use this for non-query queries like CREATE TABLE
;;; TODO  if result is class com.google.cloud.bigquery.EmptyTableResult 
(defn bare-query
  [project sql]
  (let [config (.build (QueryJobConfiguration/newBuilder sql))
        results (.query (service project) config (make-array BigQuery$JobOption 0))
        ]
    results))

;;; TODO fill this out
(def lookup-type
  {"STRING" StandardSQLTypeName/STRING})

;;; Schema is [[name0 type0]...]
(defn create-table
  [project dataset-name table schema]
  (let [schema (Schema/of (mapv (fn [{:keys [name type]}]
                                  (Field/of name (lookup-type type) (make-array Field 0)))
                                schema))
        table-def (StandardTableDefinition/of schema)
        table-id (TableId/of dataset-name table)
        table-info (.build (TableInfo/newBuilder table-id table-def))]
    (.create (service project) table-info (make-array BigQuery$TableOption 0))))

;;; Ex: (add-row "project" (table-named (dataset-named "project" "dataset") "table") {"name" "foo" "time" "2023-07-10"})
;;; The insert ID must be unique per row: BigQuery's streaming insert uses it for
;;; best-effort dedup, so a fixed literal here silently drops every row inserted
;;; within the dedup window of another (e.g. the NLQ log losing entries whenever
;;; two queries were logged in quick succession) with no error surfaced anywhere.
(defn add-row
  [project table row]
  (.insertAll
   (service project)
   (-> (InsertAllRequest/newBuilder (.getTableId table))
       (.addRow (str (gensym "row")) row)
       (.build))))

(defn clean-row
  [row]
  (->> row
       (u/map-keys name)
       (u/map-values str)))

(defn add-rows
  [project table rows]
  (.insertAll
   (service project)
   (let [builder (InsertAllRequest/newBuilder (.getTableId table))]
     (doseq [row rows]
       (.addRow builder (str (gensym "row")) (clean-row row)))
     (.build builder))))  

(def date-formatter (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))

(defn bq-time
  [t]
  (.format date-formatter t))

(defn sql-lit
  [t type]
  (case type
    ("INTEGER" "FLOAT") (u/coerce-numeric t)
    (str "'" t "'")))

(defn sql-lit-list
  [l type]
  (str "("
       (str/join ", " (map #(sql-lit % type) l))
       ")"))


;;; ── Table/column listing, for hyperphor.nlq.sources.sql ─────────────────
;;; List [{:table-name :columns}] for a dataset, optionally filtered by a
;;; table-name prefix (:subproject). db is {:project :dataset :subproject}.
;;; Column types come straight from BigQuery's own table schema.
(defmethod sql/project-tables :bigquery
  [{:keys [project dataset subproject] :as db}] ;tables but name collision
  (let [db-tables (set (:tables db))]
    (->> (tables (dataset-named project dataset))
         (filter #(cond db-tables (contains? db-tables (table-name %))
                        subproject (str/starts-with? (table-name %) subproject)
                        :else true ))
         (map (fn [t] {:table-name (table-name t)
                       :columns    (map (fn [{:keys [name type]}] {:name name :type type})
                                        (table-schema-max project t))})))))

;;; BigQuery identifiers can contain spaces etc (eg columns imported from CSV),
;;; and SELECTs need the fully-qualified `project.dataset.table` form — backtick
;;; quoting is always valid here, so just always apply it rather than detecting
;;; which identifiers actually need it.
(defmethod sql/quote-ident :bigquery
  [_db ident]
  (str "`" ident "`"))

(defmethod sql/qualify-table-name :bigquery
  [{:keys [project dataset]} tname]
  (str "`" project "." dataset "." tname "`"))

;;; Useful hack for seeing query history
(comment
  (bq/query project "select * from region-us.INFORMATION_SCHEMA.JOBS limit 3")
  (map #(select-keys % [:query :dialect :start_time])
       (bq/query project "select * from region-us.INFORMATION_SCHEMA.JOBS limit 50")))


