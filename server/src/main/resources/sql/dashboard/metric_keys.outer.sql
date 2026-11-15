-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard metric keys per bucket: the average of each instance in the bucket first (value sum /
-- samples), then over the instances: total (sum of the instance averages: heap, Tomcat threads,
-- pool connections), mean (cpu) and the counter increase (gc pause, drops, excluded).
SELECT b,
       k,
       l,
       sum(v) AS total,
       avg(v) AS mean,
       sum(inc) AS increase
FROM (
  SELECT b, k, l, i, sum(sv) / sum(n) AS v, sum(inc) AS inc
  FROM ({segments})
  GROUP BY b, k, l, i
)
GROUP BY b, k, l
ORDER BY b, k, l
LIMIT 100000
