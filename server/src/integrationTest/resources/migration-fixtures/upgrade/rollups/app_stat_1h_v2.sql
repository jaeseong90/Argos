-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE: rollup SELECT of app_stat_1h_v2 = the SELECT of app_stat_1h (cascade, source
-- app_stat_1m). Used by the view of M002 ({where} = '') and by its monthly backfill
-- ({where} = 'AND <predicate on minute>').
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
    sum(sum_dbc_wait_ms)                                    AS sum_dbc_wait_ms,
    sum(sum_ext_time_ms)                                    AS sum_ext_time_ms,
    sum(apdex_satisfied)                                    AS apdex_satisfied,
    sum(apdex_tolerating)                                   AS apdex_tolerating,
    quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q_elapsed
FROM {db}.{p}app_stat_1m
WHERE true {where}
GROUP BY app_id, env, kind, hour
