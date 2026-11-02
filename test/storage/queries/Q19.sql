-- SPDX-License-Identifier: Apache-2.0
--
-- Q19 Migration rehearsal on the full data (13 section 13.2, N-22, T-ST-052; 13 section 15 Q19).
-- Procedure MigrationRehearsal times each step as argos_migrator:
--   add_column / drop_column   ADD COLUMN on xlog (metadata only) and its rollback
--   ttl_keep_0                 MODIFY TTL with the current retention, materialize_ttl_after_modify
--                              = 0 (the runner rule of 13.2): metadata only
--   ttl_noop_1                 no-op control: the same expression with materialize_ttl_after_modify
--                              = 1; ClickHouse sees an unchanged TTL and recalculates nothing, so
--                              this step does not measure a TTL change
--   ttl_change_1               the retention really changes (xlog-days + 1, so no row expires) with
--                              materialize_ttl_after_modify = 1, waited with mutations_sync = 2:
--                              the TTL of every part is recalculated (13.2 cost of a retention
--                              change that is materialized)
--   ttl_restore_0              back to the configured retention with materialize_ttl_after_modify
--                              = 0 (metadata only; time recorded, not judged)
--   MV replacement, 4 steps for service_stat_1m (the DDL comes from deploy/clickhouse: the V001
--   table statement renamed to service_stat_1m_v2 and the rollups/service_stat_1m.sql SELECT):
--     (1) create service_stat_1m_v2 and its view, (2) backfill one day with the rebuild procedure
--     of ADR-0007 C-59 (staging table + REPLACE PARTITION, D-14), (3) switch check: v1 and v2
--     agree on that day (mv_compare, 0 rows),
--     (4) drop the rehearsal objects (in a real migration the old view and table go one minor
--     release later)
-- Criterion of 13 section 15: each step within 10 minutes.
--
-- @id        Q19
-- @title     Migration rehearsal: ADD COLUMN, MODIFY TTL (materialize 0/1, real change), MV replacement 4 steps
-- @source    docs/13 §13.2, §15 Q19; N-22; T-ST-052
-- @kind      procedure
-- @account   migrator
-- @criterion step_ms <= 600000, mv_mismatches == 0
-- @settings  max_execution_time=3600, join_use_nulls=0

-- @step add_column
ALTER TABLE {db}.{p}xlog ADD COLUMN IF NOT EXISTS q19_rehearsal UInt8 DEFAULT 0

-- @step drop_column
ALTER TABLE {db}.{p}xlog DROP COLUMN IF EXISTS q19_rehearsal

-- @step ttl_keep_0
ALTER TABLE {db}.{p}xlog MODIFY TTL toDateTime(ts) + INTERVAL {retention.xlog-days} DAY
SETTINGS materialize_ttl_after_modify = 0

-- @step ttl_noop_1
-- No-op control: the expression is unchanged, so nothing is recalculated.
-- @settings mutations_sync=2
ALTER TABLE {db}.{p}xlog MODIFY TTL toDateTime(ts) + INTERVAL {retention.xlog-days} DAY
SETTINGS materialize_ttl_after_modify = 1

-- @step ttl_change_1
-- One more day of retention: no row expires, every part's TTL info is recalculated.
-- @settings mutations_sync=2
ALTER TABLE {db}.{p}xlog MODIFY TTL toDateTime(ts) + INTERVAL ({retention.xlog-days} + 1) DAY
SETTINGS materialize_ttl_after_modify = 1

-- @step ttl_restore_0
ALTER TABLE {db}.{p}xlog MODIFY TTL toDateTime(ts) + INTERVAL {retention.xlog-days} DAY
SETTINGS materialize_ttl_after_modify = 0

-- @step xlog_size
-- Rows and parts of xlog when the steps run (the size the times belong to).
-- @account reader
SELECT sum(rows) AS rows, count() AS parts, sum(bytes_on_disk) AS bytes
FROM system.parts
WHERE database = {database:String} AND table = {table:String} AND active

-- @step mv_compare
-- Step (3): the backfilled day of service_stat_1m_v2 equals service_stat_1m per service and minute
-- (C-65 form: UNION ALL, GROUP BY the key, sumIf, HAVING).
SELECT app_id, kind, service_id, app_version_id, minute,
       sumIf(c, src = 'v1') AS v1, sumIf(c, src = 'v2') AS v2,
       sumIf(e, src = 'v1') AS e1, sumIf(e, src = 'v2') AS e2
FROM
(
    SELECT 'v1' AS src, app_id, kind, service_id, app_version_id, minute, sum(count) AS c, sum(sum_elapsed_ms) AS e
    FROM {db}.{p}service_stat_1m WHERE toDate(minute) = {day:Date}
    GROUP BY app_id, kind, service_id, app_version_id, minute
    UNION ALL
    SELECT 'v2', app_id, kind, service_id, app_version_id, minute, sum(count), sum(sum_elapsed_ms)
    FROM {db}.{p}service_stat_1m_v2 WHERE toDate(minute) = {day:Date}
    GROUP BY app_id, kind, service_id, app_version_id, minute
)
GROUP BY app_id, kind, service_id, app_version_id, minute
HAVING v1 != v2 OR e1 != e2
LIMIT 1000

-- @step mv_drop_view
DROP VIEW IF EXISTS {db}.{p}service_stat_1m_v2_mv

-- @step mv_drop_table
DROP TABLE IF EXISTS {db}.{p}service_stat_1m_v2
