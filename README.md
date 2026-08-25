# hyperphor/nlq

Natural-language query engine for structured databases — NL → SQL/SPARQL/Datomic, executed and
returned as data, with an Alzabo schema as the LLM's "schema dictionary" and a generic
semantic-column/click-to-inspect UI layer for SQL-backed projects.

For a live example (on a clinical trial database), see https://aact-9252332d616a.herokuapp.com/NL_query


## What's here

- `hyperphor.nlq.generate` — the `generate`/`run-query`/`example-queries` multimethod core:
  NL → query text/code (`:sql`, `:sparql`, `:datomic`) → executed results.
- `hyperphor.nlq.schema` — Alzabo schema loading + the semantic-column layer (`kind_field`
  naming convention, per-column icon/doc/enum?/ref-kind metadata) — takes a schema as an
  argument everywhere, not a hardcoded one.
- `hyperphor.nlq.config` — per-project `:nlq` config lookup.
- `hyperphor.nlq.inspect` — click an id/FK in a results grid, see that entity's full row.
- `hyperphor.nlq.visgen` — NL + a result sample → Vega-Lite spec.
- `hyperphor.nlq.evals` — eval harness for NL→query generation quality.
- `hyperphor.nlq.sources.sql` — backend-agnostic SQL abstraction (`project-tables`/`query`
  multimethods, DDL assembly) that a real backend (`sources.bigquery`, `sources.cirro`, or your
  own) implements.
- `hyperphor.nlq.sources.sparql` — SPARQL endpoint client (eg Wikidata Query Service).
- `hyperphor.nlq.sources.bigquery`, `hyperphor.nlq.sources.cirro` — two real `sources.sql`
  backend implementations, brought over from OKC as working examples/starting points.
- `hyperphor.nlq.frontend.*` (ClojureScript, built on `hyperphor.way`) — `qbox` (generic NL-query
  UI card), `sql-query` (full semantic-column results grid + click-to-inspect), `sparql-query`
  (plain results grid, for query types without column semantics), `nlq-viz` (Vega-Lite viewer),
  `universal-query` (cross-project picker).


# Version History

0.1.0 factored out of okc
0.2.0 schema inference
0.3.0 postgres support
0.3.1 bump com.hyperphor/way pin to 0.2.6 (config boot-log secret redaction)
0.3.2 bump com.hyperphor/way pin to 0.2.7 (redact /api/config)
0.3.7 sources/cirro.clj: check-response no longer crashes on an atypically-shaped
      error body; s3-token-ds caches per [host project dataset-id] to avoid Cirro's
      /s3-token rate limit (both cherry-picked from okc's unmerged nlq-arbitrary-schema
      branch, see design/unmerged-branches-audit.md there)


