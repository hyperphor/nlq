;;; Everything here takes the schema it should consult as an explicit
;;; argument — there is no hardcoded master schema. A consuming app supplies
;;; its own (per project, typically via that project's `:schema` config
;;; entry) and can share a common base schema across projects via Alzabo's
;;; own `:include`, the same way any other Alzabo schema composition works.
(ns hyperphor.nlq.schema
  "Alzabo schema loading, plus the semantic-column layer that recovers a SQL
   result column's meaning (kind/field/doc/icon/enum?/ref-kind) via the
   `kind_field` naming convention (`db-col`)."
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
   key (typically a project's list of NLQ config entries)."
  [confs]
  (doseq [conf confs]
    (when (:schema conf)
      (gen-doc conf))))

(defn field-def [schema kind field] (get-in schema [:kinds kind :fields field]))

;;; Deliberately `name`, not `str`: some hand-authored enums use already-
;;; namespaced idents as keys (eg umbra's :subject.sex/female), where the
;;; real stored/queryable value is only the bare name ("female") — `str`
;;; would wrongly reconstruct "subject.sex/female". A code that itself
;;; contains a literal "/" (eg a Postgres-generated enum's phase values like
;;; PHASE1/PHASE2 — see hyperphor.nlq.sources.postgres/gen-alz-schema) is
;;; built there using U+2044 FRACTION SLASH in place of the real "/", so
;;; `keyword` doesn't namespace-split it and `name` returns the whole code
;;; intact; un-substitute that back to a real "/" here.
(defn enum-values
  "The actual stored codes for an enum — the :values map's keys, via `name`."
  [schema enum]
  (->> (get-in schema [:enums enum :values])
       keys
       (map name)
       (map #(str/replace % "⁄" "/"))))

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
   schema value (cheap — read-schema returns the same value per schema-file path)."
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
   \"https://www.genecards.org/card/{{value}}\" on a :gene kind)."
  [schema kind]
  (get-in schema [:kinds kind :external-link-template]))

(defn kind-label-field
  "The bare field (a keyword, eg :brief-title) that's kind's own :label in
   `schema`, if it has one — :label is stored as a namespaced kind/field
   keyword (eg :studies/brief-title), same convention as :unique-id."
  [schema kind]
  (some-> (get-in schema [:kinds kind :label]) name keyword))

(defn kind-id-field
  "The bare field that's kind's own declared identity (:unique-id) in
   `schema`, if it has one — same namespaced kind/field convention as
   kind-label-field."
  [schema kind]
  (some-> (get-in schema [:kinds kind :unique-id]) name keyword))

;;; If col-name is a field of exactly one kind, that's an unambiguous match.
;;; If it's a field of *several* kinds, a bare name alone can't say which one
;;; truly owns it in a given query result — but if every match agrees on the
;;; field's :type (eg a foreign-key column present on several tables that
;;; always points at the same referenced kind), the reference itself is
;;; still unambiguous even though the owning kind isn't. Returned with
;;; :kind nil in that case, so column-info resolves it as a plain reference
;;; (:ref-kind) rather than wrongly claiming it's one specific ambiguous
;;; kind's own id column.
(defn direct-field-lookup
  "Semantic info ({:kind :field :type :doc}) for col-name found directly in
   `schema`'s own :kinds — for schemas with one kind per table and bare
   column names (eg AACT), where there's no kind_field flattening to reverse."
  [schema col-name]
  (let [field (keyword col-name)
        matches (for [[kind kdef] (:kinds schema)
                      :when (contains? (:fields kdef) field)]
                  (assoc (get-in kdef [:fields field]) :kind kind :field field))]
    (case (count matches)
      0 nil
      1 (first matches)
      (when (= 1 (count (distinct (map :type matches))))
        (assoc (first matches) :kind nil)))))

(defn- resolved-column-info
  "Shared shape-builder for column-info's two resolution paths (sql->alz and
   direct-field-lookup) — same fields, same :label?/external-link-template
   gating either way."
  [schema kind field type doc]
  (let [;; type points at another kind => this column is a reference to
        ;; that kind (eg clinical_observation_subject's type is :subject)
        ;; — the effective semantic type for display purposes.
        ref-kind (when (get-in schema [:kinds type]) type)
        effective-kind (or ref-kind kind)
        label?  (boolean (and kind (= field (kind-label-field schema kind))))
        own-id? (boolean (and kind (= field (kind-id-field schema kind))))]
    {:kind kind
     :field field
     :doc doc
     :enum? (alzs/is-enum-type? type schema)
     :ref-kind ref-kind
     :label? label?
     :icon (kind-icon schema effective-kind)
     ;; A kind's :external-link-template is a link destination for its own
     ;; id/FK columns, never for the kind's other data fields (eg a study's
     ;; :phase shouldn't render as a link to clinicaltrials.gov just because
     ;; :studies has an external link) and never for the :label field either
     ;; — a study's title is how a user finds/recognizes it in-app, so the
     ;; label field always stays an in-app inspector link (see
     ;; hyperphor.nlq.frontend.sql-query/label-inspect-cell-renderer); its
     ;; id/FK columns are what carry the external link instead.
     :external-link-template (when (or ref-kind own-id?)
                                (external-link-template schema effective-kind))}))

;;; The :direct-field-lookup? opt-in matters: direct-field-lookup matches a
;;; column by bare field name across EVERY kind in `schema`, which is right
;;; for a schema with no other naming convention to go on, but would be a
;;; false-positive machine against a kind_field-flattening schema like
;;; umbra's — almost every common bare name (age, id, name, sex, type,
;;; value...) collides with some kind's own field, and would start silently
;;; picking up that kind's icon/doc/grouping on a column never generated via
;;; the kind_field convention at all. Opt-in keeps every existing kind_field-
;;; style schema's column resolution exactly as it was. The gene heuristic
;;; fallback is separately opt-in (only if `schema` declares a :gene kind),
;;; not a blind assumption every schema cares about genes.
(defn column-info
  "Semantic metadata for a SQL result column, against `schema`: tries the
   kind_field naming convention (sql->alz) first, then — only if `schema`
   declares :direct-field-lookup? true (eg AACT-style, one kind per table) —
   direct-field-lookup. nil if the column doesn't resolve either way."
  [schema col-name]
  (if-let [[kind field] (sql->alz schema col-name)]
    (when-let [{:keys [type doc]} (field-def schema kind field)]
      (resolved-column-info schema kind field type doc))
    (if-let [{:keys [kind field type doc]} (when (:direct-field-lookup? schema)
                                              (direct-field-lookup schema col-name))]
      (resolved-column-info schema kind field type doc)
      (when (and (get-in schema [:kinds :gene])
                 (str/includes? (str/lower-case col-name) "gene"))
        {:kind :gene
         :icon (kind-icon schema :gene)
         :external-link-template (external-link-template schema :gene)
         :heuristic? true}))))

(defn columns-info
  "col-name -> column-info for every column in a SQL result set, against `schema`."
  [schema results]
  (into {} (keep (fn [k] (when-let [info (column-info schema (name k))] [k info])))
        (some-> results first keys)))
