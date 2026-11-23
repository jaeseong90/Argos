-- SPDX-License-Identifier: Apache-2.0
--
-- Alert engine (12 §5.2 ClickHouseProvider, WO-25): the agent metric series the alert metrics read
-- (heap, GC pause, threads, Tomcat, pools, system, agent drops; 10 §11 names) per instance over a
-- rule window longer than the 15-minute memory rings, from the minute rollup: gauge sum and sample
-- count (average) and the counter increase.
-- @tables metric_1m
SELECT instance_id AS i,
       name AS n,
       sum(avg_sum) AS sv,
       sum(sample_count) AS cnt,
       sum(increase) AS inc
FROM {db}.{p}{rollup:metric_1m}
WHERE app_id = {app:UInt32}
  AND ({all:Int64} = 1 OR instance_id = {instance:UInt32})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
  AND arrayExists(f -> startsWith(name, f), {families:Array(String)})
GROUP BY i, n
LIMIT 10000
