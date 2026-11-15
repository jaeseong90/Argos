-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT, QueryInfrastructureIT): a statement that takes longer than one
-- second (max_execution_time check).
SELECT sum(cityHash64(number)) AS s
FROM numbers({n:UInt64})
