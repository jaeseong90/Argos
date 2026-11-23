// SPDX-License-Identifier: Apache-2.0
//
// Queries of the alert history (05 §2.9, WO-38): event pages by cursor, the active alerts of the
// pinned panel, the channels (names of the delivery column and channel status), the mutes
// (operator), the rule filter options and the per-rule statistics. Keys come from
// queryKeys.alerts, so an acknowledgement or a realtime push refreshes them by the ['alerts']
// prefix (and with them the top bar badge).
import { useInfiniteQuery, useQuery, type InfiniteData } from '@tanstack/react-query';
import { useState } from 'react';

import {
  getAlertStats,
  listActiveAlerts,
  listAlertEvents,
  listAlertMutes,
  listAlertRuleOptions,
  listChannels,
  type AlertEvent,
  type AlertEventsRequest,
} from '../../../api/alerts';
import type { Enveloped } from '../../../api/client';
import { queryKeys } from '../../../api/queryKeys';
import type { ResolvedRange } from '../../../state/timeRange';
import type { TimeRange } from '../../../state/urlState';

/** Refresh of the pinned panel without realtime (the overview's 10 s, DSN-15-14). */
export const PINNED_REFRESH_MS = 10_000;

/**
 * The query range of the event list. Live: the moving range (relative ranges at the 10-second
 * query clock). Otherwise the bounds fixed when the range was chosen, so loaded pages do not
 * reset every 10 s while the user reads them; choosing a range (or "now") fixes new bounds.
 */
export function useStableRange(
  range: TimeRange,
  queryRange: ResolvedRange,
  live: boolean,
): { readonly range: ResolvedRange; readonly refresh: () => void } {
  const key = JSON.stringify(range);
  const [snapshot, setSnapshot] = useState<{ key: string; value: ResolvedRange }>(() => ({
    key,
    value: queryRange,
  }));
  const refresh = (): void => {
    setSnapshot({ key, value: queryRange });
  };
  if (live) {
    return { range: queryRange, refresh };
  }
  if (snapshot.key !== key) {
    setSnapshot({ key, value: queryRange });
    return { range: queryRange, refresh };
  }
  return { range: snapshot.value, refresh };
}

export function useAlertEventsQuery(request: AlertEventsRequest) {
  return useInfiniteQuery<
    Enveloped<AlertEvent[]>,
    Error,
    InfiniteData<Enveloped<AlertEvent[]>, string | null>,
    ReturnType<typeof queryKeys.alerts.events>,
    string | null
  >({
    queryKey: queryKeys.alerts.events(request),
    queryFn: ({ pageParam, signal }) => listAlertEvents(request, pageParam, signal),
    initialPageParam: null,
    getNextPageParam: (last) =>
      last.meta.next_cursor === null || last.meta.next_cursor === undefined
        ? undefined
        : last.meta.next_cursor,
    placeholderData: (previous) => previous,
  });
}

export function usePinnedQuery(env: string | null, polling: boolean) {
  return useQuery({
    queryKey: queryKeys.alerts.pinned(env),
    queryFn: ({ signal }) => listActiveAlerts(env, signal),
    refetchInterval: polling ? PINNED_REFRESH_MS : false,
    refetchIntervalInBackground: false,
  });
}

export function useChannelsQuery() {
  return useQuery({
    queryKey: queryKeys.alerts.channels(),
    queryFn: ({ signal }) => listChannels(signal),
    staleTime: 30_000,
  });
}

export function useMutesQuery(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.alerts.mutes(),
    queryFn: ({ signal }) => listAlertMutes(signal),
    enabled,
  });
}

export function useRuleOptionsQuery(appId: number | null) {
  return useQuery({
    queryKey: queryKeys.alerts.ruleOptions(appId),
    queryFn: ({ signal }) => listAlertRuleOptions(appId, signal),
    staleTime: 60_000,
  });
}

export function useAlertStatsQuery(days: 7 | 30, appId: number | null, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.alerts.stats(days, appId),
    queryFn: ({ signal }) => getAlertStats({ days, appId }, signal),
    enabled,
  });
}
