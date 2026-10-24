-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of sql_stat_1m (docs/13 section 4.5), source sql_stat_raw (complete 10 s windows,
-- D-12). Shared by the materialized view sql_stat_1m_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on ts>', D-14). Buckets are summed element-wise (15 buckets) into a
-- sumForEach state (read with sumForEachMerge).
SELECT
    app_id,
    env,
    kind,
    sql_id,
    db_id,
    toStartOfMinute(ts)                                  AS minute,
    sum(count)                                           AS count,
    sum(error_count)                                     AS error_count,
    sum(sum_ms)                                          AS sum_ms,
    sum(sum_exec_ms)                                     AS sum_exec_ms,
    sum(sum_fetch_ms)                                    AS sum_fetch_ms,
    max(max_ms)                                          AS max_ms,
    sum(fetch_rows)                                      AS fetch_rows,
    sumForEachState(arrayMap(x -> toUInt64(x), buckets)) AS buckets
FROM {db}.{p}sql_stat_raw
WHERE true {where}
GROUP BY app_id, env, kind, sql_id, db_id, minute
