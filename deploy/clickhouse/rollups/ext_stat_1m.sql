-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of ext_stat_1m (docs/13 section 4.5), source ext_stat_raw (complete 10 s windows,
-- D-12). Shared by the materialized view ext_stat_1m_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on ts>', D-14). Buckets: sumForEach state (read with sumForEachMerge).
SELECT
    app_id,
    env,
    kind,
    host_id,
    toStartOfMinute(ts)                                  AS minute,
    sum(count)                                           AS count,
    sum(error_count)                                     AS error_count,
    sum(timeout_count)                                   AS timeout_count,
    sum(connect_fail_count)                              AS connect_fail_count,
    sum(sum_ms)                                          AS sum_ms,
    max(max_ms)                                          AS max_ms,
    sumForEachState(arrayMap(x -> toUInt64(x), buckets)) AS buckets
FROM {db}.{p}ext_stat_raw
WHERE true {where}
GROUP BY app_id, env, kind, host_id, minute
