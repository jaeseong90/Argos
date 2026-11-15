-- SPDX-License-Identifier: Apache-2.0
--
-- Transaction search total (14 §4.6, §2.5 meta.total): the transactions that match the filters of
-- POST /apps/{appId}/transactions/search, without the page cursor. Same filters as search.*.sql:
-- empty arrays, 0, -1 and '' mean "no filter"; the indexed filters (user_id, request_id, sql_ids,
-- trace_id; bloom indexes of 13 §3.1) keep their skip indexes in this form (constant folding).
-- user_id matches plain-mode rows only (ADR-0010 C-152); remote_ip matches a full address exactly
-- and a masked one by its masked form (/24, IPv6 /48; 14 §4.6).
-- @tables xlog
SELECT count() AS total
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
  AND ({uid:String} = '' OR (user_id_mode = 'plain' AND user_id = {uid:String}))
  AND ({rid:String} = '' OR request_id = {rid:String})
  AND ({sql:UInt64} = 0 OR has(sql_ids, {sql:UInt64}))
  AND (length({versions:Array(UInt64)}) = 0 OR app_version_id IN {versions:Array(UInt64)})
  AND ({has_profile:Int8} < 0 OR profile_captured = {has_profile:Int8})
  AND ({trace:String} = '' OR trace_id = toFixedString(unhex({trace:String}), 16))
