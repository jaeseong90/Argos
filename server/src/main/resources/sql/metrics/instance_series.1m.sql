-- SPDX-License-Identifier: Apache-2.0
--
-- Instance metrics (14 §4.10): minute fragment.
-- @tables metric_1m
SELECT name,
       toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       any(kind) AS k,
       sum(avg_sum) AS sv,
       sum(sample_count) AS n,
       min(min_v) AS mn,
       max(max_v) AS mx,
       sum(increase) AS inc
FROM {db}.{p}{rollup:metric_1m}
WHERE app_id = {app:UInt32} AND instance_id = {instance:UInt32} AND name IN {names:Array(String)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY name, b
