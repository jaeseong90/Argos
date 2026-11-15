-- SPDX-License-Identifier: Apache-2.0
--
-- Overview sparklines: per application and minute bucket (merge of overview/apps.{raw,1m}).
SELECT a,
       b,
       sum(c) AS cnt,
       sum(e) AS err,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q
FROM ({segments})
GROUP BY a, b
ORDER BY a, b
LIMIT 100000
