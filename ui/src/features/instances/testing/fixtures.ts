// SPDX-License-Identifier: Apache-2.0
//
// Test fixtures of the instance screens (WO-37): an instance as GET /instances/{id} answers it,
// metric names and series as GET /instances/{id}/metrics(/names) answer them. Test-only module.
import type {
  EffectiveConfigEntry,
  Instance,
  InstanceMetrics,
  MetricName,
  MetricSeries,
} from '../../../api/instances';
import { untrusted } from '../../../api/untrusted';

export const NOW = Date.parse('2026-11-20T12:00:00.000Z');
export const FROM = Date.parse('2026-11-20T11:00:00.000Z');
export const TO = NOW;
export const RANGE_QUERY = `from=${new Date(FROM).toISOString()}&to=${new Date(TO).toISOString()}`;

/** An instance of app 1 (order-api, prod) with every field set. */
export function instanceBody(overrides: Partial<Instance> = {}): Instance {
  return {
    instance_id: 7,
    app: { app_id: 1, name: untrusted('order-api'), env: untrusted('prod') },
    name: untrusted('order-api-1'),
    host: untrusted('host-a.internal'),
    pid: 4242,
    state: 'UP',
    agent_version: untrusted('1.0.3'),
    compat: 'ok',
    config_hash: untrusted('a1b2c3'),
    config_drift: false,
    last_heartbeat_at: '2026-11-20T11:59:55.000Z',
    clock_skew_ms: 12,
    drops_24h: 0,
    modules_disabled: [],
    ip_mode: 'masked',
    user_id_mode: 'off',
    query_string_mode: 'off',
    sql_param_mode: 'off',
    virtual_threads: true,
    cgroup: untrusted('v2'),
    jvm_version: untrusted('21.0.5'),
    jvm_vendor: untrusted('Eclipse Adoptium'),
    os: untrusted('Linux 6.8 amd64'),
    protocol_version: 1,
    remote_config_allow: [untrusted('sampling.*'), untrusted('log.level')],
    started_at: '2026-11-18T10:00:00.000Z',
    connected_at: '2026-11-18T10:00:05.000Z',
    first_seen_at: '2026-11-01T00:00:00.000Z',
    last_seen_at: '2026-11-20T11:59:55.000Z',
    archived_at: null,
    version: untrusted('2.4.1'),
    ...overrides,
  };
}

/** An instance with every nullable field null. */
export function bareInstance(overrides: Partial<Instance> = {}): Instance {
  return instanceBody({
    host: null,
    pid: null,
    agent_version: null,
    compat: null,
    config_hash: null,
    config_drift: null,
    last_heartbeat_at: null,
    clock_skew_ms: null,
    drops_24h: null,
    modules_disabled: null,
    ip_mode: null,
    virtual_threads: null,
    cgroup: null,
    jvm_version: null,
    jvm_vendor: null,
    os: null,
    protocol_version: null,
    remote_config_allow: null,
    started_at: null,
    connected_at: null,
    version: null,
    ...overrides,
  });
}

/** A stored series name with labels (`metric{a="x"}`). */
export function seriesName(metric: string, labels: Record<string, string> = {}): string {
  const parts = Object.entries(labels).map(([key, value]) => `${key}="${value}"`);
  return parts.length === 0 ? metric : `${metric}{${parts.join(',')}}`;
}

/** A metrics/names row. */
export function metricName(
  metric: string,
  labels: Record<string, string> = {},
  kind: 'gauge' | 'counter' = 'gauge',
): MetricName {
  const branded: Record<string, ReturnType<typeof untrusted>> = {};
  for (const [key, value] of Object.entries(labels)) {
    branded[key] = untrusted(value);
  }
  return {
    name: untrusted(seriesName(metric, labels)),
    metric: untrusted(metric),
    labels: branded,
    kind,
    unit: null,
    last_seen_at: '2026-11-20T11:00:00.000Z',
  };
}

/** Bucket starts of a grid from `from`, `count` buckets of `stepMs`. */
export function grid(from: number, count: number, stepMs = 60_000): string[] {
  return Array.from({ length: count }, (_, i) => new Date(from + i * stepMs).toISOString());
}

/** A series of a metrics answer on `ts`. */
export function series(
  name: MetricName,
  ts: readonly string[],
  values: readonly (number | null)[],
): MetricSeries {
  return {
    name: name.name,
    metric: name.metric,
    labels: name.labels,
    kind: name.kind,
    unit: name.unit,
    ts: [...ts],
    value: [...values],
    min: [...values],
    max: [...values],
  };
}

/** A metrics answer of these series on `ts`. */
export function metricsBody(ts: readonly string[], list: readonly MetricSeries[]): InstanceMetrics {
  return { ts: [...ts], series: [...list] };
}

/** An effective-config row. */
export function configEntry(
  key: string,
  overrides: Partial<EffectiveConfigEntry> = {},
): EffectiveConfigEntry {
  return {
    key,
    remote_allowed: true,
    server_status: null,
    server_value: null,
    source: null,
    value: null,
    ...overrides,
  };
}
