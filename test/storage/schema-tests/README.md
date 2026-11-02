# test/storage/schema-tests

Tests of `deploy/clickhouse` against a real ClickHouse (Testcontainers, the bundled image).

```
./gradlew :test:storage:schema-tests:test              # no Docker: credential literals, variables, config values, grants, no fixed row dates
./gradlew :test:storage:schema-tests:integrationTest   # Docker: about 1 to 2 minutes, 53 tests
./gradlew :test:storage:schema-tests:integrationTest -Pschema.anchorOffsetDays=12   # same, rows 12 days back
```

| Class | Covers |
|---|---|
| `SchemaApplyIT` | V001 on an empty server (codecs of C-54 with no LZ4 column, bloom literals of C-55, `quantilesDD` states of C-53, C-67 names), the server default compression is ZSTD(1), second apply is a no-op, non-idempotent script refused (T-ST-052) |
| `RollupViewsIT` | every rollup equals a direct aggregation of the raw rows, 1h cascade, the 1m and 1h `quantilesDD` states equal the DD state of the raw values byte for byte, Q12 (C-65 form, also with `join_use_nulls = 1`) and Q13 at small scale, late rows; the rebuild SELECT of all 12 rollup files (1m: one day, 1h: one month) equals its view per key, `q_elapsed` state bytes included, except the read-order dependent `anyLast` `last_v` (D-03, D-12, D-14) |
| `RollupRebuildIT` | rebuild procedure of ADR-0007 C-59: 1m day and 1h month through staging + `REPLACE PARTITION` repair a damaged day (state bytes included) and keep the other day; the in-place `INSERT INTO <rollup>` of 13 §4.1 doubles the day in the 1h cascade (negative control, D-14, F-S22) |
| `DeduplicationTokenIT` | same token 100 times, failed view filled by the retry, 1h tables need their own window, midnight block, token identity, window size (D-13, T-ST-032); tokens in the C-61 form `<wal_instance_id>:<epoch>:<first>-<last>` |
| `AccountsIT`, `ConfigFailFastIT` | default user removed (merged users.xml holds exactly the three accounts), least privilege, the writer inserts into the five raw tables only and is refused on every rollup table (C-60), writer settings constraints, migrator without dictionary/function DDL and with `system.mutations` (C-60), empty `remote_url_allow_hosts`, listeners 8123/9000 only, test port on 127.0.0.1, reader limits and `system.query_log` (SEC-74, SEC-75, SEC-76, T-ST-053), missing variables stop the server |
| `SystemLogsIT` | every `*_log` section of the merged server config and every created system log table has a TTL (13 §7.2, N-19) |
| `ConfirmationItemsIT`, `UpgradeIT`, `ReplicatedConversionIT` | confirmation items of docs/13 §17 on the bundled version (deletion mutations polled by the migrator, C-60), previous LTS upgrade (T-ST-051: stored `quantilesDD` states identical and merged values independent of the version) |

The shared server (`Fixture`) grants the writer the raw tables of the test prefixes listed in `Fixture.PREFIXES`
(`ArgosClickHouse.writerTablePrefixes`), as an installation with that prefix would; a test class with a new prefix
adds it there. `DeployConfigTest` checks the repository files themselves (writer grants = the five raw tables of
the default prefix).

## Row times: the anchor day

The tests apply V001 with the production retention (`SchemaParameters.defaults()`: xlog 30 days, raw statistics
and metrics 14, profile 7). ClickHouse 26.8 drops rows older than a table's TTL at insert time
(`ttl_only_drop_parts = 1`) while the materialized views still receive them, so rows written at a fixed date make
every raw-versus-rollup assertion fail once the date leaves the retention. Row times are therefore relative to
one anchor day, `Workload.anchorDay()` = today (UTC) minus 2 days, fixed for the JVM. The tests keep their
shapes around it: 23:40 to 00:10 across midnight, 09:58 to 10:02, the three days from 10:00 two days before
the anchor day (deletion mutation), the late minute 23:41. When the anchor day is the last day of a month the
midnight windows also cross the monthly 1h partition; the 1h rebuild covers the month of the anchor day.

- `-Pschema.anchorOffsetDays=<n>` (system property `argos.test.anchorOffsetDays`) moves the anchor day to today
  minus n days. Valid: 2 (the latest rows, 00:10 of the next day, must not be in the future) to 12 (raw rows start
  at 00:00 of the anchor day; 14 days of raw retention less one day of margin). 2, 6 and 12 are green.
- Guard: `Workload.generate` and `Workload.requireRetained` (called before every direct insert into a raw
  table) throw `IllegalArgumentException` when a row time is in the future or older than the table's retention
  less one day. With n = 13 every raw-table workload of the anchor day that starts earlier than the current time
  of day fails there (7 of 53 at 16:45 UTC), with the table, the range and the retention in the message, instead
  of in a count assertion.
- `FixedDatesTest` (unit test, no Docker) fails on any `yyyy-MM-dd` literal in `src/integrationTest` unless the
  line says `fixed-date-ok: <reason>` (today only the tx_id epoch of `Workload`).
- The anchor day of a run is recorded in `build/reports/clickhouse-schema/workload.md`.

Measured values are appended to `build/reports/clickhouse-schema/*.md`.
