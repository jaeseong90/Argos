// SPDX-License-Identifier: Apache-2.0
//
// Realtime simulation for the canvas PoC (docs/15 §7.5): points arrive in 100 ms batches
// (500 points/s by default), the buffer drops points older than the 5-minute window, and the
// visible window moves once per second. Pausing stops the window only; data keeps arriving and
// nothing inside the paused window is evicted (the ring capacity still bounds memory).
import type { XLogBuffer, XLogPoint } from '../../xlog/XLogBuffer';

import type { PointFactory } from './dummyData';

export const DEFAULT_RATE_PER_SECOND = 500;
export const DEFAULT_WINDOW_MS = 5 * 60_000;
export const DEFAULT_BATCH_INTERVAL_MS = 100;
export const DEFAULT_WINDOW_STEP_MS = 1000;
/** After a stall (background tab) at most this much time is back-filled per batch. */
export const MAX_CATCH_UP_MS = 2000;

export interface Scheduler {
  setInterval(callback: () => void, ms: number): number;
  clearInterval(handle: number): void;
}

export interface RealtimeOptions {
  readonly buffer: XLogBuffer;
  readonly factory: PointFactory;
  readonly now: () => number;
  /** Called when the window moves; `end` is the right edge of the visible range. */
  readonly onWindow: (end: number) => void;
  readonly ratePerSecond?: number;
  readonly windowMs?: number;
  readonly batchIntervalMs?: number;
  readonly windowStepMs?: number;
  readonly scheduler?: Scheduler;
}

const browserScheduler: Scheduler = {
  setInterval: (callback, ms) => window.setInterval(callback, ms),
  clearInterval: (handle) => {
    window.clearInterval(handle);
  },
};

/**
 * Right edge of the live window for `now`: one step ahead of the clock, aligned to the step, so
 * points arriving before the next window move are already inside the range.
 */
export function liveWindowEnd(now: number, stepMs: number = DEFAULT_WINDOW_STEP_MS): number {
  return Math.floor(now / stepMs) * stepMs + stepMs;
}

export class RealtimeSimulation {
  private readonly options: RealtimeOptions;
  private readonly rate: number;
  private readonly windowMs: number;
  private readonly batchMs: number;
  private readonly stepMs: number;
  private readonly scheduler: Scheduler;
  private handle: number | null = null;
  private last = 0;
  /** Simulated time and points emitted since start: n = floor(rate × time) - emitted, no drift. */
  private simulatedMs = 0;
  private emitted = 0;
  private windowEnd = 0;
  private pausedFrom: number | null = null;

  constructor(options: RealtimeOptions) {
    this.options = options;
    this.rate = Math.max(0, options.ratePerSecond ?? DEFAULT_RATE_PER_SECOND);
    this.windowMs = Math.max(1000, options.windowMs ?? DEFAULT_WINDOW_MS);
    this.batchMs = Math.max(10, options.batchIntervalMs ?? DEFAULT_BATCH_INTERVAL_MS);
    this.stepMs = Math.max(this.batchMs, options.windowStepMs ?? DEFAULT_WINDOW_STEP_MS);
    this.scheduler = options.scheduler ?? browserScheduler;
  }

  get running(): boolean {
    return this.handle !== null;
  }

  get paused(): boolean {
    return this.pausedFrom !== null;
  }

  start(): void {
    if (this.handle !== null) {
      return;
    }
    this.last = this.options.now();
    this.simulatedMs = 0;
    this.emitted = 0;
    this.windowEnd = liveWindowEnd(this.last, this.stepMs);
    this.options.onWindow(this.windowEnd);
    this.handle = this.scheduler.setInterval(() => {
      this.tick();
    }, this.batchMs);
  }

  stop(): void {
    if (this.handle !== null) {
      this.scheduler.clearInterval(this.handle);
      this.handle = null;
    }
  }

  /**
   * Stops (with `from` = the visible window start, which is protected from eviction) or resumes
   * moving the window.
   */
  setPaused(paused: boolean, from?: number): void {
    if (paused) {
      this.pausedFrom = from ?? this.windowEnd - this.windowMs;
      return;
    }
    this.pausedFrom = null;
    this.windowEnd = liveWindowEnd(this.options.now(), this.stepMs);
    this.options.onWindow(this.windowEnd);
  }

  /** One 100 ms batch. Public for tests. */
  tick(): void {
    const now = this.options.now();
    const dt = Math.min(MAX_CATCH_UP_MS, Math.max(0, now - this.last));
    this.last = now;
    this.simulatedMs += dt;
    const target = Math.floor((this.rate * this.simulatedMs) / 1000 + 1e-9);
    const n = target - this.emitted;
    this.emitted = target;
    if (n > 0) {
      const batch: XLogPoint[] = [];
      for (let i = 0; i < n; i += 1) {
        batch.push(this.options.factory.next(now - dt + (dt * (i + 1)) / n));
      }
      this.options.buffer.append(batch);
    }
    const cutoff =
      this.pausedFrom === null
        ? now - this.windowMs
        : Math.min(now - this.windowMs, this.pausedFrom);
    this.options.buffer.evictBefore(cutoff);
    if (this.pausedFrom === null) {
      const end = liveWindowEnd(now, this.stepMs);
      if (end !== this.windowEnd) {
        this.windowEnd = end;
        this.options.onWindow(end);
      }
    }
  }
}
