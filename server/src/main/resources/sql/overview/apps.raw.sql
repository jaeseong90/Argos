-- SPDX-License-Identifier: Apache-2.0
--
-- Overview (14 §4.2, 13 §6.2): raw fragment for the live tail of the last minutes, per application
-- and minute bucket.
-- @tables xlog
SELECT app_id AS a,
       toStartOfInterval(toDateTime(ts), toIntervalSecond({step_seconds:UInt32})) AS b,
       count() AS c,
       countIf(error = 1) AS e,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id IN {apps:Array(UInt32)} AND kind IN {kinds:Array(String)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY a, b
