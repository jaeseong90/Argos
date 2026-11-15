-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE: rollup SELECT of instance_stat_1m_v2 = instance_stat_1m plus max_cpu_time_ms, source
-- xlog. Used by the view of M001 ({where} = '') and by its backfill ({where} = 'AND <predicate on ts>').
SELECT
    app_id,
    env,
    instance_id,
    kind,
    toStartOfMinute(ts)                                               AS minute,
    count()                                                           AS count,
    countIf(error = 1)                                                AS error_count,
    sum(elapsed_ms)                                                   AS sum_elapsed_ms,
    max(elapsed_ms)                                                   AS max_elapsed_ms,
    sum(sql_time_ms)                                                  AS sum_sql_time_ms,
    sum(dbc_wait_ms)                                                  AS sum_dbc_wait_ms,
    sum(ext_time_ms)                                                  AS sum_ext_time_ms,
    countIf(elapsed_ms <= apdex_t_ms)                                 AS apdex_satisfied,
    countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) AS apdex_tolerating,
    max(cpu_time_ms)                                                  AS max_cpu_time_ms,
    quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)              AS q_elapsed
FROM {db}.{p}xlog
WHERE true {where}
GROUP BY app_id, env, instance_id, kind, minute
