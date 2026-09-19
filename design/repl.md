# First thoughts

I should flush the static UI layout in NLQ for a repl/LLM/notebook style chat interface.

Wouldn't be hard but I feel it puts me in direct competition with the whole rest of the AI world. 

But it would work a lot better so I shouldn't hesitate. Also makes sense from a framework perspective

Can enable much more flexible use (eg: say you want to do different queries and make a scatterplot combining them — no way to even start to do that in current system)

For Cirro, might make more sense to integrate it into their existing Notebook frame (ask MZ)

## Major UI question

If there's just one REPL, how do different queries get dispatched (eg SQL vs Vega generation, but a more general question).

Answer:: The REPL has an agent selector, you can choose who you are talking to (or have a dispatcher). This selector can be smart in various ways, it should feel really easy to 
choose "who" you are talking to. 

Hm, that is one way to distinguish it from a REPL which has an implicit single-agent listener. Hm. Yes this is getting into LiveWorld territory

I like this because it starts to get into agent-management territory.


## Implementation

### UI

It would probably make sense to use some existing REPL/notebook like UI component, as long as I can fully customize it. Maybe make some suggestions and evaluate.

Or I could just do it myself, easy enough

I would love for this to replace Logseq BUT also might be done with Logseq as UI (bleah?.

### Persitance/namiing strategy

Every block should 
- have a unique id
- be persisted by default
- be linkable. 

Easy enough but important to get upfront I think. Also

- be typed in some fashion.

	- built in low-level datatypes (text, tabular, vega etc)
	- functionalites like query and databases

- be TRACEABLE
  this one is less obvious but just as importamnt. Need to know a block's provenance (eg user or reply from LLM)
  
  
- be nameable

Eg for pages, except need a better naming scheme than Logseq

Maps of names to blocks could be first class objects

#   Other goals

Should ideally be able to act as chat UI for Claude etc (no more or less than their native chat UI).

Should somehow subsume both logseq and any other random collection of text

Imagine this could be a general UI for any kind of agent system (assuming it was open and monitorable, which is probably not the case).

# Further thoughts

After making a separate visualization pane in AACT

Yes this is even more obviously in need of a repl. And one where you can wire a panel into another.

Should just do it. I guess I'm resiting because too obvious?

In Logseq, always worrying about place, is this the right place for this thought. You kind of do it in Claude to with their project architecture. 

A single universal REPL (or multiple with different contexts, either way).

# Not exactly a REPL issue but

It is stupid (or at least, unnecessary) to have separate SQL and dataviz prompts. It's the same thing, and that way if you need to add a field to a visualization, you don't have to redo the query.

But not sure how unification should work. I imagine a prompt would still usually be in two parts, data spec and viz spec. but it could be done more simply, and all the data reqs could be in the dv.

Hm. The model is, you ask questions of the data in text, and it generates a dv if appropriate or if asked. 

Doesn't Claude Science do this? I bet it does, I'm afraid to look.
