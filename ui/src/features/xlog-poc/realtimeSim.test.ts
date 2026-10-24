// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from 'vitest';

import { XLogBuffer } from '../../xlog/XLogBuffer';

import { PointFactory, createDummyModel } from './dummyData';
import {
  DEFAULT_WINDOW_MS,
  MAX_CATCH_UP_MS,
  RealtimeSimulation,
  liveWindowEnd,
  type Scheduler,
} from './realtimeSim';

const START = 1_700_000_000_000;

function harness(options: { rate?: number; capacity?: number } = {}) {
  let now = START;
  let tick: (() => void) | null = null;
  const scheduler: Scheduler = {
    setInterval: (callback) => {
      tick = callback;
      return 1;
    },
    clearInterval: () => {
      tick = null;
    },
  };
  const buffer = new XLogBuffer(options.capacity ?? 200_000);
  const onWindow = vi.fn();
  const sim = new RealtimeSimulation({
    buffer,
    factory: new PointFactory(createDummyModel(1), 1),
    now: () => now,
    onWindow,
    ratePerSecond: options.rate ?? 500,
    scheduler,
  });
  const advance = (ms: number, step = 100): void => {
    for (let t = 0; t < ms; t += step) {
      now += step;
      tick?.();
    }
  };
  return {
    sim,
    buffer,
    onWindow,
    advance,
    now: () => now,
    ticking: () => tick !== null,
  };
}

describe('RealtimeSimulation (docs/15 §7.5)', () => {
  it('appends 500 points per second in 100 ms batches', () => {
    const h = harness();
    const append = vi.spyOn(h.buffer, 'append');
    h.sim.start();
    h.advance(1000);
    expect(h.buffer.size).toBe(500);
    expect(append).toHaveBeenCalledTimes(10);
    expect(append.mock.calls[0]?.[0]).toHaveLength(50);
    h.advance(9000);
    expect(h.buffer.size).toBe(5000);
    // Points carry the batch's time span, in order.
    for (let i = 1; i < h.buffer.size; i += 1) {
      expect(h.buffer.ts[i]).toBeGreaterThan(h.buffer.ts[i - 1] ?? 0);
    }
    expect(h.buffer.ts[h.buffer.size - 1]).toBe(h.now());
  });

  it('keeps fractional rates exact over time', () => {
    const h = harness({ rate: 7 });
    h.sim.start();
    h.advance(10_000);
    expect(h.buffer.size).toBe(70);
  });

  it('keeps a 5-minute window: older points are evicted', () => {
    const h = harness();
    h.sim.start();
    h.advance(DEFAULT_WINDOW_MS + 60_000, 1000);
    expect(h.buffer.evicted).toBeGreaterThan(0);
    const oldest = h.buffer.ts[h.buffer.physical(0)] ?? 0;
    expect(oldest).toBeGreaterThanOrEqual(h.now() - DEFAULT_WINDOW_MS);
    expect(h.buffer.size).toBeLessThanOrEqual(500 * (DEFAULT_WINDOW_MS / 1000) + 500);
    expect(h.buffer.overwritten).toBe(0);
  });

  it('moves the window once per second, one step ahead of the clock', () => {
    const h = harness();
    h.sim.start();
    expect(h.onWindow).toHaveBeenCalledTimes(1);
    expect(h.onWindow).toHaveBeenLastCalledWith(liveWindowEnd(START));
    h.advance(3000);
    expect(h.onWindow).toHaveBeenCalledTimes(4);
    expect(h.onWindow).toHaveBeenLastCalledWith(liveWindowEnd(h.now()));
    expect(liveWindowEnd(START)).toBeGreaterThan(START);
    expect(liveWindowEnd(START + 999)).toBe(liveWindowEnd(START));
  });

  it('pauses the window but keeps receiving data and keeps the paused range', () => {
    const h = harness();
    h.sim.start();
    h.advance(DEFAULT_WINDOW_MS, 1000);
    const viewFrom = h.now() - DEFAULT_WINDOW_MS;
    h.sim.setPaused(true, viewFrom);
    expect(h.sim.paused).toBe(true);
    const calls = h.onWindow.mock.calls.length;
    const size = h.buffer.size;
    h.advance(30_000, 1000);
    expect(h.onWindow.mock.calls.length).toBe(calls);
    expect(h.buffer.size).toBeGreaterThan(size);
    expect(h.buffer.ts[h.buffer.physical(0)]).toBeGreaterThanOrEqual(viewFrom - 1000);
    expect(h.buffer.ts[h.buffer.physical(0)]).toBeLessThan(viewFrom + 1000);
    h.sim.setPaused(false);
    expect(h.sim.paused).toBe(false);
    expect(h.onWindow).toHaveBeenLastCalledWith(liveWindowEnd(h.now()));
    h.advance(1000);
    expect(h.buffer.ts[h.buffer.physical(0)]).toBeGreaterThanOrEqual(h.now() - DEFAULT_WINDOW_MS);
  });

  it('back-fills at most MAX_CATCH_UP_MS after a stall (background tab)', () => {
    const h = harness();
    h.sim.start();
    h.advance(60_000, 60_000);
    expect(h.buffer.size).toBe((500 * MAX_CATCH_UP_MS) / 1000);
  });

  it('stops ticking on stop()', () => {
    const h = harness();
    h.sim.start();
    expect(h.sim.running).toBe(true);
    h.sim.start();
    h.sim.stop();
    expect(h.ticking()).toBe(false);
    expect(h.sim.running).toBe(false);
  });
});
