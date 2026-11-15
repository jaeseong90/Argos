-- SPDX-License-Identifier: Apache-2.0
--
-- One error group's series (14 §4.8 errors/groups/{groupId} series): raw fragment per bucket.
-- @tables xlog
SELECT toStartOfInterval(toDateTime(ts), toIntervalSecond({step_seconds:UInt32})) AS b,
       count() AS c
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND error = 1 AND error_group_id = {group:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY b
