-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (12 §9.2, 13 §7.5): the memory the ClickHouse server tracks now (MemoryTracking,
-- bytes; ADR-0010 C-238 (13): argos_ch_memory_tracking_bytes).
-- @tables system.metrics
SELECT value
FROM system.metrics
WHERE metric = 'MemoryTracking'
LIMIT 1
