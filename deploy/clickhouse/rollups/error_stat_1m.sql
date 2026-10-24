-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of error_stat_1m (docs/13 section 4.4), source xlog. Shared by the materialized view
-- error_stat_1m_mv ({where} = '') and the rollup rebuild ({where} = 'AND <predicate on ts>', D-14).
-- Only failed transactions with an error group (fingerprint, F-S19).
SELECT
    app_id,
    env,
    kind,
    error_group_id,
    service_id,
    version_id,
    toStartOfMinute(ts) AS minute,
    count()             AS count,
    min(ts)             AS first_ts,
    max(ts)             AS last_ts
FROM {db}.{p}xlog
WHERE error = 1 AND error_group_id != 0 {where}
GROUP BY app_id, env, kind, error_group_id, service_id, version_id, minute
