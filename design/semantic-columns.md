Design for semantic columns

A pretty natural and obvious extension to what is there now. 

# Result columns mapped to semantic types

We know what types result columns are, from looking at Alzabo schema. I think, columns might get renamed, we could fix prompt though.

Cirro DDL also has descriptions which could be leveraged,

Slightly non-obvious since SQL is kind of arbitary. I wonder of Cirro results have column info natively?
Not column info, but column name suffices since we encode the table/kind into those. Won't work on arbitary SQL tables though. 


# Display them appropriately
I like Enflame's color-coding, not sure that is appropriate here but why not. Or mini-icons.

## Come up with small icons for relevant types
Subject, sample, gene...others might be harder. 

# Link them
eg if you click on a gene or sample name, something reasonable should happen (see object inspector)

# Object inspector ?

Yes but maybe think in repl mode

# Think ahead to links to knowledge graph (for reference data like genes, but not for samples etc)
Can use links to https://www.genecards.org/card/TP53 or whatever for now.

# Column headers 
Should have hover help based on Alzabo descriptions

# Cell values
For enumerated vals at least, have hover description





