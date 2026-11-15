-- SPDX-License-Identifier: Apache-2.0
--
-- The latest profiled transactions that called one host (14 §4.8 external/{hostId} related_tx):
-- their HTTP steps (target_id = host) in profile_step, joined to their xlog row, newest first.
-- @tables profile_step, xlog
SELECT x.ts AS ts,
       x.tx_id AS tx_id,
       x.service_id AS service_id,
       x.instance_id AS instance_id,
       x.elapsed_ms AS elapsed_ms,
       p.calls AS calls,
       p.sum_ms AS call_ms
FROM (
  SELECT tx_id, sum(repeat_count) AS calls, sum(elapsed_ms) AS sum_ms
  FROM {db}.{p}profile_step
  WHERE app_id = {app:UInt32} AND type = 'HTTP' AND target_id = {host:UInt64}
    AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  GROUP BY tx_id
) AS p
INNER JOIN (
  SELECT ts, tx_id, service_id, instance_id, elapsed_ms
  FROM {db}.{p}xlog
  WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND profile_captured = 1
    AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
) AS x ON p.tx_id = x.tx_id
ORDER BY ts DESC, tx_id DESC
LIMIT {limit:UInt32}
