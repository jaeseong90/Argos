-- SPDX-License-Identifier: Apache-2.0
--
-- One SQL statement's series (14 §4.8 sql/{sqlId}): raw fragment per bucket with the buckets.
-- @tables sql_stat_raw
SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_ms) AS s,
       max(max_ms) AS mx,
       sumForEach(arrayMap(x -> toUInt64(x), buckets)) AS bk
FROM {db}.{p}sql_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND sql_id = {sql:UInt64}
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY b
