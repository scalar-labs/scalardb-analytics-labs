# Quickstart runbook

This runbook takes the CLI distribution from a fresh build to cross-source queries. Docker Compose
starts every database locally with sample data:

| Service | Role | Host port | Contents |
| --- | --- | --- | --- |
| `catalog-store` | Physical Catalog store (PostgreSQL) | `15432` | Empty; the workspace creates its catalog here |
| `warehouse` | Data source (PostgreSQL) | `25432` | `public.orders`: eight orders |
| `crm` | Data source (MySQL) | `13306` | `crm.customers`: five customers in three regions |

The passwords in `compose.yaml` and below are example values for this disposable environment. Each
source grants the CLI a read-only account.

## Prerequisites

- Java 25 or later, selected through `JAVA_HOME` or the `java` command on `PATH`
- Docker with Docker Compose
- What the repository build needs; see [Run a query](../../README.md#run-a-query)

Ports `15432`, `25432`, and `13306` must be free on `127.0.0.1`.

## 1. Build the distribution

From the repository root:

```console
./gradlew installDist
```

The task writes the distribution, unarchived, to
`application/build/install/scalardb-analytics-<platform>/` for the build host platform. The rest
of the runbook runs in this directory and calls the launcher through `CLI`:

```console
cd examples/quickstart
CLI=$(echo ../../application/build/install/scalardb-analytics-*/bin/scalardb-analytics)
"$CLI" version
```

```json
{"schema":"scalardb-analytics/version","schemaVersion":"1","productVersion":"0.1.0-SNAPSHOT"}
```

To use the archive from `./gradlew distTar` instead, unpack it anywhere and set `CLI` to its
`bin/scalardb-analytics`.

## 2. Start the databases

```console
docker compose up -d --wait
```

The command returns once every database has loaded its sample data and accepts connections.

## 3. Initialize a workspace

A workspace binds a directory to one Physical Catalog catalog. It records the store connection and
the name of the environment variable that holds the store password, never the password itself:

```console
export CATALOG_PASSWORD=catalog-password
"$CLI" workspace init \
  --workspace ./workspace \
  --catalog quickstart \
  --store-jdbc-url jdbc:postgresql://127.0.0.1:15432/catalog \
  --store-user catalog \
  --store-password-env CATALOG_PASSWORD
```

Every later command needs `CATALOG_PASSWORD` in its environment.

## 4. Register the sources

Pass a source password through an environment variable:

```console
export WAREHOUSE_PASSWORD=warehouse-reader-password
"$CLI" source register --workspace ./workspace \
  --name warehouse --type postgresql \
  --host 127.0.0.1 --port 25432 --database warehouse \
  --user reader --password-env WAREHOUSE_PASSWORD
```

Or through standard input:

```console
printf '%s\n' crm-reader-password | "$CLI" source register --workspace ./workspace \
  --name crm --type mysql \
  --host 127.0.0.1 --port 13306 --database crm \
  --user reader --password-stdin
```

The catalog stores the source credentials, so later commands need only `CATALOG_PASSWORD`. Check
the registrations, which never print passwords:

```console
"$CLI" source list --workspace ./workspace
"$CLI" source show --workspace ./workspace crm
```

## 5. Discover physical metadata

```console
"$CLI" metadata list --workspace ./workspace --source crm
```

The output lists each namespace and table with its columns. Queries address a table as
`<source>.<namespace>.<table>`: here `warehouse.public.orders` and `crm.crm.customers`, because a
MySQL database is its namespace.

## 6. Query

Pass SQL as an argument:

```console
"$CLI" query --workspace ./workspace \
  --sql 'SELECT id, ordered_on, status, amount FROM warehouse.public.orders ORDER BY id LIMIT 3'
```

```json
{"schema":"scalardb-analytics/query-result","schemaVersion":"3","executionId":"...","fields":[{"name":"id","type":"Int(32, true)",...},{"name":"ordered_on","type":"Date(DAY)",...},{"name":"status","type":"Utf8",...},{"name":"amount","type":"Int(64, true)",...}],"rows":[[1001,20697,"shipped",1200],[1002,20699,"shipped",450],[1003,20706,"cancelled",300]],"truncated":false}
```

`fields` carries the Arrow type of each column. A `Date(DAY)` value is the number of days since
1970-01-01.

From a file, here joining both sources:

```console
"$CLI" query --workspace ./workspace --file queries/revenue-by-region.sql
```

```json
{...,"rows":[["APAC",2,3400],["EMEA",3,3150]],"truncated":false}
```

From standard input:

```console
echo "SELECT name, region FROM crm.crm.customers WHERE region = 'EMEA' ORDER BY name" |
  "$CLI" query --workspace ./workspace --stdin
```

`--time-limit-seconds` bounds a query, and `Ctrl-C` cancels it.

## 7. Expected failures

An operational failure exits with code `1` and writes a classified error to standard output.
Messages do not repeat the SQL or credentials:

| Try | `code` |
| --- | --- |
| `--sql 'DELETE FROM warehouse.public.orders'` | `INVALID_QUERY`: only a single read-only `SELECT` is allowed |
| `--sql 'SELECT * FROM warehouse.public.missing'` | `EXECUTION_FAILED` |
| `--time-limit-seconds 1 --sql 'SELECT sum(i * random()) FROM range(100000000000) t(i)'` | `RESOURCE_LIMIT_EXCEEDED` |
| Any command without `CATALOG_PASSWORD` set | `MISSING_CREDENTIAL` |
| `--workspace ./nowhere` | `INVALID_WORKSPACE` |

```json
{"schema":"scalardb-analytics/error","schemaVersion":"3","executionId":"...","code":"INVALID_QUERY","message":"Only a single read-only SELECT query is allowed."}
```

Invalid command usage exits with code `2`.

## 8. Hand the workspace to a Query Agent

The distribution packages Query Agent Skill v0 at `skills/query-agent-v0/SKILL.md`. Give it to the
agent together with the workspace path and the environment variables above. It covers workspace
selection, source and metadata discovery, and query execution.

## 9. Clean up

```console
docker compose down -v
rm -rf workspace
```

`-v` also deletes the databases' data volumes. A later `docker compose up` starts from the sample
data again.

The catalog lives in the store, not in `./workspace`. While the databases keep running, including
after `docker compose stop` and `start`, removing only `./workspace` and running `workspace init`
with the same `--catalog` fails with `ALREADY_EXISTS`.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `requires Java 25 or later` or `no 'java' command` | Set `JAVA_HOME` to a Java 25 or later installation |
| `docker compose up` reports a port in use | Stop what uses the port, or change the host port in `compose.yaml` and the commands |
| `ALREADY_EXISTS` from `workspace init` | The store already has that catalog. Use another `--catalog`, or reset the databases with `docker compose down -v` and `docker compose up -d --wait` |
| `WORKSPACE_ALREADY_EXISTS` | Remove `./workspace` or choose another directory |
| `DATA_SOURCE_UNREACHABLE` from `source register` | Check that the containers are healthy (`docker compose ps`) and the password is right |
