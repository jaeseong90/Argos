-- SPDX-License-Identifier: Apache-2.0
--
-- Late transactions of a range (14 §4.4 collection.late, §4.15 late_rows; xlog.late, D-15). Raw
-- rows only: the API reads it for ranges of at most 24 h (14 §7.2), null beyond.
-- @tables xlog
SELECT countIf(late = 1) AS late
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
