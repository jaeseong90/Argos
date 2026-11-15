-- SPDX-License-Identifier: Apache-2.0
--
-- Instance metrics (14 §4.10, 13 §6.3 (10)): raw fragment of the 5 s samples of the named series,
-- per series and bucket: value sum and sample count (gauge average), min, max and the counter
-- increase (sum of the reset-corrected deltas).
-- @tables metric
SELECT name,
       toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
       any(kind) AS k,
       sum(value) AS sv,
       count() AS n,
       min(value) AS mn,
       max(value) AS mx,
       sum(delta) AS inc
FROM {db}.{p}metric
WHERE app_id = {app:UInt32} AND instance_id = {instance:UInt32} AND name IN {names:Array(String)}
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY name, b
