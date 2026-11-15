-- SPDX-License-Identifier: Apache-2.0
--
-- The two-day fallback of the tx_id lookup (ADR-0007 C-50, ADR-0006 decision 1 A-6, Q05
-- fallback_2d): the day of the restored time t0 and the day before, bloom index idx_tx. Used only
-- after the +-2 s window found nothing (a clock-corrected instance, ADR-0010 C-129 (6)). Never
-- widened further: a transaction outside these two days answers 404 clock_skew_possible (C-150).
-- app = 0 means no app hint. Columns as lookup.window.sql.
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
  AND toDate(ts) BETWEEN toDate({t0:DateTime64(3, 'UTC')}) - 1 AND toDate({t0:DateTime64(3, 'UTC')})
  AND tx_id = {tx:UInt64}
ORDER BY app_id, ts_ms
LIMIT 2
