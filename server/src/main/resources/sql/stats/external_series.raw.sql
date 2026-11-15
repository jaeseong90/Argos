-- SPDX-License-Identifier: Apache-2.0
--
-- One host's series (14 §4.8 external/{hostId}): raw fragment per bucket.
-- @tables ext_stat_raw
SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(sum_ms) AS s,
       sumForEach(arrayMap(x -> toUInt64(x), buckets)) AS bk
FROM {db}.{p}ext_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND host_id = {host:UInt64}
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY b
