-- SPDX-License-Identifier: Apache-2.0
--
-- XLog drag summary (14 §4.5 POST .../xlog/summary): the transactions of the dragged rectangle
-- (range x elapsed) grouped by one dimension, the largest groups first. {dim:Identifier} is one of
-- service_id, instance_id, error_class_id, http_status, chosen by the API from that list (never
-- request text); errors_only = 1 restricts to failed transactions (by_error_class).
-- @tables xlog
SELECT {dim:Identifier} AS k,
       count() AS c,
       avg(elapsed_ms) AS avg_ms,
       max(elapsed_ms) AS max_ms,
       countIf(error = 1) AS errors
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND kind IN {kinds:Array(String)}
  AND (length({services:Array(UInt64)}) = 0 OR service_id IN {services:Array(UInt64)})
  AND (length({instances:Array(UInt32)}) = 0 OR instance_id IN {instances:Array(UInt32)})
  AND ({error:Int8} < 0 OR error = {error:Int8})
  AND elapsed_ms >= {min_ms:UInt32} AND elapsed_ms <= {max_ms:UInt32}
  AND ({errors_only:UInt8} = 0 OR error = 1)
GROUP BY k
ORDER BY c DESC, k
LIMIT {top:UInt32}
