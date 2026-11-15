-- SPDX-License-Identifier: Apache-2.0
--
-- The series of an instance (14 §4.10 metrics/names): distinct names (with labels) and kinds of the
-- hour rollup since {since} (the cascaded views fill it at insert time), last hour seen.
-- @tables metric_1h
SELECT name,
       any(kind) AS kind,
       max(hour) AS last_hour
FROM {db}.{p}{rollup:metric_1h}
WHERE app_id = {app:UInt32} AND instance_id = {instance:UInt32} AND hour >= {since:DateTime('UTC')}
GROUP BY name
ORDER BY name
LIMIT {limit:UInt32}
