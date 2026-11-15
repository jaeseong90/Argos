-- SPDX-License-Identifier: Apache-2.0
--
-- Instance metrics (14 §4.10): hour fragment.
-- @tables metric_1h
SELECT name,
       toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       any(kind) AS k,
       sum(avg_sum) AS sv,
       sum(sample_count) AS n,
       min(min_v) AS mn,
       max(max_v) AS mx,
       sum(increase) AS inc
FROM {db}.{p}{rollup:metric_1h}
WHERE app_id = {app:UInt32} AND instance_id = {instance:UInt32} AND name IN {names:Array(String)}
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY name, b
