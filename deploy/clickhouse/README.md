# deploy/clickhouse

ClickHouse schema, rollups and server/user configuration of Argos (design: `docs/13-storage-design.md`; the storage
decisions C-50 to C-71 of `docs/adr/0007-storage-alignment-decisions.md` take precedence where they differ).

| Path | Content |
|---|---|
| `image.properties` | The bundled ClickHouse: exact LTS version and multi-arch digest (26.8.34.13). Previous LTS for the upgrade test. |
| `schema/V001__init.sql` | Database, 5 raw tables, 12 rollup tables, 12 materialized views (template, see placeholders). |
| `schema/V002__ingest_columns.sql` | Columns appended for the ingest path (ADR-0010 C-144): `xlog.url_path`, `xlog.headers`, `profile_step.truncated`, `profile_step.total_steps`. |
| `schema/V003__instance_stat_1h.sql` | The hourly instance rollup `instance_stat_1h` and its view (ADR-0010 C-212 (1)). |
| `rollups/<table>.sql` | The SELECT of each rollup. The materialized view and the rollup rebuild use the same file (D-14). |
| `config.d/argos.xml` | Server settings (13 §7.2): memory, merges, default compression ZSTD(1), listeners (HTTP 8123 and native 9000 only), empty `remote_url_allow_hosts` (no outbound URL), a TTL or removal for every system log of the bundled image. |
| `users.d/argos.xml` | Profiles and the three accounts (13 §7.3, SEC-74, SEC-76, ADR-0007 C-60). |

### Mounting the configuration into the official image

Mount `config.d/argos.xml` and `users.d/argos.xml` as **single files** (read-only) at
`/etc/clickhouse-server/config.d/argos.xml` and `/etc/clickhouse-server/users.d/argos.xml`, as
`deploy/compose/compose.yaml` does (ADR-0010 C-195 (2)). Do not mount the `config.d/` or `users.d/`
directory: it hides the image's own `config.d/docker_related_config.xml`, which makes ClickHouse
listen on every interface of the container, so ClickHouse listens on 127.0.0.1 only and the server
cannot reach it (its migrations fail at start; measured in WO-40 with 26.8.34.13). The note in
`config.d/argos.xml` that `docker_related_config.xml` is merged after this file holds for the
single-file mount only. The password hashes reach the container as environment variables
(`env_file`, see "Substitution variables" below).

## Tables

| Kind | Tables | Partition | Retention (default) |
|---|---|---|---|
| raw | `xlog`, `profile_step`, `metric`, `sql_stat_raw`, `ext_stat_raw` | day | 30 / 7 / 14 / 14 / 14 days |
| 1m rollup | `app_stat_1m`, `service_stat_1m`, `instance_stat_1m`, `error_stat_1m`, `sql_stat_1m`, `ext_stat_1m`, `metric_1m` | day | 90 days |
| 1h rollup | `app_stat_1h`, `service_stat_1h`, `instance_stat_1h`, `sql_stat_1h`, `ext_stat_1h`, `metric_1h` | month | 365 days |

Views: `<rollup>_mv`. 1m views read the raw tables (`xlog` feeds app, service, instance and error), 1h views read
the 1m tables (cascade). `instance_stat_1h` (V003, ADR-0010 C-212 (1)) replaces "instance_stat_1m has no 1h table"
of docs/13 §4.7: it has the columns of `instance_stat_1m` with `hour` instead of `minute`, and its view cascades from
`instance_stat_1m`. An installation upgraded to V003 has no `instance_stat_1h` rows for the hours before the upgrade;
`argos-admin rollup rebuild --date <D>` fills them day by day (below). All views are `SQL SECURITY DEFINER` (definer: the migration account), so the writer
needs INSERT on the raw tables only.

Column names follow C-17 in every layer (ADR-0007 C-67): `xlog.dbc_wait_ms`, `xlog.timed_out`,
`xlog.app_version_id`, the rollup measure `sum_dbc_wait_ms` and the rollup dimension `app_version_id`
(`service_stat_1m`/`1h`, `error_stat_1m`). `xlog.flags` bits (C-69): 1 virtual thread, 2 leaked, 4 summarized; the
other bits are reserved. `profile_step.batch_size` and `profile_step.detail_enc` are part of V001 (C-70).

## Reading rollups

| Column | Type | Read with |
|---|---|---|
| counts, sums, max, min | `SimpleAggregateFunction(sum/max/min, ...)` | `sum`/`max`/`min` and `GROUP BY` |
| `q_elapsed` (app, service, instance) | `AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)` | `quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed)`: p50, p95, p99 |
| `buckets` (SQL, outgoing calls) | `AggregateFunction(sumForEach, Array(UInt64))` | `sumForEachMerge(buckets)`: the 15 latency buckets; their p95 is an estimate (C-14) |

Transaction percentiles are `quantilesDD` states with relative accuracy 0.01 (ADR-0007 C-53): 1m views write
`quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)`, 1h views `quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed)`.
Merging DD states adds bucket counts, so a merged value does not depend on the merge order or the server version,
and a rebuild reproduces the stored states byte for byte (D-14). ClickHouse 26.8 does not support
`SimpleAggregateFunction(sumForEach)`, hence the `AggregateFunction` bucket column.

## Compression (ADR-0007 C-54)

- Server default: `ZSTD(1)` for every part (`<compression>` in `config.d/argos.xml`).
- Column codecs end in `ZSTD(1)`: `DoubleDelta, ZSTD(1)` (timestamps), `T64, ZSTD(1)` (durations, counters),
  `Gorilla, ZSTD(1)` (metric values), `Delta, ZSTD(1)` (`xlog.tx_id`, time-ordered Snowflake ids).
- `ZSTD(3)` for the text columns `xlog.user_id`, `xlog.request_id`, `profile_step.detail`.
- `CODEC(NONE)` for `xlog.span_id` only (random bytes). `xlog.trace_id` and `xlog.parent_span_id` use the default.
- Bloom filter skip indexes of `xlog` (C-55): `bloom_filter(0.01)`, `GRANULARITY 4`, except `idx_user`
  (`GRANULARITY 1`).

## Placeholders (rendered by the migration runner)

Allowed directives (13 §13.2, ADR-0007 C-56): `{db}`, `{p}`, `{where}`, `{retention.*}` and `{rollup:<table>}`. Any
other placeholder is an error.

| Placeholder | Where | Value |
|---|---|---|
| `{db}`, `{p}` | schema, rollups | database (`argos`) and table prefix (empty) |
| `{retention.<key>}` | schema | days: `xlog-days` 30, `profile-days` 7, `raw-days` 14, `rollup-1m-days` 90, `rollup-1h-days` 365 |
| `{rollup:<table>}` | schema | include directive: the SELECT of `rollups/<table>.sql` without comments and with an empty `{where}` |
| `{where}` | rollups | `''` (view) or `AND (<predicate>)` (rebuild). It follows a WHERE clause; the predicate uses `ts` for raw sources and `minute` for 1m sources. A 1m rebuild selects one day (`toDate(ts) = D`, the 1m partition); a 1h rebuild selects one month of its 1m source (`toYYYYMM(minute) = M`), because 1h tables are partitioned by month. See "Rollup rebuild" below |

Placeholders in comments are not rendered. Every statement is idempotent (`IF NOT EXISTS`): applying V001 twice
is a no-op.

Schema checksum (`ch_schema_history`, C-56): the SHA-256 (lower-case hex) of the UTF-8 text of a migration script
after the `{rollup:<table>}` expansion and before the environment placeholders `{db}`, `{p}` and `{retention.*}` are
substituted (line endings normalized to LF). Another database name, prefix or retention keeps the checksum; a
changed script or a changed rollup SELECT changes it.

The reference renderer and lint are in `test/storage/clickhouse-support` (`SchemaRenderer.expand`/`checksum`/`render`,
`SchemaLint`, `SchemaApplier`, `RollupRebuild`).

## Rollup rebuild (D-14, ADR-0007 C-59)

A rebuild replaces whole partitions through a staging table; it never inserts into a rollup table:

1. 1m table, day D: `CREATE TABLE <t>_rebuild AS <t>`, `INSERT INTO <t>_rebuild <rollup SELECT with {where} = AND (toDate(ts) = D)>`,
   `ALTER TABLE <t> REPLACE PARTITION D FROM <t>_rebuild`, drop the staging table.
2. Then the 1h table of the same month M, from its 1m source, the same way with `{where} = AND (toYYYYMM(minute) = M)`
   and `REPLACE PARTITION M`. `REPLACE PARTITION` does not feed the views, so step 1 leaves the 1h table as it was.

Inserting the rebuild SELECT directly into a rollup table counts twice: the 1h views cascade from the 1m tables,
so every row inserted into a 1m table is added to its 1h table again (even after `DROP PARTITION` of the 1m day).
A rebuild covers whole partitions (all apps): a staging table that holds one app would remove the other apps of the
partition, so there is no per-application rebuild (C-59 (c)). `RollupRebuildIT` (test/storage/schema-tests) checks the procedure,
the double count and the byte identity of the rebuilt `quantilesDD` states.

The server runs the procedure with the migration account (`io.argos.server.ops.rollup.RollupRebuilder`, the nightly
consistency check and the operator command):

```
java -jar argos-server-<version>.jar admin rollup rebuild --date 2026-11-20 [--table sql_stat_1m]... [--json]
```

It reads the server configuration like the server (packaged application.yml, the operator's configuration file,
`ARGOS_*` environment; `--argos.<key>=<value>` arguments too), opens only ClickHouse (`argos.clickhouse.migrator.*`)
and PostgreSQL (`argos.postgres.*`: the rollup lock, the applied schema versions and `settings.rollup_alias`, which
maps a logical `--table` name to its current table), and starts no listener, so it can run next to a running server. Without `--table` it
rebuilds every 1m rollup for day D and every 1h rollup for the month of D; `--table` names 1m or 1h rollups, and a
1m table brings its 1h cascade along (C-59 (b)). Before and after each table it computes a digest of the partition:
the number of keys and the sum of one `cityHash64` per key over the key columns, the merged
`SimpleAggregateFunction` values and the hex of the merged aggregate states (`quantilesDDMergeState`,
`sumForEachMergeState`). `anyLast` columns (`metric_*.last_v`) are left out, because their merged value depends on
the order in which parts are read. Equal digests mean the stored aggregates were already what the raw data gives
(D-14); a different digest means the rebuild repaired the partition. Output is a table (or JSON with `--json`) on
standard output, logs go to standard error; exit code 0 when every table was rebuilt, 1 on a failure (the failed table
keeps its old partition, earlier tables keep their new ones), 2 for invalid arguments. A day must lie inside the
retention of every source it reads (`xlog-days` for transaction rollups, `raw-days` for SQL, outgoing call and metric
rollups), and the month of a 1h rebuild inside `rollup-1m-days`; otherwise the command refuses to run, because the
rebuilt partition would lose the expired rows. It also refuses to run while a bundled schema script is not applied
(start the server of the same version first). Two rebuilds must not overlap (they share the staging table
`<t>_rebuild`): the command holds the PostgreSQL advisory lock `AdminDatabase.ROLLUP_LOCK_KEY` and exits with 1 when
another rebuild holds it. Rows that arrive while today's day or the current month is being rebuilt can miss the swap;
the nightly consistency check finds and rebuilds such partitions. Float sums (`metric_*.avg_sum`, `increase`) are
compared bit for bit: a merge order that rounds differently shows as "changed" with the same key count.

## Substitution variables (environment of clickhouse-server)

No value has a default: ClickHouse refuses to start when one is missing.

| Variable | Meaning | 16 GiB host |
|---|---|---|
| `ARGOS_CH_MAX_SERVER_MEMORY_USAGE` | server memory limit, bytes (RAM x 0.47). In a container: at most 0.9 x the container memory limit (ClickHouse caps a larger value there). | 8053063680 |
| `ARGOS_CH_READER_MAX_MEMORY_USAGE` | memory per read query (0.4 x server) | 3221225472 |
| `ARGOS_CH_WRITER_MAX_MEMORY_USAGE` | memory per insert (4/15 x server) | 2147483648 |
| `ARGOS_CH_WRITER_PASSWORD_SHA256`, `ARGOS_CH_READER_PASSWORD_SHA256`, `ARGOS_CH_MIGRATOR_PASSWORD_SHA256` | SHA-256 (hex) of the generated passwords | generated at install |

The Docker image needs `CLICKHOUSE_SKIP_USER_SETUP=1`. `background_schedule_pool_size` keeps the LTS default
(ADR-0007 C-71).

## Accounts

| Account | Rights | Used by |
|---|---|---|
| `argos_writer` | INSERT on the five raw tables only: `argos.xlog`, `argos.profile_step`, `argos.metric`, `argos.sql_stat_raw`, `argos.ext_stat_raw` (ADR-0007 C-60, SEC-76); an INSERT into a rollup table is refused. async_insert off; deduplication on. Constraints: `async_insert`, `insert_deduplicate`, `deduplicate_blocks_in_dependent_materialized_views`, `log_queries`, `readonly`, `allow_ddl` cannot be changed (the profile value may be passed again), `max_insert_threads` stays 1, `max_memory_usage` may only be lowered | batch writer |
| `argos_reader` | SELECT on `argos.*` and the storage health system tables; readonly; limits (10 s, `ARGOS_CH_READER_MAX_MEMORY_USAGE`, 4 threads, 2e9 rows, 20 GiB, 200,000 result rows) that a query may lower but not raise. With `result_overflow_mode = break` a result can exceed `max_result_rows` by up to one block (65,409 rows), so hard caps need `LIMIT` | query API |
| `argos_migrator` | CREATE DATABASE, CREATE TABLE, CREATE VIEW, DROP DATABASE, DROP TABLE, DROP VIEW, ALTER, INSERT, SELECT, BACKUP on `argos.*` (no dictionary, function or named collection DDL), and SELECT on `system.mutations` (C-60: the deletion job polls its own mutations). BACKUP alone is not sufficient on 26.8: BACKUP TO Disk/S3 also needs WRITE ON DISK / WRITE ON S3 and a backups.allowed_disk entry; fixed with tests in the backup work order (docs/17) | migrations, rollup rebuild, deletion mutations and their completion, emergency DROP PARTITION |
| `default` | removed | - |

The grants name the default database `argos` and the default empty table prefix. An installation with another
database or prefix (DSN-13-11) writes the same grants with its names (`<db>.*`, and the five raw tables
`<db>.<prefix>xlog` ... for the writer); the installer renders them (WO-40).

## Insert contract (batch writer)

```
INSERT INTO argos.<raw table> (<columns>)
SETTINGS insert_deduplication_token = '<wal_instance_id>:<epoch>:<first>-<last>',
         deduplicate_blocks_in_dependent_materialized_views = 1
FORMAT RowBinaryWithNamesAndTypes
```

The token (ADR-0007 C-61) is a deterministic value recorded in the WAL BLOCK_INTENT record before the insert: the
WAL instance id, the WAL epoch (incremented when the WAL is re-initialized) and the positions of the first and last
data record of the block. The writer never judges a duplicate by `X-ClickHouse-Summary.written_rows`: ClickHouse
reports the rows of a deduplicated retry as written too.

Over the HTTP interface the same settings may be passed as query parameters instead (the way of
`ClickHouseInserter` in test/storage/generator, which also sends `async_insert=0` and `wait_end_of_query=1`).

A retry with the same token is a no-op for the raw table and for every view that already received the block;
views that failed receive it. Each table remembers its last 1,000 blocks (`non_replicated_deduplication_window`,
the 1h tables included). Without a token, a block identical to a recent one is dropped.
