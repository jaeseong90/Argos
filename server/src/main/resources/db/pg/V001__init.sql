-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V001 (docs/13-storage-design.md section 12; C-11 and C-16 of docs/02
-- section 13; ADR-0010 C-97 and C-98). Flyway applies it with the schema owner account in the
-- POSTGRES_MIGRATION start phase (13 section 13.1). The roles argos_app, argos_audit and
-- argos_backup must exist: deploy/postgres/roles.sql creates them (run by the installer).
--
-- Rules for this file and the next ones (C-28; deploy/postgres/README.md):
--   * name V<NNN>__<lower_snake>.sql, numbers contiguous from 001 (checkFlywayNames, Flyway);
--   * a released file never changes (Flyway refuses a changed checksum): changes are V<next>,
--     added by the work order that owns the table (C-98);
--   * no default privileges: every new table grants explicitly, argos_app the DML it needs
--     (append-only tables: INSERT and SELECT), argos_backup SELECT, argos_app USAGE on sequences.
-- Deviations from the 13 section 12 text (WO-20 report):
--   * dict_kind is the C-16 list;
--   * alert_events: primary key (id, ts), because a unique constraint of a partitioned table must
--     contain the partition key (audit_logs does the same);
--   * notification_deliveries.alert_event_id has no foreign key: a foreign key to the partitioned
--     alert_events would need ts and would block dropping expired partitions;
--   * monthly partitions of alert_events and audit_logs come from argos.maintain_monthly_partitions
--     (end of this file): the current and the next two months now, the partition job (WO-28) keeps
--     two months ahead and drops expired months.

-- roles.sql has usually created the schema and pg_trgm already: the IF NOT EXISTS statements below
-- would then report "already exists, skipping" NOTICEs, which Flyway logs as WARN on every new
-- installation. Only warnings and errors reach the client during this migration.
SET LOCAL client_min_messages = warning;

CREATE SCHEMA IF NOT EXISTS argos;
SET search_path TO argos, public;

-- pg_trgm (13 section 17 item 15): deploy/postgres/roles.sql creates it as superuser; this
-- statement covers a database prepared otherwise (pg_trgm is a trusted extension: the database
-- owner may create it).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 12.1 authentication and authorization
CREATE TABLE users (
  id            UUID PRIMARY KEY,
  username      VARCHAR(64) NOT NULL UNIQUE,
  display_name  VARCHAR(128),
  password_hash VARCHAR(100) NOT NULL,            -- bcrypt cost 12 (SEC-48)
  role          VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  failed_logins INT NOT NULL DEFAULT 0,
  locked_until  TIMESTAMPTZ,
  kiosk_allowed BOOLEAN NOT NULL DEFAULT FALSE,   -- F-U21 long session (administrator approval)
  kiosk_ip_allowlist CIDR[],
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_sessions (                      -- SEC-42
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash  CHAR(64) NOT NULL UNIQUE,         -- SHA-256(refresh token)
  issued_at     TIMESTAMPTZ NOT NULL,
  last_seen_at  TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,             -- absolute expiry (24 h or kiosk setting)
  revoked_at    TIMESTAMPTZ,
  user_agent    VARCHAR(256),
  ip            INET,
  kiosk         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_user_sessions_user ON user_sessions(user_id) WHERE revoked_at IS NULL;

-- 12.2 applications, instances, tokens
CREATE TABLE apps (
  id            SERIAL PRIMARY KEY,                -- ClickHouse app_id (UInt32)
  name          VARCHAR(128) NOT NULL,
  env           VARCHAR(32)  NOT NULL,
  app_group     VARCHAR(64),
  apdex_t_ms    INT NOT NULL DEFAULT 500,
  slow_threshold_ms INT NOT NULL DEFAULT 1000,
  auto_register BOOLEAN NOT NULL DEFAULT FALSE,
  recommended_rules_applied BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (name, env)                               -- DSN-02-14
);

CREATE TABLE instances (
  id              SERIAL PRIMARY KEY,              -- ClickHouse instance_id (UInt32)
  app_id          INT NOT NULL REFERENCES apps(id),
  name            VARCHAR(128) NOT NULL,           -- configured name -> host:port -> host:pid
  host            VARCHAR(255), pid INT,
  jvm_version     VARCHAR(64), jvm_vendor VARCHAR(64), os VARCHAR(64),
  cgroup          VARCHAR(8),                      -- v1|v2|none
  virtual_threads BOOLEAN,
  agent_version   VARCHAR(32), protocol_version INT,
  version_tag     VARCHAR(128),
  ip_mode         VARCHAR(8), user_id_mode VARCHAR(8), query_string_mode VARCHAR(10), sql_param_mode VARCHAR(8),
  config_hash     CHAR(16),
  remote_config_allow TEXT[],                      -- SEC-23 keys the agent allows
  status          VARCHAR(8) NOT NULL DEFAULT 'DOWN', -- UP|STALE|DOWN (F-S28)
  warn_flags      TEXT[],                          -- clock_skew|drops|old_version|module_disabled
  clock_offset_ms INT,
  first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at    TIMESTAMPTZ,
  started_at      TIMESTAMPTZ,
  archived_at     TIMESTAMPTZ,                     -- archived after 7 days without connection
  UNIQUE (app_id, name)
);
CREATE INDEX ix_instances_app_status ON instances(app_id, status) WHERE archived_at IS NULL;

CREATE TABLE agent_tokens (                        -- SEC-01..05
  id           UUID PRIMARY KEY,
  app_id       INT NOT NULL REFERENCES apps(id),
  token_hash   CHAR(64) NOT NULL UNIQUE,           -- SHA-256
  token_prefix CHAR(14) NOT NULL,                  -- 'argos_' + 8 characters
  issued_by    UUID REFERENCES users(id),
  issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ,
  grace_until  TIMESTAMPTZ,                        -- rotation grace period
  revoked_at   TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ,
  status       VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' -- ACTIVE|GRACE|REVOKED|EXPIRED
);
CREATE INDEX ix_agent_tokens_app ON agent_tokens(app_id) WHERE status IN ('ACTIVE','GRACE');

CREATE TABLE pending_registrations (               -- F-S30 approval queue
  id SERIAL PRIMARY KEY, name VARCHAR(128), env VARCHAR(32), host VARCHAR(255),
  first_attempt_at TIMESTAMPTZ, last_attempt_at TIMESTAMPTZ, attempts INT, UNIQUE (name, env, host)
);

-- 12.3 dictionary (kinds: C-16)
CREATE TYPE dict_kind AS ENUM ('service','sql','class','method','thread','host','frame','metric',
                               'error_group','db_target','app_version');
CREATE TABLE dictionary (
  app_id       INT NOT NULL,
  kind         dict_kind NOT NULL,
  hash64       BIGINT NOT NULL,                    -- xxh3_64 stored as signed
  text         TEXT NOT NULL,                      -- at most 64 KiB (truncated beyond)
  truncated    BOOLEAN NOT NULL DEFAULT FALSE,
  norm_version SMALLINT,                           -- sql: normalizer version (D-22)
  first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (app_id, kind, hash64)
);
CREATE INDEX ix_dictionary_text_trgm ON dictionary USING gin (text gin_trgm_ops);   -- global search (F-U14)
CREATE INDEX ix_dictionary_last_seen ON dictionary(last_seen);                      -- 90-day cleanup (SEC-13)

CREATE TABLE error_groups (                        -- F-S19
  app_id          INT NOT NULL,
  group_id        BIGINT NOT NULL,                  -- xxh3_64(class + masked message pattern + top app frame)
  error_class     VARCHAR(512),
  message_pattern TEXT,                             -- masked, normalized pattern (SEC-36)
  top_frame       VARCHAR(1024),
  sample_stack    TEXT,                             -- one representative stack (masked)
  first_seen      TIMESTAMPTZ NOT NULL, last_seen TIMESTAMPTZ NOT NULL,
  count_total     BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (app_id, group_id)
);

-- 12.4 alerting
CREATE TABLE alert_rules (
  id UUID PRIMARY KEY, app_id INT REFERENCES apps(id), env VARCHAR(32), name VARCHAR(128) NOT NULL,
  template_key VARCHAR(64), enabled BOOLEAN NOT NULL DEFAULT TRUE,
  spec JSONB NOT NULL,                              -- 02 section 5.5 rule model
  created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version INT NOT NULL DEFAULT 1
);
CREATE TABLE alert_rule_history (rule_id UUID, version INT, spec JSONB, changed_by UUID, changed_at TIMESTAMPTZ, PRIMARY KEY (rule_id, version));

CREATE TABLE alert_events (                        -- state transitions only (not evaluation ticks)
  id UUID NOT NULL, rule_id UUID NOT NULL REFERENCES alert_rules(id), app_id INT, env VARCHAR(32),
  target_kind VARCHAR(16), target_id BIGINT, target_name VARCHAR(256),
  from_state VARCHAR(10), to_state VARCHAR(10),    -- OK|PENDING|FIRING|ACKED|RESOLVED|NO_DATA
  severity VARCHAR(8), value DOUBLE PRECISION, threshold DOUBLE PRECISION, duration_s INT,
  acked_by UUID, ack_note VARCHAR(512),
  ts TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id, ts)                             -- partition key in the key (see header)
) PARTITION BY RANGE (ts);                         -- monthly partitions, retention 365 days
CREATE INDEX ix_alert_events_rule_ts ON alert_events(rule_id, ts DESC);
CREATE INDEX ix_alert_events_app_ts  ON alert_events(app_id, ts DESC);

CREATE TABLE notification_channels (
  id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL, type VARCHAR(16) NOT NULL,   -- email|slack|webhook
  config_enc BYTEA NOT NULL,                       -- AES-256-GCM (master key, SEC-52)
  config_masked JSONB NOT NULL,                    -- masked copy for the UI
  signing_key_enc BYTEA,                           -- webhook signing key (SEC-53)
  enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ
);
CREATE TABLE notification_deliveries (             -- F-S27 delivery result per channel
  id UUID PRIMARY KEY, alert_event_id UUID, channel_id UUID REFERENCES notification_channels(id),
  attempt INT, status VARCHAR(8), http_status INT, error VARCHAR(512), sent_at TIMESTAMPTZ
);
CREATE INDEX ix_deliveries_channel_sent ON notification_deliveries(channel_id, sent_at DESC);

-- 12.5 commands, configuration changes, events
CREATE TABLE commands (
  id UUID PRIMARY KEY, instance_id INT NOT NULL REFERENCES instances(id), type VARCHAR(32) NOT NULL,
  requested_by UUID NOT NULL REFERENCES users(id), requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,                  -- TTL 60 s (SEC-24)
  status VARCHAR(10) NOT NULL,                      -- QUEUED|SENT|DONE|FAILED|EXPIRED|REJECTED
  finished_at TIMESTAMPTZ, duration_ms INT, error VARCHAR(512)
);
CREATE TABLE command_results (
  command_id UUID PRIMARY KEY REFERENCES commands(id) ON DELETE CASCADE,
  body_enc BYTEA NOT NULL,                          -- gzip -> AES-256-GCM (SEC-22)
  body_size INT NOT NULL, truncated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()     -- retention 30 days
);

CREATE TABLE config_changes (                      -- F-S29
  id UUID PRIMARY KEY, app_id INT NOT NULL, target_instance_ids INT[],
  reason TEXT NOT NULL, before JSONB NOT NULL, after JSONB NOT NULL,
  requested_by UUID NOT NULL, requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reverted_from UUID REFERENCES config_changes(id)
);
CREATE TABLE config_change_results (
  change_id UUID REFERENCES config_changes(id) ON DELETE CASCADE, instance_id INT,
  status VARCHAR(10), rejected_keys TEXT[], applied_at TIMESTAMPTZ, error VARCHAR(512), PRIMARY KEY (change_id, instance_id)
);

CREATE TABLE events (                              -- F-S18 deployments and automatic events
  id UUID PRIMARY KEY, app_id INT, env VARCHAR(32), instance_id INT,
  type VARCHAR(24) NOT NULL,                        -- deploy|start|stop|config|state|apdex_t_change|retention_emergency|kill_switch
  title VARCHAR(256), detail JSONB, version VARCHAR(128), ts TIMESTAMPTZ NOT NULL, created_by UUID
);
CREATE INDEX ix_events_app_ts ON events(app_id, ts DESC);

-- 12.6 audit, settings, privacy jobs, ClickHouse migration history
CREATE TABLE audit_logs (                          -- SEC-80..82. argos_app: INSERT and SELECT only
  id BIGSERIAL, ts TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_type VARCHAR(8) NOT NULL, actor_id VARCHAR(64), actor_name VARCHAR(128),
  ip INET, user_agent VARCHAR(256), request_id VARCHAR(64),
  action VARCHAR(64) NOT NULL, target_type VARCHAR(32), target_id VARCHAR(64), target_name VARCHAR(256),
  result VARCHAR(8) NOT NULL, reason VARCHAR(512),
  before JSONB, after JSONB,                        -- secrets masked
  subject_user_id VARCHAR(128), subject_tx_id BIGINT,   -- subject of a personal data access
  PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);                         -- monthly partitions, retention 365 days (setting)
CREATE INDEX ix_audit_ts ON audit_logs(ts DESC);
CREATE INDEX ix_audit_actor_ts ON audit_logs(actor_id, ts DESC);

CREATE TABLE settings (key VARCHAR(128) PRIMARY KEY, value JSONB NOT NULL, updated_by UUID, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE privacy_jobs (                        -- 13 section 9
  id UUID PRIMARY KEY, subject_kind VARCHAR(8) NOT NULL, subject_hash CHAR(64) NOT NULL, app_id INT,
  from_ts TIMESTAMPTZ, to_ts TIMESTAMPTZ, mutation_ids TEXT[], status VARCHAR(10), created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ, error VARCHAR(512)
);

CREATE TABLE ch_schema_history (                   -- 13 section 13 (written by the ClickHouse migration runner)
  version VARCHAR(32) PRIMARY KEY, description VARCHAR(256), checksum CHAR(64) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), duration_ms INT, success BOOLEAN NOT NULL, error TEXT
);

-- Monthly partitions of alert_events and audit_logs.
--   Rule: one partition per UTC calendar month, named <table>_pYYYYMM, bounds [first day of the
--   month 00:00 UTC, first day of the next month 00:00 UTC). There is no default partition: a row
--   outside every partition is refused, so the partition job (WO-28) must keep at least the current
--   and the next month (it calls the functions below daily with months_ahead = 2). Partitions are
--   dropped only as whole past months (never the current month or later).
--   maintain_monthly_partitions runs with the rights of its caller (here: the schema owner); the
--   two wrappers are SECURITY DEFINER (owner) and are the only way for the runtime roles:
--     audit_logs_maintain_partitions   EXECUTE: argos_audit only (audit retention job, 12 section 8.1)
--     alert_events_maintain_partitions EXECUTE: argos_app (alert retention, 13 section 12 table)
--   New partitions are readable by argos_backup; argos_app reaches them through the parent table only.
CREATE FUNCTION maintain_monthly_partitions(p_parent regclass, p_months_ahead integer,
                                            p_drop_before timestamptz)
  RETURNS TABLE (action text, partition_name text)
  LANGUAGE plpgsql
  SET search_path = argos, pg_temp
AS $$
DECLARE
  v_parent  text;
  v_current date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
  v_from    date;
  v_name    text;
  v_upper   timestamptz;
  r         record;
BEGIN
  SELECT c.relname INTO v_parent
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.oid = p_parent AND n.nspname = 'argos' AND c.relkind = 'p';
  IF v_parent IS NULL OR v_parent NOT IN ('alert_events', 'audit_logs') THEN
    RAISE EXCEPTION 'monthly partitions are maintained for argos.alert_events and argos.audit_logs only';
  END IF;
  IF p_months_ahead IS NULL OR p_months_ahead < 0 OR p_months_ahead > 24 THEN
    RAISE EXCEPTION 'months ahead must be between 0 and 24';
  END IF;
  FOR i IN 0 .. p_months_ahead LOOP
    v_from := (v_current + make_interval(months => i))::date;
    v_name := v_parent || '_p' || to_char(v_from, 'YYYYMM');
    IF to_regclass('argos.' || v_name) IS NULL THEN
      EXECUTE format('CREATE TABLE argos.%I PARTITION OF argos.%I FOR VALUES FROM (%L) TO (%L)',
                     v_name, v_parent,
                     v_from::timestamp AT TIME ZONE 'UTC',
                     (v_from + interval '1 month')::timestamp AT TIME ZONE 'UTC');
      EXECUTE format('GRANT SELECT ON argos.%I TO argos_backup', v_name);
      action := 'created';
      partition_name := v_name;
      RETURN NEXT;
    END IF;
  END LOOP;
  IF p_drop_before IS NOT NULL THEN
    IF p_drop_before > v_current::timestamp AT TIME ZONE 'UTC' THEN
      RAISE EXCEPTION 'partitions of the current month or later are never dropped';
    END IF;
    FOR r IN
      SELECT child.relname AS name, pg_get_expr(child.relpartbound, child.oid) AS bound
        FROM pg_inherits inh JOIN pg_class child ON child.oid = inh.inhrelid
       WHERE inh.inhparent = p_parent
       ORDER BY child.relname
    LOOP
      v_upper := substring(r.bound FROM 'TO \(''([^'']+)''\)')::timestamptz;
      IF v_upper IS NOT NULL AND v_upper <= p_drop_before THEN
        EXECUTE format('DROP TABLE argos.%I', r.name);
        action := 'dropped';
        partition_name := r.name;
        RETURN NEXT;
      END IF;
    END LOOP;
  END IF;
END;
$$;

CREATE FUNCTION audit_logs_maintain_partitions(p_months_ahead integer, p_drop_before timestamptz)
  RETURNS TABLE (action text, partition_name text)
  LANGUAGE sql
  SECURITY DEFINER
  SET search_path = argos, pg_temp
AS $$
  SELECT * FROM argos.maintain_monthly_partitions('argos.audit_logs'::regclass, p_months_ahead, p_drop_before)
$$;

CREATE FUNCTION alert_events_maintain_partitions(p_months_ahead integer, p_drop_before timestamptz)
  RETURNS TABLE (action text, partition_name text)
  LANGUAGE sql
  SECURITY DEFINER
  SET search_path = argos, pg_temp
AS $$
  SELECT * FROM argos.maintain_monthly_partitions('argos.alert_events'::regclass, p_months_ahead, p_drop_before)
$$;

REVOKE ALL ON FUNCTION maintain_monthly_partitions(regclass, integer, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_logs_maintain_partitions(integer, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION alert_events_maintain_partitions(integer, timestamptz) FROM PUBLIC;

SELECT count(*) FROM maintain_monthly_partitions('argos.alert_events'::regclass, 2, NULL);
SELECT count(*) FROM maintain_monthly_partitions('argos.audit_logs'::regclass, 2, NULL);

-- Privileges (C-11, ADR-0010 C-97, SEC-76, DSN-12-13). The schema owner (Flyway) owns every object.
REVOKE ALL ON SCHEMA argos FROM PUBLIC;
GRANT USAGE ON SCHEMA argos TO argos_app, argos_audit, argos_backup;

-- argos_app (server runtime): DML on every application table, no DDL (no CREATE on the schema).
GRANT SELECT, INSERT, UPDATE, DELETE ON
  users, user_sessions, apps, instances, agent_tokens, pending_registrations,
  dictionary, error_groups,
  alert_rules, alert_rule_history, alert_events, notification_channels, notification_deliveries,
  commands, command_results, config_changes, config_change_results, events,
  settings, privacy_jobs
  TO argos_app;
GRANT USAGE ON SEQUENCE apps_id_seq, instances_id_seq, pending_registrations_id_seq, audit_logs_id_seq
  TO argos_app;
-- audit_logs is append-only for the runtime (SEC-82): INSERT and SELECT, never UPDATE, DELETE or
-- TRUNCATE; expired months are dropped by argos_audit through audit_logs_maintain_partitions.
GRANT SELECT, INSERT ON audit_logs TO argos_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM argos_app;
-- ClickHouse migration history: written by the owner during the start, read by the runtime.
GRANT SELECT ON ch_schema_history TO argos_app;
GRANT EXECUTE ON FUNCTION alert_events_maintain_partitions(integer, timestamptz) TO argos_app;

-- argos_audit (audit partition retention job, 12 section 8.1): the partition function only.
GRANT EXECUTE ON FUNCTION audit_logs_maintain_partitions(integer, timestamptz) TO argos_audit;

-- argos_backup (read-only dump, 13 section 10): SELECT on every table, partition and sequence,
-- including flyway_schema_history (created by Flyway before this script).
GRANT SELECT ON ALL TABLES IN SCHEMA argos TO argos_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA argos TO argos_backup;
