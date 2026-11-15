-- SPDX-License-Identifier: Apache-2.0
--
-- SQL statistics (14 §4.8): minute fragment (buckets: sumForEachMerge of the rollup state).
-- @tables sql_stat_1m
SELECT sql_id,
       db_id,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_ms) AS s,
       sum(sum_exec_ms) AS se,
       sum(sum_fetch_ms) AS sf,
       max(max_ms) AS mx,
       sum(fetch_rows) AS r,
       sumForEachMerge(buckets) AS bk
FROM {db}.{p}{rollup:sql_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_dbs:UInt8} = 1 OR db_id = {db_id:UInt64})
  AND ({all_sql:UInt8} = 1 OR sql_id IN {sqls:Array(UInt64)})
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY sql_id, db_id
