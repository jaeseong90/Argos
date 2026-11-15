// SPDX-License-Identifier: Apache-2.0
//
// React access to the shared WebSocket manager (docs/15 §10): subscribe to a topic for the life
// of a component, read how stale the data is (ws-stale-badge above 10 s) and whether to fall back
// to polling (after 30 s without messages).
import { useEffect, useRef, useState } from 'react';

import { useConnectionStore } from './connectionStore';
import type { PushFrame, Topic, TopicParams } from './frames';
import { WsManager, type TopicHandlers } from './WsManager';

/** The stale badge appears after 10 s without a server message (docs/15 §5, §10). */
export const STALE_AFTER_S = 10;
/** Polling fills in after 30 s without a server message (docs/15 §10). */
export const POLL_AFTER_S = 30;

let shared: WsManager | null = null;

/** The page-wide manager (created on first use: pages without realtime open no socket). */
export function getWsManager(): WsManager {
  shared ??= new WsManager();
  return shared;
}

/** Replaces the page-wide manager (tests). */
export function setWsManager(manager: WsManager | null): void {
  shared?.dispose();
  shared = manager;
}

export interface UseTopicOptions {
  onData(frame: PushFrame): void;
  onResync?(lastTs: number | null): void;
  onError?(error: { code: string; message?: string | undefined }): void;
}

/**
 * Subscribes while mounted and while `params` is not null (pass null to pause, e.g. when live is
 * off). Handlers may change between renders without resubscribing.
 */
export function useTopic(topic: Topic, params: TopicParams | null, options: UseTopicOptions): void {
  const handlersRef = useRef(options);
  useEffect(() => {
    handlersRef.current = options;
  });
  const paramsKey = params === null ? null : JSON.stringify(params);
  useEffect(() => {
    if (paramsKey === null) {
      return undefined;
    }
    const handlers: TopicHandlers = {
      onData: (frame) => {
        handlersRef.current.onData(frame);
      },
      onResync: (lastTs) => {
        handlersRef.current.onResync?.(lastTs);
      },
      onError: (error) => {
        handlersRef.current.onError?.(error);
      },
    };
    return getWsManager().subscribe(topic, JSON.parse(paramsKey) as TopicParams, handlers);
  }, [topic, paramsKey]);
}

/** Current time, re-read every second while `active`. */
function useSecondTick(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1_000);
    return () => {
      clearInterval(timer);
    };
  }, [active]);
  return now;
}

/**
 * Seconds since the last server message while there are subscriptions (since the first
 * subscription if no message arrived yet); null without subscriptions.
 */
export function useSecondsSinceMessage(): number | null {
  const subscriptions = useConnectionStore((s) => s.subscriptions);
  const lastMessageAt = useConnectionStore((s) => s.lastMessageAt);
  const activeSince = useConnectionStore((s) => s.activeSince);
  const active = subscriptions > 0;
  const now = useSecondTick(active);
  if (!active) {
    return null;
  }
  const since = Math.max(lastMessageAt ?? 0, activeSince ?? 0);
  return since === 0 ? null : Math.max(0, Math.floor((now - since) / 1000));
}

/** Seconds without updates when above 10 s (ws-stale-badge), else null (docs/15 §5). */
export function useStaleSeconds(): number | null {
  const seconds = useSecondsSinceMessage();
  return seconds !== null && seconds > STALE_AFTER_S ? seconds : null;
}

/** True when screens should poll because realtime has been silent for 30 s (docs/15 §10). */
export function usePollingFallback(): boolean {
  const seconds = useSecondsSinceMessage();
  return seconds !== null && seconds >= POLL_AFTER_S;
}
