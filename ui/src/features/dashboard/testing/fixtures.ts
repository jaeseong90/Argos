// SPDX-License-Identifier: Apache-2.0
//
// Dashboard responses for the screen's tests (docs/14 §4.4 dashboard_get, instances_compare): a
// grid of buckets with gaps (null) and zeros kept apart, XSS payloads in collected strings (pool
// names, event titles, instance names) and an outage. Test-only module: nothing in the
// application imports it.
import type { ResponseMeta } from '../../../api/client';
import type { Dashboard, DashboardInstance, InstanceComparison } from '../../../api/dashboard';
import { untrusted } from '../../../api/untrusted';

export const XSS_POOL = 'pool"><img src=x onerror="window.__xss=1">';
export const XSS_EVENT_TITLE = 'deploy <script>window.__xss=1</script>';
export const XSS_INSTANCE = 'web-"><svg/onload=window.__xss=1>';

export interface DashboardFixture {
  /** First bucket (epoch ms). */
  readonly from: number;
  readonly buckets?: number;
  readonly stepMs?: number;
  readonly baseline?: '1d' | '7d' | null;
  readonly basis?: 'prev' | '7d';
  /** Σ count of the range (0 = the empty state). */
  readonly count?: number;
  readonly withOutage?: boolean;
  readonly extErrors?: boolean;
  readonly active?: boolean;
}

function grid(n: number, value: (i: number) => number | null): (number | null)[] {
  return Array.from({ length: n }, (_, i) => value(i));
}

export function instanceRows(): DashboardInstance[] {
  return [
    {
      instance_id: 12,
      name: untrusted(XSS_INSTANCE),
      state: 'UP',
      tps: 6,
      avg_ms: 70,
      p95_ms: 280,
      error_rate: 0,
      active: 3,
      count: 500,
      warn_reasons: [],
    },
    {
      instance_id: 13,
      name: untrusted('web-02'),
      state: 'DOWN',
      tps: null,
      avg_ms: null,
      p95_ms: null,
      error_rate: null,
      active: null,
      count: 0,
      warn_reasons: [],
    },
  ];
}

/**
 * `GET /instances/compare` rows (docs/14 instances_compare): instance 12 (XSS name) flagged on
 * p95 and CPU, 13 with no values (DOWN, flags null), 14 flagged on the error rate. The flags are
 * the server's (C-177) and deliberately do not follow from the values: the UI must not recompute.
 */
export function compareRows(): InstanceComparison[] {
  return [
    {
      instance_id: 12,
      name: untrusted(XSS_INSTANCE),
      state: 'UP',
      tps: 6,
      avg_ms: 70,
      p95_ms: 280,
      error_rate: 0,
      active: 3,
      count: 500,
      heap_ratio: 0.42,
      cpu: 0.31,
      pool_usage_ratio: 0.2,
      uptime_s: 7200,
      outlier: { p95: true, error_rate: false, cpu: true },
    },
    {
      instance_id: 13,
      name: untrusted('web-02'),
      state: 'DOWN',
      tps: null,
      avg_ms: null,
      p95_ms: null,
      error_rate: null,
      active: null,
      count: 0,
      heap_ratio: null,
      cpu: null,
      pool_usage_ratio: null,
      uptime_s: null,
      outlier: { p95: null, error_rate: null, cpu: null },
    },
    {
      instance_id: 14,
      name: untrusted('web-03'),
      state: 'UP',
      tps: 9.5,
      avg_ms: 60,
      p95_ms: 250,
      error_rate: 0.04,
      active: 0,
      count: 800,
      heap_ratio: 0,
      cpu: 0.12,
      pool_usage_ratio: 0,
      uptime_s: 60,
      outlier: { p95: false, error_rate: true, cpu: false },
    },
  ];
}

/** A dashboard response over `buckets` buckets of `stepMs` from `from`. */
export function dashboardBody(fixture: DashboardFixture): Dashboard {
  const n = fixture.buckets ?? 10;
  const step = fixture.stepMs ?? 60_000;
  const from = fixture.from;
  const ts = grid(n, (i) => from + i * step).map((t) => new Date(t ?? 0).toISOString());
  const count = fixture.count ?? 1000;
  const empty = count === 0;
  // Bucket 1 is a gap (null), bucket 2 a measured zero.
  const tx = (value: number) =>
    grid(n, (i) => (empty ? null : i === 1 ? null : i === 2 ? 0 : value + i));
  const body: Dashboard = {
    tiles: {
      tps: empty ? null : 12.5,
      avg_ms: empty ? null : 80,
      p95_ms: empty ? null : 300,
      p99_ms: null,
      error_rate: empty ? null : 0,
      active: 7,
      count,
      delta: {
        basis: fixture.basis ?? (fixture.baseline === '7d' ? '7d' : 'prev'),
        tps: empty ? null : fixture.basis === '7d' || fixture.baseline === '7d' ? 0.25 : 0.1,
        avg_ms: empty ? null : 0.08,
        p95_ms: empty ? null : -0.05,
        // the p99 tile has no value, so neither has its change
        p99_ms: null,
        error_rate: empty ? null : 0.002,
        // live 7 against a basis mean of 5
        active: 0.4,
      },
    },
    series: {
      ts,
      count: grid(n, (i) => (empty ? 0 : i === 1 ? null : Math.round(count / n))),
      tps: tx(10),
      avg_ms: tx(80),
      p95_ms: tx(300),
      p99_ms: tx(900),
      error_rate: grid(n, (i) => (empty ? null : i === 2 ? 0 : 0.01)),
      active: fixture.active === true ? grid(n, (i) => i) : grid(n, () => null),
      heap_used: grid(n, () => 256 * 1024 * 1024),
      heap_max: grid(n, () => 1024 * 1024 * 1024),
      gc_pause_ms: grid(n, () => 4),
      cpu: grid(n, () => 0.25),
      // bucket 1 has no system CPU sample: a gap, not 0
      system_cpu: grid(n, (i) => (i === 1 ? null : 0.4)),
      tomcat_busy_ratio: grid(n, () => 0.1),
      ext_time_ms: grid(n, () => 20),
      ext_errors: fixture.extErrors === false ? null : grid(n, () => 0),
      pool_usage_ratio: {
        HikariPool: grid(n, () => 0.3),
        [XSS_POOL]: grid(n, () => 0.6),
      },
    },
    events: [
      {
        event_id: 'ev-deploy',
        ts: new Date(from + 2 * step).toISOString(),
        type: 'deploy',
        title: untrusted(XSS_EVENT_TITLE),
        version: untrusted('2.0.0'),
        detail: null,
        detail_truncated: false,
        instance_id: null,
        actor: null,
      },
      {
        event_id: 'ev-alert',
        ts: new Date(from + 6 * step).toISOString(),
        type: 'alert',
        title: untrusted('p95 above 1 s'),
        version: null,
        detail: null,
        detail_truncated: false,
        instance_id: null,
        actor: null,
      },
    ],
    outages:
      fixture.withOutage === false
        ? []
        : [
            {
              from: new Date(from + 3 * step).toISOString(),
              to: new Date(from + 4 * step).toISOString(),
              reason: 'down',
              instance_id: 12,
            },
          ],
    instances: instanceRows(),
    collection: {
      agent_drops: 0,
      server_drops: null,
      server_drops_quota: null,
      ingest_lag_s: 1.2,
      clock_skew_instances: null,
      excluded: 1204,
      late: 0,
      batch_running: null,
    },
    // A response without the alert engine's list (out of the C-212 (2) contract, which always sends
    // an array): the tests prove the panel reads it as "not available" (T-UI-007).
    firing: null as unknown as Dashboard['firing'],
  };
  if (fixture.baseline !== undefined && fixture.baseline !== null) {
    body.baseline = {
      offset: fixture.baseline,
      // The server shifts the baseline buckets forward onto the range's buckets (docs/14 §4.4).
      ts: [...ts],
      count: grid(n, () => 90),
      tps: grid(n, () => 9),
      avg_ms: grid(n, () => 75),
      p95_ms: grid(n, () => 290),
      p99_ms: grid(n, () => 850),
      error_rate: grid(n, () => 0.005),
    };
  }
  return body;
}

/** Response meta of a dashboard over [from, from + buckets × step). */
export function dashboardMeta(
  from: number,
  buckets = 10,
  stepMs = 60_000,
  overrides: Partial<ResponseMeta> = {},
): ResponseMeta {
  return {
    request_id: 'req-dashboard-1',
    generated_at: new Date(from + buckets * stepMs).toISOString(),
    from: new Date(from).toISOString(),
    to: new Date(from + buckets * stepMs).toISOString(),
    source: 'raw',
    ...overrides,
  };
}
