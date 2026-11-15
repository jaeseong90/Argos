-- SPDX-License-Identifier: Apache-2.0
--
-- SQL statistics (14 §4.8): merge per SQL and target, sorted by an allowed column ({sort}: sum_ms,
-- cnt, avg_ms, max_ms, err; {dir} = 1 descending, -1 ascending), exact LIMIT (C-64). The p95 is
-- estimated from the bucket sums by the API (C-14).
SELECT sql_id,
       db_id,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       sum(se) AS exec_ms,
       sum(sf) AS fetch_ms,
       max(mx) AS max_ms,
       sum(r) AS fetch_rows,
       sumForEach(bk) AS buckets,
       sum_ms / cnt AS avg_ms
FROM ({segments})
GROUP BY sql_id, db_id
ORDER BY {sort:Identifier} * {dir:Int8} DESC, sql_id, db_id
LIMIT {limit:UInt32}
