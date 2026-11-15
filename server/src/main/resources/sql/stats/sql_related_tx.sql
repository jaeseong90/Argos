-- SPDX-License-Identifier: Apache-2.0
--
-- The latest transactions that ran one SQL statement (14 §4.8 related_tx, 13 §6.3 (12)): xlog rows
-- whose sql_ids contain it (A-10, bloom idx_sql), newest first.
-- @tables xlog
SELECT ts,
       tx_id,
       service_id,
       instance_id,
       elapsed_ms,
       sql_count,
       sql_time_ms,
       error
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND has(sql_ids, {sql:UInt64})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
ORDER BY ts DESC, tx_id DESC
LIMIT {limit:UInt32}
