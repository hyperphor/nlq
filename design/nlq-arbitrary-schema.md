# NL query over arbitrary Cirro sheets/datasets

Goal: let a user NL-query (not just NL-*viz*, which already works for
datasets — see `design/datasets.md`) data they picked live in the UI,
rather than only the handful of projects hardcoded in `config.edn`
(RADIOHEAD/PRINCE/MAHLER/PRINCE-PICI). Requires inferring a schema
(columns, types, and — where possible — likely value sets) on the fly,
since there's no hand-authored Alzabo file for something the user just
picked.

## How schema currently flows into NL→SQL (today, static projects only)

`generate.clj`'s `endpoint` binds `*project-conf*` from a **static**
`config.edn` `:nlq` entry (`nlqc/project-named project` — a string-name
lookup, nothing dynamic). `generate :sql` then builds the LLM prompt from
three pieces, all keyed off that static conf:

1. **`sql/project-ddl db`** — table/column DDL. For `:provider :cirro`
   this is already a **live API call** (`project-sheets` + `sheet-columns`)
   — it re-discovers a project's sheets/columns every time, not sourced
   from any static file. This already works for *any* Cirro project id,
   not just the ones in config.edn.
2. **`(alz-schema)`** → `schema/read-schema (:schema *project-conf*)` — a
   **hand-authored** Alzabo file (e.g. `prince/schema.alz.edn`). Not
   nil-safe (`read-schema` calls `resource-file`/`alzs/read-schema`
   straight through — a nil/missing `:schema` throws). This is the piece
   that doesn't exist for an arbitrary project.
3. **`example-queries :sql`** — few-shot examples from `(:examples
   *project-conf*)`. Empty/nil for an arbitrary project — already handled
   fine (`filter`/`pr-str` on nil is a no-op).

`table-ddl` also calls `alz-enum-type` per column, which looks the column
up in `schema/umbra` (the *master* Alzabo schema, not a per-project one)
via `schema/sql->alz` — this only fires for columns whose name happens to
match a known Alzabo kind/field, so it degrades gracefully (returns nil →
falls back to the backend's raw type) for arbitrary columns. Not a
blocker.

So concretely, **the only hard blocker** for NL-SQL over an arbitrary
Cirro project is #2 — `alz-schema` needs to become optional, not that we
need to *author* a schema. The DDL from #1 already **is** a schema, live
and free.

## Phase 1 (small): NL query over any Cirro *sheet* project

Sheets in *any* Cirro project are already fully SQL-queryable through the
existing `sql/query :cirro` / `sql/project-tables :cirro` multimethods
(and now paginate correctly — see `design/cirro-query-pagination.md`).
Nothing new needed there. What's missing is purely plumbing:

1. **`generate.clj`**: make the Alzabo-schema prompt section optional.
   ```clojure
   (defn alz-schema
     []
     (when (:schema *project-conf*)
       (schema/read-schema (:schema *project-conf*))))
   ```
   and in `generate :sql`, only add the "This is an Alzabo schema" message
   when `(alz-schema)` is non-nil.

2. **A way to build a `*project-conf*` for a live-picked project instead
   of a config.edn entry.** Same shape `cirro.clj`'s `tenant-db` already
   uses for the datasets picker: resolve an existing `:nlq` entry purely to
   borrow its `:host`/`:auth` (a tenant has fixed credentials; the project
   is chosen live), override `:project` onto that `:db`, and synthesize a
   project-conf map with no `:schema`/`:examples`:
   ```clojure
   (defn adhoc-project-conf
     [tenant project]
     {:name (str tenant "/" project)
      :db (assoc (:db (nlqc/project-named tenant)) :project project)
      :sql-dialect "Trino"
      :llm {:provider :openai :model "gpt-4o"
            :system "You are a computational cancer biologist and ontologist, conversant with relational databases."}})
   ```
   This is the same "TEMP CROCK"-adjacent pattern as `visgen.clj`'s
   `dataset-viz-endpoint` — a sibling endpoint/qbox-id (e.g. `:sheet-sql`)
   that binds `*project-conf*` to this synthesized map instead of a
   `nlqc/project-named` lookup, then reuses `generate-or-canned`/`run-query`
   unchanged.

3. **UI**: reuse the tenant/project pickers already built for the datasets
   picker (`datasets.cljs`'s `cirro-tenants` sub, `:cirro-projects` wd/data
   method) — add a "query this project's sheets" mode alongside (or
   instead of) drilling into a dataset/file. `sql_query.cljs`'s existing
   `query-card`/`viz-card`/cards layout is otherwise directly reusable —
   the only thing that changes per-project is which `db`/project-conf the
   qbox endpoint resolves.

Net: mostly wiring existing pieces together, no new dependencies, no new
query engine. This is the "smaller" half asked about.

## Phase 2 (bigger): NL query over arbitrary CSV/dataset files

The datasets picker (`design/datasets.md`) deliberately has **no SQL
backend at all** for loaded CSV files — NL-*viz* works today by handing
the LLM the raw rows directly (`viz-generate-with-data`), which is fine
for "make a chart" but doesn't extend to open-ended NL *querying*
(filtering/joining/aggregating) — there's no engine to run a generated
query against.

Options:

- **(a) Embed an in-process query engine (DuckDB via JDBC).** DuckDB can
  `CREATE TABLE ... AS SELECT * FROM read_csv(...)` directly against the
  already-loaded rows (or the S3 presigned URL we already have from
  `dataset-file-url`, if DuckDB's httpfs extension is usable in this
  deploy environment), with its own automatic type inference. Self-
  contained — no side effects on Cirro, scoped to the request/session.
  New dependency (no JDBC/embedded-DB driver in `project.clj` today),
  and a new `sql/query`/`project-tables` `:duckdb` (or similar) provider
  implementation — real but bounded work, following the exact same
  multimethod shape `bigquery.clj`/`cirro.clj` already use.

- **(b) Promote the CSV into a real Cirro sheet.** `cirro.clj` already has
  a batched sheet-upload path (`insert-sheet-data`, built for "Automated
  CANDEL → Cirro" per TODO.org) — uploading the loaded CSV as a new sheet
  would make it queryable through the *exact* Phase 1 pipeline for free.
  Downsides: real side effects (creates persistent Cirro state), needs
  sheet lifecycle/naming/cleanup decisions (ephemeral vs. kept, collision
  handling), and a round-trip upload before the first query — worse
  latency/UX for "just let me poke at this CSV once."

- **(c) LLM-in-context querying, no real engine.** Same spirit as today's
  NL-viz — hand the LLM the rows (or a sample) and ask it to filter/
  compute in-context, no generated SQL, no schema needed at all. Simplest,
  zero new infra, but doesn't scale past however many rows fit in context,
  and "insert a schema" (per the ask) implies more structure than this.

**Recommendation: (a), DuckDB**, once Phase 1 is done and it's clear the
same prompt-construction shape (DDL + optional enum-ish value hints) is
worth reusing. It's the only option that's both self-contained and
actually schema-based.

### The "look at the columns and names, insert a schema" step (Phase 2 specifically)

Unlike Phase 1 (where `project-ddl` already gives real DDL from Cirro's
own sheet metadata), an arbitrary CSV has no declared types — just
string values as parsed by `mcsv/read-csv-ms`. To get DDL-quality input
for the LLM prompt (mirroring what `alz-enum-type` gives named/known
columns today), infer per-column:

- **Type** — numeric / date / boolean / string, from a pass over parsed
  values (already partially done — `data.clj`'s `coerce-numeric`/`nana`
  do some of this for other paths, worth reusing rather than
  reinventing).
- **Likely-categorical columns** — low distinct-value count relative to
  row count (a common heuristic, e.g. ≤20 distinct values or ≤5% of
  rows) — sample and include their actual distinct values in the prompt,
  same role `alz-enum-type`'s enum DDL plays for known fields today. This
  is what turns a bare "STRING column" into something the LLM can
  generate correct `WHERE status = 'on-treatment'`-style clauses against.
- **Column name** as-is — no renaming/normalization attempted; DuckDB
  (option a) needs valid SQL identifiers, so column names with spaces/
  punctuation would need quoting (`quote-ident`, already a per-provider
  hook in `sql.clj`) rather than mangling.

This inferred description would be built once per loaded file (cache
alongside the existing `last-dataset-csv` atom) and passed into the
prompt the same way `project-ddl`'s DDL string is today — no new prompt-
construction machinery, just a different DDL *source*.

## Sequencing

1. Phase 1 first — small, no new dependencies, immediately generalizes
   NL-SQL to every Cirro project a tenant's credentials can see.
2. Phase 2 as a deliberate follow-on — needs a real decision (DuckDB vs.
   sheet-promotion) and a new dependency either way, worth doing once
   Phase 1's prompt-construction changes (optional Alzabo schema) have
   proven out.
