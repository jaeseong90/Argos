-- SPDX-License-Identifier: Apache-2.0
--
-- The transactions of one trace (14 §4.7, 13 §6.3 (6), Q06): every application, the window chosen
-- by the API (the id's time +-1 h for Argos-generated ids, ?ts +-1 h, or the window parameter,
-- 24 h by default), bloom index idx_trace. LIMIT is the API cap (500) + 1.
-- @tables xlog
SELECT app_id,
       env,
       instance_id,
       tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       span_id,
       parent_span_id,
       incoming_trace,
       caller_app_id,
       service_id,
       app_version_id,
       elapsed_ms,
       error,
       http_status,
       profile_captured,
       kind
FROM {db}.{p}xlog
WHERE ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND trace_id = toFixedString(unhex({trace:String}), 16)
ORDER BY ts_ms, tx_id
LIMIT {limit:UInt32}
