-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call statistics (14 §4.8): merge per host, sorted by an allowed column ({sort}: cnt, err,
-- timeouts, sum_ms, avg_ms, max_ms; {dir} = 1 descending, -1 ascending), exact LIMIT (C-64).
-- Percentiles are estimated from the bucket sums by the API (C-14).
SELECT host_id,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(t) AS timeouts,
       sum(f) AS connect_failures,
       sum(s) AS sum_ms,
       max(mx) AS max_ms,
       sumForEach(bk) AS buckets,
       sum_ms / cnt AS avg_ms
FROM ({segments})
GROUP BY host_id
ORDER BY {sort:Identifier} * {dir:Int8} DESC, host_id
LIMIT {limit:UInt32}
