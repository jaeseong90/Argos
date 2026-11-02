-- SPDX-License-Identifier: Apache-2.0
--
-- Q4 Drag search (13 section 6.3 (4)): one hour x an elapsed range, optionally with a service or an
-- error filter, 1,000 rows ordered by elapsed. PREWHERE narrows app_id, ts and elapsed first.
-- Criterion of 13 section 15: 1 s.
--
-- The same search endpoint (POST /apps/{id}/transactions/search, 13 section 6.2) also searches by
-- user id and request id (13 section 6.3 (11), bloom indexes idx_user and idx_request). These are
-- not in the Q1..Q20 list; they are measured here against N-24 (1 hour range 3 s), and the 24 hour
-- maximum range is recorded.
--
-- @id        Q04
-- @title     Drag search 1h x elapsed range (+ service / error filter), 1,000 rows; user/request id
-- @source    docs/13 §6.3 (4), (11), §15 Q4; N-24
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_result_rows=1000

-- @probe search_values
-- Three busy services of the last hour, a user id and a request id seen in the last hour.
SELECT
    (SELECT groupArray(service_id) FROM
        (SELECT service_id FROM {db}.{p}xlog
         WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 1 HOUR AND ts < {end_minute:DateTime}
         GROUP BY service_id ORDER BY count() DESC, service_id LIMIT 3))                 AS services,
    (SELECT any(user_id) FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 30 MINUTE
          AND ts < {end_minute:DateTime} AND user_id != '')                               AS uid,
    (SELECT any(request_id) FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 30 MINUTE
          AND ts < {end_minute:DateTime} AND request_id != '')                            AS rid

-- @variant range ymin=500 ymax=5000
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, http_status, error_class_id, instance_id,
       sql_count, sql_time_ms, ext_count, ext_time_ms, user_id, user_id_mode, profile_captured, kind, app_version_id
FROM {db}.{p}xlog
PREWHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 1 HOUR AND ts < {end_minute:DateTime}
     AND elapsed_ms BETWEEN {ymin:UInt32} AND {ymax:UInt32}
WHERE kind = 'HTTP'
ORDER BY elapsed_ms DESC
LIMIT 1000

-- @variant range_services ymin=100 ymax=5000
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, http_status, error_class_id, instance_id,
       sql_count, sql_time_ms, ext_count, ext_time_ms, user_id, user_id_mode, profile_captured, kind, app_version_id
FROM {db}.{p}xlog
PREWHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 1 HOUR AND ts < {end_minute:DateTime}
     AND elapsed_ms BETWEEN {ymin:UInt32} AND {ymax:UInt32}
WHERE kind = 'HTTP' AND service_id IN {services:Array(UInt64)}
ORDER BY elapsed_ms DESC
LIMIT 1000

-- @variant range_errors ymin=0 ymax=60000
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, http_status, error_class_id, instance_id,
       sql_count, sql_time_ms, ext_count, ext_time_ms, user_id, user_id_mode, profile_captured, kind, app_version_id
FROM {db}.{p}xlog
PREWHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - INTERVAL 1 HOUR AND ts < {end_minute:DateTime}
     AND elapsed_ms BETWEEN {ymin:UInt32} AND {ymax:UInt32}
WHERE kind = 'HTTP' AND error = 1
ORDER BY elapsed_ms DESC
LIMIT 1000

-- @variant user_1h hours=1
-- @criterion warm_ms <= 3000
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime}
  AND user_id = {uid:String}
ORDER BY ts DESC
LIMIT 1000

-- @variant user_24h hours=24
-- @criterion record
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime}
  AND user_id = {uid:String}
ORDER BY ts DESC
LIMIT 1000

-- @variant request_1h hours=1
-- @criterion warm_ms <= 3000
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime}
  AND request_id = {rid:String}
ORDER BY ts DESC
LIMIT 1000
