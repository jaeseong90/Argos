-- SPDX-License-Identifier: Apache-2.0
--
-- One SQL statement's series: merge per bucket (counts, sums, buckets for the C-14 estimate).
SELECT b,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       max(mx) AS max_ms,
       sumForEach(bk) AS buckets
FROM ({segments})
GROUP BY b
ORDER BY b
LIMIT 1000
