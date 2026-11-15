-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): the minute rollup through its alias (13 §13.2 step 3).
-- @tables app_stat_1m
SELECT sum(count) AS c
FROM {db}.{p}{rollup:app_stat_1m}
