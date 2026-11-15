-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (13 §7.5 argos_ch_mutations_pending): mutations of the Argos database (the privacy
-- deletion jobs of 13 §9).
-- @tables system.mutations
SELECT countIf(NOT is_done) AS pending,
       countIf(NOT is_done AND latest_fail_reason != '') AS failing
FROM system.mutations
WHERE database = {database:String}
