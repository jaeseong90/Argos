-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of service_stat_1h (docs/13 section 4.3), source service_stat_1m (cascade). Shared
-- by the materialized view service_stat_1h_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on minute>', D-14).
SELECT
    app_id,
    env,
    kind,
    service_id,
    app_version_id,
    toStartOfHour(minute)                                   AS hour,
    sum(count)                                              AS count,
    sum(error_count)                                        AS error_count,
    sum(sum_elapsed_ms)                                     AS sum_elapsed_ms,
    max(max_elapsed_ms)                                     AS max_elapsed_ms,
    sum(sum_sql_time_ms)                                    AS sum_sql_time_ms,
    sum(sum_dbc_wait_ms)                                   AS sum_dbc_wait_ms,
    sum(sum_ext_time_ms)                                    AS sum_ext_time_ms,
    sum(apdex_satisfied)                                    AS apdex_satisfied,
    sum(apdex_tolerating)                                   AS apdex_tolerating,
    quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q_elapsed
FROM {db}.{p}service_stat_1m
WHERE true {where}
GROUP BY app_id, env, kind, service_id, app_version_id, hour
