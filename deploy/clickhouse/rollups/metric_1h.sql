-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of metric_1h (docs/13 section 4.6), source metric_1m (cascade). Shared by the
-- materialized view metric_1h_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on minute>', D-14).
SELECT
    app_id,
    env,
    instance_id,
    name,
    kind,
    toStartOfHour(minute) AS hour,
    sum(sample_count)     AS sample_count,
    sum(avg_sum)          AS avg_sum,
    min(min_v)            AS min_v,
    max(max_v)            AS max_v,
    anyLast(last_v)       AS last_v,
    sum(increase)         AS increase
FROM {db}.{p}metric_1m
WHERE true {where}
GROUP BY app_id, env, instance_id, name, kind, hour
