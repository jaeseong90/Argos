-- SPDX-License-Identifier: Apache-2.0
--
-- One error group's series: minute fragment.
-- @tables error_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c
FROM {db}.{p}{rollup:error_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND error_group_id = {group:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
