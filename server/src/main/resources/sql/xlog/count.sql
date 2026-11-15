-- SPDX-License-Identifier: Apache-2.0
--
-- XLog total (14 §4.5, 13 §6.3 (3)): the points of the range and filters, counted before the points
-- are read so that N = ceil(total / limit) of the deterministic sampling (A-5) is known. Whether the
-- points that satisfy the sampling rule exceed the limit (meta.truncated) is counted by points.sql.
-- Also the count of the drag summary (POST .../xlog/summary). Empty arrays and -1 mean "no filter".
-- @tables xlog
SELECT count() AS total
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND kind IN {kinds:Array(String)}
  AND (length({services:Array(UInt64)}) = 0 OR service_id IN {services:Array(UInt64)})
  AND (length({instances:Array(UInt32)}) = 0 OR instance_id IN {instances:Array(UInt32)})
  AND ({error:Int8} < 0 OR error = {error:Int8})
  AND elapsed_ms >= {min_ms:UInt32} AND elapsed_ms <= {max_ms:UInt32}
