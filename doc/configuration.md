# General configuration

# Logging

DynamoDB is the current preferred method, BigQuery is also supported.

To configure:
- create a DynamoDB table with uuid as the partition key
- set the config, something like:

```
{:type :dynamo
:table "nlq-log"
:region "us-west-2"
}
```

- set `AWS_ACCESS_KEY_ID` and  `AWS_SECRET_ACCESS_KEY` in env vrs

