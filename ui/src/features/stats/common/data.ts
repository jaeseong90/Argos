// SPDX-License-Identifier: Apache-2.0
//
// Data hooks of the statistics screens (docs/15 §4.2, §9.7): the absolute query range of the URL
// range (relative ranges re-resolved once a minute on the 10-second query clock, so the cache key
// stays stable between refreshes), one screen query with the previous answer kept while the next
// question loads (same application only), and the events of the range for the chart markers.
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import type { Enveloped, ResponseMeta } from '../../../api/client';
import { queryKeys, type ScreenQueryParams } from '../../../api/queryKeys';
import { listAppEvents, type AppEvent, type StatsRange } from '../../../api/stats';
import type { ChartMarker } from '../../../charts/chartModel';
import { browserTimeZone, resolveForQuery } from '../../../state/timeRange';
import type { TimeRange } from '../../../state/urlState';
import { useClock } from '../../../state/useTimeRange';

import { eventMarkers } from './model';

/** Relative ranges are re-resolved this often (D-03 "최근 5분 재조회" once a minute). */
export const STATS_REFRESH_MS = 60_000;

/** The absolute query range of a URL range (epoch ms). */
export function useStatsRange(time: TimeRange): StatsRange {
  const now = useClock(STATS_REFRESH_MS);
  const timeZone = useMemo(() => browserTimeZone(), []);
  const key = JSON.stringify(time);
  return useMemo(() => {
    const resolved = resolveForQuery(JSON.parse(key) as TimeRange, now, timeZone);
    return { from: resolved.from, to: resolved.to };
  }, [key, now, timeZone]);
}

export type StatsQueryKey = ReturnType<typeof queryKeys.screen>;

/** The key of a statistics query (`stats-<screen>`, app, range, kind, extra parameters). */
export function statsKey(screen: string, params: ScreenQueryParams): StatsQueryKey {
  return queryKeys.screen(`stats-${screen}`, params);
}

export interface StatsQueryResult<D> {
  readonly data: D | null;
  readonly meta: ResponseMeta | null;
  readonly status: 'loading' | 'error' | 'ready';
  readonly error: unknown;
  /** A newer question loads while the previous answer is shown. */
  readonly refreshing: boolean;
  readonly retry: () => void;
}

/** One screen query; `enabled` false (no application id) requests nothing. */
export function useStatsQuery<D>(
  key: StatsQueryKey,
  fetcher: (signal: AbortSignal) => Promise<Enveloped<D>>,
  enabled: boolean,
): StatsQueryResult<D> {
  const query = useQuery<Enveloped<D>, Error, Enveloped<D>, StatsQueryKey>({
    queryKey: key,
    queryFn: ({ signal }) => fetcher(signal),
    enabled,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[1] === key[1] ? keepPreviousData(previous) : undefined,
  });
  const refetch = query.refetch;
  const status: StatsQueryResult<D>['status'] =
    query.data !== undefined ? 'ready' : query.isError ? 'error' : 'loading';
  return {
    data: query.data?.data ?? null,
    meta: query.data?.meta ?? null,
    status,
    error: query.error,
    refreshing: query.isPlaceholderData || (query.isFetching && query.data !== undefined),
    retry: () => {
      void refetch();
    },
  };
}

/** The event markers of the range (deploy, start, stop, config, ...; docs/14 §4.12). */
export function useEventMarkers(appId: number | null, range: StatsRange): readonly ChartMarker[] {
  const query = useStatsQuery<AppEvent[]>(
    statsKey('events', { appId, range }),
    (signal) => listAppEvents({ appId: appId ?? 0, range }, signal),
    appId !== null,
  );
  const events = query.data;
  return useMemo(() => (events === null ? [] : eventMarkers(events)), [events]);
}
