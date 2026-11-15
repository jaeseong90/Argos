-- SPDX-License-Identifier: Apache-2.0
--
-- The latest transactions of an error group (14 §4.8 recent_tx, 13 §6.3 (9)): xlog.error_group_id
-- in the raw window, newest first.
-- @tables xlog
SELECT ts,
       tx_id,
       service_id,
       instance_id,
       app_version_id,
       elapsed_ms,
       http_status
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND error_group_id = {group:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
ORDER BY ts DESC, tx_id DESC
LIMIT {limit:UInt32}
