-- SPDX-License-Identifier: Apache-2.0
--
-- Error groups (14 §4.8): per group the count, first and last occurrence in the range, its top 5
-- services by count and its versions (at most 20). Sorted by an allowed column ({sort}: sort_count,
-- sort_first, sort_last; {dir} = 1 descending, -1 ascending), exact LIMIT (C-64).
SELECT g,
       sum(sc) AS cnt,
       min(gf) AS first_ts,
       max(gl) AS last_ts,
       arraySlice(arraySort(x -> (-x.2, x.1), groupArray((sv, sc))), 1, 5) AS services,
       arraySlice(arraySort(arrayDistinct(arrayFlatten(groupArray(vs)))), 1, 20) AS versions,
       toFloat64(cnt) AS sort_count,
       toFloat64(toUnixTimestamp64Milli(first_ts)) AS sort_first,
       toFloat64(toUnixTimestamp64Milli(last_ts)) AS sort_last
FROM (
  SELECT g, sv, sum(c) AS sc, min(f) AS gf, max(l) AS gl, groupUniqArray(v) AS vs
  FROM ({segments})
  GROUP BY g, sv
)
GROUP BY g
ORDER BY {sort:Identifier} * {dir:Int8} DESC, g
LIMIT {limit:UInt32}
