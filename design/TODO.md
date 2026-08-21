# Really need editbable SQL

The user should be able to edit and rerun the generated SQL.

I did this in the old nlfame, and it should't be hard.

Might need to change the API logic a bit to support doing SQL queries direclty. 

# Plan

- Backend (`generate.clj`): fix `requery-endpoint` to take a `project` arg and
  bind `*project-conf*` (it didn't — `run-query` would've resolved a nil db).
  Factor the columns-annotation step out of `endpoint` into `with-columns` and
  reuse it in `requery-endpoint`, so a requery gets the same
  icons/tooltips/click-to-inspect as a fresh NL query instead of a silently
  downgraded grid. Tag requery responses `:user-reason "requery"` so they're
  distinguishable in the BigQuery log.
- Expose it as a `wd/data` method (`:nlq-requery`) rather than a new route —
  `inspect.clj`'s `:sql-inspect` already proved this pattern (generic `GET
  /api/data`, dispatched by `:data-id`, way's `data/data` multimethod gets the
  full params map). Ships entirely inside this repo; no okc route changes.
- Frontend (`qbox.cljs`): new `query-editor` component + `:qbox-requery`
  event, generic across query types (`:sql`, `:sparql`, ...). Editable text
  lives at its own form path (`[id :query-code]`), seeded only when a
  response lands (`:qbox-query-response`) — not rebuilt from the response on
  every render, which is what made the old nlflame version of this
  (`datalog-pane`, disabled via `if false`) fight React over cursor position.
  Wired into `sql_query.cljs`/`sparql_query.cljs` in place of the read-only
  `[:pre.m-3 query]` pane.
