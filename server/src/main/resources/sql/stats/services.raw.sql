-- SPDX-License-Identifier: Apache-2.0
--
-- Service statistics (14 §4.8, 13 §6.3 (7)): raw fragment, per service and (with {split} = 1) per
-- application version. {all_services} = 1 reads every service, otherwise those of {services}.
-- @tables xlog
SELECT service_id AS sv,
       if({split:UInt8} = 1, app_version_id, toUInt64(0)) AS v,
       count() AS c,
       countIf(error = 1) AS e,
       sum(elapsed_ms) AS s,
       max(elapsed_ms) AS mx,
       sum(sql_time_ms) AS sq,
       sum(ext_time_ms) AS x,
       countIf(elapsed_ms <= apdex_t_ms) AS sat,
       countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) AS tol,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_services:UInt8} = 1 OR service_id IN {services:Array(UInt64)})
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY sv, v
