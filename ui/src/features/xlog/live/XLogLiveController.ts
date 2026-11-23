// SPDX-License-Identifier: Apache-2.0
//
// Realtime XLog (docs/15 §5 data flow, §7.5, §10; docs/14 §5.2, §5.3): an initial REST load over
// [now - 5 min, now], then the `xlog` topic's 100 ms batches appended to the ring buffer, the
// window moved every second (the canvas shifts its point layer and draws only the new points),
// points older than the window evicted. Pause stops only the window; data keeps arriving. Resume
// and a reconnect (WsManager onResync) fill [last received, now] through REST. Every point goes
// through one bounded tx_id set, so the overlap of REST and push never draws a point twice. A
// point more than 60 s older than its batch is not shown live (D-15; the server leaves such rows
// out of the topic anyway, docs/14 §5.3): a REST query shows it. The topic has no error or response
// time parameter (docs/14 §5.2), so those scatter filters are applied to every batch here with the
// meaning of the points API (`livePointAccepts`): push and REST show the same set of points. While
// the realtime connection is silent for 30 s (docs/15 §10, useXLogLive) the controller polls the
// REST API every POLL_INTERVAL_MS through the same de-duplicated fill.
//
// Pure TypeScript (no React): the page drives it through useXLogLive; tests drive it with a fake
// fetch and fake timers.
import type { ResponseMeta } from '../../../api/client';
import type { PushFrame } from '../../../realtime/frames';
import { STATUS_ERROR, type XLogBuffer } from '../../../xlog/XLogBuffer';
import { appendPointColumns, type AppendStats, type XLogPointColumns } from '../data/columns';
import { LocalDictionary } from '../data/dictionary';
import type { XLogPoints } from '../data/xlogApi';

import { BoundedIdSet } from './boundedIdSet';
import { parseXLogPush } from './pushData';

/** The realtime window (docs/15 §7.5 "5분 창"). */
export const LIVE_WINDOW_MS = 5 * 60_000;
/** The window moves once per second (docs/15 §5). */
export const LIVE_TICK_MS = 1_000;
/** D-15: a point arriving more than 60 s after its time is not shown live. */
export const LATE_MS = 60_000;
/**
 * Batches kept while the initial load runs (100 ms batches: 10 s), the oldest dropped first. Their
 * rows are bounded too, by the ring's capacity (more rows would overwrite each other in the ring
 * anyway): the worst case is one ring of rows, not MAX_PENDING_PUSHES × MAX_PUSH_ROWS (docs/15 §14
 * tab heap budget).
 */
export const MAX_PENDING_PUSHES = 100;

/** Rows a push claims (its tx_id column; the payload is validated when it is applied). */
function claimedRows(frame: PushFrame): number {
  const column = frame.data.tx_id;
  return Array.isArray(column) ? column.length : 0;
}
/** A fill starts this much before the last received time; duplicates are dropped by tx_id. */
export const FILL_OVERLAP_MS = 1_000;
/** REST polling period while the realtime connection is silent (docs/15 §10 "폴링 보충"). */
export const POLL_INTERVAL_MS = 10_000;

/**
 * The scatter filters the `xlog` topic cannot apply (docs/14 §5.2 has no such parameter), with the
 * meaning of the points API (docs/14 §4.5, server `points.sql`): `error` true keeps error points
 * only and false keeps the others; `minMs` and `maxMs` are inclusive bounds of the response time.
 */
export interface LivePointFilter {
  readonly error?: boolean;
  readonly minMs?: number;
  readonly maxMs?: number;
}

/** Whether a pushed point passes `filter` (status 2 = error, the server's point status). */
export function livePointAccepts(
  filter: LivePointFilter,
  elapsedMs: number,
  status: number,
): boolean {
  if (filter.error !== undefined && filter.error !== (status === STATUS_ERROR)) {
    return false;
  }
  if (filter.minMs !== undefined && !(elapsedMs >= filter.minMs)) {
    return false;
  }
  if (filter.maxMs !== undefined && !(elapsedMs <= filter.maxMs)) {
    return false;
  }
  return true;
}

function hasPointFilter(filter: LivePointFilter): boolean {
  return filter.error !== undefined || filter.minMs !== undefined || filter.maxMs !== undefined;
}

export interface LiveWindow {
  readonly from: number;
  readonly to: number;
}

export interface LiveLoad {
  /** The dictionary of this live session: new for every initial load, extended by pushes. */
  readonly dictionary: LocalDictionary;
  readonly meta: ResponseMeta;
  readonly stats: AppendStats;
  /** Range of the initial REST query. */
  readonly range: LiveWindow;
}

export interface LiveFill {
  readonly range: LiveWindow;
  readonly meta: ResponseMeta;
  readonly stats: AppendStats;
  /** resume, reconnect (WsManager onResync), or polling while realtime is silent (docs/15 §10). */
  readonly reason: 'resume' | 'resync' | 'poll';
}

export interface LiveStats {
  /** Batches received (valid or not). */
  pushes: number;
  /** Batches dropped as invalid (docs/15 §10: counted, never partly applied). */
  invalidPushes: number;
  /**
   * Batches dropped because the initial load took longer than MAX_PENDING_PUSHES batches, or their
   * rows exceeded the ring's capacity.
   */
  droppedPending: number;
  /** Points received in batches. */
  received: number;
  /** Points appended to the buffer (batches and fills). */
  appended: number;
  /** Points dropped as duplicates (tx_id already seen). */
  duplicates: number;
  /** Points dropped as late (D-15). */
  late: number;
  /** Pushed points outside the error / response time filters (`livePointAccepts`). */
  filtered: number;
  /** Points the server dropped under back-pressure (push `dropped`, docs/14 §5.3). */
  serverDropped: number;
  /** REST fills completed. */
  fills: number;
  /** REST polls started while realtime was silent (docs/15 §10). */
  polls: number;
}

export interface LiveControllerOptions {
  readonly buffer: XLogBuffer;
  /** REST query of the points of [from, to) (docs/14 §4.5) with the screen's filters. */
  readonly fetchPoints: (range: LiveWindow, signal: AbortSignal) => Promise<XLogPoints>;
  /** The window to draw: once at start and once per second while not paused. */
  readonly onWindow: (window: LiveWindow) => void;
  /** The initial load finished (a new dictionary). */
  readonly onLoad: (load: LiveLoad) => void;
  /** The initial load failed; the controller stays idle until restarted. */
  readonly onError: (error: unknown) => void;
  readonly onFill?: (fill: LiveFill) => void;
  /** A fill failed (the live view keeps running; the gap stays until the next fill). */
  readonly onFillError?: (error: unknown) => void;
  /**
   * While paused (the window stands still, onWindow is not called) the buffer changed since the
   * last tick: called at most once per tick, so the page can show the new point count.
   */
  readonly onData?: (size: number) => void;
  /** Error and response time filters applied to the pushed batches (the topic has none). */
  readonly pointFilter?: LivePointFilter;
  readonly now?: () => number;
}

function acceptsRow(filter: LivePointFilter, columns: XLogPointColumns, row: number): boolean {
  return livePointAccepts(
    filter,
    columns.elapsed_ms[row] ?? Number.NaN,
    columns.status[row] ?? Number.NaN,
  );
}

function ceilSecond(ms: number): number {
  return Math.ceil(ms / 1000) * 1000;
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export class XLogLiveController {
  readonly stats: LiveStats = {
    pushes: 0,
    invalidPushes: 0,
    droppedPending: 0,
    received: 0,
    appended: 0,
    duplicates: 0,
    late: 0,
    filtered: 0,
    serverDropped: 0,
    fills: 0,
    polls: 0,
  };

  private readonly options: LiveControllerOptions;
  private readonly now: () => number;
  private readonly seen: BoundedIdSet;
  private readonly pointFilter: LivePointFilter | null;
  private dictionary: LocalDictionary | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  /** Buffer version at the last paused tick (onData once per change). */
  private notifiedVersion = -1;
  private paused = false;
  private pausedFrom: number | null = null;
  private windowEnd = 0;
  /** Server time of the newest batch applied, or the end of the last REST range. */
  private lastReceivedTs: number | null = null;
  private pending: PushFrame[] = [];
  /** Rows of the pending batches (claimedRows). */
  private pendingRows = 0;
  private loading: AbortController | null = null;
  private filling: AbortController | null = null;
  /** A fill requested while another one runs: merged into one range, run afterwards. */
  private queuedFill: { range: LiveWindow; reason: LiveFill['reason'] } | null = null;
  private stopped = false;

  constructor(options: LiveControllerOptions) {
    this.options = options;
    this.now = options.now ?? (() => Date.now());
    this.seen = new BoundedIdSet(options.buffer.capacity);
    this.pointFilter =
      options.pointFilter !== undefined && hasPointFilter(options.pointFilter)
        ? options.pointFilter
        : null;
  }

  /** True while the REST polling of a silent realtime connection runs. */
  get isPolling(): boolean {
    return this.pollTimer !== null;
  }

  /** Current window. */
  get window(): LiveWindow {
    return { from: this.windowEnd - LIVE_WINDOW_MS, to: this.windowEnd };
  }

  get isPaused(): boolean {
    return this.paused;
  }

  /** The dictionary of this session, null until the initial load finished. */
  get currentDictionary(): LocalDictionary | null {
    return this.dictionary;
  }

  /** Starts the window and the initial REST load over [now - 5 min, now]. */
  start(): void {
    if (this.stopped || this.loading !== null || this.timer !== null) {
      return;
    }
    const now = this.now();
    this.windowEnd = ceilSecond(now);
    this.options.onWindow(this.window);
    const range = { from: now - LIVE_WINDOW_MS, to: now };
    const controller = new AbortController();
    this.loading = controller;
    this.timer = setInterval(() => {
      this.tick();
    }, LIVE_TICK_MS);
    this.options.fetchPoints(range, controller.signal).then(
      (points) => {
        if (this.stopped || this.loading !== controller) {
          return;
        }
        this.loading = null;
        this.applyInitial(points, range);
      },
      (error: unknown) => {
        if (this.stopped || this.loading !== controller || isAbort(error)) {
          return;
        }
        this.loading = null;
        this.options.onError(error);
      },
    );
  }

  /** A batch of the `xlog` topic (WsManager onData). */
  onPush(frame: PushFrame): void {
    if (this.stopped || frame.topic !== 'xlog') {
      return;
    }
    if (this.dictionary === null) {
      this.pending.push(frame);
      this.pendingRows += claimedRows(frame);
      while (
        this.pending.length > MAX_PENDING_PUSHES ||
        (this.pendingRows > this.options.buffer.capacity && this.pending.length > 0)
      ) {
        const dropped = this.pending.shift();
        this.pendingRows -= dropped === undefined ? 0 : claimedRows(dropped);
        this.stats.droppedPending += 1;
      }
      return;
    }
    this.applyPush(frame);
  }

  /** WsManager onResync after a reconnect or a hidden page: fill [lastTs, now] through REST. */
  resync(lastTs: number | null): void {
    const now = this.now();
    const since = lastTs ?? this.lastReceivedTs ?? now - LIVE_WINDOW_MS;
    this.fill({ from: Math.max(since, now - LIVE_WINDOW_MS), to: now }, 'resync');
  }

  /** Stops the window; data keeps arriving (docs/15 §7.5). */
  pause(): void {
    if (this.paused || this.stopped) {
      return;
    }
    this.paused = true;
    this.pausedFrom = this.windowEnd - LIVE_WINDOW_MS;
    this.notifiedVersion = this.options.buffer.version;
  }

  /** Moves the window to now and fills [last received, now] through REST. */
  resume(): void {
    if (!this.paused || this.stopped) {
      return;
    }
    this.paused = false;
    this.pausedFrom = null;
    this.tick();
    const now = this.now();
    const since = this.lastReceivedTs ?? now - LIVE_WINDOW_MS;
    this.fill({ from: Math.max(since, now - LIVE_WINDOW_MS), to: now }, 'resume');
  }

  /**
   * Polling fallback (docs/15 §10): while on, [last received, now] is filled through REST at once
   * and every POLL_INTERVAL_MS, de-duplicated by tx_id like every fill; off stops it. useXLogLive
   * turns it on after 30 s without a realtime message (usePollingFallback) and off when one arrives.
   */
  setPolling(on: boolean): void {
    if (this.stopped || on === (this.pollTimer !== null)) {
      return;
    }
    if (!on) {
      if (this.pollTimer !== null) {
        clearInterval(this.pollTimer);
      }
      this.pollTimer = null;
      return;
    }
    this.pollTimer = setInterval(() => {
      this.poll();
    }, POLL_INTERVAL_MS);
    this.poll();
  }

  /** Stops everything; pending requests are aborted. */
  stop(): void {
    this.stopped = true;
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.loading?.abort();
    this.loading = null;
    this.filling?.abort();
    this.filling = null;
    this.queuedFill = null;
    this.pending = [];
    this.pendingRows = 0;
  }

  private applyInitial(points: XLogPoints, range: LiveWindow): void {
    const dictionary = new LocalDictionary();
    dictionary.nameServices(points.data.dictionary.service);
    dictionary.nameInstances(points.data.dictionary.instance);
    this.options.buffer.clear();
    this.seen.clear();
    const stats = appendPointColumns(this.options.buffer, dictionary, points.data, {
      accept: (txId) => this.seen.add(txId),
    });
    this.stats.appended += stats.appended;
    this.stats.duplicates += stats.skipped;
    this.dictionary = dictionary;
    this.lastReceivedTs = Math.max(this.lastReceivedTs ?? 0, range.to);
    this.options.onLoad({ dictionary, meta: points.meta, stats, range });
    const queued = this.pending;
    this.pending = [];
    this.pendingRows = 0;
    for (const frame of queued) {
      this.applyPush(frame);
    }
  }

  private applyPush(frame: PushFrame): void {
    const dictionary = this.dictionary;
    if (dictionary === null) {
      return;
    }
    this.stats.pushes += 1;
    if (frame.dropped !== undefined) {
      this.stats.serverDropped += frame.dropped;
    }
    const columns = parseXLogPush(frame.data);
    const t = Date.parse(frame.t);
    if (columns === null || !Number.isFinite(t)) {
      this.stats.invalidPushes += 1;
      return;
    }
    dictionary.nameServices(frame.dict?.service);
    dictionary.nameInstances(frame.dict?.instance);
    const lateBefore = t - LATE_MS;
    const filter = this.pointFilter;
    let late = 0;
    let filtered = 0;
    let duplicates = 0;
    const stats = appendPointColumns(this.options.buffer, dictionary, columns, {
      accept: (txId, ts, row) => {
        if (!(ts >= lateBefore)) {
          late += 1;
          return false;
        }
        if (filter !== null && !acceptsRow(filter, columns, row)) {
          filtered += 1;
          return false;
        }
        if (!this.seen.add(txId)) {
          duplicates += 1;
          return false;
        }
        return true;
      },
    });
    this.stats.received += stats.rows;
    this.stats.appended += stats.appended;
    this.stats.late += late;
    this.stats.filtered += filtered;
    this.stats.duplicates += duplicates;
    this.lastReceivedTs = Math.max(this.lastReceivedTs ?? 0, t);
  }

  private tick(): void {
    if (this.stopped) {
      return;
    }
    const now = this.now();
    if (this.paused) {
      // The window stands still; keep what it shows, drop only what is older than both it and
      // the live window (the ring's capacity bounds the rest).
      const buffer = this.options.buffer;
      buffer.evictBefore(Math.min(now - LIVE_WINDOW_MS, this.pausedFrom ?? now));
      // Batches keep arriving: the page learns the new point count once per tick.
      if (buffer.version !== this.notifiedVersion) {
        this.notifiedVersion = buffer.version;
        this.options.onData?.(buffer.size);
      }
      return;
    }
    this.windowEnd = ceilSecond(now);
    this.options.buffer.evictBefore(this.windowEnd - LIVE_WINDOW_MS);
    this.options.onWindow(this.window);
  }

  private poll(): void {
    if (this.stopped || this.dictionary === null) {
      // Before the initial load finished, that load covers the gap.
      return;
    }
    const now = this.now();
    const since = this.lastReceivedTs ?? now - LIVE_WINDOW_MS;
    this.stats.polls += 1;
    this.fill({ from: Math.max(since, now - LIVE_WINDOW_MS), to: now }, 'poll');
  }

  private fill(range: LiveWindow, reason: LiveFill['reason']): void {
    if (this.stopped || this.dictionary === null || !(range.to > range.from)) {
      // Before the initial load finished, that load covers the gap.
      return;
    }
    const padded = { from: Math.max(0, range.from - FILL_OVERLAP_MS), to: range.to };
    if (this.filling !== null) {
      const queued = this.queuedFill;
      this.queuedFill = {
        range:
          queued === null
            ? padded
            : {
                from: Math.min(queued.range.from, padded.from),
                to: Math.max(queued.range.to, padded.to),
              },
        reason,
      };
      return;
    }
    const controller = new AbortController();
    this.filling = controller;
    this.options.fetchPoints(padded, controller.signal).then(
      (points) => {
        if (this.stopped || this.filling !== controller) {
          return;
        }
        this.filling = null;
        this.applyFill(points, padded, reason);
        this.runQueuedFill();
      },
      (error: unknown) => {
        if (this.stopped || this.filling !== controller || isAbort(error)) {
          return;
        }
        this.filling = null;
        this.options.onFillError?.(error);
        this.runQueuedFill();
      },
    );
  }

  private runQueuedFill(): void {
    const queued = this.queuedFill;
    this.queuedFill = null;
    if (queued !== null) {
      this.fill(queued.range, queued.reason);
    }
  }

  private applyFill(points: XLogPoints, range: LiveWindow, reason: LiveFill['reason']): void {
    const dictionary = this.dictionary;
    if (dictionary === null) {
      return;
    }
    dictionary.nameServices(points.data.dictionary.service);
    dictionary.nameInstances(points.data.dictionary.instance);
    const stats = appendPointColumns(this.options.buffer, dictionary, points.data, {
      accept: (txId) => this.seen.add(txId),
    });
    this.stats.appended += stats.appended;
    this.stats.duplicates += stats.skipped;
    this.stats.fills += 1;
    this.lastReceivedTs = Math.max(this.lastReceivedTs ?? 0, range.to);
    this.options.onFill?.({ range, meta: points.meta, stats, reason });
  }
}
