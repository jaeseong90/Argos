-- SPDX-License-Identifier: Apache-2.0
--
-- Q15 Duplicate re-insert (13 section 5, D-13, 13 section 15 Q15, T-ST-032). Procedure
-- DedupReinsert, with the writer path of 13 section 7.4 (argos_writer, explicit columns, RowBinary,
-- insert_deduplication_token = 'argos:<table>:<block_id>'):
--   1. one block per raw table (xlog, profile_step, metric, sql_stat_raw, ext_stat_raw) inside the
--      loaded time range, inserted once -> snapshot S1
--   2. the same blocks with the same tokens 100 times -> snapshot equals S1 (raw and every rollup
--      increase 0)
--   3. one view broken (its target service_stat_1m refuses rows: CHECK 0), a new xlog block -> the
--      insert fails; xlog has the block, service_stat_1m does not
--   4. view repaired, the same xlog block with the same token -> xlog unchanged, service_stat_1m and
--      its 1h cascade filled, every other rollup unchanged; Q12 window check 0 rows
--   5. the same block 100 more times -> unchanged
-- Criterion: raw and all rollups increase 0 on re-insert (increase), the broken view and its 1h
-- cascade get the block on the retry (broken_view_filled), and the Q12 window check finds no
-- mismatch afterwards (window_mismatches).
--
-- @id        Q15
-- @title     Duplicate re-insert: same block_id 100x, one view broken -> repaired -> re-insert
-- @source    docs/13 §5, §15 Q15; D-13; T-ST-032
-- @kind      procedure
-- @account   migrator
-- @criterion increase == 0, broken_view_filled == 1, window_mismatches == 0
-- @settings  max_execution_time=600

-- @step snapshot
-- Totals of every raw and rollup table in the window (rollups: the sum of their count, which does
-- not depend on background merges).
SELECT 'xlog' AS t, toFloat64(count()) AS v FROM {db}.{p}xlog WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
UNION ALL SELECT 'profile_step', count() FROM {db}.{p}profile_step WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
UNION ALL SELECT 'metric', count() FROM {db}.{p}metric WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
UNION ALL SELECT 'sql_stat_raw', count() FROM {db}.{p}sql_stat_raw WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
UNION ALL SELECT 'ext_stat_raw', count() FROM {db}.{p}ext_stat_raw WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
UNION ALL SELECT 'app_stat_1m', sum(count) FROM {db}.{p}app_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'app_stat_1h', sum(count) FROM {db}.{p}app_stat_1h WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime}
UNION ALL SELECT 'service_stat_1m', sum(count) FROM {db}.{p}service_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'service_stat_1h', sum(count) FROM {db}.{p}service_stat_1h WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime}
UNION ALL SELECT 'instance_stat_1m', sum(count) FROM {db}.{p}instance_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'error_stat_1m', sum(count) FROM {db}.{p}error_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'sql_stat_1m', sum(count) FROM {db}.{p}sql_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'sql_stat_1h', sum(count) FROM {db}.{p}sql_stat_1h WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime}
UNION ALL SELECT 'ext_stat_1m', sum(count) FROM {db}.{p}ext_stat_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'ext_stat_1h', sum(count) FROM {db}.{p}ext_stat_1h WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime}
UNION ALL SELECT 'metric_1m', sum(sample_count) FROM {db}.{p}metric_1m WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
UNION ALL SELECT 'metric_1h', sum(sample_count) FROM {db}.{p}metric_1h WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime}

-- @step break_view
-- An MV failure in the middle of the chain: the target of service_stat_1m_mv refuses every row.
ALTER TABLE {db}.{p}service_stat_1m ADD CONSTRAINT IF NOT EXISTS q15_broken CHECK 0

-- @step repair_view
ALTER TABLE {db}.{p}service_stat_1m DROP CONSTRAINT IF EXISTS q15_broken
