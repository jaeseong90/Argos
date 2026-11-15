-- SPDX-License-Identifier: Apache-2.0
--
-- The outgoing calls of one service (14 §4.8 top_external): ext_stat has no service dimension, so
-- this reads the HTTP steps (target_id = host) of the profiled transactions of the service in the
-- raw window (profile_step: conditional capture, 7-day retention): a sample, not a total.
-- @tables profile_step, xlog
SELECT target_id AS host_id,
       sum(repeat_count) AS calls,
       sum(elapsed_ms) AS sum_ms,
       countIf(error = 1) AS errors,
       uniqExact(tx_id) AS tx
FROM {db}.{p}profile_step
WHERE app_id = {app:UInt32} AND type = 'HTTP'
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND tx_id IN (
    SELECT tx_id FROM {db}.{p}xlog
    WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
      AND profile_captured = 1
      AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')})
GROUP BY host_id
ORDER BY calls DESC, host_id
LIMIT {limit:UInt32}
