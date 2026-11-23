-- SPDX-License-Identifier: Apache-2.0
--
-- Late transactions of a dashboard range (14 §4.4 collection.late; xlog.late, D-15) with the
-- dashboard's instance filter: {all} = 1 reads every instance except those of {excluded}
-- (exclude_instance), otherwise those of {instances}. Raw rows only, ranges of at most 24 h.
-- @tables xlog
SELECT countIf(late = 1) AS late
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
