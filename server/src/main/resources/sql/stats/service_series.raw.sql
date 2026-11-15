-- SPDX-License-Identifier: Apache-2.0
--
-- One service's series (14 §4.8 services/{serviceId}): raw fragment per bucket.
-- @tables xlog
SELECT toStartOfInterval(toDateTime(ts), toIntervalSecond({step_seconds:UInt32})) AS b,
       count() AS c,
       countIf(error = 1) AS e,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY b
