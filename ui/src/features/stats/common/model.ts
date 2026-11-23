// SPDX-License-Identifier: Apache-2.0
//
// Pure model of the statistics screens (docs/15 §9.7, 05 §2.5, WO-36): which URL filters the
// server applies and which narrow the received Top N on the client, the XLog filters a statistics
// view carries, event markers and outages for the trend charts, the raw `data-value` of a cell and
// series conversion.
//
// Filters (PM question 2 of the WO-36 plan, assumption): the statistics API takes no filter
// parameters except `db_id` (SQL), `kind` and `new_only` (errors). URL filters (`f.<field>`) whose
// field has a server parameter go to the server; every other filter narrows the rows of the
// response (the server's Top N, at most 500) on the client, and the screen says so
// (`stats-filter-scope`). A filter on a field the screen does not know is listed as not applied.
// 64-bit keys stay decimal strings (ADR-0010 C-175): compared as strings, never as numbers.
import type { ResponseMeta } from '../../../api/client';
import type { AppEvent, StatsSeries } from '../../../api/stats';
import type { ChartMarker, ChartOutage, ChartSeries, MarkerType } from '../../../charts/chartModel';
import type { Filter } from '../../../state/urlState';
import type { ValueUnit } from '../../../utils/format';

/** How a filter field reads a row: keys for eq/ne, text for contains, numbers for ranges. */
export interface FieldAccessor<Row> {
  /** The key compared by `eq` (any of) and `ne` (excluded), as a string. */
  readonly key?: (row: Row) => string | null | undefined;
  /**
   * Several keys of one row (e.g. the top services of an error group): `eq` matches when any of
   * them equals the value, `ne` excludes the row when any of them does. Used when `key` is absent.
   */
  readonly keys?: (row: Row) => readonly (string | null | undefined)[];
  /** The text searched by `contains` (case-insensitive). */
  readonly text?: (row: Row) => string | null | undefined;
  /** The number compared by `gt`, `gte`, `lt` and `lte` (a missing value never matches). */
  readonly number?: (row: Row) => number | null | undefined;
}

export type FilterAccessors<Row> = Readonly<Record<string, FieldAccessor<Row>>>;

export interface ClientFilterResult<Row> {
  /** The rows that pass every applied filter, in the server's order. */
  readonly rows: Row[];
  /** Filters applied (on the server or the client). */
  readonly applied: Filter[];
  /** Filters this screen cannot apply (unknown field or operator): listed, never guessed. */
  readonly unapplied: Filter[];
}

function accessorFor<Row>(
  accessors: FilterAccessors<Row>,
  filter: Filter,
): ((row: Row) => boolean) | null {
  const accessor = Object.prototype.hasOwnProperty.call(accessors, filter.field)
    ? accessors[filter.field]
    : undefined;
  if (accessor === undefined) {
    return null;
  }
  const value: string = filter.value;
  switch (filter.op) {
    case 'eq':
    case 'ne': {
      const key = accessor.key;
      if (key !== undefined) {
        return (row) => {
          const own = key(row);
          return own !== null && own !== undefined && own === value;
        };
      }
      const keys = accessor.keys;
      if (keys === undefined) {
        return null;
      }
      return (row) => keys(row).some((own) => own !== null && own !== undefined && own === value);
    }
    case 'contains': {
      const text = accessor.text;
      if (text === undefined) {
        return null;
      }
      const needle = value.toLowerCase();
      return (row) => (text(row) ?? '').toLowerCase().includes(needle);
    }
    case 'gt':
    case 'gte':
    case 'lt':
    case 'lte': {
      const number = accessor.number;
      const bound = Number(value);
      if (number === undefined || value.trim() === '' || !Number.isFinite(bound)) {
        return null;
      }
      return (row) => {
        const own = number(row);
        if (own === null || own === undefined || !Number.isFinite(own)) {
          return false;
        }
        switch (filter.op) {
          case 'gt':
            return own > bound;
          case 'gte':
            return own >= bound;
          case 'lt':
            return own < bound;
          default:
            return own <= bound;
        }
      };
    }
  }
}

/**
 * Applies URL filters to the rows of a response. Several `eq` values of one field mean "any of
 * them" (DSN-14-17); `ne` excludes its value; `contains`, `gt`, `gte`, `lt`, `lte` must all hold.
 */
export function applyClientFilters<Row>(
  rows: readonly Row[],
  filters: readonly Filter[],
  accessors: FilterAccessors<Row>,
): ClientFilterResult<Row> {
  const applied: Filter[] = [];
  const unapplied: Filter[] = [];
  const anyOf = new Map<string, ((row: Row) => boolean)[]>();
  const allOf: ((row: Row) => boolean)[] = [];
  for (const filter of filters) {
    const test = accessorFor(accessors, filter);
    if (test === null) {
      unapplied.push(filter);
      continue;
    }
    applied.push(filter);
    if (filter.op === 'eq') {
      anyOf.set(filter.field, [...(anyOf.get(filter.field) ?? []), test]);
    } else if (filter.op === 'ne') {
      allOf.push((row) => !test(row));
    } else {
      allOf.push(test);
    }
  }
  const groups = [...anyOf.values()];
  const kept = rows.filter(
    (row) =>
      groups.every((group) => group.some((test) => test(row))) && allOf.every((test) => test(row)),
  );
  return { rows: kept, applied, unapplied };
}

/**
 * The single `eq` value of a field, or null: a server parameter that takes one value (`db_id`)
 * is sent only for exactly one `eq` and no `ne` of that field (the client narrows the rest).
 */
export function singleEqValue(filters: readonly Filter[], field: string): string | null {
  const own = filters.filter((filter) => filter.field === field);
  const eq = own.filter((filter) => filter.op === 'eq');
  return own.length === 1 && eq.length === 1 ? (eq[0]?.value ?? null) : null;
}

/**
 * True when some applied filter narrows the received rows on the client (anything but the
 * server-side fields): the screen then shows the "filtered within the top N rows" note.
 */
export function narrowsOnClient(
  applied: readonly Filter[],
  serverApplied: readonly Filter[] = [],
): boolean {
  return applied.some((filter) => !serverApplied.includes(filter));
}

/**
 * Fields of the XLog filter vocabulary (api/transactionSearch.ts) that a statistics screen can
 * carry: the same keys (service, SQL, error group and version keys, instance ids).
 */
export const XLOG_CARRIED_FIELDS: ReadonlySet<string> = new Set([
  'service',
  'sql',
  'error_group',
  'version',
  'instance',
]);

/**
 * The XLog filters of a statistics view: the view's own filter (e.g. `service=<id>` of a service
 * detail) and the `eq` URL filters of the XLog vocabulary. Exclusions and range or text filters are
 * not carried (the XLog search has no exclusion filters, docs/14 §4.6). Duplicates are dropped.
 */
export function xlogFilters(own: readonly Filter[], urlFilters: readonly Filter[] = []): Filter[] {
  const out: Filter[] = [];
  const seen = new Set<string>();
  for (const filter of [...own, ...urlFilters]) {
    if (filter.op !== 'eq' || !XLOG_CARRIED_FIELDS.has(filter.field)) {
      continue;
    }
    const key = `${filter.field}\u0000${filter.value}`;
    if (!seen.has(key)) {
      seen.add(key);
      out.push(filter);
    }
  }
  return out;
}

/** `data-value` of a raw API number: the number, '' when missing (then `data-missing` is set). */
export function rawValue(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '';
}

/** A finite number or null. */
export function finite(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

const MARKER_TYPES: ReadonlySet<string> = new Set<MarkerType>([
  'deploy',
  'start',
  'stop',
  'config',
  'alert',
  'state',
  'apdex',
  'custom',
]);

/** Event markers (docs/14 §4.12): unknown types draw as custom, unparsable times are left out. */
export function eventMarkers(events: readonly AppEvent[]): ChartMarker[] {
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

/** Collection outages of a response (meta.outages, C-5, D-18): `to` absent = still ongoing. */
export function metaOutages(meta: ResponseMeta | null | undefined): ChartOutage[] {
  const out: ChartOutage[] = [];
  for (const outage of meta?.outages ?? []) {
    const from = Date.parse(outage.from);
    const to = outage.to === undefined ? null : Date.parse(outage.to);
    if (!Number.isFinite(from) || (to !== null && !Number.isFinite(to))) {
      continue;
    }
    out.push({ from, to, reason: outage.reason });
  }
  return out;
}

/** Bucket times of a series (epoch ms); an unparsable bucket becomes NaN and is dropped. */
export function seriesTimes(series: StatsSeries): number[] {
  return series.ts.map((iso) => Date.parse(iso));
}

/** Series values aligned with the buckets: missing values stay null (lines break, D-09). */
export function seriesValues(
  values: readonly (number | null)[] | null | undefined,
  length: number,
): (number | null)[] {
  const out = new Array<number | null>(length);
  for (let i = 0; i < length; i += 1) {
    out[i] = finite(values?.[i]);
  }
  return out;
}

/** One chart series of a statistics series field (null when the server did not send it). */
export function chartSeries(
  series: StatsSeries,
  field:
    | 'count'
    | 'errors'
    | 'error_rate'
    | 'timeouts'
    | 'connect_failures'
    | 'avg_ms'
    | 'p95_ms'
    | 'p99_ms',
  label: string,
  unit: ValueUnit,
): ChartSeries | null {
  const values = series[field];
  if (values === null || values === undefined) {
    return null;
  }
  return { key: field, label, unit, values: seriesValues(values, series.ts.length) };
}
