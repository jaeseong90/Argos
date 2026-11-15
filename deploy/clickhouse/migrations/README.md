# deploy/clickhouse/migrations

Multi-step ClickHouse procedures (`docs/13-storage-design.md` section 13.2, `docs/12-server-design.md` section 9.3):
changes that a single idempotent schema script cannot make, above all the replacement of a rollup table and its
materialized view in four steps. There is no procedure yet; the format below is implemented by the migration runner
(`io.argos.server.ops.migration`) and tested with fixtures (`server/src/integrationTest/resources/migration-fixtures`).

The build copies `schema/*.sql`, `rollups/*.sql` and `migrations/*.yaml` of `deploy/clickhouse` into the server jar
(`db/clickhouse/...`); this directory stays the single source. README files are not copied.

## Files

- Name `M<NNN>__<lower_snake>.yaml`, numbers contiguous from `001` (separate from the `V<NNN>` schema scripts).
- The SPDX header is a YAML comment on the first line.
- A released procedure never changes once a step of it is applied: the runner compares a checksum per step (below).

## Format

```yaml
# SPDX-License-Identifier: Apache-2.0
description: replace instance_stat_1m by instance_stat_1m_v2 (adds max_cpu_time_ms)
steps:
  - id: create_v2                  # [a-z][a-z0-9_]{0,15}, unique in the file
    kind: ddl
    statements:                    # one statement per element
      - CREATE TABLE IF NOT EXISTS {db}.{p}instance_stat_1m_v2 ( ... ) ENGINE = AggregatingMergeTree ...
      - CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}instance_stat_1m_v2_mv TO {db}.{p}instance_stat_1m_v2
        SQL SECURITY DEFINER AS {rollup:instance_stat_1m_v2}
  - id: backfill
    kind: backfill
    table: instance_stat_1m_v2     # rollup table; its SELECT is rollups/instance_stat_1m_v2.sql
    unit: day                      # day (default, 1m tables, source column ts) or month (1h tables, source column minute)
    days: "{retention.xlog-days}"  # the most recent days, today included: a number or {retention.<key>}
  - id: switch
    kind: switch
    alias: instance_stat_1m        # logical rollup name
    table: instance_stat_1m_v2     # physical table
  - id: drop_old
    kind: drop
    statements:
      - DROP VIEW IF EXISTS {db}.{p}instance_stat_1m_mv
      - DROP TABLE IF EXISTS {db}.{p}instance_stat_1m
```

| Kind | Keys | What the runner does |
|---|---|---|
| `ddl` | `statements` | Runs the statements in order. Same rules as the schema scripts: placeholders `{db}`, `{p}`, `{retention.<key>}`, `{rollup:<table>}` only; idempotent DDL (`CREATE ... IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `RENAME COLUMN IF EXISTS`); no `MODIFY ORDER BY`, no `MODIFY COLUMN` type change, `MODIFY TTL` only with `materialize_ttl_after_modify = 0`; no `RENAME TABLE`/`DATABASE`/`DICTIONARY` statement (it has no `IF EXISTS` form, so a failed step could never run again: a table is renamed with the four steps instead). No `DROP` statement (that is a `drop` step). |
| `backfill` | `table`, `unit`, `days` | Fills the rollup table from its source, one partition at a time, through a staging table `<table>_backfill` and `ALTER TABLE ... REPLACE PARTITION` (the rebuild procedure of ADR-0007 C-59): nothing is inserted into the rollup table itself. Only partitions with source rows in the day range are processed; a partition whose staging table is empty is left untouched. Each partition writes a progress row; a restarted backfill skips the partitions already done. |
| `switch` | `alias`, `table` | Checks that the table exists, then merges `{"<alias>": "<table>"}` into the JSON object of the PostgreSQL setting `rollup_alias` (`argos.settings`). The query layer (WO-23) reads a rollup through this map; a name without an entry is its own table. Names are without database and prefix. |
| `drop` | `statements` | Runs `DROP ... IF EXISTS` statements only. Nothing is ever dropped implicitly: the old objects disappear only when a procedure declares this step (usually in the next minor release, 13 section 13.2 step 4). |

Unknown keys, keys of another kind, missing keys, duplicate step ids and invalid YAML refuse the start.

## History and checksums (PostgreSQL `argos.ch_schema_history`)

| Version | Row |
|---|---|
| `M<NNN>.<step id>` | the step, written when it completes (failed attempts: `success = false` and the error; retried at the next start) |
| `M<NNN>.<step id>/<yyyyMMdd>` (`/<yyyyMM>` for months) | backfill progress of one partition, with its row count |
| `repair.<yyyyMMddHHmmss>` | a checksum repair (see below) |

The checksum of a step is the SHA-256 of its canonical text: id, kind, the statements after the `{rollup:}` expansion
and before the environment placeholders, table, alias, unit, days, and for a backfill the rollup SELECT (ADR-0007
C-56: another database, prefix or retention keeps the checksum). A changed checksum of an applied step refuses the
start, naming the version; `argos.clickhouse.migration.repair-checksums=true` for one start re-records the checksums
after a WARN per version instead (nothing is run again) and writes one `repair.*` row.

## Order and timing

At start, in the `CLICKHOUSE_MIGRATION` phase (before WAL recovery and before any listener, DSN-12-15): first the
pending schema scripts `schema/V<NNN>` in number order, then the pending procedure steps in number and file order.
A pending version before an applied one of the same kind refuses the start. Every statement runs with
`max_execution_time` = 10 minutes (12 section 9.3); each script and step is logged with its duration.

Scripts and procedures are numbered separately, so the two paths of 13 section 13.2 run them in a different order: a
new installation runs every `V` script before every `M` step, while an upgraded installation has already run the
procedures of earlier releases before the scripts of a later release. Both paths reach the same schema only under this
rule, which the runner checks when it reads the files (the start is refused, naming the script and the object):

- A `V` script does not reference (`{db}.{p}<name>` in SQL code) an object that a procedure creates (`CREATE` in a
  `ddl` step), backfills or switches to (`table` of a `backfill` or `switch` step), or drops (`drop` step).
- Exception: the first `V` script that creates an object a procedure later drops (the old table and view of a
  four-step replacement, usually created by `V001`).
- Example: a `V002` that alters `instance_stat_1m_v2` of `M001` runs after `M001` on an upgrade but before it, on a
  table that does not exist yet, on a new installation. A procedure's object is changed by a `ddl` step of a later
  procedure instead (`ADD COLUMN IF NOT EXISTS`); a dropped object is not referenced again.

The backfill runs while nothing is ingested (the WAL is replayed after the migration). A WAL block that was already
inserted before the stop but not committed in the WAL is replayed through the new materialized view and can be counted
twice in the new table; the nightly reconcile and rebuild (WO-27) repairs such a day.

## ClickHouse reset

When the ClickHouse database does not exist although `ch_schema_history` has rows (ClickHouse was replaced or its data
lost while PostgreSQL was restored, 13 section 10), the runner logs a WARN, ignores the history and applies every
script and procedure again (all statements are idempotent), rewriting the history rows.
