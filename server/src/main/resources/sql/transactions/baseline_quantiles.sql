-- SPDX-License-Identifier: Apache-2.0
--
-- Baseline of a transaction (14 §4.6 GET /transactions/{txId}/baseline): the service's p50 and p95
-- in the minutes around the transaction (+-30 min), merged from the minute rollup states (C-53);
-- q is [p50, p95, p99] (the first parameter of quantilesDD is the accuracy).
-- @tables service_stat_1m
SELECT sum(count) AS cnt,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:service_stat_1m}
WHERE app_id = {app:UInt32}
  AND kind = {kind:String}
  AND service_id = {service:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
