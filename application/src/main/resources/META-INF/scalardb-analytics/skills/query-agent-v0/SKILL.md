---
name: scalardb-analytics-query-agent
version: 0
description: Query registered PostgreSQL and MySQL sources through the ScalarDB Analytics CLI.
---

# ScalarDB Analytics Query Agent v0

Use this skill only for the standalone query path. It covers workspace selection, registered
source discovery, physical metadata discovery, and read-only SQL execution. It does not cover a
Semantic Catalog, knowledge construction, feedback, or catalog snapshots.

## Working contract

- Select an initialized workspace directory supplied by the user or operator. Do not inspect or
  modify `workspace.json`; pass the directory through `--workspace`.
- Discover sources before writing SQL:

  ```console
  scalardb-analytics source list --workspace <dir>
  scalardb-analytics source show --workspace <dir> <source>
  scalardb-analytics metadata list --workspace <dir> --source <source>
  ```

- Address a table as `source.namespace.table`. Use only names returned by source and metadata
  discovery.
- Submit exactly one read-only `SELECT` using one input mode:

  ```console
  scalardb-analytics query --workspace <dir> --sql '<sql>'
  scalardb-analytics query --workspace <dir> --file <utf8-file>
  scalardb-analytics query --workspace <dir> --stdin
  ```

  Add `--time-limit-seconds <positive-seconds>` when the query must be bounded by time.
- Read the versioned JSON object from stdout. Exit code `0` is success, `1` is a classified
  operational error, and `2` is invalid command usage. Do not parse progress text or native
  diagnostics.

## Operator-only setup

An operator initializes a workspace and registers sources. Password values must come from the
named environment or stdin; never place one in an argument or file.

```console
scalardb-analytics workspace init \
  --workspace <dir> --catalog <name> \
  --store-jdbc-url <jdbc-url> --store-user <user> \
  --store-password-env <ENV_NAME>

scalardb-analytics source register \
  --workspace <dir> --name <source> --type postgresql \
  --host <host> --port <port> --database <database> --user <user> \
  --password-env <ENV_NAME>

printf '%s\n' "$SOURCE_PASSWORD" | scalardb-analytics source register \
  --workspace <dir> --name <source> --type mysql \
  --host <host> --port <port> --database <database> --user <user> \
  --password-stdin
```

MySQL may omit `--database`; PostgreSQL requires it. Source list, source show, metadata, query
results, and errors never contain passwords.
