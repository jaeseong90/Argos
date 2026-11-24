// SPDX-License-Identifier: Apache-2.0
//
// API bodies of the administration screens for component tests (docs/14 §4.3, §4.14 schemas).
// Test-only module.
import type { App } from '../../../api/admin/apps';
import type { Instance, InstanceCollection } from '../../../api/admin/instances';
import type { PendingRegistration } from '../../../api/admin/pending';
import type { AgentToken } from '../../../api/admin/tokens';
import type { AdminUser } from '../../../api/admin/users';
import { untrusted } from '../../../api/untrusted';

/**
 * True when the text is anywhere in the document: text nodes, field values or attribute values
 * (where a once-shown secret must not remain after its dialog closed, SEC-02).
 */
export function domContains(text: string): boolean {
  const values = [...document.querySelectorAll<HTMLInputElement>('input, textarea')].map(
    (field) => field.value,
  );
  const attributes = [...document.querySelectorAll('*')].flatMap((element) =>
    [...element.attributes].map((attribute) => attribute.value),
  );
  return [document.body.textContent, ...values, ...attributes].some((value) =>
    value.includes(text),
  );
}

/** A token value of the server's shape (argos_ + 43 base64url characters); a test value. */
export const TOKEN_VALUE = 'argos_TESTtokenONCEvalue0123456789abcdefghijklmno'; // gitleaks:allow (test fixture, not a real token)
export const PASSWORD_VALUE = 'Init-Pass-Test-4f9c2a71b3'; // gitleaks:allow (test fixture, not a real secret)

export function appBody(appId: number, overrides: Partial<App> = {}): App {
  return {
    app_id: appId,
    name: untrusted(`app-${String(appId)}`),
    env: untrusted('prod'),
    group: null,
    apdex_t_ms: 500,
    app_packages: [],
    created_at: '2026-11-01T00:00:00.000Z',
    instances_total: 3,
    instances_up: 2,
    kinds: null,
    last_seen_at: '2026-11-23T10:00:00.000Z',
    recommended_rules_applied: true,
    registered_from_app_id: null,
    registration_policy: 'auto',
    registration_policy_inherited: false,
    slow_threshold_ms: 1000,
    tokens_active: 1,
    ...overrides,
  };
}

export function instanceBody(instanceId: number, overrides: Partial<Instance> = {}): Instance {
  return {
    instance_id: instanceId,
    name: untrusted(`host-${String(instanceId)}:8080`),
    app: { app_id: 1, name: untrusted('app-1'), env: untrusted('prod') },
    agent_version: untrusted('1.0.0'),
    archived_at: null,
    cgroup: null,
    clock_skew_ms: 12,
    compat: 'ok',
    config_drift: null,
    config_hash: untrusted('hash-a'),
    connected_at: '2026-11-23T09:00:00.000Z',
    drops_24h: 0,
    first_seen_at: '2026-11-01T00:00:00.000Z',
    host: untrusted(`host-${String(instanceId)}`),
    ip_mode: 'masked',
    jvm_vendor: null,
    jvm_version: null,
    last_heartbeat_at: '2026-11-23T10:00:00.000Z',
    last_seen_at: '2026-11-23T10:00:00.000Z',
    modules_disabled: [],
    os: null,
    pid: 1234,
    protocol_version: 1,
    query_string_mode: 'masked',
    remote_config_allow: null,
    sql_param_mode: 'masked',
    started_at: '2026-11-23T08:00:00.000Z',
    state: 'UP',
    user_id_mode: 'plain',
    version: untrusted('2.1.0'),
    virtual_threads: false,
    ...overrides,
  };
}

export function collectionBody(
  instanceId: number,
  overrides: Partial<InstanceCollection> = {},
): InstanceCollection {
  return {
    instance_id: instanceId,
    name: untrusted(`host-${String(instanceId)}:8080`),
    drops: 0,
    excluded: 0,
    lag_s: 0.5,
    skew_ms: 0,
    ...overrides,
  };
}

/** A collection health body around the per-instance totals (other series empty). */
export function collectionHealthBody(perInstance: InstanceCollection[]): Record<string, unknown> {
  const series = { ts: [], value: [] };
  return {
    agent_drops: series,
    clock_skew: null,
    excluded: { ts: [], value: [], by_reason: {} },
    ingest_lag_s: series,
    late_rows: null,
    outages: [],
    per_instance: perInstance,
    server_drops: series,
    server_drops_quota: series,
  };
}

export function tokenBody(tokenId: string, overrides: Partial<AgentToken> = {}): AgentToken {
  return {
    token_id: tokenId,
    app_id: 1,
    prefix: `argos_${tokenId.slice(0, 8)}`,
    created_by: untrusted('admin'),
    created_at: '2026-11-01T00:00:00.000Z',
    expires_at: '2027-11-01T00:00:00.000Z',
    grace_until: null,
    instances_using: 0,
    last_used_at: null,
    revoked_at: null,
    state: 'active',
    ...overrides,
  };
}

export function issuedBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    app_id: 1,
    collector_address: null,
    expires_at: '2027-11-24T00:00:00.000Z',
    grace_until: null,
    prefix: 'argos_TESTtoke',
    rotated_token_id: null,
    token: TOKEN_VALUE,
    token_id: '0b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9aa', // gitleaks:allow (test fixture, not a real token)
    ...overrides,
  };
}

export function pendingBody(
  id: number,
  overrides: Partial<PendingRegistration> = {},
): PendingRegistration {
  return {
    id,
    name: untrusted('new-api'),
    env: untrusted('stage'),
    host: untrusted('10.0.0.5'),
    home_app_id: 1,
    attempts: 4,
    first_attempt_at: '2026-11-23T08:00:00.000Z',
    last_attempt_at: '2026-11-23T09:59:00.000Z',
    ...overrides,
  };
}

export function userBody(id: string, overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id,
    username: untrusted(`user-${id.slice(0, 4)}`),
    roles: ['viewer'],
    enabled: true,
    locked_until: null,
    must_change_password: false,
    permissions: [],
    kiosk_session: { allowed: false, ip_cidrs: [] },
    created_at: '2026-11-01T00:00:00.000Z',
    updated_at: '2026-11-01T00:00:00.000Z',
    ...overrides,
  };
}
