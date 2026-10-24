# test/storage/schema-tests

Tests of `deploy/clickhouse` against a real ClickHouse (Testcontainers, the bundled image).

```
./gradlew :test:storage:schema-tests:test              # no Docker: credential literals, variables, config values
./gradlew :test:storage:schema-tests:integrationTest   # Docker: about 2 minutes, 51 tests
```

| Class | Covers |
|---|---|
| `SchemaApplyIT` | V001 on an empty server, second apply is a no-op, non-idempotent script refused (T-ST-052) |
| `RollupViewsIT` | every rollup equals a direct aggregation of the raw rows, 1h cascade, Q12/Q13 at small scale, late rows; the rebuild SELECT of all 12 rollup files (1m: one day, 1h: one month) equals its view per key, except the merge-order dependent tDigest `q_elapsed` and `anyLast` `last_v` (D-03, D-12, D-14) |
| `RollupRebuildIT` | rebuild procedure: 1m day and 1h month through staging + `REPLACE PARTITION` repair a damaged day and keep the other day; the in-place `INSERT INTO <rollup>` of 13 §4.1 doubles the day in the 1h cascade (D-14, F-S22) |
| `DeduplicationTokenIT` | same token 100 times, failed view filled by the retry, 1h tables need their own window, midnight block, token identity, window size (D-13, T-ST-032) |
| `AccountsIT`, `ConfigFailFastIT` | default user removed (merged users.xml holds exactly the three accounts), least privilege, writer settings constraints, migrator without dictionary/function DDL, empty `remote_url_allow_hosts`, listeners 8123/9000 only, test port on 127.0.0.1, reader limits and `system.query_log` (SEC-74, SEC-75, SEC-76, T-ST-053), missing variables stop the server |
| `SystemLogsIT` | every `*_log` section of the merged server config and every created system log table has a TTL (13 §7.2, N-19) |
| `ConfirmationItemsIT`, `UpgradeIT`, `ReplicatedConversionIT` | confirmation items of docs/13 §17 on the bundled version, previous LTS upgrade (T-ST-051) |

Measured values are appended to `build/reports/clickhouse-schema/*.md`.
