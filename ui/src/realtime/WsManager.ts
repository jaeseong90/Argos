// SPDX-License-Identifier: Apache-2.0
//
// One WebSocket for the whole page (docs/15 §10, DSN-15-10, SEC-44): topics are multiplexed over
// a single connection with reference counting per topic + params.
//
// Rules (each has a test in WsManager.test.ts):
//  - connect lazily on the first subscription to ws(s)://<host>/ws (cookie session; the browser
//    sends Origin); close after 30 s without subscriptions;
//  - one subscribe frame per distinct topic + params, the last release sends unsubscribe;
//  - at most 20 subscriptions per connection, a 21st is refused on the client (SEC-44);
//  - reconnect after a drop with exponential backoff 1 s -> 30 s and ±20 % jitter; after
//    reconnecting resubscribe everything and call each onResync(lastTs) so screens fill the gap
//    through REST (docs/14 §5.3: the server does not resend); a subscription made during the
//    back-off waits for the scheduled attempt instead of connecting at once;
//  - page hidden for 30 s -> unsubscribe all (connection kept); visible again -> resubscribe and
//    resync;
//  - ping every 30 s, reconnect when no pong arrives within 10 s;
//  - close 4401 or a reauth frame (session expired or revoked, C-167) -> no reconnect, report the
//    expired session; logout closes the socket (shutdown) before the shell navigates;
//  - server frames are validated (frames.ts); invalid ones are dropped and counted.
import { useConnectionStore, type ConnectionState } from './connectionStore';
import {
  CLOSE_NO_PONG,
  CLOSE_NORMAL,
  CLOSE_ORIGIN,
  CLOSE_UNAUTHENTICATED,
  encodeClientFrame,
  MAX_SUBSCRIPTIONS,
  parseServerFrame,
  type ClientFrame,
  type ErrorFrame,
  type PushFrame,
  type Topic,
  type TopicParams,
} from './frames';

export const BACKOFF_INITIAL_MS = 1_000;
export const BACKOFF_MAX_MS = 30_000;
export const BACKOFF_JITTER = 0.2;
export const PING_INTERVAL_MS = 30_000;
export const PONG_TIMEOUT_MS = 10_000;
export const HIDDEN_UNSUBSCRIBE_MS = 30_000;
export const IDLE_CLOSE_MS = 30_000;

/** Callbacks of one subscriber. */
export interface TopicHandlers {
  onData(frame: PushFrame): void;
  /**
   * After a reconnect or a return from a hidden page: fill [lastTs, now] through REST.
   * `lastTs` is the server time of the last push received (epoch ms), null if none arrived.
   */
  onResync?(lastTs: number | null): void;
  /** Server error for the subscription, or a client-side refusal (`limit_exceeded`). */
  onError?(error: { code: string; message?: string | undefined }): void;
}

export type Unsubscribe = () => void;

/** The WebSocket surface the manager uses (the browser class or a test double). */
export interface SocketLike {
  readonly readyState: number;
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code: number }) => void) | null;
  onerror: ((event: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type SocketFactory = (url: string) => SocketLike;

/** Visibility surface (document in the browser). */
export interface VisibilitySource {
  readonly visibilityState: string;
  addEventListener(type: 'visibilitychange', listener: () => void): void;
  removeEventListener(type: 'visibilitychange', listener: () => void): void;
}

export interface WsManagerOptions {
  readonly createSocket?: SocketFactory;
  /** ws(s)://<host>/ws of the page (docs/14 §5.1). */
  readonly url?: () => string;
  readonly visibility?: VisibilitySource | null;
  /** Random source for the jitter, in [0, 1). */
  readonly random?: () => number;
  readonly now?: () => number;
  /** The session is gone (4401 or reauth): the shell expires the auth store. */
  readonly onSessionExpired?: () => void;
  /** Connection state sink (default: the shared connection store). */
  readonly setState?: (patch: Partial<ConnectionState>) => void;
  readonly getState?: () => ConnectionState;
}

const OPEN = 1;
const CONNECTING = 0;

interface Subscription {
  readonly id: string;
  readonly key: string;
  readonly topic: Topic;
  readonly params: TopicParams;
  readonly handlers: Set<TopicHandlers>;
  lastTs: number | null;
  /** A subscribe frame was sent on the current connection (and not withdrawn). */
  sent: boolean;
}

/** Stable key of topic + params (sorted keys). */
export function subscriptionKey(topic: Topic, params: TopicParams): string {
  const entries = Object.keys(params)
    .filter((key) => params[key] !== undefined)
    .sort()
    .map((key) => [key, params[key]]);
  return `${topic}:${JSON.stringify(entries)}`;
}

/** ws:// or wss:// URL of /ws on the page's host (docs/14 §5.1). */
export function defaultSocketUrl(): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/ws`;
}

/** Backoff delay of a reconnect attempt (0-based): 1 s doubling to 30 s, ±20 % jitter. */
export function backoffDelay(attempt: number, random: () => number): number {
  const base = Math.min(BACKOFF_MAX_MS, BACKOFF_INITIAL_MS * 2 ** Math.min(attempt, 10));
  const jitter = (random() * 2 - 1) * BACKOFF_JITTER;
  return Math.round(base * (1 + jitter));
}

export class WsManager {
  private readonly createSocket: SocketFactory;
  private readonly url: () => string;
  private readonly visibility: VisibilitySource | null;
  private readonly random: () => number;
  private readonly now: () => number;
  private readonly setState: (patch: Partial<ConnectionState>) => void;
  private readonly getState: () => ConnectionState;
  private onSessionExpired: (() => void) | undefined;

  private socket: SocketLike | null = null;
  private readonly subscriptions = new Map<string, Subscription>();
  private readonly byId = new Map<string, Subscription>();
  private nextId = 1;
  private attempt = 0;
  /** Set by shutdown, 4401, reauth and 4403: no (re)connect until resetSession(). */
  private stopped = false;
  private sessionGone = false;
  private hidden = false;
  private suspended = false;
  private hadConnection = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null;
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly visibilityListener = (): void => {
    this.handleVisibility();
  };

  constructor(options: WsManagerOptions = {}) {
    this.createSocket =
      options.createSocket ??
      ((url: string): SocketLike => new WebSocket(url) as unknown as SocketLike);
    this.url = options.url ?? defaultSocketUrl;
    this.visibility =
      options.visibility === undefined
        ? typeof document === 'undefined'
          ? null
          : document
        : options.visibility;
    this.random = options.random ?? Math.random;
    this.now = options.now ?? Date.now;
    this.onSessionExpired = options.onSessionExpired;
    this.setState =
      options.setState ??
      ((patch) => {
        useConnectionStore.setState(patch);
      });
    this.getState = options.getState ?? (() => useConnectionStore.getState());
    this.visibility?.addEventListener('visibilitychange', this.visibilityListener);
    this.hidden = this.visibility?.visibilityState === 'hidden';
  }

  setSessionExpiredHandler(handler: (() => void) | undefined): void {
    this.onSessionExpired = handler;
  }

  /** Number of distinct active subscriptions. */
  get subscriptionCount(): number {
    return this.subscriptions.size;
  }

  /**
   * Subscribes to a topic. Subscribers of the same topic + params share one server subscription.
   * A new distinct subscription beyond 20 is refused (onError limit_exceeded, nothing sent).
   */
  subscribe(topic: Topic, params: TopicParams, handlers: TopicHandlers): Unsubscribe {
    const key = subscriptionKey(topic, params);
    let sub = this.subscriptions.get(key);
    if (sub === undefined) {
      if (this.subscriptions.size >= MAX_SUBSCRIPTIONS) {
        this.setState({ refusedSubscriptions: this.getState().refusedSubscriptions + 1 });
        handlers.onError?.({ code: 'limit_exceeded', message: 'At most 20 subscriptions' });
        return () => undefined;
      }
      sub = {
        id: `s${String(this.nextId)}`,
        key,
        topic,
        params,
        handlers: new Set(),
        lastTs: null,
        sent: false,
      };
      this.nextId += 1;
      this.subscriptions.set(key, sub);
      this.byId.set(sub.id, sub);
      this.publishCount();
    }
    sub.handlers.add(handlers);
    this.cancelIdleClose();
    // After shutdown (logout), 4401 or reauth the subscription is kept but nothing connects until
    // resetSession() (a new login): a socket must never outlive the session it was opened for.
    // While a reconnect is scheduled (back-off after a drop) the subscription only registers: the
    // scheduled attempt connects and onopen sends every subscription (resubscribeAll), so screen
    // changes during an outage never skip the back-off (docs/14 §5.3, docs/15 §10).
    if (this.socket === null) {
      if (this.reconnectTimer === null) {
        this.open();
      }
    } else {
      this.sendSubscribe(sub);
    }
    const target = sub;
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.release(target, handlers);
    };
  }

  private release(sub: Subscription, handlers: TopicHandlers): void {
    sub.handlers.delete(handlers);
    if (sub.handlers.size > 0) {
      return;
    }
    this.subscriptions.delete(sub.key);
    this.byId.delete(sub.id);
    this.publishCount();
    if (sub.sent) {
      this.send({ op: 'unsubscribe', id: sub.id });
      sub.sent = false;
    }
    if (this.subscriptions.size === 0) {
      this.scheduleIdleClose();
    }
  }

  /** Closes the socket for good (logout): no reconnect until resetSession() after a new login. */
  shutdown(): void {
    this.stopped = true;
    this.clearTimers();
    this.closeSocket(CLOSE_NORMAL, 'logout');
    this.setState({ status: 'closed' });
  }

  /**
   * After a new login: the session is valid again, subscriptions may reconnect. This connects at
   * once, also when a back-off is pending: a new session is not a retry of the failed one (a drop
   * of the old session, e.g. an upgrade refused for an expired cookie, says nothing about the new
   * one), so open() drops the scheduled attempt instead of waiting for it.
   */
  resetSession(): void {
    this.sessionGone = false;
    this.stopped = false;
    if (this.socket === null && this.subscriptions.size > 0) {
      this.open();
    }
  }

  /** Releases listeners (tests, hot reload). */
  dispose(): void {
    this.shutdown();
    this.visibility?.removeEventListener('visibilitychange', this.visibilityListener);
  }

  private publishCount(): void {
    const count = this.subscriptions.size;
    const state = this.getState();
    const activeSince = count === 0 ? null : (state.activeSince ?? this.now());
    this.setState({ subscriptions: count, activeSince });
  }

  private open(): void {
    if (this.stopped || this.sessionGone) {
      return;
    }
    this.clearReconnect();
    let socket: SocketLike;
    try {
      socket = this.createSocket(this.url());
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    this.setState({ status: 'connecting' });
    socket.onopen = () => {
      if (this.socket !== socket) {
        return;
      }
      const reconnected = this.hadConnection;
      this.hadConnection = true;
      this.attempt = 0;
      this.setState({ status: 'open' });
      this.startPing();
      if (!this.hidden) {
        this.resubscribeAll(reconnected);
      } else {
        this.suspended = true;
      }
    };
    socket.onmessage = (event) => {
      if (this.socket === socket) {
        this.handleMessage(event.data);
      }
    };
    socket.onerror = () => {
      // A close event always follows; reconnect is decided there.
    };
    socket.onclose = (event) => {
      if (this.socket === socket) {
        this.handleClose(event.code);
      }
    };
  }

  private resubscribeAll(resync: boolean): void {
    for (const sub of this.subscriptions.values()) {
      sub.sent = false;
      this.sendSubscribe(sub);
      if (resync) {
        for (const handler of sub.handlers) {
          handler.onResync?.(sub.lastTs);
        }
      }
    }
  }

  private sendSubscribe(sub: Subscription): void {
    if (sub.sent || this.socket?.readyState !== OPEN || this.suspended) {
      return;
    }
    if (this.send({ op: 'subscribe', id: sub.id, topic: sub.topic, params: sub.params })) {
      sub.sent = true;
    } else {
      for (const handler of sub.handlers) {
        handler.onError?.({ code: 'limit_exceeded', message: 'Subscription message too large' });
      }
    }
  }

  private send(frame: ClientFrame): boolean {
    const text = encodeClientFrame(frame);
    if (text === null || this.socket?.readyState !== OPEN) {
      return false;
    }
    try {
      this.socket.send(text);
      return true;
    } catch {
      return false;
    }
  }

  private handleMessage(data: unknown): void {
    const frame = parseServerFrame(data);
    if (frame === null) {
      this.setState({ invalidFrames: this.getState().invalidFrames + 1 });
      return;
    }
    // Freshness counts frames about data (ack, push, notice); a pong only proves the connection,
    // so a server that answers pings but stopped pushing still shows the stale badge.
    if (frame.op === 'ack' || frame.op === 'push' || frame.op === 'notice') {
      this.setState({ lastMessageAt: this.now() });
    }
    switch (frame.op) {
      case 'push': {
        const sub = this.byId.get(frame.id);
        if (sub === undefined) {
          return;
        }
        const ts = Date.parse(frame.t);
        if (!Number.isNaN(ts)) {
          sub.lastTs = ts;
        }
        for (const handler of [...sub.handlers]) {
          handler.onData(frame);
        }
        return;
      }
      case 'error':
        this.handleError(frame);
        return;
      case 'pong':
        this.clearPongTimer();
        this.setState({ rttMs: Math.max(0, this.now() - frame.t) });
        return;
      case 'notice':
        if (frame.dropped !== undefined) {
          this.setState({ serverDropped: this.getState().serverDropped + frame.dropped });
        }
        return;
      case 'reauth':
        this.endSession();
        return;
      case 'ack':
        return;
    }
  }

  private handleError(frame: ErrorFrame): void {
    if (frame.id === undefined) {
      return;
    }
    const sub = this.byId.get(frame.id);
    if (sub === undefined) {
      return;
    }
    // The server ended this subscription (forbidden, invalid params, limit): drop it.
    this.subscriptions.delete(sub.key);
    this.byId.delete(sub.id);
    sub.sent = false;
    this.publishCount();
    for (const handler of [...sub.handlers]) {
      handler.onError?.({ code: frame.code, message: frame.message });
    }
  }

  private endSession(): void {
    this.sessionGone = true;
    this.stopped = true;
    this.clearTimers();
    this.closeSocket(CLOSE_NORMAL, 'session ended');
    this.setState({ status: 'closed' });
    this.onSessionExpired?.();
  }

  private handleClose(code: number): void {
    this.socket = null;
    this.clearPing();
    for (const sub of this.subscriptions.values()) {
      sub.sent = false;
    }
    this.setState({ status: 'closed' });
    if (code === CLOSE_UNAUTHENTICATED) {
      this.sessionGone = true;
      this.stopped = true;
      this.clearTimers();
      this.onSessionExpired?.();
      return;
    }
    if (code === CLOSE_ORIGIN) {
      // Origin mismatch is a configuration error; retrying cannot succeed.
      this.stopped = true;
      return;
    }
    if (!this.stopped && this.subscriptions.size > 0) {
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect(): void {
    this.clearReconnect();
    const delay = backoffDelay(this.attempt, this.random);
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.stopped && this.subscriptions.size > 0 && this.socket === null) {
        this.open();
      }
    }, delay);
  }

  private startPing(): void {
    this.clearPing();
    this.pingTimer = setInterval(() => {
      if (this.send({ op: 'ping', t: this.now() })) {
        this.clearPongTimer();
        this.pongTimer = setTimeout(() => {
          this.pongTimer = null;
          // No pong: the connection is dead even if the browser has not noticed; reconnect.
          const socket = this.socket;
          this.socket = null;
          if (socket !== null) {
            socket.onclose = null;
            try {
              socket.close(CLOSE_NO_PONG, 'no pong');
            } catch {
              // Already closed.
            }
          }
          this.handleClose(CLOSE_NO_PONG);
        }, PONG_TIMEOUT_MS);
      }
    }, PING_INTERVAL_MS);
  }

  private handleVisibility(): void {
    const hidden = this.visibility?.visibilityState === 'hidden';
    this.hidden = hidden;
    if (hidden) {
      if (this.hiddenTimer === null) {
        this.hiddenTimer = setTimeout(() => {
          this.hiddenTimer = null;
          this.suspend();
        }, HIDDEN_UNSUBSCRIBE_MS);
      }
      return;
    }
    if (this.hiddenTimer !== null) {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
    }
    if (this.suspended) {
      this.suspended = false;
      this.resubscribeAll(true);
    }
  }

  private suspend(): void {
    for (const sub of this.subscriptions.values()) {
      if (sub.sent) {
        this.send({ op: 'unsubscribe', id: sub.id });
        sub.sent = false;
      }
    }
    this.suspended = true;
  }

  private scheduleIdleClose(): void {
    this.cancelIdleClose();
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null;
      if (this.subscriptions.size === 0) {
        this.clearReconnect();
        this.clearPing();
        this.closeSocket(CLOSE_NORMAL, 'idle');
        this.setState({ status: 'idle' });
      }
    }, IDLE_CLOSE_MS);
  }

  private cancelIdleClose(): void {
    if (this.idleTimer !== null) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private closeSocket(code: number, reason: string): void {
    const socket = this.socket;
    this.socket = null;
    this.hadConnection = false;
    if (socket === null) {
      return;
    }
    socket.onclose = null;
    socket.onmessage = null;
    socket.onopen = null;
    if (socket.readyState === OPEN || socket.readyState === CONNECTING) {
      try {
        socket.close(code, reason);
      } catch {
        // Already closing.
      }
    }
  }

  private clearPongTimer(): void {
    if (this.pongTimer !== null) {
      clearTimeout(this.pongTimer);
      this.pongTimer = null;
    }
  }

  private clearPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    this.clearPongTimer();
  }

  private clearReconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private clearTimers(): void {
    this.clearReconnect();
    this.clearPing();
    this.cancelIdleClose();
    if (this.hiddenTimer !== null) {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
    }
  }
}
