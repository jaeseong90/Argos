-- SPDX-License-Identifier: Apache-2.0
--
-- Error groups (14 §4.8 errors/groups, 13 §4.4): raw fragment per group, service and version, with
-- the same filter as the error_stat_1m view (error = 1 AND error_group_id != 0).
-- {all_groups} / {all_services} = 1 read everything, otherwise the ids of {groups} / {services}.
-- @tables xlog
SELECT error_group_id AS g,
       service_id AS sv,
       app_version_id AS v,
       count() AS c,
       min(ts) AS f,
       max(ts) AS l
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND error = 1 AND error_group_id != 0
  AND ({all_groups:UInt8} = 1 OR error_group_id IN {groups:Array(UInt64)})
  AND ({all_services:UInt8} = 1 OR service_id IN {services:Array(UInt64)})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY g, sv, v
