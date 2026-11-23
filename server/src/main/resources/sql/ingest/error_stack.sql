-- SPDX-License-Identifier: Apache-2.0
--
-- The representative stack of error groups that have none yet (12 §3.5 (4), 13 §12.4
-- error_groups.sample_stack, WO-27 ErrorGroupStackFiller): per group, the FRAME dictionary keys of
-- the earliest ERROR step with frames among the captured profiles (xlog.profile_captured = 1) of
-- its transactions in [from, to). The keys are returned as unsigned decimal strings.
-- @tables xlog, profile_step
SELECT toString(x.error_group_id)                                    AS group_id,
       arrayMap(f -> toString(f), argMin(s.stack, (s.ts, s.tx_id, s.seq))) AS frames
FROM {db}.{p}profile_step AS s
INNER JOIN
(
    SELECT tx_id, error_group_id
    FROM {db}.{p}xlog
    WHERE app_id = {app:UInt32} AND error_group_id IN {groups:Array(UInt64)}
      AND profile_captured = 1
      AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
) AS x ON s.tx_id = x.tx_id
WHERE s.app_id = {app:UInt32} AND s.type = 'ERROR' AND notEmpty(s.stack)
  AND s.ts >= {from:DateTime64(3, 'UTC')} AND s.ts < {to:DateTime64(3, 'UTC')}
GROUP BY x.error_group_id
