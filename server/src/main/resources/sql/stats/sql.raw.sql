-- SPDX-License-Identifier: Apache-2.0
--
-- SQL statistics (14 §4.8 sql/stats, 13 §6.3 (8); complete counts, D-12): raw fragment of the 10 s
-- windows per SQL and JDBC target, with the 15 latency buckets summed element-wise (C-14).
-- {all_dbs} / {all_sql} = 1 read everything, otherwise {db} / the ids of {sqls}.
-- @tables sql_stat_raw
SELECT sql_id,
       db_id,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_ms) AS s,
       sum(sum_exec_ms) AS se,
       sum(sum_fetch_ms) AS sf,
       max(max_ms) AS mx,
       sum(fetch_rows) AS r,
       sumForEach(arrayMap(x -> toUInt64(x), buckets)) AS bk
FROM {db}.{p}sql_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_dbs:UInt8} = 1 OR db_id = {db_id:UInt64})
  AND ({all_sql:UInt8} = 1 OR sql_id IN {sqls:Array(UInt64)})
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY sql_id, db_id
