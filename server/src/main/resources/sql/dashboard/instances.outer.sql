-- SPDX-License-Identifier: Apache-2.0
--
-- Per-instance transaction totals: merge of dashboard/instances.{raw,1m,1h}.
SELECT i,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY i
ORDER BY i
LIMIT 10000
