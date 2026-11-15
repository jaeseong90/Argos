-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): a defect (unknown function) must answer 500 internal.
SELECT argosNoSuchFunction(1) AS x
