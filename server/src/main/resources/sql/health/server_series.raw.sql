-- SPDX-License-Identifier: Apache-2.0
--
-- The server's own collection series (14 §4.15; 12 §9.4, DSN-12-14: the server writes its meters to
-- metric with app_id = 0, WO-28): raw fragment. Keys: dropped (argos_collector_dropped_total,
-- label: reason; server-wide, not per application) and ingest_lag (argos_ingest_lag_seconds).
-- No row means the series is not recorded (yet): the API answers null, never 0.
-- @tables metric
SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b,
       multiIf(startsWith(name, 'argos_collector_dropped_total'), 'dropped',
               startsWith(name, 'argos_ingest_lag_seconds'), 'ingest_lag',
               '') AS k,
       if(k = 'dropped', extract(name, 'reason="([^"]*)"'), '') AS l,
       sum(value) AS sv,
       count() AS n,
       sum(delta) AS inc
FROM {db}.{p}metric
WHERE app_id = 0 AND ts >= {from:DateTime('UTC')} AND ts < {to:DateTime('UTC')} AND k != ''
GROUP BY b, k, l
