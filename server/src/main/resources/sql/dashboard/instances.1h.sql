-- SPDX-License-Identifier: Apache-2.0
--
-- Per-instance transaction totals: hour fragment of the instance hour rollup (ADR-0010 C-212 (1)).
-- Filter as in dashboard/instances.raw.
-- @tables instance_stat_1h
SELECT instance_id AS i,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_elapsed_ms) AS s,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:instance_stat_1h}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY i
