// SPDX-License-Identifier: Apache-2.0
//
// Data of the dashboard screen (docs/15 §9.2, §10): the one or two `GET /dashboard` requests of the
// URL state (data/plan.ts) and, while realtime applies, the `counter` topic on top of them. The
// subscription uses exactly the topic and parameters of the shell's own live subscription
// (routes/liveStatus.ts), so WsManager shares one server subscription and no frame is added.
// REST: a relative range is resolved on a 60-second clock (D-03 "최근 5분 재조회" once a minute),
// on a 5-second clock while realtime is silent for 30 s or disconnected (polling fallback), and
// again after a reconnect (onResync). Without realtime nothing is subscribed (past ranges).
import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';

import type { Enveloped } from '../../../api/client';
import { getDashboard, type Dashboard, type DashboardRequest } from '../../../api/dashboard';
import { queryKeys } from '../../../api/queryKeys';
import { useConnectionStore } from '../../../realtime/connectionStore';
import type { PushFrame } from '../../../realtime/frames';
import { usePollingFallback, useTopic } from '../../../realtime/hooks';
import type { ResolvedRange } from '../../../state/timeRange';
import type { DashboardState, TimeRange } from '../../../state/urlState';
import { useClock } from '../../../state/useTimeRange';
import {
  applyCounterPushes,
  MAX_PENDING_PUSHES,
  parseCounterPush,
  type CounterPush,
} from '../live/counterPush';

import { dashboardModel, type DashboardModel } from './model';
import {
  DASHBOARD_POLL_MS,
  DASHBOARD_REFRESH_MS,
  dashboardPlan,
  dashboardQueryRange,
  instanceFilter,
  type DashboardPlan,
} from './plan';

export interface UseDashboardInput {
  /** null while the route has no valid application id (nothing is requested). */
  readonly appId: number | null;
  readonly state: DashboardState;
  readonly timeZone: string;
  /** Live mode (or kiosk) on a range that ends now: subscribe to `counter`. */
  readonly realtime: boolean;
}

export interface DashboardData {
  readonly plan: DashboardPlan | null;
  /** The resolved range of the request (absolute, epoch ms). */
  readonly queryRange: ResolvedRange;
  /** The REST model with the applicable pushes on top, or null before the first response. */
  readonly model: DashboardModel | null;
  readonly status: 'loading' | 'error' | 'ready';
  readonly error: unknown;
  /** A newer request runs while the previous question's data is shown. */
  readonly refreshing: boolean;
  /** The tiles wait for their own request (the tile basis needs another baseline parameter). */
  readonly tilesPending: boolean;
  /** REST polling fills in for silent realtime (docs/15 §10). */
  readonly polling: boolean;
  /** `t` of the last push applied to the model (data-live-ts), or null. */
  readonly liveTs: string | null;
  /** Server time of that push (epoch ms), or null. */
  readonly liveT: number | null;
  readonly unapplied: ReturnType<typeof instanceFilter>['unapplied'];
  readonly retry: () => void;
}

type DashboardKey = ReturnType<typeof queryKeys.screen>;

function requestKey(request: DashboardRequest, range: ResolvedRange): DashboardKey {
  return queryKeys.screen('dashboard', {
    appId: request.appId,
    range,
    kind: request.kind,
    extra: {
      baseline: request.baseline ?? 'none',
      instance: request.instances.join(','),
      exclude: request.excluded.join(','),
    },
  });
}

/** Key of a disabled query (no application, or no tiles request). */
function idleKey(screen: string, range: ResolvedRange): DashboardKey {
  return queryKeys.screen(screen, { appId: null, range });
}

interface PushState {
  readonly key: string | null;
  readonly pushes: readonly CounterPush[];
}

export function useDashboard({
  appId,
  state,
  timeZone,
  realtime,
}: UseDashboardInput): DashboardData {
  const subscribed = realtime && appId !== null;
  const pollingFallback = usePollingFallback();
  const closed = useConnectionStore((s) => s.status === 'closed');
  const polling = subscribed && (pollingFallback || closed);
  const stepMs = polling ? DASHBOARD_POLL_MS : DASHBOARD_REFRESH_MS;
  const now = useClock(stepMs);

  const timeKey = JSON.stringify(state.time);
  const queryRange = useMemo(
    () => dashboardQueryRange(JSON.parse(timeKey) as TimeRange, now, timeZone, stepMs),
    [timeKey, now, timeZone, stepMs],
  );
  const filtersKey = JSON.stringify(state.filters);
  const instances = useMemo(
    () => instanceFilter(JSON.parse(filtersKey) as DashboardState['filters']),
    [filtersKey],
  );
  const plan = useMemo(
    () =>
      appId === null
        ? null
        : dashboardPlan({
            appId,
            range: queryRange,
            kind: state.kind,
            instances: instances.ids,
            excluded: instances.excluded,
            baseline: state.baseline,
            tileRef: state.tileRef,
          }),
    [
      appId,
      queryRange,
      state.kind,
      instances.ids,
      instances.excluded,
      state.baseline,
      state.tileRef,
    ],
  );

  // Keeps the previous answer while the next question loads, within the same application only.
  const placeholder = useCallback(
    (previous: Enveloped<Dashboard> | undefined, query: { queryKey: DashboardKey } | undefined) =>
      query?.queryKey[1] === appId ? previous : undefined,
    [appId],
  );
  const mainRequest = plan?.main ?? null;
  const main = useQuery<Enveloped<Dashboard>, Error, Enveloped<Dashboard>, DashboardKey>({
    queryKey:
      mainRequest === null ? idleKey('dashboard', queryRange) : requestKey(mainRequest, queryRange),
    queryFn: ({ signal }) => {
      if (mainRequest === null) {
        throw new Error('no application');
      }
      return getDashboard(mainRequest, signal);
    },
    enabled: mainRequest !== null,
    placeholderData: placeholder,
  });
  const tilesRequest = plan?.tiles ?? null;
  const tiles = useQuery<Enveloped<Dashboard>, Error, Enveloped<Dashboard>, DashboardKey>({
    queryKey:
      tilesRequest === null
        ? idleKey('dashboard-tiles', queryRange)
        : requestKey(tilesRequest, queryRange),
    queryFn: ({ signal }) => {
      if (tilesRequest === null) {
        throw new Error('no tiles request');
      }
      return getDashboard(tilesRequest, signal);
    },
    enabled: tilesRequest !== null,
    placeholderData: placeholder,
  });

  // Realtime: pushes of the current subscription only (a new app or live off starts empty).
  const subscriptionKey = subscribed ? `counter:${String(appId)}` : null;
  const [pushState, setPushState] = useState<PushState>({ key: null, pushes: [] });
  const pushes = useMemo(
    () => (pushState.key === subscriptionKey ? pushState.pushes : []),
    [pushState, subscriptionKey],
  );
  const refetchMain = main.refetch;
  const refetchTiles = tiles.refetch;
  const resync = useCallback(() => {
    void refetchMain();
    if (tilesRequest !== null) {
      void refetchTiles();
    }
  }, [refetchMain, refetchTiles, tilesRequest]);
  useTopic('counter', subscribed ? { app: appId } : null, {
    onData: (frame: PushFrame) => {
      const push = parseCounterPush(frame.t, frame.data);
      if (push === null) {
        return;
      }
      setPushState((current) => {
        const base = current.key === subscriptionKey ? current.pushes : [];
        return { key: subscriptionKey, pushes: [...base, push].slice(-MAX_PENDING_PUSHES) };
      });
    },
    onResync: resync,
  });

  // Tiles of the tiles' own request when the basis needs one; the main tiles if that request
  // failed (the tile then shows the basis the server actually used).
  const restModel = useMemo(() => {
    const data = main.data;
    if (data === undefined) {
      return null;
    }
    const tilesData = tilesRequest === null ? null : (tiles.data?.data ?? null);
    return dashboardModel(data.data, data.meta, tilesData);
  }, [main.data, tiles.data, tilesRequest]);
  const model = useMemo(
    () => (restModel === null || !subscribed ? restModel : applyCounterPushes(restModel, pushes)),
    [restModel, pushes, subscribed],
  );
  const lastTs = restModel?.ts[restModel.ts.length - 1] ?? Number.NEGATIVE_INFINITY;
  const applied = subscribed ? pushes.filter((push) => push.t >= lastTs) : [];
  const lastPush = applied.reduce<CounterPush | null>(
    (latest, push) => (latest === null || push.t >= latest.t ? push : latest),
    null,
  );

  const status: DashboardData['status'] =
    main.data !== undefined ? 'ready' : main.isError ? 'error' : 'loading';
  return {
    plan,
    queryRange,
    model,
    status,
    error: main.error,
    tilesPending: tilesRequest !== null && tiles.data === undefined && !tiles.isError,
    refreshing: main.isPlaceholderData || (tilesRequest !== null && tiles.isPlaceholderData),
    polling,
    liveTs: lastPush?.tIso ?? null,
    liveT: lastPush?.t ?? null,
    unapplied: instances.unapplied,
    retry: resync,
  };
}
