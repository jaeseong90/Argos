-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE (T-ST-052): a MODIFY COLUMN type change, forbidden by 13 section 13.2 (new table and
-- the 4-step replacement instead). Numbered after the bundled V001 and V002 (ADR-0010 C-144).
ALTER TABLE {db}.{p}xlog MODIFY COLUMN sql_count UInt32;
