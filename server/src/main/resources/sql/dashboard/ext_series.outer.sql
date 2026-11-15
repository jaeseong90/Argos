-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call errors per bucket: merge of dashboard/ext_series.{raw,1m,1h}.
SELECT b,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(t) AS timeouts,
       sum(f) AS connect_failures
FROM ({segments})
GROUP BY b
ORDER BY b
LIMIT 1000
