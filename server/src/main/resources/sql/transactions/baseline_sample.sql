-- SPDX-License-Identifier: Apache-2.0
--
-- Baseline sample (14 §4.6 sample_normal_tx_id): a successful transaction of the same service around
-- the transaction (+-30 min) whose elapsed time is closest to the service's p50, the same instance
-- first ("같은 서비스·같은 인스턴스 우선").
-- @tables xlog
SELECT tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       elapsed_ms,
       instance_id
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND kind = {kind:String}
  AND service_id = {service:UInt64}
  AND error = 0
  AND tx_id != {tx:UInt64}
ORDER BY instance_id = {instance:UInt32} DESC,
         abs(toInt64(elapsed_ms) - {target:Int64}),
         ts_ms,
         tx_id
LIMIT 1
