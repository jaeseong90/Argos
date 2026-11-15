-- SPDX-License-Identifier: Apache-2.0
--
-- The services that called one host (14 §4.8 external/{hostId} by_service): ext_stat has no service
-- dimension, so this reads the HTTP steps (target_id = host) of profiled transactions in the raw
-- window and their xlog service: a sample of the profiled transactions, not a total.
-- @tables profile_step, xlog
SELECT x.service_id AS sv,
       sum(p.calls) AS calls,
       sum(p.sum_ms) AS sum_ms,
       sum(p.errors) AS errors,
       count() AS tx
FROM (
  SELECT tx_id, sum(repeat_count) AS calls, sum(elapsed_ms) AS sum_ms, countIf(error = 1) AS errors
  FROM {db}.{p}profile_step
  WHERE app_id = {app:UInt32} AND type = 'HTTP' AND target_id = {host:UInt64}
    AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  GROUP BY tx_id
) AS p
INNER JOIN (
  SELECT tx_id, service_id
  FROM {db}.{p}xlog
  WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND profile_captured = 1
    AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
) AS x ON p.tx_id = x.tx_id
GROUP BY sv
ORDER BY calls DESC, sv
LIMIT 100
