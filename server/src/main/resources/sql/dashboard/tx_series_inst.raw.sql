-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard transaction series of selected instances (14 §4.4 instance[]): raw fragment.
-- @tables xlog
SELECT toStartOfInterval(toDateTime(ts), toIntervalSecond({step_seconds:UInt32})) AS b,
       count() AS c,
       countIf(error = 1) AS e,
       sum(elapsed_ms) AS s,
       sum(ext_time_ms) AS x,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND instance_id IN {instances:Array(UInt32)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY b
