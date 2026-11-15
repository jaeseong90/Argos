-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call errors of the dashboard (14 §4.4 ext_errors): raw fragment of the 10 s statistics.
-- @tables ext_stat_raw
SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f
FROM {db}.{p}ext_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY b
