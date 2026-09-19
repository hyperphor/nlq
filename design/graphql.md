# GraphQL as an NLQ query type

Follow-up to `cdd.md`. Covers: how GraphQL actually works (just enough to know
what's different from SQL/SPARQL), what prior art already exists for this
exact problem, and a phased plan to add `:graphql` as a third NLQ query type
alongside `:sql`/`:sparql`.

## GraphQL, briefly, and why it's not just "SQL with braces"

- A GraphQL endpoint exposes one typed schema (SDL — Schema Definition
  Language, or fetchable live via an `__schema` introspection query) rooted
  at a `Query` type. Every field on `Query` is basically a stored
  procedure — `company(name: String!): CompanyType` — not a table.
- A query is a **tree of field selections**, not a row projection: you must
  explicitly name every nested field you want, all the way down
  (`companies { result { name url } }`). There's no `SELECT *`, no joins —
  nesting an object field *is* the join, already resolved server-side.
- Responses are `{"data": {...}, "errors": [...]}` and — this is the trap —
  **errors can arrive on HTTP 200** with partial/null `data` alongside an
  `errors` array. Status-code-based error checking (what `sparql.clj` does)
  silently swallows this.
- No aggregation primitives. No `GROUP BY`, no `COUNT(*) ... GROUP BY`. If
  the schema doesn't expose a pre-aggregated field, the only way to answer
  an aggregate question is fetch rows and aggregate client-side (if the
  endpoint even allows fetching enough rows).
- Responses are **trees**, and NLQ's pipeline is built entirely around
  **flat rows**: `sql/query`/`sparql/query` both return "a seq of maps keyed
  by the query's own SELECT variables," and both grid frontends
  (`sql_query.cljs`, `sparql_query.cljs`) render exactly that shape.

## Prior art (already in other repos, not hypothetical)

This was prototyped once already, against the same target (PharmaKB),
outside the nlq/Alzabo framework:

- `exobrain/src/clj/exobrain/sources/pharmakb.clj` — working PharmaKB REST
  client: token auth (`POST /api/v1/token/create/` with basic auth →
  bearer token, cached in an atom) + `graphql-query` (`POST
  /api/v1/graphql/` with `{:query ...}`). Ported into this repo already, in
  cruder form, as `src/clj/hyperphor/nlq/sources/pharmakb.clj` (commit
  `ff6acd1`, branch `pharmakb-graphql`, not yet pushed).
- `exobrain/src/clj/exobrain/cases/nlq.clj` — the actual NL→GraphQL
  prototype: feeds the raw SDL file (`resources/pharmakb-schema2.graphql`,
  1509 lines) plus one worked example straight into the LLM as prompt
  context, uses `extract-code` to pull the ` ```graphql ` fence, POSTs it.
  Its own comment: *"PKB not really a very good test case, it doesn't
  support very flexible queries."* — confirms the no-aggregation limit
  above from hands-on experience, not speculation.
  - `walk-find-path`/`extract-data` and `collect-results`'s hardcoded
    `(case type :Drug [:drugs])` are its answer to the tree→rows problem:
    a heuristic guess ("first vector whose head has `:id`/`:name`") plus a
    per-type-hardcoded key path. Evidence the tree→rows step is real work,
    not evidence of a design to copy — don't port this part as-is.
- `candelbio/src/clj/org/candelbio/alzabo/graphql.clj` — a GraphQL SDL →
  Alzabo schema converter (`schema->alz`, via lacinia's SDL parser),
  already run once against the same PharmaKB SDL
  (`candelbio/resources/pharmakb-schema.edn` is its output). This is the
  GraphQL analog of `sources.sql/project-ddl`, if/when we need it (see
  Phase 2 below — v1 doesn't).
- `extract-code` (`hyperphor.ellum.util`) needs no changes: it keywordizes
  whatever fence tag the LLM writes, so ` ```graphql ` already yields
  `[:graphql code text]` for free — confirmed by reading the code, matches
  exobrain's own `(when (= type :graphql) ...)` usage.

## Design decisions this plan is making explicitly

**Tree → rows.** Constrain the *prompt*, not the flattener: instruct the LLM
to emit a query with exactly one top-level list field and scalar-only
sub-selections (no nested object fields inside the list). Then
`graphql/query` does a deterministic "descend single-key wrapper objects
until you hit a list, return that list" walk — no guessing by field name
like `walk-find-path` does. If the LLM nests anyt way (some schemas force
it — e.g. `companies.result[].stockData.currentTicker`), fall back to a
dotted-column flatten (`stockData.currentTicker` as one column) rather than
failing outright. This keeps `graphql/query`'s return shape identical to
`sql/query`/`sparql/query`: a seq of flat maps.

**No aggregation in v1.** State it in the example-query set and the system
prompt rather than let the LLM silently fail on "how many X per Y" —
steer it toward list-and-let-the-user-count, or explicitly decline
aggregate asks. Client-side aggregation (fetch rows, `group-by` in Clojure)
is a plausible v2 if a real use case needs it; not building it speculatively.

**Schema context: skip the Alzabo conversion for v1.** CLAUDE.md already
guarantees a project with no `:schema` works — the semantic-column layer is
just absent. exobrain's prototype got usable queries out of the LLM from
the raw SDL alone. So v1 has no `:schema` entry for the PharmaKB project;
`generate :graphql` feeds the SDL resource directly, same role `ddl` plays
for `:sql`. Port `candelbio/graphql.clj`'s `schema->alz` in Phase 2, once
click-to-inspect is worth building (see `kind_field` note below). Do check
the SDL's token cost once against the model's context budget before
assuming "paste the whole 1509-line schema" is free — `generate :sql`
already flags this same risk for large DDL.

**No `kind_field` in v1 → plain grid.** GraphQL field names (`name`,
`approvedDrugsCount`) don't carry the `kind_field` convention
`schema/column-info` depends on, so click-to-inspect can't work regardless
of the schema question above. Wire the frontend to `sparql_query.cljs`'s
plain-grid path, not `sql_query.cljs`. A `field-path → kind` mapping
(probably derived from the Alzabo conversion once that exists) is a later
phase, not v1.

**Errors on HTTP 200.** `graphql/query` must check the parsed body's
`:errors` key itself (not rely on HTTP status, unlike
`sparql/check-response`) and throw with the error payload attached, so
`with-ex` in `generate/endpoint` can short-circuit correctly. The committed
`pharmakb.clj`'s error handling is currently commented out — that's a bug
to fix, not a pattern to keep.

**Layer split — and where nlq-cdd's boundary actually is.** Mirror
`sources.sql` / `sources.bigquery` / `sources.cirro`:
- `hyperphor.nlq.sources.graphql` (this repo, generic): POST transport,
  JSON body/headers, `:errors`-checking response unwrapping, single-list
  flattening, a `query` multimethod dispatched on `(:provider db)` only for
  where *auth* differs.
- `hyperphor.nlq.sources.pharmakb` — cdd.md says "nlq-cdd should have the
  pharmakb code." Resolve that now, before more code accumulates on the
  wrong side: move PharmaKB-specific bits (token endpoint, basic-auth creds,
  the `:pharmakb` provider method) into the new `nlq-cdd` repo; only the
  generic transport stays in `nlq`. Currently all of it (including creds)
  is sitting in `nlq` (commit `ff6acd1`) — that's the thing to undo.

**Credentials.** `src/clj/hyperphor/nlq/sources/pharmakb.clj` (tracked,
commit `ff6acd1`) has a `creds` map; `pharmakb-creds.clj` (untracked, not
gitignored) has the real password in plaintext. The branch hasn't been
pushed yet (`git branch -r --contains ff6acd1` is empty) so this hasn't
reached GitHub — good, but fix it before it does, not after:
1. Switch to `#env PHARMAKB_USER` / `#env PHARMAKB_PASS` read via
   `environ`, same pattern as nlq-aact's `config.edn` (`#env AACT_USER`).
2. Delete `pharmakb-creds.clj`; it has no reason to exist once creds live
   in env vars and/or the consuming app's `config.edn`.
3. Do not paste the real password into chat/logs/commits again — it already
   leaked into this session's transcript once (a redaction regex tuned for
   long base64 blobs let a short plaintext password through). Once
   rotation is convenient, rotate it anyway since it's already been
   displayed outside its intended context.

## Open items to verify (cheap, do before/while building)

- Does PharmaKB's live endpoint have introspection enabled? If not, the
  checked-in `pharmakb-schema2.graphql` SDL snapshot is the *only* schema
  source — there's no GraphQL analog of "pull DDL from the live backend"
  to fall back on, and staleness becomes a real risk to flag to users.
- Token TTL/refresh behavior — `pharmakb.clj`'s own TODO ("token will
  expire and there's no good notification of that") is still unresolved;
  decide whether to preemptively refresh or just retry-on-401.

## Phased plan

**Phase 0 — cleanup (do first, small, unblocks everything else)**
1. Fix credentials per above (env vars, delete creds file).
2. Move `pharmakb.clj` error handling from commented-out to real
   `:errors`-checking (can land in the new `sources/graphql.clj` instead,
   see next phase).

**Phase 1 — `:graphql` query type in `nlq` (generic)**
3. `src/clj/hyperphor/nlq/sources/graphql.clj`: `query` multimethod
   (`db`, `graphql-string` → seq of flat maps), dispatched on
   `(:provider db)`; POST transport, `:errors` checking, single-list
   flattening with dotted-key fallback.
4. `generate.clj`: `defmethod generate :graphql` (system prompt: single
   top-level list, scalar sub-selections only, no aggregation, GraphQL
   syntax notes analogous to the `:sparql` method's tips) +
   `defmethod example-queries :graphql` + `defmethod run-query :graphql`
   calling `graphql/query`. No `:schema`/Alzabo/DDL involvement yet —
   feed the SDL resource as one more `:content` block, same role `ddl`
   plays for `:sql`.
5. Seed `example-queries` from exobrain's `cases/nlq.clj` `queries` list
   and its one worked `one-shotting` example (psoriasis trials query) —
   already-proven-to-work examples, not written from scratch.

**Phase 2 — `nlq-cdd` consuming repo**
6. New repo modeled directly on `nlq-aact`
   (`core.clj`/`handler.clj`/`config.edn`, no `schema_gen.clj` equivalent
   needed yet since v1 has no Alzabo schema): `:nlq [{:name "PharmaKB" :db
   {:provider :pharmakb ...} :examples [...] :llm {...}}]`, no `:schema`
   key.
7. `nlq_cdd.sources.pharmakb` (moved from `nlq`): token auth,
   `:pharmakb`-provider `defmethod` on `graphql/query`'s multimethod,
   pulling creds from env.
8. Frontend: reuse `qbox.cljs` + `sparql_query.cljs`'s plain grid — no new
   cljs needed for v1.
9. Smoke-test against the live endpoint with the seeded example queries
   before considering v1 done.

**Phase 3 — only if v1 proves the concept is worth deepening**
10. Port `candelbio/graphql.clj`'s `schema->alz` into `nlq` (e.g.
    `schema/graphql->alz` or a new `sources/graphql_schema.clj`), generate
    `resources/pharmakb/schema.alz.edn` from the SDL, wire `:schema` into
    the PharmaKB config entry.
11. `kind_field`-equivalent mapping for GraphQL field paths → Alzabo kinds,
    to unlock click-to-inspect and move off the plain grid onto
    `sql_query.cljs`'s semantic grid.
12. Revisit aggregation (client-side `group-by` over fetched rows) if a
    real query need shows up.
13. Add a second GraphQL endpoint (cdd.md: "other GraphQL endpoints to use
    as examples") once one clean implementation exists, to pressure-test
    that `sources/graphql.clj`'s generic/provider split actually holds up
    to a second backend — same role bigquery/cirro play for validating
    `sources.sql`.
