-- SPDX-License-Identifier: Apache-2.0
--
-- One error group's series: merge per bucket.
SELECT b,
       sum(c) AS cnt
FROM ({segments})
GROUP BY b
ORDER BY b
LIMIT 1000
