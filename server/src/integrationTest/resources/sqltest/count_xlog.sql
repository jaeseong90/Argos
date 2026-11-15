-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): xlog rows in a range, as argos_reader.
-- @tables xlog
SELECT count() AS c
FROM {db}.{p}xlog
WHERE ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
