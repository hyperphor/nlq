# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A natural-language query engine for structured databases: NL → SQL/SPARQL/Datomic, executed and
returned as data, with an [Alzabo](https://github.com/hyperphor/alzabo) schema as the LLM's
"schema dictionary" and a generic semantic-column/click-to-inspect UI layer for SQL-backed
projects.

Extracted from `ParkerICI/okc` (private) as the config-liftable, schema-configurable core. This
repo is a *library* — no hardcoded schema, no hardcoded project. A consuming app supplies its own
Alzabo schema and `:nlq` config entries (see `hyperphor.way.config`) and gets NL→query for free;
it adds its own query types (eg `:datomic`) by extending this repo's multimethods with new
`defmethod`s, the same way `sources.bigquery`/`sources.cirro` extend `sources.sql`.

## Commands

Build via Leiningen (`project.clj` is canonical; `pom.xml` is auto-generated from it — don't hand-edit `pom.xml`).

```
lein deps                 # fetch dependencies
lein repl                 # REPL — most development happens here, not via a build/test command
```

There is no `test/` directory and no CI config in this repo yet — verification is via
`hyperphor.nlq.evals` (the eval harness) and REPL exercise of the multimethods, not a
`lein test` suite.

Frontend (ClojureScript) is built with shadow-cljs (a `:dev` profile dependency) but there is no
`shadow-cljs.edn` in this repo — the cljs sources here are compiled as part of a *consuming* app's
shadow-cljs build, not standalone.

## Architecture

### Backend (`src/clj/hyperphor/nlq/`)

The core flow is **NL → `generate` → query text/code → `run-query` → results**, all driven by a
per-request dynamic var `*project-conf*` (bound in `generate/endpoint`) that holds one `:nlq`
config entry: `{:name :schema :db :llm :sql-dialect :examples ...}`.

- **`generate.clj`** — the heart of the library. Three multimethods, all dispatched on a
  `query-type` keyword (`:sql`, `:sparql`, and whatever a consuming app adds):
  - `generate` — NL text → `[type code text]`, by prompting the LLM (via `llm-complete`) with
    few-shot examples (`example-queries`), the project's Alzabo schema, and (for `:sql`) DDL
    assembled from the live backend.
  - `run-query` — executes the generated query text/code against the project's `:db`, dispatched
    on the dynamic `*query-type*` var (not the query's own shape), so a consuming app's own
    extension (eg `:datomic`, dispatched on a Clojure map rather than a string) routes correctly.
  - `example-queries` — few-shot examples for a query type, pulled from the project's config.
  - `generate-or-canned` wraps `generate` with a memoized cache per `[project-name nl]`, so
    repeated identical NL queries skip the LLM call.
  - `endpoint`/`requery-endpoint` are the two entry points a web layer calls; `endpoint` binds
    `*project-conf*`/`*query-type*`, runs generate→run-query via the `with-ex` short-circuiting
    macro (keeps partial results up to the first exception, under `:error`), annotates result
    columns via `schema/columns-info` + `inspect/annotate-inspectable`, and optionally logs to
    BigQuery (`record`) if the consuming app configures `:nlq-log`.
  - A project with no `:schema` config entry still works — the Alzabo/semantic-column context is
    just absent from the prompt and UI.

- **`schema.clj`** — Alzabo schema loading (with classpath-resource staging for uberjar
  deployment — schemas' `:include` references need real files, not JVM resources) plus the
  **semantic-column layer**: every generated SQL column follows a `kind_field` naming convention
  (`db-col`), which lets `column-info`/`columns-info` recover a bare result column's semantic type
  (kind, field, doc, icon, enum?, ref-kind) purely from its name, against whatever schema is
  passed in — no per-project hardcoding beyond that convention itself.

- **`config.clj`** — trivial `:nlq` config-entry lookup by project name (split out of
  `generate.clj` so `inspect.clj` doesn't have to depend on all of `generate.clj`).

- **`inspect.clj`** — the object inspector: given an id/FK value clicked in a results grid, looks
  up that entity's full row via `sources.sql/table-for-kind` + the `kind_field` id-column
  convention. `annotate-inspectable` tags each column with whether its target kind has a live
  backing table right now (computed on the fly, not a hardcoded list).

- **`visgen.clj`** — NL + a sample of the last query's results → a Vega-Lite spec, via the same
  `llm-complete`/`example-queries`/`*project-conf*` machinery as `generate.clj`, but always bound
  to a fixed `"Vegalite"` config entry (not the data project) since visualization generation isn't
  itself project-specific.

- **`evals.clj`** — eval harness: feed it `{:nl ... :results {...}}` cases (optionally
  `:expected` for query types with a canonical comparable form — Datomic-style types compare via
  α-equivalence over logic-variable renaming; `:sql`/`:sparql` don't, since textually different
  query strings can be semantically identical) and it runs each through `generate`/`run-query`,
  scoring on run success and a result-count spec. `cross-check` runs the same cases across
  multiple `{:provider :model}` configs for side-by-side comparison.

- **`sources/sql.clj`** — the backend-agnostic SQL abstraction other backends implement:
  `project-tables` and `query` multimethods dispatched on `(:provider db)`, plus shared DDL
  assembly (`project-ddl`) that injects Alzabo enum types into column definitions (`alz-enum-type`)
  since bare backend types like `STRING` lose information the LLM benefits from. Also defines
  `quote-ident`/`qualify-table-name` multimethods (default: no-op) for backends whose
  table/column names need dialect-specific quoting.
  - **`sources/bigquery.clj`**, **`sources/cirro.clj`** — two real backend implementations
    (`:provider :bigquery` / `:provider :cirro`), brought over from OKC as working
    examples/starting points, not meant to be the only backends. `bigquery.clj` is also reused
    directly by `generate.clj`'s optional query-logging (a separate concern from being a `db`
    backend).
  - **`sources/sparql.clj`** — SPARQL endpoint client (Wikidata Query Service by default), a peer
    to `sources.sql` but *not* built on its provider multimethods (SPARQL/RDF has no DDL/table
    structure to share).

- **`infer.clj`** — early stub (WIP): the planned direction is reading a set of tabular files,
  sampling their columns/rows, and asking an LLM to synthesize a new Alzabo schema for them (given
  an example schema as a guide).

### Frontend (`src/cljs/hyperphor/nlq/frontend/`)

Built on `hyperphor.way` (re-frame + this org's own form/API helpers), consumed by a host app's
own shadow-cljs build:

- **`qbox.cljs`** — generic reusable NL-query UI card (textarea + examples dropdown + spinner),
  dispatches to a host app's `/api/qbox/query` endpoint.
- **`sql_query.cljs`** — full semantic-column results grid with click-to-inspect, the cljs
  counterpart to `schema.clj`'s column-info and `inspect.clj`'s inspector.
- **`sparql_query.cljs`** — plain results grid for query types without column semantics.
- **`nlq_viz.cljs`** — Vega-Lite spec viewer, pairs with `visgen.clj`.
- **`universal_query.cljs`** — cross-project picker.
- **`utils.cljs`** — shared helpers.

### Key cross-cutting conventions

- **Dynamic-var request scoping**: `*project-conf*`, `*query-type*`, `*last-llm-call*` are all
  bound once per request in `generate/endpoint` (or explicitly by REPL/eval callers via
  `with-project` or direct `binding`) rather than threaded as explicit arguments through every
  function — keep this pattern when adding new generation/execution paths.
- **Schema is always an explicit argument**, never a hardcoded/global one, even though in
  practice it's usually `generate/alz-schema` (resolved once from `*project-conf*`) threaded
  through. When adding a function that needs schema info, take it as a parameter.
- **`kind_field` naming convention** (`schema/db-col`) is the load-bearing link between Alzabo
  schema fields and generated SQL column names — it's how semantic metadata, the object inspector,
  and enum-type DDL injection all recover meaning from bare SQL text without per-query annotation.
- Multimethod dispatch is the extension mechanism throughout (`generate`, `run-query`,
  `example-queries`, `sources.sql/project-tables`, `sources.sql/query`) — a consuming app adds a
  new backend or query type by adding `defmethod`s for a new dispatch keyword, not by modifying
  this repo.
