-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of app_stat_1h (docs/13 section 4.2), source app_stat_1m (cascade). Shared by the
-- materialized view app_stat_1h_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on minute>', D-14).
SELECT
    app_id,
    env,
    kind,
    toStartOfHour(minute)                                   AS hour,
    sum(count)                                              AS count,
    sum(error_count)                                        AS error_count,
    sum(sum_elapsed_ms)                                     AS sum_elapsed_ms,
    max(max_elapsed_ms)                                     AS max_elapsed_ms,
    sum(sum_sql_time_ms)                                    AS sum_sql_time_ms,
    sum(sum_dbc_time_ms)                                    AS sum_dbc_time_ms,
    sum(sum_ext_time_ms)                                    AS sum_ext_time_ms,
    sum(apdex_satisfied)                                    AS apdex_satisfied,
    sum(apdex_tolerating)                                   AS apdex_tolerating,
    quantilesTDigestMergeState(0.5, 0.95, 0.99)(q_elapsed)  AS q_elapsed
FROM {db}.{p}app_stat_1m
WHERE true {where}
GROUP BY app_id, env, kind, hour
