// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { cpuSnapshot, loadBetween, snapshotOf, waitForQuiet, type QuietClock } from './cpuLoad';

const core = (user: number, sys: number, idle: number) => ({
  times: { user, nice: 0, sys, idle, irq: 0 },
});

describe('snapshotOf and loadBetween', () => {
  it('sums every core and reports the busy share of the elapsed time', () => {
    const before = snapshotOf([core(100, 50, 850), core(0, 0, 1000)]);
    expect(before).toEqual({ idle: 1850, total: 2000 });
    // 2 cores × 1,000 ms; core 1 was busy 300 ms (user) + 100 ms (sys): 400 / 2,000 = 20 %.
    const after = snapshotOf([core(400, 150, 1450), core(0, 0, 2000)]);
    expect(loadBetween(before, after)).toBeCloseTo(20, 10);
  });

  it('returns null without elapsed time and clamps to 0-100', () => {
    const s = { idle: 10, total: 100 };
    expect(loadBetween(s, s)).toBeNull();
    expect(loadBetween(s, { idle: 30, total: 110 })).toBe(0);
    expect(loadBetween(s, { idle: 0, total: 110 })).toBe(100);
  });

  it('reads the machine counters', () => {
    const s = cpuSnapshot();
    expect(s.total).toBeGreaterThan(0);
    expect(s.idle).toBeGreaterThanOrEqual(0);
    expect(s.idle).toBeLessThanOrEqual(s.total);
  });
});

/**
 * A virtual clock for waitForQuiet: sampling and sleeping advance it by exactly the requested time
 * and samples return the scripted loads (the last one repeats). The tests then depend neither on
 * the machine load nor on real timers, which overran on a busy build host (WO-01 report B-1).
 */
function virtualClock(loads: readonly (number | null)[]) {
  let now = 1_000_000;
  const sampled: number[] = [];
  const slept: number[] = [];
  const clock: QuietClock = {
    now: () => now,
    sleep: (ms) => {
      slept.push(ms);
      now += ms;
      return Promise.resolve();
    },
    sample: (ms) => {
      const load = loads[Math.min(sampled.length, loads.length - 1)] ?? null;
      sampled.push(ms);
      now += ms;
      return Promise.resolve(load);
    },
  };
  return { clock, sampled, slept };
}

describe('waitForQuiet', () => {
  it('returns after one sample when the machine is quiet enough', async () => {
    const { clock, sampled, slept } = virtualClock([35]);
    const result = await waitForQuiet({ maxLoad: 50, maxWaitMs: 1000, sampleMs: 50 }, clock);
    expect(result.quiet).toBe(true);
    expect(result.samples).toHaveLength(1);
    expect(result.load).toBe(35);
    expect(result.waitedMs).toBe(50);
    expect(sampled).toEqual([50]);
    expect(slept).toEqual([]);
  });

  it('gives up after maxWaitMs and reports a busy machine', async () => {
    // A limit below any real load (an idle machine reads 0 %): never quiet.
    const { clock, sampled, slept } = virtualClock([0]);
    const result = await waitForQuiet(
      { maxLoad: -1, maxWaitMs: 300, sampleMs: 50, pauseMs: 50 },
      clock,
    );
    expect(result.quiet).toBe(false);
    expect(result.samples.length).toBeGreaterThanOrEqual(2);
    expect(result.waitedMs).toBeLessThanOrEqual(300);
    // Samples end at 50, 150 and 250 ms; one more pause and sample (350 ms) would pass maxWaitMs.
    expect(result.samples).toEqual([0, 0, 0]);
    expect(result.waitedMs).toBe(250);
    expect(sampled).toEqual([50, 50, 50]);
    expect(slept).toEqual([50, 50]);
  });

  it('keeps sampling with a pause in between until the load drops (defaults: 2 s, 3 s)', async () => {
    // An unmeasurable sample (null) is not quiet either.
    const { clock, sampled, slept } = virtualClock([null, 90, 20]);
    const result = await waitForQuiet({ maxLoad: 50, maxWaitMs: 20_000 }, clock);
    expect(result).toEqual({ load: 20, samples: [null, 90, 20], waitedMs: 12_000, quiet: true });
    expect(sampled).toEqual([2000, 2000, 2000]);
    expect(slept).toEqual([3000, 3000]);
  });
});
