-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE (T-ST-052): the first statement is fine, the second is not idempotent (no IF NOT
-- EXISTS). The runner lints every pending statement first and must run neither.
CREATE TABLE IF NOT EXISTS {db}.{p}fixture_first (x UInt8) ENGINE = MergeTree ORDER BY x;
CREATE TABLE {db}.{p}fixture_second (x UInt8) ENGINE = MergeTree ORDER BY x;
