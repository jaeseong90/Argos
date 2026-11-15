-- SPDX-License-Identifier: Apache-2.0
--
-- One error group per instance (14 §4.8 errors/groups/{groupId} by_instance): error_stat_1m has no
-- instance dimension, so this reads raw rows of the raw window (the last 24 h of the range at most).
-- @tables xlog
SELECT instance_id AS i,
       count() AS cnt
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND error = 1 AND error_group_id = {group:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY i
ORDER BY cnt DESC, i
LIMIT 100
