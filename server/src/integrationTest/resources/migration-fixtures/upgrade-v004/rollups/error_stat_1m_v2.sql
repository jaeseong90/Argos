-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE: rollup SELECT of error_stat_1m_v2 = error_stat_1m plus max_elapsed_ms, source xlog.
-- Used by the view of M001 ({where} = '') and by its backfill ({where} = 'AND <predicate on ts>').
SELECT
    app_id,
    env,
    kind,
    error_group_id,
    service_id,
    app_version_id,
    toStartOfMinute(ts) AS minute,
    count()             AS count,
    min(ts)             AS first_ts,
    max(ts)             AS last_ts,
    max(elapsed_ms)     AS max_elapsed_ms
FROM {db}.{p}xlog
WHERE error = 1 AND error_group_id != 0 {where}
GROUP BY app_id, env, kind, error_group_id, service_id, app_version_id, minute
