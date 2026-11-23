// SPDX-License-Identifier: Apache-2.0
//
// Data of the active transactions screen (docs/15 §9.6, §10, 14 §4.9, §5): the REST snapshot
// first, then every `active` push (5 s, latest only) replaces it as a whole (no merge). After 30 s
// without a server message the REST snapshot is polled every 5 s (usePollingFallback; not while the
// tab is hidden). A reconnect resubscribes (WsManager) and refetches REST (onResync). Pause keeps
// the subscription and freezes the displayed snapshot; resume shows the newest one at once.
//
// Staleness: "as of N s ago" is the age of the displayed snapshot on the server clock
// (model.asOfSeconds). "Updates stopped N s" (active-stale-badge) shows while the shared socket has
// been silent for more than 10 s (useStaleSeconds) and, after it comes back, until the first new
// snapshot arrives (no snapshot for more than 10 s); also when a live socket brings no snapshot for
// more than 10 s.
import { useCallback, useEffect, useMemo, useState } from 'react';

import { parseActivePush, useActiveQuery } from '../../api/active';
import type { PushFrame } from '../../realtime/frames';
import { STALE_AFTER_S, usePollingFallback, useStaleSeconds, useTopic } from '../../realtime/hooks';

import { newerSnapshot, receiveSnapshot, silenceSeconds, type ReceivedSnapshot } from './model';

/** Current time, re-read every second. */
export function useSecondTick(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1_000);
    return () => {
      clearInterval(timer);
    };
  }, []);
  return now;
}

interface Store {
  readonly appId: number | null;
  /** The newest snapshot of the `active` topic. */
  readonly ws: ReceivedSnapshot | null;
  /** The displayed snapshot while paused (null: not paused). */
  readonly frozen: ReceivedSnapshot | null;
}

function emptyStore(appId: number | null): Store {
  return { appId, ws: null, frozen: null };
}

export interface ActiveSnapshotApi {
  /** The newest snapshot received (WS or REST). */
  readonly latest: ReceivedSnapshot | null;
  /** The displayed snapshot (frozen while paused). */
  readonly shown: ReceivedSnapshot | null;
  readonly paused: boolean;
  readonly setPaused: (paused: boolean) => void;
  readonly now: number;
  /** Seconds without updates when the stale badge shows, else null. */
  readonly staleSeconds: number | null;
  /** REST polling replaces the silent socket. */
  readonly polling: boolean;
  readonly query: ReturnType<typeof useActiveQuery>;
}

export function useActiveSnapshot(appId: number | null): ActiveSnapshotApi {
  const polling = usePollingFallback();
  const query = useActiveQuery(appId, polling);
  const [store, setStore] = useState<Store>(() => emptyStore(appId));
  const current: Store = store.appId === appId ? store : emptyStore(appId);

  // The REST answer (first load, resync refetch, polling), received when it was fetched.
  const restData = query.data;
  const restAt = query.dataUpdatedAt;
  const rest = useMemo(
    () =>
      restData === undefined
        ? null
        : // dataUpdatedAt: the browser time the answer arrived (set whenever data is).
          receiveSnapshot(restData.data, restData.meta.generated_at, restAt, 'rest'),
    [restData, restAt],
  );

  const refetch = query.refetch;
  useTopic('active', appId === null ? null : { app: appId, kind: 'ALL' }, {
    onData: (frame: PushFrame) => {
      const data = parseActivePush(frame.data);
      if (data === null) {
        return;
      }
      const received = receiveSnapshot(data, frame.t, Date.now(), 'ws');
      if (received === null) {
        return;
      }
      setStore((previous) => {
        const base = previous.appId === appId ? previous : emptyStore(appId);
        return { ...base, ws: newerSnapshot(base.ws, received) };
      });
    },
    onResync: () => {
      void refetch();
    },
  });

  // The newer of the two by server time replaces the other as a whole.
  const ws = current.ws;
  const latest = rest === null ? ws : newerSnapshot(ws, rest);

  const now = useSecondTick();
  const wsStale = useStaleSeconds();
  const silence = latest === null ? null : silenceSeconds(latest, now);
  let staleSeconds: number | null = null;
  if (wsStale !== null) {
    staleSeconds = Math.max(wsStale, silence ?? 0);
  } else if (silence !== null && silence > STALE_AFTER_S) {
    staleSeconds = silence;
  }

  const setPaused = useCallback(
    (paused: boolean) => {
      setStore((previous) => {
        const base = previous.appId === appId ? previous : emptyStore(appId);
        return { ...base, frozen: paused ? latest : null };
      });
    },
    [appId, latest],
  );

  return {
    latest,
    shown: current.frozen ?? latest,
    paused: current.frozen !== null,
    setPaused,
    now,
    staleSeconds,
    polling,
    query,
  };
}
