(ns hyperphor.nlq.infer
  "Schema *inference* -- the reverse of schema.clj's read-schema (which
   assumes an Alzabo schema already exists): read a set of tabular files
   (CSV, from a directory -- could come from anywhere), take each one's
   table name, columns, a few rows of sample data, and small-cardinality
   columns' actual distinct values, and feed all of that plus a worked
   example schema to an LLM, asking it to synthesize a new custom Alzabo
   schema to represent the dataset.

   The result is a semantic model of the data (kinds/fields/types/docs), not
   yet wired up to any SQL backend -- it doesn't presume the kind_field
   naming convention schema.clj's semantic-column layer relies on (real CSV
   headers are whatever the data source calls them, eg camelCase). Loading
   the inferred schema into a project that queries these tables via
   sources.sql would still need the tables/columns actually renamed (or a
   backend that maps between the two) to line up with schema/db-col -- not
   attempted here."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.nlq.generate :as generate]
            ;; hyperphor.ellellem.* (not hyperphor.ellum.*, the current
            ;; artifact's actual namespace -- see generate.clj's identical
            ;; require) only resolves because alzabo-1.3.4.jar happens to
            ;; shade an AOT-compiled copy of the old ellellem package
            ;; alongside its own classes. Kept for consistency with
            ;; generate.clj/visgen.clj, which already depend on this same
            ;; coincidence; a future alzabo bump that stops shading it would
            ;; break all three at once, not just this file.
            [hyperphor.ellellem.util :as llm-util]))

;;; ── Reading tabular files ────────────────────────────────────────────────────

(defn- ->kebab
  "camelCase or snake_case -> kebab-case, for turning a raw filename/column
   name into an idiomatic Alzabo kind/field name."
  [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"[_\s]+" "-")
      str/lower-case))

(defn- read-csv-summary
  "One pass over `file` (whole file read into memory -- fine for the
   sample-dataset sizes this is meant for, not intended for huge tables):
   [header sample-rows column-values].
     sample-rows   - the first `n` data rows, for the prompt's row examples.
     column-values - column -> a sorted vec of its distinct values across
                     the *whole* file, but only for columns whose distinct
                     count stays within `max-distinct` (a column that
                     overflows that cap is dropped from the map entirely --
                     it's not a realistic enum candidate, and `n` sample
                     rows alone usually can't tell a closed-set categorical
                     column from a free-text one)."
  [file n max-distinct]
  (with-open [r (io/reader file)]
    (let [[header & rows] (csv/read-csv r)
          rows (doall rows)]
      [header
       (vec (take n rows))
       (into {}
             (keep (fn [[col vs]] (when vs [col (vec (sort vs))])))
             (reduce (fn [acc row]
                       (reduce (fn [acc [col v]]
                                 (let [seen (get acc col)]
                                   (cond
                                     (nil? seen) acc          ;; already overflowed max-distinct
                                     (>= (count seen) max-distinct) (assoc acc col nil)
                                     :else (assoc acc col (conj seen v)))))
                               acc (map vector header row)))
                     (zipmap header (repeat #{}))
                     rows))])))

(defn describe-table
  "One tabular file -> {:table-name :file :columns :sample-rows
   :column-values}, ready for the schema-inference prompt. :table-name is a
   kebab-cased keyword derived from the file's basename; :columns keeps each
   column's own header text verbatim (that's the real name the inferred
   schema's :doc should call out when it renames something); :column-values
   is the small-cardinality-column summary from read-csv-summary, for
   spotting :enums candidates sample rows alone would miss."
  [file & {:keys [sample-rows max-distinct-values] :or {sample-rows 5 max-distinct-values 20}}]
  (let [f (io/file file)
        [header rows column-values] (read-csv-summary f sample-rows max-distinct-values)]
    {:table-name (-> (.getName f) (str/replace #"\.[^.]+$" "") ->kebab keyword)
     :file (.getPath f)
     :columns (vec header)
     :sample-rows (mapv #(zipmap header %) rows)
     :column-values column-values}))

(defn describe-directory
  "Every *.csv file directly in `dir` (non-recursive), as a seq of
   describe-table maps, sorted by filename for a stable prompt."
  [dir & {:keys [sample-rows max-distinct-values glob]
          :or {sample-rows 5 max-distinct-values 20 glob #"(?i).*\.csv$"}}]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (re-matches glob (.getName ^java.io.File %))))
       (sort-by #(.getName ^java.io.File %))
       (mapv #(describe-table % :sample-rows sample-rows :max-distinct-values max-distinct-values))))

(defn- format-table
  [{:keys [table-name columns sample-rows column-values]}]
  (str "Table: " (name table-name) "\n"
       "Columns: " (str/join ", " columns) "\n"
       "Sample rows: " (pr-str sample-rows) "\n"
       (when (seq column-values)
         (str "Columns whose distinct values across the WHOLE file are exactly this small set "
              "(real :enums candidates, not just what the sample rows happen to show): "
              (pr-str column-values) "\n"))))

(defn format-tables
  "The tables (from describe-directory) rendered as plain text for the LLM
   prompt -- one table name/columns/sample-rows/column-values block per table."
  [tables]
  (str/join "\n" (map format-table tables)))

;;; ── Schema format + worked example ───────────────────────────────────────────
;;; No dependency on alzabo's own doc/schema-format.md file (this library
;;; doesn't bundle it, and it lives in a sibling repo) -- the spec the LLM
;;; needs is short enough to inline here directly.

(def ^:private schema-format-system-prompt
  "You are an expert at data modeling. You design Alzabo schemas -- EDN maps
   describing a dataset's entity types (\"kinds\") and their fields, used as
   a semantic dictionary for natural-language-to-SQL generation.

   Format:
   - Top level: {:title \"...\" :kinds {...} :enums {...}}
   - :kinds is a map of kind-name (a keyword, kebab-case, singular -- eg
     :patient not :patients) to {:fields {...}}.
   - Each kind's :fields is a map of field-name (keyword, kebab-case) to a
     field definition: {:type ... :doc \"...\" :cardinality :one-or-:many
     :unique :identity-if-this-is-the-kind's-own-id}.
   - :type is either a primitive (:string :boolean :float :double :long
     :bigint :bigdec :instant :keyword :uuid), another kind's name (for a
     reference/foreign-key field -- eg a :sample kind with a :patient field
     of :type :patient), or an enum name declared in :enums.
   - :doc is a one-line string explaining what the field means, and MUST
     name the exact source column it came from (eg \"...from the
     'patientId' column.\") so the mapping back to the raw data is traceable.
   - :enums is a map of enum-name to {:values {code \"Display label\" ...}}
     -- use this ONLY for a column the prompt explicitly lists as having a
     small closed set of distinct values across the whole file, never for
     one you're merely guessing looks categorical from a handful of sample
     rows. Each code is a KEYWORD (eg :NUMBER, not \"NUMBER\"), even when
     the raw value itself isn't naturally keyword-shaped -- see the example.

   Two hard rules, more important than anything above:
   1. EVERY column listed for a table must map to some field on that
      table's kind -- never drop a column, even one that looks redundant,
      derived, or empty in the sample rows. If a column's purpose is
      genuinely unclear, still include the field and say so in its :doc,
      don't omit it.
   2. Mark a field :unique :identity ONLY when it corresponds to one of the
      table's own real, listed columns that actually functions as a row
      identifier. NEVER invent/synthesize an id field with no backing
      column -- if a table has no natural id-like column, give its kind NO
      :unique :identity field at all rather than fabricate one.

   Return ONLY the schema as a single EDN code block (```clojure ... ```),
   nothing else outside the block.")

(def example-schema
  "A small worked example of the format above, given to the LLM as a guide
   for infer-schema's output shape. Deliberately generic -- a made-up
   bookstore domain, not tied to any real project -- just enough surface to
   show :kinds/:fields/:type/:doc/:cardinality/:unique, a cross-kind
   reference (:book's :author field), and :enums."
  '{:title "Bookstore example"
    :kinds
    {:book
     {:fields
      {:id        {:type :string, :cardinality :one, :unique :identity,
                    :doc "The book's ISBN, from the source data's 'isbn' column."}
       :title     {:type :string, :cardinality :one, :doc "The book's title, from 'title'."}
       :author    {:type :author, :cardinality :one,
                    :doc "The book's author -- a reference to the :author kind, from 'authorId'."}
       :genre     {:type :genre, :cardinality :one, :doc "The book's genre, from 'genre'."}
       :published {:type :long, :cardinality :one, :doc "Year of first publication, from 'pubYear'."}
       :in-print? {:type :boolean, :cardinality :one, :doc "Whether still in print, from 'inPrint'."}}}
     :author
     {:fields
      {:id   {:type :string, :cardinality :one, :unique :identity,
              :doc "Author's own id, from 'authorId'."}
       :name {:type :string, :cardinality :one, :doc "Author's full name, from 'authorName'."}}}}
    :enums
    {:genre {:values {:fiction "Fiction" :nonfiction "Non-fiction" :poetry "Poetry"}}}})

;;; ── Inference ─────────────────────────────────────────────────────────────────

(defn infer-schema
  "Ask an LLM to synthesize a new Alzabo schema describing the tabular
   dataset in `dir` (every *.csv file there, non-recursive -- see
   describe-directory). Returns the parsed schema as a Clojure map; throws
   if the LLM's response had no parseable EDN code block (eg truncation at
   `max-tokens` -- an unterminated code fence just looks like \"no code
   block\" to extract-clojure, so a nil-body ex-info here on a big/wide
   dataset is a good first thing to suspect, not necessarily a bad prompt).

   Doesn't validate or write anything -- run the result through alzabo's own
   schema validation, or `spit` it out to a .alz.edn file, once you're happy
   with it. In particular, still worth a human pass over every kind's field
   list against describe-directory's :columns before trusting it -- an LLM
   can still drop or mistype a column despite the prompt's explicit
   coverage requirement.

   `title`, if given, is passed through as a hint for the schema's own
   :title. `sample-rows` (default 5) and `max-distinct-values` (default 20)
   thread to describe-directory. `max-tokens` (default 4000) is generate's
   usual 2000 doubled -- a several-table schema's EDN output is easily
   1500+ tokens, and a silently-truncated response just fails opaquely (see
   above), so this defaults higher rather than requiring every caller to
   remember to raise it. `provider`/`model` override generate/llm-complete's
   defaults (see there) -- eg for a bigger-context model on a wide dataset."
  [dir & {:keys [title sample-rows max-distinct-values provider model max-tokens]
          :or {sample-rows 5 max-distinct-values 20 max-tokens 4000}}]
  (let [tables (describe-directory dir :sample-rows sample-rows :max-distinct-values max-distinct-values)
        _ (when (empty? tables)
            (throw (ex-info (str "No CSV files found in " dir) {:dir dir})))
        response (generate/llm-complete
                  [{:role :user
                    :content (str "Here is a worked example of the schema format: "
                                  (pr-str example-schema))}
                   {:role :user
                    :content (str "Here are the tables in a new dataset -- infer a new schema "
                                  "to represent them, following the format, the two hard rules, "
                                  "and the example above. Use kebab-case kind/field names, not the "
                                  "raw column names verbatim, but always name the original column "
                                  "in :doc. "
                                  (when title (str "Set the schema's :title to \"" title "\". "))
                                  "\n\n" (format-tables tables))}]
                  :provider provider :model model :max-tokens max-tokens
                  :system schema-format-system-prompt)]
    (if-let [[schema _text] (llm-util/extract-clojure response)]
      schema
      (throw (ex-info "No EDN code block in LLM response" {:response response})))))
