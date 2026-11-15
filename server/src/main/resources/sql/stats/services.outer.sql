-- SPDX-License-Identifier: Apache-2.0
--
-- Service statistics (14 §4.8): merge per service (and version), sorted by an allowed column
-- ({sort} is chosen by the server from its allow list; {dir} = 1 descending, -1 ascending) with an
-- exact LIMIT (C-64; the API asks for one row more than it shows to know whether it truncated).
SELECT sv,
       v,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       max(mx) AS max_ms,
       sum(sq) AS sql_ms,
       sum(x) AS ext_ms,
       sum(sat) AS satisfied,
       sum(tol) AS tolerating,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q,
       err / cnt AS error_rate,
       sum_ms / cnt AS avg_ms,
       q[2] AS p95_ms,
       q[3] AS p99_ms,
       (satisfied + tolerating / 2) / cnt AS apdex
FROM ({segments})
GROUP BY sv, v
ORDER BY {sort:Identifier} * {dir:Int8} DESC, sv, v
LIMIT {limit:UInt32}
