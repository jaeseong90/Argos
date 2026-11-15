-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard transaction series (14 §4.4): merges the fragments per bucket and over the whole range
-- (the row with total = 1). Percentiles are quantilesDDMerge of the states (C-53): [p50, p95, p99].
-- Fragments: dashboard/tx_series.{raw,1m,1h} (application) or dashboard/tx_series_inst.{raw,1m}
-- (selected instances).
SELECT b,
       grouping(b) AS total,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       sum(x) AS ext_ms,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY GROUPING SETS ((b), ())
ORDER BY total, b
LIMIT 1000
