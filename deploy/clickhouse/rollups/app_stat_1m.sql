-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of app_stat_1m (docs/13 section 4.2), source xlog. Shared by the materialized view
-- app_stat_1m_mv ({where} = '') and the rollup rebuild ({where} = 'AND <predicate on ts>', D-14).
-- Apdex counts use the service T recorded in each row (D-17).
SELECT
    app_id,
    env,
    kind,
    toStartOfMinute(ts)                                               AS minute,
    count()                                                           AS count,
    countIf(error = 1)                                                AS error_count,
    sum(elapsed_ms)                                                   AS sum_elapsed_ms,
    max(elapsed_ms)                                                   AS max_elapsed_ms,
    sum(sql_time_ms)                                                  AS sum_sql_time_ms,
    sum(dbc_time_ms)                                                  AS sum_dbc_time_ms,
    sum(ext_time_ms)                                                  AS sum_ext_time_ms,
    countIf(elapsed_ms <= apdex_t_ms)                                 AS apdex_satisfied,
    countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) AS apdex_tolerating,
    quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms)                AS q_elapsed
FROM {db}.{p}xlog
WHERE true {where}
GROUP BY app_id, env, kind, minute
