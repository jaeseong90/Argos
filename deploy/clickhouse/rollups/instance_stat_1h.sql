-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of instance_stat_1h (ADR-0010 C-212 (1), schema V003), source instance_stat_1m
-- (cascade). Shared by the materialized view instance_stat_1h_mv ({where} = '') and the rollup
-- rebuild ({where} = 'AND <predicate on minute>', D-14). Same measures as app_stat_1h, per instance.
SELECT
    app_id,
    env,
    instance_id,
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
FROM {db}.{p}instance_stat_1m
WHERE true {where}
GROUP BY app_id, env, instance_id, kind, hour
