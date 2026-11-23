// SPDX-License-Identifier: Apache-2.0
//
// React side of the realtime XLog (docs/15 §7.5, §10): one XLogLiveController per live session
// (app, environment, kind and scatter filters), the `xlog` topic through the shared WsManager
// (useTopic: one socket per page, docs/14 §5) with exactly the topic's parameters (the error and
// response time filters are applied to the batches by the controller), the window as state for the
// canvas (once per second; while paused, the point count once per second), pause/resume, the REST
// polling fallback after 30 s without a realtime message (docs/15 §10, usePollingFallback), and the
// counters for diagnostics. The WebSocket server is WO-22; until then the E2E suite plays the
// server with the docs/14 §5 stub (ADR-0010 C-205 (3)).
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { ResponseMeta } from '../../../api/client';
import type { XlogPointFilters } from '../../../api/transactionSearch';
import type { TopicParams } from '../../../realtime/frames';
import { usePollingFallback, useTopic } from '../../../realtime/hooks';
import type { Kind } from '../../../state/urlState';
import type { XLogBuffer } from '../../../xlog/XLogBuffer';
import { LocalDictionary } from '../data/dictionary';
import { fetchXLogPoints } from '../data/xlogApi';
import { xlogRequest, xlogTopicParams } from '../data/xlogQuery';

import {
  XLogLiveController,
  type LiveFill,
  type LivePointFilter,
  type LiveStats,
  type LiveWindow,
} from './XLogLiveController';

export type LiveStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface XLogLiveState {
  readonly status: LiveStatus;
  readonly error: unknown;
  readonly meta: ResponseMeta | null;
  /** Dictionary of the session: new for every start, extended in place by the batches. */
  readonly dictionary: LocalDictionary;
  /** The window on screen (moves every second unless paused); null when not live. */
  readonly window: LiveWindow | null;
  readonly paused: boolean;
  /** The last REST fill (resume, reconnect), for the sampling note of a large gap. */
  readonly lastFill: LiveFill | null;
  /** The last fill failed (the gap stays until the next resume or reconnect). */
  readonly fillError: unknown;
  /** Realtime has been silent for 30 s: the points are polled through REST (docs/15 §10). */
  readonly polling: boolean;
  /**
   * Buffer size after the last data change (also while paused): a new value re-renders a consumer
   * that reads the buffer (the page's point count, empty state, "select visible").
   */
  readonly size: number;
  readonly pause: () => void;
  readonly resume: () => void;
  /** Counters of the session (not reactive: read them when needed). */
  readonly stats: () => LiveStats | null;
}

export interface UseXLogLiveOptions {
  readonly enabled: boolean;
  readonly appId: number;
  readonly env: string | undefined;
  readonly kind: Kind;
  readonly filters: XlogPointFilters;
  readonly buffer: XLogBuffer;
  /** Changing it starts a new session (retry after an error). */
  readonly attempt?: number;
}

/** State of one session, tagged with its key so that a new session never shows an old one's. */
interface SessionState {
  readonly key: string;
  readonly status: LiveStatus;
  readonly error: unknown;
  readonly meta: ResponseMeta | null;
  readonly dictionary: LocalDictionary;
  readonly window: LiveWindow | null;
  readonly paused: boolean;
  readonly lastFill: LiveFill | null;
  readonly fillError: unknown;
  /** Buffer size at the last paused tick (a new value re-renders the page). */
  readonly size: number;
}

/** The topic parameters of a session as its key holds them (xlogTopicParams). */
interface TopicKeyParams {
  readonly app: number;
  readonly env?: string;
  readonly kind: Kind;
  readonly service: string[];
  readonly instance: number[];
}

/** A session: the topic's parameters and the filters applied to its batches on the client. */
interface SessionParams {
  readonly topic: TopicKeyParams;
  readonly points: LivePointFilter;
}

const EMPTY_DICTIONARY = new LocalDictionary();

function pointFilterOf(filters: XlogPointFilters): LivePointFilter {
  return {
    ...(filters.error === undefined ? {} : { error: filters.error }),
    ...(filters.minMs === undefined ? {} : { minMs: filters.minMs }),
    ...(filters.maxMs === undefined ? {} : { maxMs: filters.maxMs }),
  };
}

function filtersOf(params: SessionParams): XlogPointFilters {
  return {
    service: params.topic.service,
    instance: params.topic.instance,
    ...params.points,
    unapplied: [],
    empty: false,
  };
}

export function useXLogLive({
  enabled,
  appId,
  env,
  kind,
  filters,
  buffer,
  attempt = 0,
}: UseXLogLiveOptions): XLogLiveState {
  const [session, setSession] = useState<SessionState | null>(null);
  const controllerRef = useRef<XLogLiveController | null>(null);
  const active = enabled && !filters.empty;
  // The subscription follows the topic's parameters only; a session (REST loads, client filters)
  // also follows the error and response time filters.
  const topicKey = active ? JSON.stringify(xlogTopicParams(appId, env, kind, filters)) : null;
  const sessionKey =
    topicKey === null
      ? null
      : JSON.stringify({ topic: JSON.parse(topicKey) as unknown, points: pointFilterOf(filters) });
  const key = sessionKey === null ? null : `${sessionKey}#${String(attempt)}`;
  const topicParams = useMemo(
    () => (topicKey === null ? null : (JSON.parse(topicKey) as TopicParams)),
    [topicKey],
  );
  const pollingWanted = usePollingFallback();

  useEffect(() => {
    if (key === null || sessionKey === null) {
      controllerRef.current = null;
      return undefined;
    }
    const params = JSON.parse(sessionKey) as SessionParams;
    const update = (patch: Partial<Omit<SessionState, 'key'>>): void => {
      setSession((current) => {
        const base: SessionState =
          current?.key === key
            ? current
            : {
                key,
                status: 'loading',
                error: null,
                meta: null,
                dictionary: EMPTY_DICTIONARY,
                window: null,
                paused: false,
                lastFill: null,
                fillError: null,
                size: 0,
              };
        return { ...base, ...patch };
      });
    };
    const controller = new XLogLiveController({
      buffer,
      pointFilter: params.points,
      fetchPoints: (range, signal) =>
        fetchXLogPoints(
          params.topic.app,
          xlogRequest(range, params.topic.kind, filtersOf(params)),
          signal,
        ),
      onWindow: (window) => {
        update({ window });
      },
      onData: (size) => {
        update({ size });
      },
      onLoad: (load) => {
        update({ status: 'ready', error: null, meta: load.meta, dictionary: load.dictionary });
      },
      onError: (error) => {
        update({ status: 'error', error });
      },
      onFill: (fill) => {
        update({ lastFill: fill, fillError: null });
      },
      onFillError: (error) => {
        update({ fillError: error });
      },
    });
    controllerRef.current = controller;
    controller.start();
    return () => {
      controller.stop();
      if (controllerRef.current === controller) {
        controllerRef.current = null;
      }
    };
  }, [key, sessionKey, buffer]);

  // docs/15 §10: REST polling while realtime is silent for 30 s, stopped by the next message. The
  // controller of a new session starts in the current state (`key` re-runs this after it).
  const polling = pollingWanted && key !== null;
  useEffect(() => {
    controllerRef.current?.setPolling(polling);
  }, [polling, key, buffer]);

  useTopic('xlog', topicParams, {
    onData: (frame) => {
      controllerRef.current?.onPush(frame);
    },
    onResync: (lastTs) => {
      controllerRef.current?.resync(lastTs);
    },
  });

  const pause = useCallback(() => {
    const controller = controllerRef.current;
    if (controller === null) {
      return;
    }
    controller.pause();
    setSession((current) => (current === null ? current : { ...current, paused: true }));
  }, []);
  const resume = useCallback(() => {
    const controller = controllerRef.current;
    if (controller === null) {
      return;
    }
    controller.resume();
    setSession((current) => (current === null ? current : { ...current, paused: false }));
  }, []);
  const stats = useCallback(() => controllerRef.current?.stats ?? null, []);

  const mine = key !== null && session?.key === key ? session : null;
  return {
    status: key === null ? 'idle' : (mine?.status ?? 'loading'),
    error: mine?.error ?? null,
    meta: mine?.meta ?? null,
    dictionary: mine?.dictionary ?? EMPTY_DICTIONARY,
    window: key === null ? null : (mine?.window ?? null),
    paused: mine?.paused ?? false,
    lastFill: mine?.lastFill ?? null,
    fillError: mine?.fillError ?? null,
    polling: polling && mine?.status === 'ready',
    size: mine?.size ?? 0,
    pause,
    resume,
    stats,
  };
}
