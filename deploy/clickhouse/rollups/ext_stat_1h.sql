-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of ext_stat_1h (docs/13 section 4.5), source ext_stat_1m (cascade). Shared by the
-- materialized view ext_stat_1h_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on minute>', D-14).
SELECT
    app_id,
    env,
    kind,
    host_id,
    toStartOfHour(minute)         AS hour,
    sum(count)                    AS count,
    sum(error_count)              AS error_count,
    sum(timeout_count)            AS timeout_count,
    sum(connect_fail_count)       AS connect_fail_count,
    sum(sum_ms)                   AS sum_ms,
    max(max_ms)                   AS max_ms,
    sumForEachMergeState(buckets) AS buckets
FROM {db}.{p}ext_stat_1m
WHERE true {where}
GROUP BY app_id, env, kind, host_id, hour
