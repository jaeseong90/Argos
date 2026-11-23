-- SPDX-License-Identifier: Apache-2.0
--
-- TEST FIXTURE (T-ST-052): a placeholder outside the C-56 list ({db}, {p}, {where}, {retention.*},
-- {rollup:<table>}). The runner must refuse the start before any statement runs. Numbered after the
-- bundled V001, V002 (ADR-0010 C-144) and V003 (C-212).
CREATE TABLE IF NOT EXISTS {db}.{p}fixture_bad_placeholder
(
    x UInt8 CODEC({codec.default})
)
ENGINE = MergeTree
ORDER BY x;
