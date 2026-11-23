-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V009: the reason of an alert transition (WO-38; 12 §5.4, 05 §2.9 "상태
-- 전이", ADR-0010 C-227 (3)). Rules of V001 apply: never edited once released, explicit grants for
-- every new table (none here: a new column inherits the table's grants).
--
-- alert_events.reason: why the engine made the transition, a closed list of codes (no personal
--   data, no collected text):
--   condition       the condition is met: OK/RESOLVED -> PENDING, PENDING -> FIRING, or an
--                   event-driven firing (instance DOWN, channel failing)
--   cleared         the condition stopped holding before 'for' elapsed: PENDING -> OK
--   escalated       FIRING (or ACKED) warn -> critical
--   acked           an operator acknowledged the firing: FIRING -> ACKED
--   resolved        the recovery condition held for recover.for, or the event-driven condition
--                   ended (instance up again, channel healthy): -> RESOLVED
--   target_gone     the unit's instance is DOWN, archived or deleted (C-226 (12)): -> RESOLVED
--   rule_disabled   the rule was disabled (or can no longer be evaluated): -> RESOLVED
--   rule_deleted    the rule was deleted: -> RESOLVED
--   no_data         no value with no_data warn|critical: -> NO_DATA
--   data_returned   a value again after NO_DATA: NO_DATA -> the state before
--   Rows written before V009 keep NULL (the reason is unknown); the UI shows them as such.
--
-- The column and its CHECK are added on the partitioned parent, so every monthly partition (now
-- and later ones of argos.maintain_monthly_partitions) gets both. ADD COLUMN IF NOT EXISTS with the
-- inline constraint is a no-op on a database that already has the column.

SET search_path TO argos, public;

ALTER TABLE argos.alert_events ADD COLUMN IF NOT EXISTS reason VARCHAR(16)
  CONSTRAINT ck_alert_events_reason CHECK (reason IN (
    'condition', 'cleared', 'escalated', 'acked', 'resolved', 'target_gone', 'rule_disabled',
    'rule_deleted', 'no_data', 'data_returned'));
