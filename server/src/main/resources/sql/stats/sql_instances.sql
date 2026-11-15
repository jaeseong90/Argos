-- SPDX-License-Identifier: Apache-2.0
--
-- One SQL statement per instance (14 §4.8 sql/{sqlId} by_instance): only the raw statistics carry
-- instance_id, so this reads sql_stat_raw in the raw window (the last 24 h of the range at most).
-- @tables sql_stat_raw
SELECT instance_id AS i,
       sum(count) AS cnt,
       sum(error_count) AS err,
       sum(sum_ms) AS sum_ms,
       max(max_ms) AS max_ms
FROM {db}.{p}sql_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND sql_id = {sql:UInt64}
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY i
ORDER BY sum_ms DESC, i
LIMIT 100
