-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE (T-ST-052 path 2, T-SV-002): a next-version schema script on top of the bundled
-- V001, V002 (ADR-0010 C-144) and V003 (C-212). ADD COLUMN IF NOT EXISTS is a metadata change (13
-- section 13.2): it does not rewrite parts, so it takes the same time with or without data.
ALTER TABLE {db}.{p}xlog ADD COLUMN IF NOT EXISTS fixture_v002 UInt8 DEFAULT 0;
ALTER TABLE {db}.{p}metric ADD COLUMN IF NOT EXISTS fixture_unit LowCardinality(String) DEFAULT '';
