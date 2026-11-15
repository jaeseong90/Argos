-- SPDX-License-Identifier: Apache-2.0
--
-- The server's own collection series per bucket, key and label: gauge average and counter increase.
SELECT b,
       k,
       l,
       sum(sv) / sum(n) AS v,
       sum(inc) AS increase
FROM ({segments})
GROUP BY b, k, l
ORDER BY b, k, l
LIMIT 100000
