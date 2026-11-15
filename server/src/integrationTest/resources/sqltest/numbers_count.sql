-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): reads n rows of numbers() (max_rows_to_read check).
SELECT count() AS c
FROM numbers({n:UInt64})
