-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard transaction series (14 §4.4, 13 §6.3 (2)): minute fragment of one application.
-- @tables app_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_elapsed_ms) AS s,
       sum(sum_ext_time_ms) AS x,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:app_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
