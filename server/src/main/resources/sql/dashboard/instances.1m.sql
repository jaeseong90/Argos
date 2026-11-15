-- SPDX-License-Identifier: Apache-2.0
--
-- Per-instance transaction totals (14 §4.4, 14 §12 open item 1): minute fragment of the instance
-- rollup (13 §4.7).
-- @tables instance_stat_1m
SELECT instance_id AS i,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_elapsed_ms) AS s,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:instance_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY i
