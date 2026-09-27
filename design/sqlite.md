# Spec

Straigthforward enough – add sqlite as a data source.

Use the opencode db as a test case, not even sure where that lives but I'm sure you can find it.

# Claude Notes

Added `src/clj/hyperphor/nlq/sources/sqlite.clj` (`:provider :sqlite`, db config is
just `{:provider :sqlite :dbname "/path/to/file.db"}`) plus the
`org.xerial/sqlite-jdbc` dependency in `project.clj`. Modeled on `sources/postgres.clj`'s
core (`clojure.java.jdbc` already understands `{:dbtype "sqlite" ...}` natively) but
dropped its AACT-specific `gen-alz-schema` reverse-engineering tooling — out of scope
here, schema-less projects already work per `generate.clj`. `project-tables` lists
tables via `sqlite_master` (filtering out `sqlite_%` internal tables) and columns via
`PRAGMA table_info`. No `qualify-table-name`/`quote-ident` overrides needed — SQLite
has no schema-qualification and this repo's tables use plain lowercase identifiers.

Verified against `~/.local/share/opencode/opencode.db` (a real SQLite file, the
opencode CLI's local session-history database) at the REPL: `project-tables`,
`project-ddl`, and `query` all work end-to-end. Also checked the two failure modes
`postgres.clj` had to work around, on `org.xerial/sqlite-jdbc` 3.46.1.3 specifically
(not a claim about the driver in general): a trailing `; -- comment` after generated
SQL executed fine and returned the correct count, and an `avg`/`sum`/`count` aggregate
query came back as plain `Double`/`Integer`, never `BigDecimal`/`BigInteger` — so
`sqlite.clj` doesn't need `strip-trailing-terminator` or `untag-numerics` equivalents
against this driver version. Note `sql/table-for-kind`'s `kind_id` search matches
`message.session_id` before its same-named-table fallback ever runs (eg `:session`
resolves to `message`) — pre-existing generic behavior in `sql.clj` for any
one-kind-per-table schema without the `kind_field` convention (same caveat
postgres.clj's AACT schema documents), not a sqlite-specific issue, and moot for a
schema-less project since there are no kinds to look up.

Two deliberately out-of-scope items, left for whoever configures a real project:
`project-tables` filters `sqlite_master` to `type = 'table'`, so views aren't listed
(unlike postgres's `information_schema.columns`); and unlike postgres there's no
read-only-account layer here, so LLM-generated SQL runs against the file read-write —
add an `:open_mode` (SQLite's read-only URI mode) to the db config if that's wanted.

## Dev config, for trying it at the REPL

Added `dev-resources/config.edn` — an `:nlq` project entry named "OpenCode" pointed at
the same opencode.db, with a handful of example queries over `session`/`message` (the
only tables with real row counts here; `project` has a single row with a null `name`,
not worth an example). `dev-resources/` is wired into the classpath only via
`project.clj`'s `:dev` profile (`lein repl`'s default profile), never packaged with the
library — keeps CLAUDE.md's "no hardcoded project" intact for real builds. Usage:

```clojure
(require '[hyperphor.nlq.sources.sqlite])   ; registers the :sqlite defmethods
(require '[hyperphor.way.config :as config])
(config/read-config "config.edn")
(require '[hyperphor.nlq.generate :as generate])
(generate/endpoint "OpenCode" :sql "How many sessions are there?")
```

Needs `OPENAI_API_KEY` set (the config's `:llm` defaults to `:openai`/`gpt-4o`) since
`endpoint` calls a real LLM to translate the NL into SQL, then runs it.

While wiring this up, hit and fixed a real bug in `generate.clj`: both `generate :sql`
and `generate :sparql` built their LLM message vector with a bare `(when schema {...})`
entry — when a project has no `:schema` (the documented, supported case here), that's a
literal `nil` sitting in the vector, which `hyperphor.ellum.providers.openai` chokes on
(`(name (:role nil))` → NPE) before ever reaching the LLM. Fixed by wrapping both
vectors in `(remove nil? ...)`. `visgen.clj` uses the same `llm-complete` machinery but
builds no conditional message entries, so it wasn't affected.

