-- SPDX-License-Identifier: Apache-2.0
--
-- Dashboard metric keys per instance over the whole range (instances/compare, collection health
-- per instance): value sum / samples (gauge average) and the counter increase.
SELECT i,
       k,
       l,
       sum(sv) / sum(n) AS v,
       sum(inc) AS increase
FROM ({segments})
GROUP BY i, k, l
ORDER BY i, k, l
LIMIT 100000
