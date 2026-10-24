-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of sql_stat_1h (docs/13 section 4.5), source sql_stat_1m (cascade). Shared by the
-- materialized view sql_stat_1h_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on minute>', D-14).
SELECT
    app_id,
    env,
    kind,
    sql_id,
    db_id,
    toStartOfHour(minute)         AS hour,
    sum(count)                    AS count,
    sum(error_count)              AS error_count,
    sum(sum_ms)                   AS sum_ms,
    sum(sum_exec_ms)              AS sum_exec_ms,
    sum(sum_fetch_ms)             AS sum_fetch_ms,
    max(max_ms)                   AS max_ms,
    sum(fetch_rows)               AS fetch_rows,
    sumForEachMergeState(buckets) AS buckets
FROM {db}.{p}sql_stat_1m
WHERE true {where}
GROUP BY app_id, env, kind, sql_id, db_id, hour
