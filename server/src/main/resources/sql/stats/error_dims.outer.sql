-- SPDX-License-Identifier: Apache-2.0
--
-- One error group by service and version (14 §4.8 errors/groups/{groupId} by_service, by_version):
-- merge of stats/errors.{raw,1m} with the group filter. Counts only, exact.
SELECT sv,
       v,
       sum(c) AS cnt,
       min(f) AS first_ts,
       max(l) AS last_ts
FROM ({segments})
GROUP BY sv, v
ORDER BY cnt DESC, sv, v
LIMIT 10000
