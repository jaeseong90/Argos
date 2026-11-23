-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard metric keys (see dashboard/metric_keys.raw): minute fragment.
-- @tables metric_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       instance_id AS i,
       multiIf(startsWith(name, 'jvm_memory_used_bytes') AND position(name, 'area="heap"') > 0, 'heap_used',
               startsWith(name, 'jvm_memory_max_bytes') AND position(name, 'area="heap"') > 0, 'heap_max',
               startsWith(name, 'jvm_gc_pause_seconds_total'), 'gc_pause',
               name IN ('process_cpu_usage_ratio', 'process_cpu_usage'), 'cpu',
               name IN ('system_cpu_usage_ratio', 'system_cpu_usage'), 'system_cpu',
               startsWith(name, 'tomcat_threads_busy'), 'tomcat_busy',
               startsWith(name, 'tomcat_threads_max'), 'tomcat_max',
               startsWith(name, 'jdbc_pool_active'), 'pool_active',
               startsWith(name, 'jdbc_pool_max'), 'pool_max',
               startsWith(name, 'argos_agent_dropped_total'), 'agent_drops',
               startsWith(name, 'argos_agent_ignored_total'), 'excluded',
               name = 'argos_active_transactions', 'active',
               '') AS k,
       multiIf(k IN ('pool_active', 'pool_max'), extract(name, 'pool="([^"]*)"'),
               k = 'excluded', extract(name, 'reason="([^"]*)"'),
               '') AS l,
       sum(avg_sum) AS sv,
       sum(sample_count) AS n,
       sum(increase) AS inc
FROM {db}.{p}{rollup:metric_1m}
WHERE app_id = {app:UInt32}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
  AND k != '' AND has({keys:Array(String)}, k)
GROUP BY b, i, k, l
