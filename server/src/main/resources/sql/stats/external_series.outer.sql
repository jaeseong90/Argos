-- SPDX-License-Identifier: Apache-2.0
--
-- One host's series: merge per bucket (buckets for the C-14 estimate; timeouts and connection
-- failures per bucket, ADR-0010 C-222 (7)).
SELECT b,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(t) AS timeouts,
       sum(f) AS connect_failures,
       sum(s) AS sum_ms,
       sumForEach(bk) AS buckets
FROM ({segments})
GROUP BY b
ORDER BY b
LIMIT 1000
