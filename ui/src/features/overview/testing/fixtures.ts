// SPDX-License-Identifier: Apache-2.0
//
// Overview responses for tests (docs/14 §4.2 `GET /overview`): typed rows with defaults, a whole
// response body and its meta. Test-only module: nothing in the application imports it.
import type { Overview, OverviewApp, ServerHealth } from '../../../api/overview';
import { untrusted } from '../../../api/untrusted';

/** 60 per-minute sparkline points ending at `end` (epoch ms of the last minute start). */
export function sparkline(
  end: number,
  values: { tps?: number | null; p95?: number | null; err?: number | null } = {},
): OverviewApp['sparkline'] {
  const ts: string[] = [];
  const tps: (number | null)[] = [];
  const p95: (number | null)[] = [];
  const err: (number | null)[] = [];
  for (let i = 59; i >= 0; i--) {
    ts.push(new Date(end - i * 60_000).toISOString());
    tps.push(values.tps === undefined ? 1 + (i % 5) : values.tps);
    p95.push(values.p95 === undefined ? 100 + (i % 7) * 10 : values.p95);
    err.push(values.err === undefined ? (i % 3) / 100 : values.err);
  }
  return { ts, tps, p95, err };
}

export type OverviewAppOverrides = Partial<
  Omit<OverviewApp, 'name' | 'env' | 'group' | 'firing'>
> & {
  readonly env?: string;
  readonly group?: string | null;
  /** null (the default): a row without firing counts, see {@link withoutEngine}. */
  readonly firing?: OverviewApp['firing'] | null;
};

/**
 * A value the API contract no longer allows since the alert engine fills it (ADR-0010 C-212 (2):
 * `firing` and `alerts_firing_total` are always sent), kept by the fixtures so the tests still prove
 * the tolerant reading of a response without it ("not available", never 0; T-UI-007).
 */
function withoutEngine<T>(value: T | null): T {
  return value as T;
}

/** One row: UP, 2/2 instances, values of a quiet application unless overridden. */
export function overviewApp(
  appId: number,
  name: string,
  overrides: OverviewAppOverrides = {},
): OverviewApp {
  const { env, group, firing, ...rest } = overrides;
  return {
    app_id: appId,
    name: untrusted(name),
    env: untrusted(env ?? 'prod'),
    group: group === undefined || group === null ? null : untrusted(group),
    status: 'UP',
    instances: { up: 2, total: 2 },
    tps: 1.5,
    p95_ms: 100,
    error_rate: 0.01,
    active: 0,
    batch_running: 0,
    firing: withoutEngine(firing ?? null),
    last_deploy: null,
    sparkline: sparkline(Date.parse('2026-11-23T10:00:00.000Z')),
    ...rest,
  };
}

export const SERVER: ServerHealth = {
  ingest_lag_s: 1.2,
  disk_usage_ratio: null,
  channel_failures_24h: null,
  sessions: 3,
  cert_expiry_days: null,
};

/** A whole `GET /overview` body. */
export function overviewBody(
  apps: readonly OverviewApp[],
  options: { alerts_firing_total?: number | null; server?: Partial<ServerHealth> } = {},
): Overview {
  return {
    apps: [...apps],
    alerts_firing_total: withoutEngine(options.alerts_firing_total ?? null),
    server: { ...SERVER, ...options.server },
  };
}

/** Response meta (generated at `at`). */
export function overviewMeta(
  at = '2026-11-23T10:00:00.000Z',
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return { request_id: 'req-overview', generated_at: at, ...extra };
}
