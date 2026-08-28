# needs a better name


# where should this go
Started as a branch to OKC, but more appropriate for NLFlame or Way
Solution: merge those two and make it the platform, call it Way, why not. 

# goals
- platform for data-backed apps (Incorporate Way goals)
- flexible component based, dataflow architecture
- generalized configs that can be extracted, saved, reconstituted

# non-goals
- complere end-user configurability 


* use case
- natual language query and datavizualization
eg extend what nlq already does, and nlflame started to compenentize. 
Once you have this, a nl repl for data science, do you need any other UI?
The trick here is specifying the formal language to be generated


# way to publish dataviz
- eg Patient's configured to something reasonable by site
- should hide the controls, but also allow user to go back to controls and customize
- basically library feature from enflame    
- could be done in stages (eg tools for me as dev to make something and put it somewhere, users can come later)
- need a general configuration language

# Language
Need to design a configuration language that can encompass the various tools here:
- vega and vega lite dvs, parameterized and wrapped in re-frame
- various controls, 
- filters (with data types, field selector, etc)
- encoders (map a data field to a visual attribute typically)
- table display as well as vega, 
  and this should include column specs to some degree of flexibility
- actions, eg from clicking a vega object or region, or selecting rows in a table
- dataflow language, see 
