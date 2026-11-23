-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard transaction series of selected instances (14 §4.4 instance[], exclude_instance): hour
-- fragment from the instance hour rollup (ADR-0010 C-212 (1), a cascade of instance_stat_1m).
-- Filter as in dashboard/tx_series_inst.raw.
-- @tables instance_stat_1h
SELECT toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_elapsed_ms) AS s,
       sum(sum_ext_time_ms) AS x,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:instance_stat_1h}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY b
