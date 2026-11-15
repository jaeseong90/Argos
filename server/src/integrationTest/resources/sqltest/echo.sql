-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): parameters come back exactly as bound (hostile strings included).
SELECT {s:String} AS s,
       {arr:Array(String)} AS arr,
       {u:UInt64} AS u,
       {t:DateTime64(3, 'UTC')} AS t,
       {ids:Array(UInt64)} AS ids,
       {sort:Identifier} AS sort_column_value
FROM (SELECT 7 AS elapsed_ms)
