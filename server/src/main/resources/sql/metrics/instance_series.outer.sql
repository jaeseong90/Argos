-- SPDX-License-Identifier: Apache-2.0
--
-- Instance metrics (14 §4.10): merge per series and bucket. A bucket without samples has no row:
-- the API fills the time grid with null (D-09), never 0.
SELECT name,
       b,
       any(k) AS kind,
       sum(sv) / sum(n) AS avg_v,
       min(mn) AS min_v,
       max(mx) AS max_v,
       sum(inc) AS increase
FROM ({segments})
GROUP BY name, b
ORDER BY name, b
LIMIT 100000
