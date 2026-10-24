# test/storage/clickhouse-support

Development-only library for the storage PoC and its tests. Starts a ClickHouse server configured like an
Argos installation and talks to it over HTTP without a ClickHouse client dependency.

```java
try (ArgosClickHouse ch = ArgosClickHouse.builder().memoryLimit(5 * MemoryBudget.GIB).start()) {
  SchemaApplier.apply(ch.client(Account.MIGRATOR), SchemaParameters.defaults());
  ch.client(Account.WRITER).insert(
      "INSERT INTO argos.xlog (app_id, env, instance_id, ts, tx_id) FORMAT TabSeparated",
      rows,
      Map.of("insert_deduplication_token", "argos:xlog:" + blockId));
  long n = ch.client(Account.READER).scalarLong("SELECT count() FROM argos.xlog");
}
```

| Class | Purpose |
|---|---|
| `ArgosClickHouse` | Testcontainers launcher: pinned image (`deploy/clickhouse/image.properties`), `config.d`/`users.d` of the repository, generated passwords (only their SHA-256 reaches the container), memory limit and the matching substitution variables, optional named data volume, test-only extra config files; the HTTP port is published on 127.0.0.1 only when the Docker daemon is local (`httpPublication()`) |
| `ClickHouseHttp` | HTTP client per account: `execute`, `insert` (text or binary body), `rows` (TabSeparated), `scalar`, `queryBytes`, `failure`; settings as URL parameters; `Response.summaryValue("read_rows")` |
| `MemoryBudget` | values of the RAM substitution variables for a container limit |
| `SchemaRenderer` | renders `schema/V*.sql` and `rollups/*.sql` with `SchemaParameters` (database, prefix, retention, bloom parameters); `rebuildInsert`; `withoutColumnCodecs` (codec-off variant) |
| `SchemaLint`, `SchemaApplier` | idempotency rules of the migration runner; lint then apply as `argos_migrator` |

Docker is required. The first Docker access of every JVM (`DockerStartup`) holds the lock file
`build/tmp/argos-testcontainers-startup.lock` of the repository and, with a named-pipe Docker host (Docker
Desktop on Windows), waits until the pipe can be opened: Testcontainers checks the pipe with `Files.exists`,
which fails while another process holds every pipe instance, and then gives up for the whole JVM. The
storage integration test tasks start in parallel, so without the lock one of them failed every root
`./gradlew integrationTest` run on Windows. Use a table prefix (`SchemaParameters.withTablePrefix`) to keep several schemas in one
server; the accounts are granted on the database `argos` only.
