# test/storage/clickhouse-support

Development-only library for the storage PoC and its tests. Starts a ClickHouse server configured like an
Argos installation and talks to it over HTTP without a ClickHouse client dependency.

```java
try (ArgosClickHouse ch = ArgosClickHouse.builder().memoryLimit(5 * MemoryBudget.GIB).start()) {
  SchemaApplier.apply(ch.client(Account.MIGRATOR), SchemaParameters.defaults());
  ch.client(Account.WRITER).insert(
      "INSERT INTO argos.xlog (app_id, env, instance_id, ts, tx_id) FORMAT TabSeparated",
      rows,
      Map.of("insert_deduplication_token", walId + ":" + epoch + ":" + first + "-" + last));
  long n = ch.client(Account.READER).scalarLong("SELECT count() FROM argos.xlog");
}
```

| Class | Purpose |
|---|---|
| `ArgosClickHouse` | Testcontainers launcher: pinned image (`deploy/clickhouse/image.properties`), `config.d`/`users.d` of the repository, generated passwords (only their SHA-256 reaches the container), memory limit and the matching substitution variables, optional named data volume, test-only extra config files; the HTTP port is published on 127.0.0.1 only when the Docker daemon is local (`httpPublication()`). `writerTablePrefixes(...)` adds the writer grants of other table prefixes in a test-only users.d file (users.d/argos.xml grants INSERT on the five raw tables of the default prefix only, ADR-0007 C-60) |
| `ClickHouseHttp` | HTTP client per account: `execute`, `insert` (text or binary body), `rows` (TabSeparated), `scalar`, `queryBytes`, `failure`; settings as URL parameters; `Response.summaryValue("read_rows")` |
| `MemoryBudget` | values of the RAM substitution variables for a container limit |
| `SchemaRenderer` | renders `schema/V*.sql` and `rollups/*.sql` with `SchemaParameters` (database, prefix, retention; only `{db}`, `{p}`, `{where}`, `{retention.*}`, `{rollup:<table>}` are allowed, ADR-0007 C-56); `expand` (the `{rollup:}` expansion with the environment placeholders kept) and `checksum` (SHA-256 of `expand`, the `ch_schema_history` value); `rebuildInsert`; `withoutColumnCodecs` (codec-off variant) |
| `RollupRebuild` | the rebuild procedure of ADR-0007 C-59 as statements: staging table `<t>_rebuild`, `REPLACE PARTITION` of the whole day (1m) and then of its month (1h) |
| `SchemaLint`, `SchemaApplier` | idempotency rules of the migration runner; lint then apply as `argos_migrator` |

The Testcontainers resource reaper (Ryuk) image is pinned by digest in `src/main/resources/testcontainers.properties`
(`ryuk.container.image=testcontainers/ryuk:0.14.0@sha256:...`): every JVM with this library on its classpath (the
storage tests and the storage benchmark tools, the only Testcontainers users of the repository) runs exactly that
image. Ryuk still publishes its control port on all interfaces of the Docker host: Testcontainers has no setting for
the host address of that port (known limitation, docs/poc/clickhouse-benchmark.md chapter 17).

Docker is required. The first Docker access of every JVM (`DockerStartup`) holds the lock file
`build/tmp/argos-testcontainers-startup.lock` of the repository and, with a named-pipe Docker host (Docker
Desktop on Windows), waits until the pipe can be opened: Testcontainers checks the pipe with `Files.exists`,
which fails while another process holds every pipe instance, and then gives up for the whole JVM. The
storage integration test tasks start in parallel, so without the lock one of them failed every root
`./gradlew integrationTest` run on Windows. Use a table prefix (`SchemaParameters.withTablePrefix`) to keep several schemas in one
server; the accounts are granted on the database `argos` only.
