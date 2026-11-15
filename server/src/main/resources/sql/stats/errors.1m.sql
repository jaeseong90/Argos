-- SPDX-License-Identifier: Apache-2.0
--
-- Error groups (14 §4.8): minute fragment of error_stat_1m (no hour rollup, 13 §4.4).
-- @tables error_stat_1m
SELECT error_group_id AS g,
       service_id AS sv,
       app_version_id AS v,
       sum(count) AS c,
       min(first_ts) AS f,
       max(last_ts) AS l
FROM {db}.{p}{rollup:error_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_groups:UInt8} = 1 OR error_group_id IN {groups:Array(UInt64)})
  AND ({all_services:UInt8} = 1 OR service_id IN {services:Array(UInt64)})
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY g, sv, v
