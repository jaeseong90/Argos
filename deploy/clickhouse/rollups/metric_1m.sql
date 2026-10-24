-- SPDX-License-Identifier: Apache-2.0
--
-- Rollup SELECT of metric_1m (docs/13 section 4.6), source metric (5 s samples). Shared by the
-- materialized view metric_1m_mv ({where} = '') and the rollup rebuild
-- ({where} = 'AND <predicate on ts>', D-14). Gauges: avg = avg_sum / sample_count, min, max.
-- Counters: increase = sum(delta) (reset-corrected by the agent, D-16) and last_v.
SELECT
    app_id,
    env,
    instance_id,
    name,
    kind,
    toStartOfMinute(ts) AS minute,
    count()             AS sample_count,
    sum(value)          AS avg_sum,
    min(value)          AS min_v,
    max(value)          AS max_v,
    anyLast(value)      AS last_v,
    sum(delta)          AS increase
FROM {db}.{p}metric
WHERE true {where}
GROUP BY app_id, env, instance_id, name, kind, minute
