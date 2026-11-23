// SPDX-License-Identifier: Apache-2.0
//
// The `counter` topic (docs/14 §5.2, §5.3: every 5 s, the application's tiles, the last point of
// the series and per-instance active count and state) on the dashboard model (docs/15 §10 "counter
// → 대시보드 쿼리 캐시에 최신 버킷 append"). WsManager validates the frame envelope and leaves
// `data` unknown; the payload is validated here with a tolerant schema (unknown fields ignored,
// missing fields keep the REST value) and a payload with a wrong type is dropped as a whole, never
// partly applied (docs/15 §10).
import type { DashboardInstance } from '../../../api/dashboard';
import { z } from '../../../app/zod';
import type { DashboardModel, SeriesValues } from '../data/model';

/** Pushes kept on top of one REST response (2 minutes of 5-second pushes, bounded). */
export const MAX_PENDING_PUSHES = 120;
/** Most per-instance rows accepted from one push. */
export const MAX_PUSH_INSTANCES = 1_000;

// zod 4 numbers are finite (NaN and ±Infinity fail).
const Value = z.number().nullable().optional();

const CounterData = z.object({
  tps: Value,
  avg_ms: Value,
  p95_ms: Value,
  p99_ms: Value,
  error_rate: Value,
  active: Value,
  heap_used: Value,
  gc_pause_ms: Value,
  cpu: Value,
  per_instance: z
    .array(
      z.object({
        instance_id: z.number().int().positive(),
        active: Value,
        state: z.string().max(16).optional(),
      }),
    )
    .max(MAX_PUSH_INSTANCES)
    .optional(),
});

type CounterValue = number | null | undefined;

/** A validated `counter` push: values (undefined = not sent) at the server time `t`. */
export interface CounterPush {
  /** Server time of the push, epoch ms. */
  readonly t: number;
  /** The push's `t` as sent (data-live-ts). */
  readonly tIso: string;
  readonly tps: CounterValue;
  readonly avg_ms: CounterValue;
  readonly p95_ms: CounterValue;
  readonly p99_ms: CounterValue;
  readonly error_rate: CounterValue;
  readonly active: CounterValue;
  readonly heap_used: CounterValue;
  readonly gc_pause_ms: CounterValue;
  readonly cpu: CounterValue;
  readonly perInstance: readonly {
    readonly instance_id: number;
    readonly active: CounterValue;
    readonly state: string | undefined;
  }[];
}

/** The push of a frame, or null when its time or payload is invalid (the frame is ignored). */
export function parseCounterPush(t: string, data: unknown): CounterPush | null {
  const time = Date.parse(t);
  if (!Number.isFinite(time)) {
    return null;
  }
  const parsed = CounterData.safeParse(data);
  if (!parsed.success) {
    return null;
  }
  const d = parsed.data;
  return {
    t: time,
    tIso: t,
    tps: d.tps,
    avg_ms: d.avg_ms,
    p95_ms: d.p95_ms,
    p99_ms: d.p99_ms,
    error_rate: d.error_rate,
    active: d.active,
    heap_used: d.heap_used,
    gc_pause_ms: d.gc_pause_ms,
    cpu: d.cpu,
    perInstance: (d.per_instance ?? []).map((row) => ({
      instance_id: row.instance_id,
      active: row.active,
      state: row.state,
    })),
  };
}

/** `current` with the sent value (undefined keeps it; null is a sent "no data"). */
function pick(current: number | null, sent: CounterValue): number | null {
  return sent === undefined ? current : sent;
}

function setAt(list: SeriesValues, index: number, sent: CounterValue): SeriesValues {
  if (sent === undefined || list[index] === sent) {
    return list;
  }
  const next = list.slice();
  next[index] = sent;
  return next;
}

function append(list: SeriesValues, sent: CounterValue): SeriesValues {
  return [...list, sent === undefined ? null : sent];
}

/**
 * The model with one push applied: tiles take the sent values (delta keeps its REST basis); the
 * bucket that contains `t` takes the sent series values, or a new bucket is appended when `t` is
 * past the last one (the live window moves); per-instance active counts and states are replaced.
 * A push older than the last bucket changes the tiles and instances only.
 */
export function applyCounterPush(model: DashboardModel, push: CounterPush): DashboardModel {
  const tiles = {
    tps: pick(model.tiles.tps, push.tps),
    avg_ms: pick(model.tiles.avg_ms, push.avg_ms),
    p95_ms: pick(model.tiles.p95_ms, push.p95_ms),
    p99_ms: pick(model.tiles.p99_ms, push.p99_ms),
    error_rate: pick(model.tiles.error_rate, push.error_rate),
    active: pick(model.tiles.active, push.active),
  };

  let instances: readonly DashboardInstance[] = model.instances;
  if (push.perInstance.length > 0) {
    const byId = new Map(push.perInstance.map((row) => [row.instance_id, row]));
    instances = model.instances.map((instance) => {
      const row = byId.get(instance.instance_id);
      if (row === undefined) {
        return instance;
      }
      const state = row.state ?? instance.state;
      return {
        ...instance,
        active: pick(instance.active, row.active),
        state,
        // The push carries no WARN reasons: they hold while the state stays WARN (the next REST
        // read refreshes them) and go with it (ADR-0010 C-216 (2)).
        warn_reasons: state === 'WARN' ? instance.warn_reasons : [],
      };
    });
  }

  const ts = model.ts;
  const last = ts.length - 1;
  const lastTs = ts[last];
  let series = model.series;
  let nextTs = ts;
  if (lastTs !== undefined && push.t >= lastTs) {
    const s = model.series;
    if (push.t < lastTs + model.stepMs) {
      series = {
        ...s,
        tps: setAt(s.tps, last, push.tps),
        avg: setAt(s.avg, last, push.avg_ms),
        p95: setAt(s.p95, last, push.p95_ms),
        p99: setAt(s.p99, last, push.p99_ms),
        error_rate: setAt(s.error_rate, last, push.error_rate),
        active: setAt(s.active, last, push.active),
        heap_used: setAt(s.heap_used, last, push.heap_used),
        gc_pause_ms: setAt(s.gc_pause_ms, last, push.gc_pause_ms),
        cpu: setAt(s.cpu, last, push.cpu),
      };
    } else {
      // A new bucket on the same grid; series the push does not carry stay gaps (null, not 0).
      const bucket = lastTs + Math.floor((push.t - lastTs) / model.stepMs) * model.stepMs;
      nextTs = [...ts, bucket];
      series = {
        tps: append(s.tps, push.tps),
        avg: append(s.avg, push.avg_ms),
        p95: append(s.p95, push.p95_ms),
        p99: append(s.p99, push.p99_ms),
        error_rate: append(s.error_rate, push.error_rate),
        active: append(s.active, push.active),
        heap_used: append(s.heap_used, push.heap_used),
        heap_max: append(s.heap_max, undefined),
        gc_pause_ms: append(s.gc_pause_ms, push.gc_pause_ms),
        cpu: append(s.cpu, push.cpu),
        // the push has no system CPU (docs/14 §5.3): a gap until the next REST read
        system_cpu: append(s.system_cpu, undefined),
        tomcat_busy_ratio: append(s.tomcat_busy_ratio, undefined),
        ext_time_ms: append(s.ext_time_ms, undefined),
        ext_errors: s.ext_errors === null ? null : append(s.ext_errors, undefined),
        pools: s.pools.map((pool) => ({ name: pool.name, values: append(pool.values, undefined) })),
      };
    }
  }
  const baseline =
    model.baseline === null || nextTs === ts
      ? model.baseline
      : {
          offset: model.baseline.offset,
          values: {
            tps: append(model.baseline.values.tps, undefined),
            avg: append(model.baseline.values.avg, undefined),
            p95: append(model.baseline.values.p95, undefined),
            p99: append(model.baseline.values.p99, undefined),
            error_rate: append(model.baseline.values.error_rate, undefined),
          },
        };
  return { ...model, ts: nextTs, series, baseline, tiles, instances };
}

/**
 * The model with the pushes that are not older than the response's last bucket applied in time
 * order (a REST refresh replaces what the pushes added before it).
 */
export function applyCounterPushes(
  model: DashboardModel,
  pushes: readonly CounterPush[],
): DashboardModel {
  const lastTs = model.ts[model.ts.length - 1] ?? Number.NEGATIVE_INFINITY;
  return pushes
    .filter((push) => push.t >= lastTs)
    .sort((a, b) => a.t - b.t)
    .reduce(applyCounterPush, model);
}
