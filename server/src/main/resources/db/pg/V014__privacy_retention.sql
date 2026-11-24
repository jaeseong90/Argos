-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V014: privacy deletion jobs and the audit partition floor (WO-28; 13
-- section 9, 12, 14 section 4.14, 16 section 6.8, 11.3; ADR-0007 C-66, ADR-0010 C-116). Rules of
-- V001 apply: never edited once released, explicit grants for every new table (none here: a new
-- column inherits the table's grants, CREATE OR REPLACE FUNCTION keeps the function's owner and
-- privileges).
--
-- privacy_jobs (DELETE /api/v1/admin/privacy/subjects): the request is stored as the SHA-256 of the
--   normalised value only (subject_hash, 13 section 9: never the value itself), with its kind, the
--   optional application, the range and who asked. New columns:
--     started_at     when the job runner took the job (null while queued);
--     rows_affected  rows of xlog and profile_step that matched before the mutations were issued
--                    (an estimate: rows arriving later in the same partitions are not counted);
--     tables         JSON array, one element per issued mutation:
--                    {"name": "xlog"|"profile_step", "partition": "yyyy-MM-dd",
--                     "mutation_id": "<system.mutations id>", "done": true|false}
--                    (table names without the configured prefix; no subject value);
--     warnings       short machine codes shown with the job (e.g. ip_masked_scope: rows of masked
--                    instances match the whole /24 or /48 network of the address);
--   status takes the lower-case values of 14 section 4.14 (queued, running, done, failed); error is
--   a short code with a ClickHouse error code at most, never the text of a failed mutation (it may
--   quote the deletion condition).
--
-- audit_logs_maintain_partitions (audit retention job, 17 section 9.3 04:30, account argos_audit):
--   replaced with the same signature, SECURITY DEFINER and privileges. A drop bound later than
--   now() - 90 days is refused (C-116), so no audit row younger than 90 days can be dropped whatever
--   the server configuration says; the server also refuses argos.retention.audit below 90 days.

SET search_path TO argos, public;

ALTER TABLE argos.privacy_jobs ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ NULL;
ALTER TABLE argos.privacy_jobs ADD COLUMN IF NOT EXISTS rows_affected BIGINT NULL;
ALTER TABLE argos.privacy_jobs ADD COLUMN IF NOT EXISTS tables JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE argos.privacy_jobs ADD COLUMN IF NOT EXISTS warnings TEXT[] NOT NULL DEFAULT '{}';

UPDATE argos.privacy_jobs SET status = lower(status) WHERE status IS NOT NULL;
UPDATE argos.privacy_jobs SET status = 'failed' WHERE status IS NULL;
ALTER TABLE argos.privacy_jobs ALTER COLUMN status SET NOT NULL;
ALTER TABLE argos.privacy_jobs ALTER COLUMN status SET DEFAULT 'queued';
ALTER TABLE argos.privacy_jobs ADD CONSTRAINT privacy_jobs_status_check
  CHECK (status IN ('queued', 'running', 'done', 'failed'));
ALTER TABLE argos.privacy_jobs ADD CONSTRAINT privacy_jobs_kind_check
  CHECK (subject_kind IN ('user_id', 'ip'));
ALTER TABLE argos.privacy_jobs ADD CONSTRAINT privacy_jobs_hash_check
  CHECK (subject_hash ~ '^[0-9a-f]{64}$');

-- Unfinished jobs are read at every start (resume) and by the gauge argos_privacy_jobs{state}.
CREATE INDEX IF NOT EXISTS ix_privacy_jobs_open ON argos.privacy_jobs(created_at)
  WHERE status IN ('queued', 'running');

CREATE OR REPLACE FUNCTION audit_logs_maintain_partitions(p_months_ahead integer, p_drop_before timestamptz)
  RETURNS TABLE (action text, partition_name text)
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = argos, pg_temp
AS $$
BEGIN
  IF p_drop_before IS NOT NULL AND p_drop_before > now() - interval '90 days' THEN
    RAISE EXCEPTION 'audit_logs partitions younger than 90 days are never dropped (C-116)'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN QUERY
    SELECT m.action, m.partition_name
      FROM argos.maintain_monthly_partitions('argos.audit_logs'::regclass, p_months_ahead, p_drop_before) AS m;
END;
$$;

REVOKE ALL ON FUNCTION audit_logs_maintain_partitions(integer, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_logs_maintain_partitions(integer, timestamptz) TO argos_audit;
