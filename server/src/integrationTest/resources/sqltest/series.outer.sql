-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): outer template of a segmented series. Merges the partial
-- aggregates of the raw, minute and hour fragments per bucket (counts, errors, DD states, C-53).
SELECT b,
       sum(c) AS cnt,
       sum(e) AS err,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY b
ORDER BY b
