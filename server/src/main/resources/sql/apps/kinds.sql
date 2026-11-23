-- SPDX-License-Identifier: Apache-2.0
--
-- Transaction kinds an application recorded in the last 7 days (GET /apps/{appId} `kinds`, ADR-0010
-- C-205 (4), C-191 (2): the kind filter disables "Batch" for an application without batch jobs,
-- docs/15 §3.2). The hour rollup is filled by its materialized view on every insert (the current
-- hour included) and sorted by (app_id, kind, ...), so this reads a few index granules per day.
-- @tables service_stat_1h
SELECT DISTINCT toString(kind) AS kind
FROM {db}.{p}{rollup:service_stat_1h}
WHERE app_id = {app:UInt32} AND hour >= {from:DateTime('UTC')}
ORDER BY kind
LIMIT 3
