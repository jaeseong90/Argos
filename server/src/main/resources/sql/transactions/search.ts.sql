-- SPDX-License-Identifier: Apache-2.0
--
-- Transaction search, sort ts:desc (14 §4.6, 13 §6.3 (11), (12); Q04 user/request variants): the
-- filters of count.sql, then the keyset of the page cursor (ts, tx_id) below the last row of the
-- previous page (the first page passes the largest values). LIMIT is the page size + 1.
-- @tables xlog
SELECT tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       service_id,
       elapsed_ms,
       http_status,
       error,
       error_class_id,
       instance_id,
       sql_count,
       sql_time_ms,
       ext_count,
       ext_time_ms,
       user_id,
       user_id_mode,
       profile_captured,
       kind,
       app_version_id
FROM {db}.{p}xlog
PREWHERE app_id = {app:UInt32}
     AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
     AND elapsed_ms >= {min_ms:UInt32} AND elapsed_ms <= {max_ms:UInt32}
WHERE kind IN {kinds:Array(String)}
  AND (length({services:Array(UInt64)}) = 0 OR service_id IN {services:Array(UInt64)})
  AND (length({instances:Array(UInt32)}) = 0 OR instance_id IN {instances:Array(UInt32)})
  AND (length({statuses:Array(UInt16)}) = 0 OR http_status IN {statuses:Array(UInt16)})
  AND ({error:Int8} < 0 OR error = {error:Int8})
  AND (length({classes:Array(UInt64)}) = 0 OR error_class_id IN {classes:Array(UInt64)})
  AND ({group:UInt64} = 0 OR error_group_id = {group:UInt64})
  AND ({ip_filter:UInt8} = 0
       OR (ip_mode = 'full' AND remote_ip = {ip:IPv6})
       OR (ip_mode = 'masked' AND remote_ip = {ip_masked:IPv6}))
  AND ({uid:String} = ''
       OR (user_id_mode = 'plain' AND user_id = {uid:String})
       OR ({uid_hash:String} != '' AND user_id_mode = 'hashed' AND user_id = {uid_hash:String}))
  AND ({rid:String} = '' OR request_id = {rid:String})
  AND ({sql:UInt64} = 0 OR has(sql_ids, {sql:UInt64}))
  AND (length({versions:Array(UInt64)}) = 0 OR app_version_id IN {versions:Array(UInt64)})
  AND ({has_profile:Int8} < 0 OR profile_captured = {has_profile:Int8})
  AND ({trace:String} = '' OR trace_id = toFixedString(unhex({trace:String}), 16))
  AND (ts, tx_id) < ({c_ts:DateTime64(3, 'UTC')}, {c_tx:UInt64})
ORDER BY ts DESC, tx_id DESC
LIMIT {limit:UInt32}
