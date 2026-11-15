-- SPDX-License-Identifier: Apache-2.0
--
-- The server's own collection series (see health/server_series.raw): hour fragment.
-- @tables metric_1h
SELECT toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       multiIf(startsWith(name, 'argos_collector_dropped_total'), 'dropped',
               startsWith(name, 'argos_ingest_lag_seconds'), 'ingest_lag',
               '') AS k,
       if(k = 'dropped', extract(name, 'reason="([^"]*)"'), '') AS l,
       sum(avg_sum) AS sv,
       sum(sample_count) AS n,
       sum(increase) AS inc
FROM {db}.{p}{rollup:metric_1h}
WHERE app_id = 0 AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')} AND k != ''
GROUP BY b, k, l
