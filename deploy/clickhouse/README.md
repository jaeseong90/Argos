# deploy/clickhouse

ClickHouse schema, rollups and server/user configuration of Argos (design: `docs/13-storage-design.md`).

| Path | Content |
|---|---|
| `image.properties` | The bundled ClickHouse: exact LTS version and multi-arch digest (26.8.34.13). Previous LTS for the upgrade test. |
| `schema/V001__init.sql` | Database, 5 raw tables, 12 rollup tables, 12 materialized views (template, see placeholders). |
| `rollups/<table>.sql` | The SELECT of each rollup. The materialized view and the rollup rebuild use the same file (D-14). |
| `config.d/argos.xml` | Server settings (13 §7.2): memory, merges, listeners (HTTP 8123 and native 9000 only), empty `remote_url_allow_hosts` (no outbound URL), a TTL or removal for every system log of the bundled image. |
| `users.d/argos.xml` | Profiles and the three accounts (13 §7.3, SEC-74, SEC-76). |

## Tables

| Kind | Tables | Partition | Retention (default) |
|---|---|---|---|
| raw | `xlog`, `profile_step`, `metric`, `sql_stat_raw`, `ext_stat_raw` | day | 30 / 7 / 14 / 14 / 14 days |
| 1m rollup | `app_stat_1m`, `service_stat_1m`, `instance_stat_1m`, `error_stat_1m`, `sql_stat_1m`, `ext_stat_1m`, `metric_1m` | day | 90 days |
| 1h rollup | `app_stat_1h`, `service_stat_1h`, `sql_stat_1h`, `ext_stat_1h`, `metric_1h` | month | 365 days |

Views: `<rollup>_mv`. 1m views read the raw tables (`xlog` feeds app, service, instance and error), 1h views read
the 1m tables (cascade). All views are `SQL SECURITY DEFINER` (definer: the migration account), so the writer
needs INSERT only.

Reading rollups: `SimpleAggregateFunction` columns with `sum`/`max`/`min` and `GROUP BY`; `q_elapsed` with
`quantilesTDigestMerge(0.5, 0.95, 0.99)`; `buckets` (SQL and outgoing-call latency histograms) with
`sumForEachMerge(buckets)` (ClickHouse 26.8 does not support `SimpleAggregateFunction(sumForEach)`).

## Placeholders (rendered by the migration runner)

| Placeholder | Where | Value |
|---|---|---|
| `{db}`, `{p}` | schema, rollups | database (`argos`) and table prefix (empty) |
| `{retention.<key>}` | schema | days: `xlog-days` 30, `profile-days` 7, `raw-days` 14, `rollup-1m-days` 90, `rollup-1h-days` 365 |
| `{bloom.<index>.fpp}`, `{bloom.<index>.granularity}` | schema | xlog skip indexes `tx`, `trace`, `user`, `request`, `sql`; default 0.01 / 4 until the PoC fixes them |
| `{rollup:<table>}` | schema | the SELECT of `rollups/<table>.sql` with an empty `{where}` |
| `{where}` | rollups | `''` (view) or `AND (<predicate>)` (rebuild). It follows a WHERE clause; the predicate uses `ts` for raw sources and `minute` for 1m sources. A 1m rebuild selects one day (`toDate(ts) = D`, the 1m partition); a 1h rebuild selects one month of its 1m source (`toYYYYMM(minute) = M`), because 1h tables are partitioned by month. See "Rollup rebuild" below |

Placeholders in comments are not rendered. Every statement is idempotent (`IF NOT EXISTS`): applying V001 twice
is a no-op. The reference renderer and lint are in `test/storage/clickhouse-support` (`SchemaRenderer`,
`SchemaLint`, `SchemaApplier`).

## Rollup rebuild (D-14)

A rebuild replaces whole partitions through a staging table; it never inserts into a rollup table:

1. 1m table, day D: `CREATE TABLE <t>_rebuild AS <t>`, `INSERT INTO <t>_rebuild <rollup SELECT with {where} = AND (toDate(ts) = D)>`,
   `ALTER TABLE <t> REPLACE PARTITION D FROM <t>_rebuild`, drop the staging table.
2. Then the 1h table of the same month M, from its 1m source, the same way with `{where} = AND (toYYYYMM(minute) = M)`
   and `REPLACE PARTITION M`. `REPLACE PARTITION` does not feed the views, so step 1 leaves the 1h table as it was.

Inserting the rebuild SELECT directly into a rollup table counts twice: the 1h views cascade from the 1m tables,
so every row inserted into a 1m table is added to its 1h table again (even after `DROP PARTITION` of the 1m day).
A rebuild covers whole partitions (all apps): a staging table that holds one app would remove the other apps of the
partition. `RollupRebuildIT` (test/storage/schema-tests) checks both the procedure and the double count.

## Substitution variables (environment of clickhouse-server)

No value has a default: ClickHouse refuses to start when one is missing.

| Variable | Meaning | 16 GiB host |
|---|---|---|
| `ARGOS_CH_MAX_SERVER_MEMORY_USAGE` | server memory limit, bytes (RAM x 0.47). In a container: at most 0.9 x the container memory limit (ClickHouse caps a larger value there). | 8053063680 |
| `ARGOS_CH_READER_MAX_MEMORY_USAGE` | memory per read query (0.4 x server) | 3221225472 |
| `ARGOS_CH_WRITER_MAX_MEMORY_USAGE` | memory per insert (4/15 x server) | 2147483648 |
| `ARGOS_CH_WRITER_PASSWORD_SHA256`, `ARGOS_CH_READER_PASSWORD_SHA256`, `ARGOS_CH_MIGRATOR_PASSWORD_SHA256` | SHA-256 (hex) of the generated passwords | generated at install |

The Docker image needs `CLICKHOUSE_SKIP_USER_SETUP=1`.

## Accounts

| Account | Rights | Used by |
|---|---|---|
| `argos_writer` | INSERT on `argos.*`; async_insert off; deduplication on. Constraints: `async_insert`, `insert_deduplicate`, `deduplicate_blocks_in_dependent_materialized_views`, `log_queries`, `readonly`, `allow_ddl` cannot be changed (the profile value may be passed again), `max_insert_threads` stays 1, `max_memory_usage` may only be lowered | batch writer |
| `argos_reader` | SELECT on `argos.*` and the storage health system tables; readonly; limits (10 s, `ARGOS_CH_READER_MAX_MEMORY_USAGE`, 4 threads, 2e9 rows, 20 GiB, 200,000 result rows) that a query may lower but not raise. With `result_overflow_mode = break` a result can exceed `max_result_rows` by up to one block (65,409 rows), so hard caps need `LIMIT` | query API |
| `argos_migrator` | CREATE DATABASE, CREATE TABLE, CREATE VIEW, DROP DATABASE, DROP TABLE, DROP VIEW, ALTER, INSERT, SELECT, BACKUP on `argos.*` (no dictionary, function or named collection DDL). BACKUP alone is not sufficient on 26.8: BACKUP TO Disk/S3 also needs WRITE ON DISK / WRITE ON S3 and a backups.allowed_disk entry; fixed with tests in the backup work order (docs/17) | migrations, rollup rebuild, deletion mutations, emergency DROP PARTITION |
| `default` | removed | - |

## Insert contract (batch writer)

```
INSERT INTO argos.<table> (<columns>)
SETTINGS insert_deduplication_token = 'argos:<table>:<block_id>',
         deduplicate_blocks_in_dependent_materialized_views = 1
FORMAT RowBinaryWithNamesAndTypes
```

Over the HTTP interface the same settings may be passed as query parameters instead (the way of
`ClickHouseInserter` in test/storage/generator, which also sends `async_insert=0` and `wait_end_of_query=1`).

A retry with the same token is a no-op for the raw table and for every view that already received the block;
views that failed receive it. Each table remembers its last 1,000 blocks (`non_replicated_deduplication_window`).
Without a token, a block identical to a recent one is dropped.
