-- SPDX-License-Identifier: Apache-2.0
--
-- Q6 Distributed trace by trace_id (13 section 6.3 (6)): Argos-generated trace ids carry the Unix
-- second in bytes 0..3 (big-endian), so the API searches +-1 hour around it; external ids get the
-- last 24 hours; 30 days without a hint is recorded (idx_trace bloom filter only). The trace id is
-- passed as 32 hex digits. Criteria of 13 section 15 and N-24: 0.5 s / 1 s / measured.
--
-- @id        Q06
-- @title     trace_id: hint +-1h / 24h / 30d
-- @source    docs/13 §6.3 (6), §15 Q6; N-24
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 500
-- @settings  max_execution_time=10, max_result_rows=500

-- @probe trace
-- A trace of the busiest application 6 hours before the end (or at the start of shorter data),
-- preferably one that crossed applications (a callee transaction has caller_app_id != 0).
SELECT hex(trace_id) AS trace
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 6 HOUR)
  AND ts < greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 6 HOUR) + INTERVAL 1 HOUR
ORDER BY caller_app_id != 0 DESC, ts, tx_id
LIMIT 1

-- @variant hint_1h
-- @expect rows >= 1
WITH toFixedString(unhex({trace:String}), 16) AS id,
     toDateTime(reinterpretAsUInt32(reverse(substring(id, 1, 4))), 'UTC') AS t0
SELECT app_id, tx_id, ts, span_id, parent_span_id, caller_app_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE ts >= t0 - INTERVAL 1 HOUR AND ts < t0 + INTERVAL 1 HOUR AND trace_id = id
LIMIT 500

-- @variant 24h hours=24
-- @criterion warm_ms <= 1000
-- @expect rows >= 1
WITH toFixedString(unhex({trace:String}), 16) AS id
SELECT app_id, tx_id, ts, span_id, parent_span_id, caller_app_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime} AND trace_id = id
LIMIT 500

-- @variant 30d hours=720
-- @criterion record
-- @expect rows >= 1
WITH toFixedString(unhex({trace:String}), 16) AS id
SELECT app_id, tx_id, ts, span_id, parent_span_id, caller_app_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime} AND trace_id = id
LIMIT 500
