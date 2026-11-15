-- SPDX-License-Identifier: Apache-2.0
--
-- The caller of a distributed call (14 §4.6 detail "caller"): transactions of the caller application
-- in the same trace that started at most one hour before the callee; the API picks the one whose
-- run covers the callee's start (the outgoing span id is not stored, 10 §9.5).
-- @tables xlog
SELECT tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       elapsed_ms,
       instance_id,
       service_id
FROM {db}.{p}xlog
WHERE app_id = {caller:UInt32}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts <= {to:DateTime64(3, 'UTC')}
  AND trace_id = toFixedString(unhex({trace:String}), 16)
ORDER BY ts, tx_id
LIMIT 50
