-- SPDX-License-Identifier: Apache-2.0
--
-- Global search by user id or request id (14 §4.11, 13 §6.3 (11), N-24): transactions of the range
-- (from/to required, 1 h by default in the UI), bloom indexes idx_user and idx_request; every
-- application unless app is given (app = 0), optionally one environment (env = ''). user_id matches
-- plain-mode rows only (ADR-0010 C-152). Newest first, LIMIT is the API cap.
-- @tables xlog
SELECT app_id,
       tx_id,
       toUnixTimestamp64Milli(ts) AS ts_ms,
       service_id,
       elapsed_ms,
       error
FROM {db}.{p}xlog
WHERE ({app:UInt32} = 0 OR app_id = {app:UInt32})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND ({env:String} = '' OR env = {env:String})
  AND ({uid:String} = '' OR (user_id_mode = 'plain' AND user_id = {uid:String}))
  AND ({rid:String} = '' OR request_id = {rid:String})
ORDER BY ts_ms DESC, tx_id DESC
LIMIT {limit:UInt32}
