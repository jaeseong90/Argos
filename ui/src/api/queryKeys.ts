// SPDX-License-Identifier: Apache-2.0
//
// Query keys (docs/15 §4.2, DSN-15-01): the cache key of a server query is its normalised URL
// state. Time ranges enter as absolute ISO instants (relative ranges resolved at the 10-second
// query clock, state/timeRange.ts), filters sorted, 64-bit ids as decimal strings (C-175), so two
// screens asking the same question share one cache entry.
import type { ResolvedRange } from '../state/timeRange';
import type { Filter, Kind } from '../state/urlState';

/** A filter in key form (plain strings, stable order). */
export type FilterKey = readonly [field: string, op: string, value: string];

/** Filters sorted by field, op and value. */
export function normalizeFilters(filters: readonly Filter[]): FilterKey[] {
  return filters
    .map((f): FilterKey => [f.field, f.op, f.value])
    .sort(
      (a, b) => a[0].localeCompare(b[0]) || a[1].localeCompare(b[1]) || a[2].localeCompare(b[2]),
    );
}

/** The key part of a time range: ISO instants. */
export function rangeKey(range: ResolvedRange): readonly [from: string, to: string] {
  return [new Date(range.from).toISOString(), new Date(range.to).toISOString()];
}

export interface ScreenQueryParams {
  readonly appId?: number | null;
  readonly range: ResolvedRange;
  readonly kind?: Kind;
  readonly env?: string | null;
  readonly filters?: readonly Filter[];
  /** Further screen parameters (sort, selection, tab, ...); plain JSON values only. */
  readonly extra?: Readonly<Record<string, string | number | boolean | null | undefined>>;
}

function sortedExtra(
  extra: ScreenQueryParams['extra'],
): Record<string, string | number | boolean | null> {
  const out: Record<string, string | number | boolean | null> = {};
  for (const key of Object.keys(extra ?? {}).sort()) {
    const value = extra?.[key];
    if (value !== undefined) {
      out[key] = value;
    }
  }
  return out;
}

export const queryKeys = {
  auth: {
    me: () => ['auth', 'me'] as const,
  },
  setup: {
    status: () => ['setup', 'status'] as const,
  },
  apps: {
    all: () => ['apps'] as const,
    list: (
      filter: { env?: string | undefined; group?: string | undefined; q?: string | undefined } = {},
    ) =>
      [
        'apps',
        'list',
        { env: filter.env ?? null, group: filter.group ?? null, q: filter.q ?? null },
      ] as const,
    detail: (appId: number) => ['apps', 'detail', appId] as const,
  },
  alerts: {
    firingCount: (env: string | null) => ['alerts', 'firing-count', env] as const,
  },
  transactions: {
    /** Where a transaction lives (the /tx/:txId alias); txId stays a decimal string (C-175). */
    location: (txId: string, ts: string | null) => ['transactions', 'location', txId, ts] as const,
  },
  /**
   * Key of a screen query: [screen, appId, from, to, {kind, env, filters, ...extra}]. Screens
   * (WO-31..39) use it with their resolved range, e.g. queryKeys.screen('xlog', {...}).
   */
  screen: (screen: string, params: ScreenQueryParams) => {
    const [from, to] = rangeKey(params.range);
    return [
      screen,
      params.appId ?? null,
      from,
      to,
      {
        kind: params.kind ?? 'HTTP',
        env: params.env ?? null,
        filters: normalizeFilters(params.filters ?? []),
        ...sortedExtra(params.extra),
      },
    ] as const;
  },
} as const;
