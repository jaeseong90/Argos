// SPDX-License-Identifier: Apache-2.0
//
// The `overview` topic on the REST rows (docs/14 §5.2, §5.3; docs/15 §10 "overview → 앱 요약
// 병합"; ADR-0010 C-213 (3): every 5 s the full rows of the tracked applications, latest only).
// WsManager validates the frame envelope and leaves `data` unknown; the payload is validated here
// with a tolerant schema (unknown fields ignored, a missing field keeps the REST value) and a
// payload with a wrong type is dropped as a whole, never partly applied.
//
// Merge rule (plan assumptions 2 and 5): tps, p95_ms, error_rate and active come from the push;
// the status is the worse of the push and REST, because the push counts only instances with a
// session or a recent state change while REST counts every instance that is not archived (one
// that never connected since the server started is DOWN there); applications missing from the
// push keep their REST values; a `firing` that is not `{critical, warn}` (null before the alert
// engine's first report, C-212 (2)) keeps the REST value. A push older than the REST answer is not
// applied (the REST values are newer).
import type { OverviewApp } from '../../api/overview';
import { z } from '../../app/zod';
import { normalizeStatus } from '../../components/StatusBadge';

import { firingCounts, worseStatus } from './model';

/** Most rows accepted from one push (the overview lists at most 500 applications). */
export const MAX_PUSH_APPS = 1_000;

// zod 4 numbers are finite (NaN and ±Infinity fail).
const Value = z.number().nullable().optional();

const OverviewPushData = z.object({
  apps: z
    .array(
      z.object({
        app_id: z.number().int().positive(),
        status: z.string().max(16).optional(),
        tps: Value,
        p95_ms: Value,
        error_rate: Value,
        active: Value,
        firing: z.unknown().optional(),
      }),
    )
    .max(MAX_PUSH_APPS),
});

type PushValue = number | null | undefined;

/** One application of a push (undefined = not sent). */
export interface OverviewPushApp {
  readonly app_id: number;
  readonly status: string | undefined;
  readonly tps: PushValue;
  readonly p95_ms: PushValue;
  readonly error_rate: PushValue;
  readonly active: PushValue;
  readonly firing: unknown;
}

/** A validated `overview` push at the server time `t`. */
export interface OverviewPush {
  /** Server time of the push, epoch ms. */
  readonly t: number;
  readonly apps: ReadonlyMap<number, OverviewPushApp>;
}

/** The push of a frame, or null when its time or payload is invalid (the frame is ignored). */
export function parseOverviewPush(t: string, data: unknown): OverviewPush | null {
  const time = Date.parse(t);
  if (!Number.isFinite(time)) {
    return null;
  }
  const parsed = OverviewPushData.safeParse(data);
  if (!parsed.success) {
    return null;
  }
  const apps = new Map<number, OverviewPushApp>();
  for (const app of parsed.data.apps) {
    apps.set(app.app_id, {
      app_id: app.app_id,
      status: app.status,
      tps: app.tps,
      p95_ms: app.p95_ms,
      error_rate: app.error_rate,
      active: app.active,
      firing: app.firing,
    });
  }
  return { t: time, apps };
}

function pick(push: PushValue, rest: number | null): number | null {
  return push === undefined ? rest : push;
}

/**
 * The REST rows with the push on top (see the file comment). `restAt` is the REST answer's
 * `generated_at` (epoch ms), or null when unknown (then the push applies).
 */
export function mergeLive(
  apps: readonly OverviewApp[],
  push: OverviewPush | null,
  restAt: number | null,
): OverviewApp[] {
  if (push === null || (restAt !== null && push.t < restAt)) {
    return [...apps];
  }
  return apps.map((app) => {
    const live = push.apps.get(app.app_id);
    if (live === undefined) {
      return app;
    }
    return {
      ...app,
      // A status the UI does not know (or none) leaves the REST status.
      status:
        normalizeStatus(live.status) === 'UNKNOWN'
          ? app.status
          : worseStatus(app.status, live.status),
      tps: pick(live.tps, app.tps),
      p95_ms: pick(live.p95_ms, app.p95_ms),
      error_rate: pick(live.error_rate, app.error_rate),
      active: pick(live.active, app.active),
      firing: firingCounts(live.firing) ?? app.firing,
    };
  });
}
