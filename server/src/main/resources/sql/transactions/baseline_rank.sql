-- SPDX-License-Identifier: Apache-2.0
--
-- Baseline rank (14 §4.6 percentile_of_this): of the service's transactions around the transaction
-- (+-30 min, raw rows), how many were faster than it.
-- @tables xlog
SELECT count() AS cnt,
       countIf(elapsed_ms < {elapsed:UInt32}) AS faster
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND kind = {kind:String}
  AND service_id = {service:UInt64}
