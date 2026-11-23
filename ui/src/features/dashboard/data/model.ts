// SPDX-License-Identifier: Apache-2.0
//
// The dashboard response (docs/14 §4.4 dashboard_get) as the screen draws it: shared x values in
// epoch ms, the series keyed for the charts (null kept as null, never 0: D-09, D-16, T-UI-007), the
// baseline aligned to the same buckets, the tiles of the request whose delta basis matches tileRef,
// event markers (docs/15 §6.1 shapes, titles stay collected strings) and collection outages (C-5).
import type { ResponseMeta } from '../../../api/client';
import type {
  Dashboard,
  DashboardCollection,
  DashboardEvent,
  DashboardInstance,
  DashboardOutage,
} from '../../../api/dashboard';
import { untrustedEntries, type Untrusted } from '../../../api/untrusted';
import type { ChartMarker, ChartOutage, MarkerType } from '../../../charts/chartModel';

export type SeriesValues = readonly (number | null)[];

/** Keys of the baseline series (the transaction series the server shifts back, docs/14 §4.4). */
export type BaselineKey = 'tps' | 'avg' | 'p95' | 'p99' | 'error_rate';

export interface DashboardSeriesModel {
  readonly tps: SeriesValues;
  readonly avg: SeriesValues;
  readonly p95: SeriesValues;
  readonly p99: SeriesValues;
  readonly error_rate: SeriesValues;
  /** Sum over the instances of the active-transaction gauge (ADR-0010 C-178); null = no sample. */
  readonly active: SeriesValues;
  readonly heap_used: SeriesValues;
  readonly heap_max: SeriesValues;
  readonly gc_pause_ms: SeriesValues;
  /** Process CPU (0..1), mean over the instances. */
  readonly cpu: SeriesValues;
  /** System CPU (0..1), mean over the instances (ADR-0010 C-212 (1)). */
  readonly system_cpu: SeriesValues;
  readonly tomcat_busy_ratio: SeriesValues;
  readonly ext_time_ms: SeriesValues;
  /** null when the server has no error source for the range. */
  readonly ext_errors: SeriesValues | null;
  /** One series per connection pool, by name (collected strings), sorted by name. */
  readonly pools: readonly { readonly name: Untrusted; readonly values: SeriesValues }[];
}

export interface DashboardTilesModel {
  readonly tps: number | null;
  readonly avg_ms: number | null;
  readonly p95_ms: number | null;
  readonly p99_ms: number | null;
  readonly error_rate: number | null;
  readonly active: number | null;
}

export interface DashboardDeltaModel {
  readonly basis: 'prev' | '7d';
  /** Relative change. */
  readonly tps: number | null;
  /** Relative change (ADR-0010 C-212 (1)). */
  readonly avg_ms: number | null;
  /** Relative change. */
  readonly p95_ms: number | null;
  /** Relative change (ADR-0010 C-212 (1)). */
  readonly p99_ms: number | null;
  /** Absolute difference (ratio units). */
  readonly error_rate: number | null;
  /**
   * Relative change of the live active count against the basis range's mean active count
   * (ADR-0010 C-212 (1)); null for a range that does not end now.
   */
  readonly active: number | null;
}

export interface DashboardModel {
  /** Bucket starts, epoch ms, ascending. */
  readonly ts: readonly number[];
  /** Bucket width in ms (from the buckets; the range length when there is one bucket). */
  readonly stepMs: number;
  readonly series: DashboardSeriesModel;
  readonly baseline: {
    readonly offset: '1d' | '7d';
    readonly values: Readonly<Record<BaselineKey, SeriesValues>>;
  } | null;
  readonly tiles: DashboardTilesModel;
  readonly delta: DashboardDeltaModel;
  readonly instances: readonly DashboardInstance[];
  readonly markers: readonly ChartMarker[];
  readonly outages: readonly ChartOutage[];
  readonly collection: DashboardCollection;
  readonly firing: readonly unknown[] | null;
  readonly meta: ResponseMeta;
  /** Transactions of the range (Σ series.count, D-03): 0 = no transactions, the empty state. */
  readonly count: number;
}

const MARKER_TYPES: ReadonlySet<string> = new Set([
  'deploy',
  'start',
  'stop',
  'config',
  'alert',
  'state',
  'apdex',
  'custom',
]);

function finite(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function values(list: readonly (number | null)[] | null | undefined, length: number): SeriesValues {
  const out = new Array<number | null>(length);
  for (let i = 0; i < length; i += 1) {
    out[i] = finite(list?.[i]);
  }
  return out;
}

/** Event markers (docs/14 §4.12): unknown types draw as custom; unparsable times are left out. */
export function eventMarkers(events: readonly DashboardEvent[]): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const event of events) {
    const ts = Date.parse(event.ts);
    if (!Number.isFinite(ts)) {
      continue;
    }
    markers.push({
      id: event.event_id,
      ts,
      type: MARKER_TYPES.has(event.type) ? (event.type as MarkerType) : 'custom',
      title: event.title,
      version: event.version,
      severity: null,
    });
  }
  return markers;
}

/** Collection outages (C-5, D-18): `to` null = still ongoing; unparsable rows are left out. */
export function chartOutages(outages: readonly DashboardOutage[]): ChartOutage[] {
  const out: ChartOutage[] = [];
  for (const outage of outages) {
    const from = Date.parse(outage.from);
    const to = outage.to === null ? null : Date.parse(outage.to);
    if (!Number.isFinite(from) || (to !== null && !Number.isFinite(to))) {
      continue;
    }
    out.push({ from, to, reason: outage.reason });
  }
  return out;
}

/** The baseline values re-indexed on the main buckets (the server shifts its ts forward). */
function alignedBaseline(dashboard: Dashboard, ts: readonly number[]): DashboardModel['baseline'] {
  const baseline = dashboard.baseline;
  if (baseline === undefined || (baseline.offset !== '1d' && baseline.offset !== '7d')) {
    return null;
  }
  const index = new Map<number, number>();
  baseline.ts.forEach((iso, i) => {
    const t = Date.parse(iso);
    if (Number.isFinite(t)) {
      index.set(t, i);
    }
  });
  const pick = (list: readonly (number | null)[]): SeriesValues =>
    ts.map((t) => {
      const i = index.get(t);
      return i === undefined ? null : finite(list[i]);
    });
  return {
    offset: baseline.offset,
    values: {
      tps: pick(baseline.tps),
      avg: pick(baseline.avg_ms),
      p95: pick(baseline.p95_ms),
      p99: pick(baseline.p99_ms),
      error_rate: pick(baseline.error_rate),
    },
  };
}

/**
 * The model of a dashboard response. `tilesFrom` is the response of the tiles' own request when
 * the tile basis needed one (DashboardPlan.tiles), else null: tiles and delta then come from it.
 */
export function dashboardModel(
  dashboard: Dashboard,
  meta: ResponseMeta,
  tilesFrom: Dashboard | null = null,
): DashboardModel {
  const ts = dashboard.series.ts.map((iso) => Date.parse(iso)).filter(Number.isFinite);
  const n = ts.length;
  const s = dashboard.series;
  const first = ts[0];
  const second = ts[1];
  const rangeFrom = meta.from === undefined ? Number.NaN : Date.parse(meta.from);
  const rangeTo = meta.to === undefined ? Number.NaN : Date.parse(meta.to);
  const stepMs =
    first !== undefined && second !== undefined && second > first
      ? second - first
      : Number.isFinite(rangeTo - rangeFrom) && rangeTo > rangeFrom
        ? rangeTo - rangeFrom
        : 60_000;
  const pools = untrustedEntries(s.pool_usage_ratio)
    .map(([name, list]) => ({ name, values: values(list, n) }))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const tileSource = tilesFrom ?? dashboard;
  const tiles = tileSource.tiles;
  const delta = tiles.delta;
  const count = s.count.reduce<number>((sum, c) => sum + (finite(c) ?? 0), 0);
  return {
    ts,
    stepMs,
    series: {
      tps: values(s.tps, n),
      avg: values(s.avg_ms, n),
      p95: values(s.p95_ms, n),
      p99: values(s.p99_ms, n),
      error_rate: values(s.error_rate, n),
      active: values(s.active, n),
      heap_used: values(s.heap_used, n),
      heap_max: values(s.heap_max, n),
      gc_pause_ms: values(s.gc_pause_ms, n),
      cpu: values(s.cpu, n),
      system_cpu: values(s.system_cpu, n),
      tomcat_busy_ratio: values(s.tomcat_busy_ratio, n),
      ext_time_ms: values(s.ext_time_ms, n),
      ext_errors: s.ext_errors === null ? null : values(s.ext_errors, n),
      pools,
    },
    baseline: alignedBaseline(dashboard, ts),
    tiles: {
      tps: finite(tiles.tps),
      avg_ms: finite(tiles.avg_ms),
      p95_ms: finite(tiles.p95_ms),
      p99_ms: finite(tiles.p99_ms),
      error_rate: finite(tiles.error_rate),
      active: finite(tiles.active),
    },
    delta: {
      basis: delta.basis === '7d' ? '7d' : 'prev',
      tps: finite(delta.tps),
      avg_ms: finite(delta.avg_ms),
      p95_ms: finite(delta.p95_ms),
      p99_ms: finite(delta.p99_ms),
      error_rate: finite(delta.error_rate),
      active: finite(delta.active),
    },
    instances: dashboard.instances,
    markers: eventMarkers(dashboard.events),
    outages: chartOutages(dashboard.outages),
    collection: dashboard.collection,
    firing: dashboard.firing,
    meta,
    count: Math.max(count, tiles.count),
  };
}
