-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health job (12 §9.2, WO-28): the p99 duration of the queries that finished in the last
-- five minutes (argos_ch_query_p99_seconds); NULL when none finished.
-- @tables system.query_log
SELECT count() AS queries,
       if(count() = 0, NULL, quantile(0.99)(query_duration_ms)) AS p99_ms
FROM system.query_log
WHERE event_date >= toDate(now() - INTERVAL 5 MINUTE)
  AND event_time >= now() - INTERVAL 5 MINUTE
  AND type = 'QueryFinish'
