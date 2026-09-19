# dbt manifest → Alzabo schema converter

## Background: market research — does dbt already do this?

Prompted by dbt's 2017 "what is dbt" intro post (https://www.getdbt.com/blog/what-exactly-is-dbt),
which is pure transformation-tool content and predates all of this — dbt-the-product has moved a
long way since. As of 2026 it ships real NL capabilities:

- **dbt Copilot** — inline AI for *writing* dbt code (SQL models, docs, tests, semantic models) in
  Studio IDE/Canvas.
- **dbt Wizard** — dbt's flagship agent for the full dev lifecycle.
- **Copilot in Insights** — actual chat-with-your-data, NL→answer over the warehouse.

All of the querying-side features sit on the **dbt Semantic Layer / MetricFlow**, not on raw
text-to-SQL: the LLM decomposes a question into a structured request over pre-modeled
metrics/dimensions (hand-defined once, in YAML), and MetricFlow compiles that deterministically to
SQL — the LLM never generates SQL directly. Architecturally that's the same instinct as this repo's
Alzabo-schema-as-dictionary approach (keep the LLM inside a modeled, named vocabulary rather than
free-associating over table/column names), just pushed one step further (structured metric query,
not SQL, as the LLM's output).

dbt publishes a 2026 benchmark claiming Semantic Layer accuracy of 98–100% vs. 84–90% for
text-to-SQL (docs.getdbt.com/blog/semantic-layer-vs-text-to-sql-2026), and a related Snowflake
Cortex write-up claims "83% of *addressable*" NL questions answered correctly via the Semantic
Layer (docs.getdbt.com/blog/semantic-layer-cortex). Worth reading with a raised eyebrow: "addressable"
and "within modeled scope" means the semantic layer is scored only on questions someone already
built a metric for, and it declines (fails loudly) outside that scope. Text-to-SQL/NLQ-style
generation covers a larger question space with a different failure mode — silently-plausible-wrong
vs. explicit-refusal. So the semantic-layer approach isn't strictly dominant, it's a
precision/coverage trade-off, and the benchmark is comparing its best case against text-to-SQL's
average case.

**Would a MetricFlow connector (an NLQ query-type that targets the Semantic Layer instead of
SQL) make sense?** Checked specifically whether it's queryable without dbt Cloud, since that's the
deciding constraint: MetricFlow-the-compiler is Apache-2.0 and works locally with dbt Core (`mf
query` against your own warehouse), but the **hosted query API** (GraphQL/JDBC — what BI tools and
the Cortex/Streamlit reference app actually call) is **dbt Cloud-only, paid**. A connector is
technically buildable (shell out to the Python CLI, or stand up a serving shim) but real
integration cost, and it would only matter for dbt Core users without Cloud — Cloud users already
have dbt Labs' own official chat product. Conclusion: not a natural fit for this repo, and not
clearly an open gap in the market. Scoped out below (see Non-goals).

**The better fit**: skip query-time integration entirely and go upstream, to schema authoring. A
dbt project's `manifest.json`/`catalog.json` already carries column descriptions, types, tests, and
lineage — substantially the content Alzabo encodes, and exactly the shape `infer.clj` is stubbed to
produce by *guessing* from sampled CSVs. dbt hands you the answer already worked out by the analytics
engineers who built the models. That's what the rest of this document sketches: no new query type,
no Python interop, no dbt Cloud dependency, works for dbt Core users today, and doesn't compete with
dbt Labs' paid product — it serves the segment that doesn't have it.

## Converter overview

dbt (the transformation-layer tool, https://www.getdbt.com) ships its own NL-querying story — dbt
Copilot/Insights chat, built on the dbt Semantic Layer/MetricFlow — but that's a dbt Cloud, paid
feature, and the Semantic Layer's *query* API specifically is Cloud-only (dbt Core exposes
MetricFlow as a local library/CLI, not a servable API). The lower-cost, higher-leverage integration
for this repo isn't competing with that product — it's treating a dbt project's own build artifacts
as a ready-made Alzabo schema source, so NLQ's existing `:sql` generate/run-query path can point
straight at the warehouse tables dbt built, documented by dbt's own metadata. Works for dbt Core
users with no Cloud dependency.

This is the same job `infer.clj` does (produce an Alzabo schema for a dataset with none) but from
**structured input instead of sampled CSVs** — dbt has already done the column-typing and (via its
generic tests) some of the enum/FK-detection work infer.clj's LLM has to guess at. Closer in spirit
to `sources/postgres.clj`'s `gen-alz-schema`: a dev-time, one-shot, REPL-invoked function that
reverse-engineers a schema from a live/structured source and writes it out for a human to review,
not something computed live per-request. Belongs alongside `infer.clj` as a top-level
`hyperphor.nlq.dbt` namespace, not under `sources/` — it produces a schema, it doesn't implement a
`sql/query`/`project-tables` backend. The actual SQL execution still goes through whatever backend
already talks to the warehouse dbt built into (`sources/bigquery.clj`, `sources/postgres.clj`, or
a new one — see Open questions).

## Inputs

`dbt docs generate` (or `dbt compile`) produces two JSON artifacts under `target/`, entirely local
— no dbt Cloud API call needed:

- **`manifest.json`** — every node (model/seed/snapshot/source/test) keyed by `unique_id`, with
  `name`, `description`, `columns` (name → `{description, data_type, meta, ...}` — `data_type` here
  is whatever the model's YAML *declared*, not authoritative), `depends_on.nodes` (the `ref()`/
  `source()` lineage graph), `resource_type`, `config.materialized`, `config.tags`, `schema`,
  `database`, `alias`.
- **`catalog.json`** — the actual warehouse-introspected shape: same node keys, but `columns` here
  come from querying the database itself (real `type`, ordinal position, stats). Only nodes that
  actually exist as relations in the warehouse appear here — critically, this is how you filter out
  `materialized: ephemeral` models, which compile inline into whatever references them and never
  become a real table/view. `catalog.json`'s presence is the filter; `manifest.json`'s
  `config.materialized` field says the same thing more explicitly if preferred.

Read both, keyed by `unique_id`; manifest for description/tests/lineage, catalog for ground-truth
types and to know what's actually queryable.

## Mapping dbt → Alzabo

| dbt | Alzabo |
|---|---|
| model node (`resource_type: model`, non-ephemeral) | `:kind` |
| model's `description` | kind's `:doc` |
| column | field |
| column's `description` | field's `:doc` |
| catalog.json column `type` | field's `:type`, via a per-warehouse type map (see below) |
| `unique` + `not_null` generic tests on one column | `:unique :identity` |
| `relationships` generic test (`to: ref('other_model'), field: id_col`) | field `:type` = the
  referenced model's kind (a real, declared FK — same role `pg_constraint` plays in
  `postgres.clj/relations`, but explicit in dbt instead of inferred from live constraints, which
  most analytics warehouses don't even enforce) |
| `accepted_values` generic test (`values: [...]`) | an `:enums` entry, keyed off the column, values
  taken verbatim from the test config |
| `source` nodes (raw ingested tables, pre-transformation) | optional — could map the same way as
  models if they're meant to be end-user queryable, but usually excluded (see below) |

The `accepted_values`/`relationships` signals are a real upgrade over what `postgres.clj` and
`infer.clj` have to do today: those infer enum-hood by sampling live distinct values
(`enum-candidate-columns`) or asking an LLM to guess from a handful of sample rows. A dbt project
that actually tests its models hands you the *declared, intentional* value set and FK graph
directly — no sampling, no LLM call, no guessing. (Where a project has *no* such tests on a column
— true of a lot of real dbt projects — fall back to the existing sampling heuristics rather than
leaving the column untyped; see Open questions.)

Per-warehouse `catalog.json` type → Alzabo type map is the same shape as `postgres.clj`'s
`pg-type->alz-type` (`{"integer" :long, "numeric" :float, "boolean" :boolean, "timestamp..." :instant,
... :else :string}`), just one map per dialect dbt might be pointed at (Snowflake/BigQuery/
Postgres/Redshift/Databricks types differ). Deterministic, no LLM needed for this step at all —
unlike infer.clj, which has to ask an LLM to *guess* a type from sample CSV values, dbt/the
warehouse already knows it exactly.

## `:direct-field-lookup? true`

Same reasoning as `postgres.clj`'s `gen-alz-schema`: this is one-kind-per-table with the warehouse's
own bare column names, not the `kind_field`-flattening convention `schema/db-col`/`sql->alz` expect
(there's no reason a dbt-modeled `orders` table would have `orders_id`-style prefixed columns). Set
`:direct-field-lookup? true` on the generated schema so `schema/column-info` resolves columns via
`direct-field-lookup` (match by bare field name across every kind), the same path AACT uses.

## Kind-name / table-name alignment

`sources.sql/table-for-kind` falls back to "a table literally named after the kind" when there's no
`kind_field` id column to find (the AACT-schema case, which this is too). That means the converter
must set each kind's keyword to the model's **actual materialized relation name** — which is the
model's `alias` config if set, not necessarily its filename/`name` in manifest.json — and the
backend's `project-tables`/`qualify-table-name` need to agree on the same `schema`/`database` dbt
built it into. Get this wrong and generated SQL will reference a table that doesn't resolve, or
`table-for-kind` (and so the object inspector) will silently miss.

## What has to be filtered, and by what

A real dbt project can have hundreds of models — staging, intermediate, and marts layers, most of
which aren't meant to be queried directly by an end user (staging models exist to normalize a raw
source 1:1, not to be a business-meaningful entity). Two problems, one knob:

1. **Correctness**: ephemeral models never exist as relations — excluded automatically by scoping
   to `catalog.json` nodes.
2. **Prompt size**: `project-ddl` already has this problem today (`sql.clj`'s `(:tables db)`
   allowlist, `postgres.clj/gen-alz-schema`'s explicit `tables` arg) — a schema with every model
   from `stg_` through `int_` through `fct_`/`dim_` is too large for one SQL-generation prompt and
   mostly noise for an NL question anyway.

Recommend the same explicit-list pattern already used elsewhere in this repo (an allowlist of model
names/tags passed in, not auto-derived) rather than guessing at a convention — dbt has no single
universal signal for "this is a user-facing mart" (tag names, `group`/`access` config, and
`exposures` all exist and are all optional/project-specific). Simplest starting point: take an
explicit list of model names, same shape as `postgres.clj/gen-alz-schema`'s `tables` argument;
revisit auto-selection (eg by tag convention) once there's a real project to test it against.

## Sketch

Rough shape, following `gen-alz-schema`'s signature convention — not a real implementation:

```clojure
(defn gen-alz-schema
  "Reverse-engineer an Alzabo schema from a dbt project's build artifacts.
   `manifest` / `catalog` are the parsed JSON from target/manifest.json and
   target/catalog.json. `models` is an explicit allowlist of model names to
   include (same role as postgres.clj's `tables` arg)."
  [manifest catalog models]
  ...)
```

producing the same `{:title ... :direct-field-lookup? true :kinds {...} :enums {...}}` shape
`postgres.clj/gen-alz-schema` does, spit to a `.alz.edn` file for a human pass, then wired into a
project's `:nlq` config `:schema` entry same as any hand-authored schema — and, for free,
renderable via `schema/gen-doc`'s existing HTML schema-doc output.

## Open questions (before implementing)

- **Warehouse backend coverage.** This repo has `sources/bigquery.clj` and (per the code just read)
  a Postgres path — two of dbt's common targets. Snowflake, one of dbt's most common targets, has
  no backend here yet; would need one (`sources/snowflake.clj`, following `sql.clj`'s multimethod
  shape) before this converter is useful against a Snowflake-backed dbt project.
- **Snowflake identifier casing.** Snowflake upper-cases unquoted identifiers by default —
  `catalog.json` column names for a Snowflake project may come back upper-case even though the dbt
  YAML/model SQL is written lower-case. Whatever casing the converter emits into kind/field names
  has to match what the eventual `quote-ident`/generated SQL actually sends, or queries silently
  won't match.
- **Sparse test coverage fallback.** The enum/FK mapping above assumes a project actually uses
  `accepted_values`/`relationships`/`unique` generic tests. Many real dbt projects don't test every
  column. Worth deciding whether to fall back to `postgres.clj`-style live sampling
  (`enum-candidate-columns`, real FK... except analytics warehouses usually have none) or to an
  `infer.clj`-style LLM pass over `catalog.json` + a data sample for untested columns, rather than
  leaving them typed as bare `:string` with no `:doc`.
- **manifest.json schema-version drift.** The JSON shape has changed across dbt versions (schema
  version bumps roughly every couple of major releases) — need to pick a floor dbt version to
  support, or version-sniff the artifact's own `metadata.dbt_schema_version`.
- **Sources.** Left out of the mapping table above by default (they're the raw pre-dbt tables, sitting
  behind whatever staging models normalize away their quirks) — but there's no strong reason not to
  include them as their own kinds later if a project wants NL queries reaching past the modeled
  layer, same allowlist mechanism.

## Non-goals

Not attempting to reach the dbt **Semantic Layer**/MetricFlow itself (metric definitions, not table
schemas) — that's a separate, heavier integration (see the earlier research: Cloud-only query API,
Python-only local engine) with a different payoff (deterministic metric math) and a different
audience (orgs that have already invested in modeling metrics, not just models). This document is
scoped to the cheaper, schema-only half.
