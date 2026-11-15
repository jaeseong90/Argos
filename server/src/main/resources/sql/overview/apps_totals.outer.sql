-- SPDX-License-Identifier: Apache-2.0
--
-- Overview tiles: per application over the tile window (merge of overview/apps.{raw,1m}).
SELECT a,
       sum(c) AS cnt,
       sum(e) AS err,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY a
ORDER BY a
LIMIT 1000
