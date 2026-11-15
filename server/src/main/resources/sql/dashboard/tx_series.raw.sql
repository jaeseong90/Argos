-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard transaction series (14 §4.4, 13 §6.3 (1)): raw fragment of one application, partial
-- aggregates per bucket (count, errors, elapsed and outgoing-call sums, DD state, C-53).
-- @tables xlog
SELECT toStartOfInterval(toDateTime(ts), toIntervalSecond({step_seconds:UInt32})) AS b,
       count() AS c,
       countIf(error = 1) AS e,
       sum(elapsed_ms) AS s,
       sum(ext_time_ms) AS x,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY b
