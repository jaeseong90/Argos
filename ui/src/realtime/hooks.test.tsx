// SPDX-License-Identifier: Apache-2.0
//
// Realtime hooks (docs/15 §5, §10): the stale badge after 10 s without data frames, the polling
// fallback after 30 s, and useTopic's subscription lifetime on top of the shared manager.
// Requirements (test/README.md §5): { type: 'req', description: 'DSN-15-10' }
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fakeSockets } from '../../test/fakeWebSocket';

import { INITIAL_CONNECTION_STATE, useConnectionStore } from './connectionStore';
import {
  POLL_AFTER_S,
  setWsManager,
  STALE_AFTER_S,
  usePollingFallback,
  useSecondsSinceMessage,
  useStaleSeconds,
  useTopic,
} from './hooks';
import { WsManager } from './WsManager';

const T0 = Date.UTC(2026, 10, 14, 1, 0, 0);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
  useConnectionStore.setState(INITIAL_CONNECTION_STATE);
});

afterEach(() => {
  setWsManager(null);
  vi.useRealTimers();
});

function advance(ms: number): void {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

describe('T-SEC-44 realtime hooks: staleness (docs/15 §5 "갱신 중단 N초", §10)', () => {
  it('T-SEC-44 reports nothing without subscriptions', () => {
    const { result } = renderHook(() => useSecondsSinceMessage());
    expect(result.current).toBeNull();
  });

  it('T-SEC-44 shows the stale badge only after more than 10 s without a data frame', () => {
    useConnectionStore.setState({ subscriptions: 1, activeSince: T0, lastMessageAt: T0 });
    const stale = renderHook(() => useStaleSeconds());
    advance(STALE_AFTER_S * 1000);
    expect(stale.result.current).toBeNull();
    advance(1000);
    expect(stale.result.current).toBe(11);
    // A new frame clears it.
    act(() => {
      useConnectionStore.setState({ lastMessageAt: Date.now() });
    });
    advance(1000);
    expect(stale.result.current).toBeNull();
  });

  it('T-SEC-44 counts from the first subscription when no frame arrived yet', () => {
    useConnectionStore.setState({ subscriptions: 1, activeSince: T0, lastMessageAt: null });
    const { result } = renderHook(() => useSecondsSinceMessage());
    advance(4000);
    expect(result.current).toBe(4);
  });

  it('T-SEC-44 starts the polling fallback after 30 s of silence', () => {
    useConnectionStore.setState({ subscriptions: 2, activeSince: T0, lastMessageAt: T0 });
    const { result } = renderHook(() => usePollingFallback());
    advance((POLL_AFTER_S - 1) * 1000);
    expect(result.current).toBe(false);
    advance(1000);
    expect(result.current).toBe(true);
  });
});

describe('T-SEC-44 realtime hooks: useTopic', () => {
  it('T-SEC-44 subscribes while mounted, pauses with null params and releases on unmount', () => {
    const sockets = fakeSockets();
    setWsManager(new WsManager({ createSocket: sockets.factory, visibility: null }));
    const onData = vi.fn();
    const view = renderHook(
      ({ params }: { params: { app: number } | null }) => {
        useTopic('counter', params, { onData });
      },
      { initialProps: { params: null as { app: number } | null } },
    );
    expect(sockets.sockets).toHaveLength(0);
    view.rerender({ params: { app: 3 } });
    const socket = sockets.last();
    socket.serverOpen();
    expect(socket.framesOf('subscribe')).toEqual([
      { op: 'subscribe', id: 's1', topic: 'counter', params: { app: 3 } },
    ]);
    socket.serverSend({
      op: 'push',
      id: 's1',
      topic: 'counter',
      t: '2026-11-14T01:00:00Z',
      data: {},
    });
    expect(onData).toHaveBeenCalledTimes(1);
    // The same params on a re-render do not resubscribe.
    view.rerender({ params: { app: 3 } });
    expect(socket.framesOf('subscribe')).toHaveLength(1);
    view.unmount();
    expect(socket.framesOf('unsubscribe')).toEqual([{ op: 'unsubscribe', id: 's1' }]);
  });
});
