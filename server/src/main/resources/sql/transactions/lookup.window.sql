-- SPDX-License-Identifier: Apache-2.0
--
-- One transaction by tx_id inside a time window (14 §4.6, 13 §6.3 (5), ADR-0007 C-50, Q05 restored /
-- url_ts): the window is ?ts +-2 s, or the time restored from tx_id ((tx_id >> 23) + 2026-01-01)
-- +-2 s, computed by the API. app = 0 means no app hint (partition and sort key narrow enough).
-- LIMIT 2: two rows are a tx_id collision (13 §17 #11), returned with a warning.
-- @tables xlog
SELECT app_id,
       env,
       instance_id,
       tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       kind,
       lower(hex(trace_id)) AS trace_hex,
       span_id,
       parent_span_id,
       incoming_trace,
       caller_app_id,
       service_id,
       app_version_id,
       http_method,
       http_status,
       elapsed_ms,
       error,
       error_class_id,
       error_group_id,
       toString(remote_ip) AS remote_ip_text,
       ip_mode,
       user_id,
       user_id_mode,
       request_id,
       sql_count,
       sql_time_ms,
       dbc_wait_ms,
       ext_count,
       ext_time_ms,
       cpu_time_ms,
       alloc_bytes,
       thread_name_id,
       profile_captured,
       streaming,
       timed_out,
       dispatch_count,
       late,
       flags,
       url_path,
       headers
FROM {db}.{p}xlog
WHERE ({app:UInt32} = 0 OR app_id = {app:UInt32})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts <= {to:DateTime64(3, 'UTC')}
  AND tx_id = {tx:UInt64}
ORDER BY app_id, ts_ms
LIMIT 2
