-- SPDX-License-Identifier: Apache-2.0
--
-- One service's series: merge per bucket (C-53).
SELECT b,
       sum(c) AS cnt,
       sum(e) AS err,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY b
ORDER BY b
LIMIT 1000
