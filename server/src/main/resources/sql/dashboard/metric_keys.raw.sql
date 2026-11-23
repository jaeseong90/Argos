-- SPDX-License-Identifier: Apache-2.0
--
-- The JVM, container and agent series of the dashboard, instance comparison and collection health
-- (14 §4.4, §4.15; metric names of 10 §11): raw fragment. Per bucket, instance, key and label the
-- partial aggregates: value sum and sample count (gauge average) and the counter increase.
-- Keys: heap_used, heap_max (jvm_memory_*_bytes area heap), gc_pause (jvm_gc_pause_seconds_total,
-- pauses only, D-25), cpu (process_cpu_usage_ratio), system_cpu (system_cpu_usage_ratio, ADR-0010
-- C-212 (1)), tomcat_busy, tomcat_max (tomcat_threads_*),
-- pool_active, pool_max (jdbc_pool_*, label: pool), agent_drops (argos_agent_dropped_total),
-- excluded (argos_agent_ignored_total, label: reason), active (argos_active_transactions, the
-- server's gauge of the active snapshots, ADR-0010 C-178 (1)).
-- @tables metric
SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
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
       sum(value) AS sv,
       count() AS n,
       sum(delta) AS inc
FROM {db}.{p}metric
WHERE app_id = {app:UInt32}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
  AND k != '' AND has({keys:Array(String)}, k)
GROUP BY b, i, k, l
