This repo should include enough of a demo app that you can investigate a new db just by making a config and launching. 

Some of the machinery seems to have migrated out to nlq-aact and might need backporting

NLQ includes way so most of the basic machinery should be therfe.

Either make this a separate project/repo (nlq-demo) or maybe a subdirectory / namespace within this repo

Goal is to make it easy to investigate a new db, it should jsut take a config. The UI of this app shiuld be config driven.

# Claude Notes

Added `demo/` — a subdirectory within this repo (confirmed with the user over a
separate `nlq-demo` repo: via Leiningen `checkouts/`, `demo/` tracks this repo's own
`src/` live, no publish/version-bump loop to try changes, at the cost of `demo/`
being its own nested lein project rather than a real monorepo).

Read `nlq-aact` (`/opt/mt/repos/hyperphor/nlq-aact`, a sibling repo) in full first —
it already proves this exact pattern (way + nlq, a real backend, a real browser UI)
end to end, but hardcodes one project ("AACT") in three places: the handler's
`qbox-endpoint`, the frontend's `project` def and branding, and the schema-doc
generation call. None of that hardcoding turned out to be load-bearing — the library
already had fully generic, config-driven pieces that made a truly generic version
*smaller* than nlq-aact, not bigger:

- `hyperphor.nlq.frontend.universal-query/ui` already reads every `:db`-backed `:nlq`
  config entry, renders a project picker, and mounts the full query/grid/viz/inspect
  UI — this alone satisfies "config-driven UI." `demo/`'s whole frontend
  (`frontend/core.cljs`) is two tabs: this, plus a new ~20-line Schema tab (no
  library component covered that yet — picks among `:schema`-bearing projects,
  iframes `hyperphor.nlq.schema/init`'s generated doc).
- `hyperphor.nlq.schema/init` already generates docs for *every* configured project
  with a `:schema` — nlq-aact's `schema-gen.clj` just called it with one hardcoded
  name. `demo/core.clj`'s `build-schema` calls it generically over
  `(nlqc/projects)`.
- `way.server`/`way.handler`/`way.ui.init` were already fully generic — read in full,
  no changes needed.

`demo/handler.clj`'s `qbox-endpoint` is the one place generalized *beyond* just
copying nlq-aact: both nlq-aact and okc (the original app nlq was extracted from)
hardcode a fallback project name when none is passed; here the only fallback is
`(first (nlqc/projects))` — genuinely config-driven, no hardcoded name anywhere.

`demo/core.clj`'s `-main` requires every optional backend namespace
(`sources.sqlite`/`.postgres`/`.bigquery`/`.cirro`/`.sparql`) unconditionally, so any
`:provider` in `resources/config.edn` works without touching this file — each of
those namespaces only registers its `:provider` defmethods once required (see
`sources/sql.clj`), a gotcha hit firsthand while building the sqlite backend.

**Real gotcha hit and fixed**: `checkouts/nlq` shares *source*, not *dependencies*.
The first end-to-end run failed with `No suitable driver found for
jdbc:sqlite:...` — `org.xerial/sqlite-jdbc` is declared in this repo's own
project.clj, but that's on the unreleased `sqlite-source` branch; the pinned
`com.hyperphor/nlq "0.3.28"` jar's *published* pom (what actually resolves
`:dependencies`, checkouts or not) predates it. Fixed by declaring
`org.xerial/sqlite-jdbc` directly in `demo/project.clj` too, with a comment
explaining why — this will bite again for any future dependency nlq adds before its
next Clojars release, not just sqlite.

Verified for real, not just compiled: `npm install`, `lein shadow release app`
(0 warnings), `lein run 8099`, then exercised the actual running server over HTTP —
`/api/config` shows the seeded "OpenCode" project; `/api/qbox/query?id=sql&project=
OpenCode&query=...` returns real generated SQL and results (`SELECT COUNT(*) FROM
session;` → 6); `id=vizq` returns a real Vega-Lite spec. Confirmed the served page's
`window.onload` calls the correctly-named compiled export
(`hyperphor.nlq.demo.frontend.core.init`). Could not get a live visual/browser check
in this environment (Chrome extension wasn't connected) — the Schema tab's graceful-
empty-state (seeded config has no `:schema`) is verified by code reading, not by
clicking it.

