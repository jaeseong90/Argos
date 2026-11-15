-- SPDX-License-Identifier: Apache-2.0
--
-- One service per instance (14 §4.8 services/{serviceId} by_instance). The rollups have no service
-- x instance dimension, so this reads raw rows of the raw window (the last 24 h of the range at
-- most, inside the xlog retention).
-- @tables xlog
SELECT instance_id AS i,
       count() AS cnt,
       countIf(error = 1) AS err,
       sum(elapsed_ms) AS sum_ms,
       quantilesDD(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY i
ORDER BY cnt DESC, i
LIMIT 100
