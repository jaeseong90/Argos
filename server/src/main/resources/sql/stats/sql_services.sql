-- SPDX-License-Identifier: Apache-2.0
--
-- The services that ran one SQL statement (14 §4.8 sql/{sqlId} by_service): sql_stat has no service
-- dimension, so the transactions whose xlog.sql_ids contain it (A-10) are counted per service in
-- the raw window. total = all those transactions (the denominator of the share).
-- @tables xlog
SELECT service_id AS sv,
       count() AS tx,
       sum(count()) OVER () AS total
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND has(sql_ids, {sql:UInt64})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY sv
ORDER BY tx DESC, sv
LIMIT 100
