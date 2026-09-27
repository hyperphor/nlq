# Two plans for design/repl.md — now scoped as a new project ("Traverse"?)

repl.md's own "Zeroth thought" (added since the first pass at this doc) reframes the
whole thing: not an nlq feature, a new project. These plans assume that: a **new
repo**, consuming `nlq`/`way`/`ellum` as libraries exactly the way `okc` already
does, not a branch of nlq's frontend. Once that repo exists, this file and
`repl.md` probably want to move there.

⚠️ **Naming note before anything else**: `mtravers/exobrain` (checked out locally
at `/opt/mt/repos/exobrain`) is *already* "Traverse 3.0" internally — its own
CLAUDE.md says so — and it's a live, CDD-branded product deployed at
`traverse.cddinternal.com` and `traverse-dev.cddinternal.com`. Reusing "Traverse"
for this new, unrelated project means two different things answer to that name,
one of them a client-facing product at another company. Worth a deliberate yes
("it's an internal code name, fine") rather than a default, before it ends up in
a repo URL. Referred to below just as "the new project."

## Prior art (this is at least attempt #3 — worth sitting with)

Went looking because repl.md's own "Prehistory" section pointed at it. Found more
than expected:

- **BioBike 2** (`design/image_1657409233911_0.png`, ~2007-era): a REPL where
  every entry has a stable id (`#$sam/session/23/entry/3`), shows the input
  expression and its rendered result (table, plot) inline, can be named
  (`melanoma-workflow`), and carries per-user commentary threaded underneath.
  This is, almost exactly, repl.md's block model — id, persisted, typed,
  nameable, traceable — from before LLMs existed.
- **KnowOS / KnowOS 2.0** (`hyperphor.com/ammdi/KnowOS`, copied into repl.md):
  the same idea generalized into "any random map can be an object; type and
  persistence are add-ons," CLOS-like dispatch, explicit **Alzabo integration**
  for the type layer, and UI notes to "start with BlockNote or equivalent."
- **Traverse 3.0** (`design/Traverse 3.md`, May 2024): a full platform proposal —
  entities+relationships as the uniform abstraction, Neo4j/Datomic backend,
  ontology layer, multi-user collaboration layer, agent layer, a proposed 3-4
  person team — with UI stack **"ClojureScript, React, Way... AG-grid, Vega,
  BlockNote"** (the same three components already used or being considered
  here). KnowOS 2.0's note that it was written "post-Traverse 3.0 collapse"
  says this shipped as a scoped-down product (Exobrain), not the full vision.
- **Exobrain** (`/opt/mt/repos/exobrain`, live at traverse.cddinternal.com):
  what actually got built — Neo4j-backed object system (`exobrain.object`,
  per-type views), source integrations (PubMed, PharmaKB, OpenAI...), Way-based
  Reagent/re-frame frontend, background agents. Real, deployed, working — but
  CDD-specific, Neo4j-shaped, and its own product with its own security/deploy
  constraints. **Not something to fork or extend for this** — a reference for
  "what the object-system layer looks like once built," not a starting point.

**The lesson, not just the trivia**: the full-platform framing (entities +
ontology + multi-user + 3-4 person team) is the version that "collapsed" once
already. The thing this attempt has that the others didn't at the same stage is
`nlq` itself — a small, working, opinionated query engine to anchor to, instead
of designing the platform first. Both plans below lean on that; Plan B still
carries more platform ambition than Plan A, but neither proposes starting from
an ontology layer or a collaboration layer up front.

## What the vision actually requires (from repl.md)

- A chat/transcript UI replacing the static card layout.
- An **agent selector**: which "who" you're talking to (SQL vs SPARQL vs Vega vs
  future agents), ideally smart/dispatcher-like, not just a fixed dropdown.
- **Blocks**: unique id, persisted by default, linkable, **typed** (text, tabular,
  vega, query, database... built-ins), **traceable** (provenance: user or which
  agent), **nameable**.
- Maps of names→blocks as first-class objects (pages, essentially).
- Stretch: works as a plain Claude-style chat UI; subsumes Logseq; general UI for
  any (monitorable) agent system.

## What's already available to build on

- **Ellum already has chat + tool-calling** (`ellum.chat`, `ellum.tools`,
  `ellum.mcp-client`) — multi-turn conversation state and an agentic
  tool-dispatch loop, provider-agnostic. Natural substrate for the "smart
  agent selector," not something to hand-roll.
- **nlq already has three typed-block renderers**: `way.aggrid` (tabular),
  `way.markdown` (text), `way.vega` (charts, via `nlq_viz.cljs`) — plus the
  `generate`/`run-query` multimethods themselves, usable unchanged as the
  SQL/SPARQL "agents." nlq is designed to be consumed this way already (that's
  what `okc` does).
- **A generic object-store primitive exists**: `nlq`'s `logging/dynamo.clj` —
  `write-item`/`all-items`/`recent-items` against a uuid-keyed DynamoDB table.
  Has one real bug to fix before reuse: `tag-attribute`/`untag-attributes`
  (dynamo.clj:36-44) has no map branch — `:else {:S (str v)}` stringifies
  anything that isn't boolean/sequential/nil, so a block whose content is a
  map (a Vega spec, a result row) comes back a string, not data. Needs a real
  `{:M ...}` tag/untag pair or `pr-str`/`read-string` on content.
- **`way` has no streaming transport** (no SSE/websockets anywhere, confirmed
  by grep) — token-by-token responses aren't available off the shelf.
- **BlockNote is a real, better-fitting component than the notebook tools
  evaluated in the first pass of this doc** (see below) — and is what the
  user's own prior designs (Traverse 3.0, KnowOS 2.0) already pointed at
  independently, twice.

### BlockNote, evaluated

Checked directly (blocknotejs.org), not assumed:

- React + ProseMirror + Yjs. Core editor is free for any use (personal, OSS,
  or closed-source commercial); only the *extra* AI/export/layout features
  are dual-licensed (subscription for closed-source) — irrelevant here since
  the plan is custom blocks, a core feature.
- **Custom block types with custom rendering are first-class** ("Create
  custom blocks, inline content, menus and more") — a Reagent component can
  back a block's render function exactly the way `way.aggrid`/`way.vega`
  already wrap other React libraries in this stack. This is in-pattern, not a
  new kind of integration.
- **A document is a plain JSON-serializable array of blocks, and every block
  has a stable id that persists for the block's lifetime** (confirmed from
  BlockNote's own docs) — this is close to a direct implementation of
  persistence.md's own proposal ("just JSON... with a naming scheme") and
  repl.md's "unique id... typed... persisted."
- **Custom blocks can be non-editable/atomic** (`content: "none"`), rendering
  any React component from data in the block's own props rather than editable
  inline text (confirmed from BlockNote's docs) — so an `:sql-result` or
  `:vega` block can wrap `way.aggrid`/`way.vega` directly as an opaque island,
  no awkward "chart as rich text" contortion.
- Built-in `/` slash-command menu — a free implementation of the
  agent-selector-as-command-palette idea, not something to build.
- First-class Yjs real-time collaboration — the exact thing Traverse 3.0's
  "Collaboration Layer" flagged as hard and TBD in 2024 is now close to free,
  *if* wanted later.
- Gap: BlockNote's model is one ordered document (a "page"), like Notion —
  it doesn't give cross-page block reference/naming/transclusion
  ("maps of names to blocks" spanning multiple pages) for free. That layer is
  extra work in either plan below, BlockNote or not.

The earlier version of this doc evaluated Clerk/Portal/Starboard and rejected
all of them as notebook shells; that conclusion stands, but BlockNote wasn't in
that pass and changes the answer — it's the one candidate worth genuinely
adopting rather than hand-rolling a transcript view.

### Constraints inherited regardless of plan

- **This needs real project scaffolding** — its own `project.clj`/
  `shadow-cljs.edn`/`package.json`, deploy target, `way`-based Ring backend —
  same shape as `okc`, but new. Not a config change to an existing app.
- **repl.md's own Cirro aside** ("might make more sense to integrate it into
  their existing Notebook frame — ask MZ") is still unresolved and still
  matters: if a Cirro deployment needs this embedded in someone else's
  notebook frame rather than owning its own page, that argues for a
  self-contained, embeddable component over a full standalone app shell.
  Worth resolving before locking the UI shape.
- **Don't entangle with the live Exobrain/CDD deployment.** Same vision,
  different product, different company relationship, different backend
  (Neo4j vs nlq's SQL/Alzabo world). A new repo, informed by it, not built on it.

---

## Plan A — minimal, maximum reuse

New repo, but the smallest one that could work: `nlq` + `way` + `ellum` +
BlockNote, wired together, nothing novel invented.

1. **New repo**, `way`-based backend/frontend scaffolding (copy okc's shape:
   Ring/Compojure routes, Reagent/re-frame app), depending on `nlq` as a
   library for the SQL/SPARQL "agents" — no changes to nlq itself beyond the
   dynamo.clj map-tagging fix (which is a good fix to land in nlq regardless,
   isn't Traverse-specific).
2. **UI shell = BlockNote**, one page = one session/transcript. Custom block
   types: `:sql-query`, `:sql-result` (renders via `way.aggrid`), `:vega`
   (renders via `way.vega`), `:text`/chat message (via `way.markdown`).
   Provenance (`:from :user`/`:agent`, `:agent-name`) rides as custom props on
   the block, which BlockNote's schema already supports — no separate
   tracking system needed.
3. **Agent selection via BlockNote's built-in `/` menu** — `/sql`, `/sparql`,
   `/viz` insert a block of that type and route it through nlq's existing
   `generate`/`run-query` multimethods, unchanged. No custom command-palette
   to build. Defer the "smart" dispatcher: wrap each `generate` method as an
   Ellum tool, run one `ellum.chat` tool-calling session as a `/ask` default
   agent — cheap once v1 ships, since `ellum.tools`/`ellum.chat` exist already.
4. **Persistence: per-block, not per-page** — repl.md is explicit that block
   addressability is "important to get upfront," not something to defer.
   BlockNote's own block ids are stable for the block's lifetime (confirmed
   above), so that id *is* the Dynamo partition key: write each block as its
   own item (`{:id :page-id :type :content :from :agent-name}`) via a fixed
   `dynamo.clj` (the map-tagging fix now matters more, since content lives
   per-item rather than buried in one blob), and use `editor.document`'s
   ordering purely as the page's *view* over those ids, rebuilt from a
   `page-id` scan/filter on open. Every block is independently addressable
   and linkable from day one, for about the same cost as the blob approach —
   Plan B's persistence work then reduces to "add GSIs for name/parent
   lookup" instead of a storage re-architecture.
5. **No streaming, no real-time collab** for v1 — request/response per block,
   single-user. Both are unusually cheap fast-follows *because* BlockNote
   already ships Yjs, but they're still fast-follows, not v1.

**Cost**: new repo/scaffolding (comparable to any new `way`-based app) + one
BlockNote integration + the reused nlq/ellum backends. No bespoke transcript
UI, no bespoke persistence format, no bespoke command palette.

**What you give up**: blocks are individually addressable and persisted from
day one, but *discovering* one by name or composing across pages is still
scan-based (no GSI) — repl.md's scatterplot-from-two-queries example is
technically possible (you can address block #3 by id) but not yet convenient
(nothing helps you find or name it first). Smart dispatch is deferred;
"subsume Logseq" stays aspirational, though BlockNote's own page model is a
much closer starting point for that than a hand-rolled one would have been.

---

## Plan B — build it ourselves

Same new-repo premise and same BlockNote UI layer (no reason to hand-roll the
transcript when BlockNote fits — "build it ourselves" here means the *object
system and agent framework*, not the editor widget). This is the version that
actually attempts the KnowOS/Traverse 3.0 ambition, scoped to avoid repeating
its collapse: no ontology layer, no multi-user security model, no 3-4 person
team assumption, single-user-first throughout.

1. **A real object system**, per KnowOS 2.0's own sketch: any map can be an
   object; type and persistence are orthogonal add-ons rather than baked into
   the map's shape. Dogfood **Alzabo** for the type layer (nlq already
   depends on it; KnowOS 2.0 explicitly calls for this) — a block's `:type`
   resolves to an Alzabo kind, giving it schema, icon, and validation for
   free, the same mechanism nlq's own semantic-column layer already uses.
2. **Persistence: add real indexes on top of Plan A's per-block store**: GSIs
   on `:name` and `:page-id`/`:parent`, so name→block lookup and cross-page
   discovery are real queries instead of scans — closing the "maps of names
   to blocks are first-class" gap Plan A leaves open (Plan A already made
   blocks individually addressable; this makes them *findable*).
3. **Agent framework**: an explicit registry (`{:name :system-prompt :tools
   :renderer}`), agents as values. Ship SQL/SPARQL/Vega agents wrapping
   nlq's multimethods, plus a `:dispatcher` agent (one `ellum.chat`
   tool-calling session across every registered agent) as default —
   BlockNote's `/` menu becomes the agent picker, backed by this registry
   instead of a hardcoded case statement.
4. **Cross-block composition**: since blocks carry real ids and references
   now, an agent can be pointed at "chart the results of blocks #3 and #5
   together" — the specific capability gap repl.md calls out by name.
5. **Turn on BlockNote's Yjs collaboration** once single-user is solid —
   genuinely cheap here (it's the editor's own built-in feature), unlike
   Traverse 3.0's from-scratch "Collaboration Layer... sounds difficult."
6. **Streaming**: an SSE (or comparable) endpoint added to `way` itself for
   token-by-token agent responses — new shared infra, needs review since
   other `way` consumers would inherit it.

**Cost**: real persistence schema + GSIs, an object/type system worth naming
as its own component if it graduates beyond this repo, an agent registry
redesign, new shared streaming infra in `way`. Multi-week. The BlockNote
adoption itself is no more expensive than in Plan A — the added cost is
entirely the object/agent/linking layers underneath it.

**What you get that Plan A doesn't**: the smart dispatcher repl.md actually
describes, real cross-block composition, a name→block index spanning pages,
and collaboration — the parts of the KnowOS/Traverse vision that two earlier
attempts didn't ship, attempted this time with `nlq` (and BlockNote) as the
concrete anchor those attempts didn't have.

---

## Recommendation

Ship Plan A first, in a real new repo. BlockNote de-risks it more than the
first pass of this doc assumed — it isn't a compromise UI, it's most of what
KnowOS/Traverse 3.0 were reaching for in the editor layer, for free. Given
this is at least the third run at this idea, and the one attempt that reached
platform scale (Traverse 3.0) is the one that "collapsed," the strongest
argument for Plan A isn't just that it's cheaper — it's that it breaks the
pattern of designing the object/ontology/collaboration layers before shipping
anything. Land Plan A, use it, and let real usage (not another design pass)
decide which of Plan B's bets — object system, cross-block linking, smart
dispatch, collaboration — actually earn their cost.
