# Positioning: why this isn't just another NL→SQL tool

Framing: **"The schema is the interface."** Chosen over the other two candidates (see bottom)
because it puts the differentiator — the schema, and Alzabo as the engine behind it — in the
headline, not the query language. "NLQ" is the surface feature; the schema is the product.

## The story, not the feature list

This week's bug is the whole pitch. `schema.clj`'s `enum-values` handed the LLM a DDL fragment
like `ENUM (Complete Response, Partial Response, Stable Disease, ...)`. The LLM, reasonably,
wrote `WHERE clinical_observation_bor = 'Complete Response'`. That's valid SQL. It runs. It
returns zero rows. **Nothing errors.** A scientist reading that result concludes "no patients in
this trial had a complete response" — silently wrong, not loudly wrong.

That's the failure mode of every NL→SQL tool that grounds itself in raw DDL: a database column
only ever stores `'CR'`. The fact that `'CR'` means "Complete Response" exists nowhere the LLM can
see it, except by accident of a doc string sitting next to the code. Generic Text2SQL introspects
the database and gets `clinical_observation_bor ENUM('CR','PR','SD','PD','MR','NE')` — codes only,
no meaning, so the LLM guesses at meaning from training data and sometimes guesses the display
form back into a literal. Or it gets column names and comments as free text with no structural
guarantee the "human" side and the "stored" side stay distinguishable at all.

The one thing worth selling isn't "natural language to SQL" — that's commodity, every BI tool and
half the model providers ship it now. It's: **the semantic layer is what makes the answer
trustworthy, and the same layer that grounds generation also renders and documents the result.**
Everything else in this doc is evidence for that one claim, not a peer feature next to it.

## Alzabo is part of the product, not an implementation detail

If the schema is the interface, Alzabo (`com.hyperphor/alzabo`) is the engine that interface runs
on, and it belongs in the pitch by name, the same way the stack already names `way` (the app
framework) and `ellum` (the LLM layer). Alzabo is what makes a kind's `:doc`, `:icon`, `:label`,
enum codes-vs-glosses, and kind/field relationships a structured, machine-readable format instead
of scattered comments and hopeful naming conventions — that structure is precisely what a raw
`information_schema` dump from Postgres/BigQuery/Trino can't give an LLM. Every claim below is a
claim about what Alzabo, specifically, provides — not a generic "we use a schema" hand-wave.

## What the schema is actually doing (evidence, not a list of "also")

- **Grounding generation without losing meaning.** The Alzabo schema keeps `:CR` (the stored
  code) and `"Complete Response"` (its gloss) as structurally distinct — a map's key vs. its
  value, not two strings mashed into a comment. Fixed correctly, that distinction is exactly what
  stops this week's bug class, not a prompt-engineering patch on top of it.
- **The same grounding renders the result, for free.** Because the schema also carries `:icon`,
  `:doc`, `:label`, and kind/field relationships, the identical metadata that grounded the SQL also
  drives the object inspector (click a row → full entity, drill down through FKs) and semantic
  column grouping/icons in the results grid. Commodity Text2SQL stops at "here's a grid of
  strings." This stops at "here's a grid where every column knows what it is."
- **The same schema generates its own documentation.** `schema.clj`'s `gen-doc` walks the same
  Alzabo file into browsable HTML docs the portal already serves. One declarative source produces
  three outputs — SQL grounding, an interactive result browser, and reference docs — instead of
  three things maintained separately.
- **One schema, multiple physical backends.** `umbra.alz.edn` is the schema for RADIOHEAD
  (BigQuery), PRINCE (Cirro/Trino), and MAHLER (Cirro/Trino) simultaneously. A consortium running
  the same trial design across sites/vendors doesn't re-teach the LLM per database — the semantic
  layer is portable even when the storage engine isn't. That's a real argument for a multi-site
  research org specifically, worth its own sentence in any pitch, separate from the trust claim.
- **Evals close the loop.** `nlq/evals.clj` runs natural-language test cases against expected
  Datomic/SQL/result shapes. That's a real regression harness for "does the model still get this
  right," which matters a lot more once you're claiming trustworthiness as the differentiator —
  otherwise it's just an assertion.

## Who this is actually for (say the "no" out loud)

The differentiator only pays off when a column's stored value is a **code from a controlled
vocabulary** whose meaning isn't recoverable from the column name alone — RECIST, CTCAE, HGNC gene
symbols, Cell Ontology terms, MedDRA, that whole family. That's most of a clinical trial's
observation and variant data, which is why it matters here.

The honest qualifying question for a prospect: **"do your columns store codes whose meaning isn't
recoverable from the column name?"** If the answer is no — mostly free-text or self-describing
columns (`patient_age`, `city`) — the schema layer buys little and a commodity Text2SQL tool is
the right, cheaper answer. Saying that out loud is what makes the rest of the pitch credible;
a tool that claims to be better at everything is claiming to be better at nothing in particular.

## The obvious objection, answered

`umbra.alz.edn` is 112KB of hand-curated EDN. The skeptical read: that's real authoring labor, and
a bigger model plus live DB introspection is free by comparison — why pay the schema tax?

Answer: PICI didn't author this schema *for* the LLM. It already existed as the data dictionary,
descended from CANDEL, and it already generates the HTML schema docs the portal serves today. The
marginal cost of also pointing NLQ generation at it is close to zero — it's not new authoring
burden, it's leverage on a data asset the org already owns and maintains for other reasons. The
pitch isn't "author a schema to get better SQL," which is a hard sell against a free-feeling
alternative. It's "the schema you already have to maintain is worth more than you're using it
for" — which is a much easier sell to someone who's already staring at a schema file.

## Chosen framing: "The schema is the interface"

Positions against tools that treat the database as the interface and the schema as incidental
metadata (a comment, a naming convention) rather than the thing doing the work. Concretely, this
means leading pitches, docs, and demos with the schema and Alzabo, not with "ask a question in
plain English" — the NL box is the affordance, the schema is why the answer can be trusted.
Practical implications:

- Naming/branding should surface "Alzabo" and "schema" before "natural language" or "AI" —
  the model is swappable; the schema is the moat.
- Demos should show the schema file itself (or its generated HTML docs) alongside a query result,
  not just the query box — the point is that both come from the same source.
- The RECIST bug (see top) is the go-to demo of *why*: show the same query failing silently
  without the schema's code/gloss distinction, then succeeding with it.

Two framings considered and set aside, for later if this one doesn't land:
- "Trustworthy NLQ for coded data" — narrower and more honest, but buries the schema/Alzabo
  branding under a use-case description.
- "One schema, three surfaces" (query grounding / inspector / docs) — strong internally, but
  leads with an architecture claim rather than the schema itself.

# Competition

There must be scads /AskClaude fill this out

https://github.com/vanna-ai/vanna
https:/ /vanna.ai/
            
