-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): xlog rows of one application, the app as a parameter.
-- @tables xlog
SELECT count() AS c
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
