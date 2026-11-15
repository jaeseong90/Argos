-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call statistics (14 §4.8 external/stats): raw fragment of the 10 s windows per host,
-- with the 15 latency buckets (C-14). {all_hosts} = 1 reads every host, otherwise {hosts}.
-- @tables ext_stat_raw
SELECT host_id,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f,
       sum(sum_ms) AS s,
       max(max_ms) AS mx,
       sumForEach(arrayMap(x -> toUInt64(x), buckets)) AS bk
FROM {db}.{p}ext_stat_raw
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_hosts:UInt8} = 1 OR host_id IN {hosts:Array(UInt64)})
  AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')}
GROUP BY host_id
