(ns hyperphor.nlq.schema
  "Alzabo schema loading, plus the semantic-column layer that recovers a SQL
   result column's meaning (kind/field/doc/icon/enum?/ref-kind) from its bare
   name via the `kind_field` naming convention (`db-col`). Everything here
   takes the schema it should consult as an explicit argument — there is no
   hardcoded master schema. A consuming app supplies its own (per project,
   typically via that project's `:schema` config entry) and can share a
   common base schema across projects via Alzabo's own `:include`, the same
   way any other Alzabo schema composition works.
"
  (:require
   [hyperphor.multitool.core :as u]
   [hyperphor.multitool.cljcore :as ju]
   [hyperphor.alzabo.schema :as alzs]
   [hyperphor.alzabo.html :as alzh]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.set]))

;;; ── Resource staging ─────────────────────────────────────────────────────────
;;; Alzabo's read-schema needs a real filesystem path: it slurps the file, and
;;; regex-splits that same path to resolve sibling :include-d schemas. That
;;; doesn't exist when running from an uberjar — there's no resources/
;;; directory on disk at all, everything's bundled as classpath resources. So
;;; we copy each schema resource out to a real temp file first, preserving its
;;; relative path (all under one shared temp root) so that sibling :include
;;; references still resolve exactly as they do in dev.

(u/def-lazy schema-temp-dir (ju/temp-dir-path))

(defn- normalize-rel-path
  "Lexically collapse . and .. segments (classpath resource lookup, unlike
   java.io.File, doesn't do this itself). Doesn't touch the filesystem."
  [p]
  (str (.normalize (java.nio.file.Paths/get p (make-array String 0)))))

(defn- resource-includes
  "The :include list of a not-yet-staged schema resource, straight off the
   classpath. Alzabo itself only ever reads :include (singular)."
  [relative-path]
  (:include (edn/read-string (slurp (io/resource relative-path)))))

(defn resource-file
  "Copy classpath resource `relative-path` (and, recursively, anything it
   :include-s) out to real files under a single shared temp directory,
   preserving each one's relative path. Returns the top file's absolute path."
  [relative-path]
  (let [relative-path (normalize-rel-path relative-path)
        f             (io/file @schema-temp-dir relative-path)]
    (when-not (.exists f)
      (io/make-parents f)
      (with-open [in (io/input-stream (io/resource relative-path))]
        (io/copy in f))
      (let [dir (alzs/path-dir relative-path)]
        (doseq [inc (resource-includes relative-path)]
          (resource-file (normalize-rel-path (str (when dir (str dir "/")) inc))))))
    (.getAbsolutePath f)))

(defn select-keys-by [m pred]
  (into {} (filter (comp pred key) m)))

(defn kind-references
  [schema kind]
  (->> schema
       :kinds
       kind
       :fields
       vals
       (map :type)
       distinct))

;;; It would make sense to do the transitive closure on kinds. BUT that brings
;;; in too much, so require everything to be included explicitly.
(defn schema-subset
  [schema kinds]
  (let [references (apply u/lunion (map (partial kind-references schema) kinds))]
    (-> schema
        (update :kinds #(select-keys % kinds))
        (update :enums #(select-keys-by % references)))))

;;; Implements :kind-subset
(defn schema-subset-hack
  [schema]
  (if-let [subset (:kind-subset schema)]
    (-> (schema-subset schema subset)
        (dissoc :kind-subset))
    schema))

(u/defn-memoized read-schema
  [schema-file]
  (-> schema-file
      resource-file
      alzs/read-schema
      schema-subset-hack))

;; Generate the HTML doc for one project conf; the web UI can point an
;; iframe at the resulting resources/public/<name>/schema/index.html.
(defn gen-doc
  [{:keys [schema name] :as _conf}]
  (let [schema (read-schema schema)
        outdir (u/tx "resources/public/{{name}}/schema")]
    (alzh/schema->html schema outdir)))

(defn init
  "Generate HTML schema docs for every conf in `confs` that has a :schema
   key. `confs` is typically a project's own list of NLQ config entries
   (eg `(config/config :nlq)`); include any shared/base schema the caller
   wants documented too (okc, eg, adds its own umbra master-schema conf)."
  [confs]
  (doseq [conf confs]
    (when (:schema conf)
      (gen-doc conf))))

(defn field-def [schema kind field] (get-in schema [:kinds kind :fields field]))

(defn enum-values
  "The actual stored codes for an enum"
  [schema enum]
  (->> (get-in schema [:enums enum :values])
       keys
       (map name)))

(defn enum-ddl-type
  "If kind/field is an Alzabo enum type, return its DDL enum type string
   (e.g. \"ENUM (female, male, unknown)\"); otherwise nil. Used to override a
   backend's raw column type (STRING etc) with the actual enum values, which
   makes a big difference to generation quality."
  [schema kind field]
  (let [{:keys [type]} (field-def schema kind field)]
    (when (alzs/is-enum-type? type schema)
      (format "ENUM (%s)" (str/join ", " (enum-values schema type)))))) ;To be proper DDL values should be quoted, but for current purposes maybe not

(defn sql-type
  [schema kind field]
  (let [{:keys [type]} (field-def schema kind field)]
    (cond (alzs/is-enum-type? type schema)
          (format "ENUM (%s)" (str/join ", " (enum-values schema type)))
          (alzs/primitives type)
          (str/upper-case (name type))
          :else
          "STRING")))

(defn clean-string
  [s]
  (str/replace s "-" "_"))

;;; The kind_field naming convention every generated SQL column follows.
;;; Schema-independent — takes only the kind/field names themselves.
(defn db-col
  [kind field]
  (str (clean-string (name kind)) "_" (clean-string (name field))))

(defn do-fields
  [schema f]
  (doseq [[kind kdef] (:kinds schema)]
    (doseq [field (keys (:fields kdef))]
      (f kind field))))

;;; ── Semantic column info ─────────────────────────────────────────────────────
;;; See design/semantic-columns.md (okc). LLM-generated SQL reliably selects
;;; bare, unaliased columns, and every sql/query backend keys result rows by
;;; the column's own name — so db-col's kind_field naming convention is
;;; enough to recover a result column's semantic type via sql->alz, no
;;; per-project schema plumbing needed *beyond passing the schema itself in*.

(u/defn-memoized alz->sql-map
  "kind/field -> its db-col name, for every field in `schema`. Memoized per
   schema value (read-schema already returns the same value for the same
   schema-file path every time, so this only computes once per distinct
   schema actually in use)."
  [schema]
  (into {}
        (u/collecting
         (fn [collect]
           (do-fields schema
                      (fn [kind field]
                        (collect [[kind field] (db-col kind field)])))))))

(u/defn-memoized sql->alz-map
  [schema]
  (clojure.set/map-invert (alz->sql-map schema)))

(defn alz->sql [schema kind field] (get (alz->sql-map schema) [kind field]))
(defn sql->alz [schema col-name] (get (sql->alz-map schema) col-name))

(defn kind-icon
  "A kind's display icon, if it has one — an :icon attribute on the kind
   itself in `schema` (alongside :doc, :reference? etc), not hardcoded here."
  [schema kind]
  (get-in schema [:kinds kind :icon]))

(defn external-link-template
  "A kind's external-link URL template, if it has one — an
   :external-link-template attribute on the kind (eg
   \"https://www.genecards.org/card/{{value}}\" on a :gene kind), alongside
   :icon/:doc. Frontend cell renderers use this instead of any
   domain-specific hardcoded link (see sql_query.cljs's old gene-card-link)."
  [schema kind]
  (get-in schema [:kinds kind :external-link-template]))

(defn column-info
  "Semantic metadata for a SQL result column, via the kind_field naming
   convention (sql->alz) against `schema`. nil if the column doesn't resolve
   (LLM aliases, aggregates like COUNT(*) AS n, etc — expected and fine).

   Falls back to a heuristic match for unresolved columns whose name suggests
   a gene symbol, *only if* `schema` actually declares a :gene kind — opt-in
   per schema, not a blind assumption every schema cares about genes."
  [schema col-name]
  (if-let [[kind field] (sql->alz schema col-name)]
    (when-let [{:keys [type doc]} (field-def schema kind field)]
      (let [;; type points at another kind => this column is a reference to
            ;; that kind (eg clinical_observation_subject's type is :subject)
            ;; — the effective semantic type for display purposes.
            ref-kind (when (get-in schema [:kinds type]) type)
            effective-kind (or ref-kind kind)]
        {:kind kind
         :field field
         :doc doc
         :enum? (alzs/is-enum-type? type schema)
         :ref-kind ref-kind
         :icon (kind-icon schema effective-kind)
         :external-link-template (external-link-template schema effective-kind)}))
    (when (and (get-in schema [:kinds :gene])
               (str/includes? (str/lower-case col-name) "gene"))
      {:kind :gene
       :icon (kind-icon schema :gene)
       :external-link-template (external-link-template schema :gene)
       :heuristic? true})))

(defn columns-info
  "col-name -> column-info for every column in a SQL result set, against `schema`."
  [schema results]
  (into {} (keep (fn [k] (when-let [info (column-info schema (name k))] [k info])))
        (some-> results first keys)))
