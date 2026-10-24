// SPDX-License-Identifier: Apache-2.0
//
// Machine CPU load around each measurement run (all cores, as Windows' "% Processor Time" or
// top's load): from two snapshots of the per-core times in os.cpus(), so it works the same on
// Windows, Linux and macOS without spawning typeperf or reading /proc.
import { cpus } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';

export interface CpuSnapshot {
  /** Idle time summed over all cores, ms. */
  readonly idle: number;
  /** Total time summed over all cores, ms. */
  readonly total: number;
}

export interface CoreTimes {
  readonly user: number;
  readonly nice: number;
  readonly sys: number;
  readonly idle: number;
  readonly irq: number;
}

export function snapshotOf(cores: readonly { readonly times: CoreTimes }[]): CpuSnapshot {
  let idle = 0;
  let total = 0;
  for (const core of cores) {
    const t = core.times;
    idle += t.idle;
    total += t.user + t.nice + t.sys + t.idle + t.irq;
  }
  return { idle, total };
}

export function cpuSnapshot(): CpuSnapshot {
  return snapshotOf(cpus());
}

/** Busy percentage of all cores between two snapshots (0-100), or null without elapsed time. */
export function loadBetween(before: CpuSnapshot, after: CpuSnapshot): number | null {
  const total = after.total - before.total;
  if (!(total > 0)) {
    return null;
  }
  const busy = 1 - (after.idle - before.idle) / total;
  return Math.min(100, Math.max(0, busy * 100));
}

/** Machine CPU load over the next `ms` milliseconds. */
export async function sampleLoad(ms = 2000): Promise<number | null> {
  const before = cpuSnapshot();
  await sleep(ms);
  return loadBetween(before, cpuSnapshot());
}

export interface QuietResult {
  /** Load of the last sample, % (null when it could not be measured). */
  readonly load: number | null;
  /** Every sample taken, %. */
  readonly samples: readonly (number | null)[];
  readonly waitedMs: number;
  /** The last sample was at or below the limit. */
  readonly quiet: boolean;
}

/** Time and load sources of {@link waitForQuiet}; tests replace them with a virtual clock. */
export interface QuietClock {
  /** Current time, ms. */
  readonly now: () => number;
  /** Resolves after `ms` milliseconds. */
  readonly sleep: (ms: number) => Promise<unknown>;
  /** Machine CPU load over the next `ms` milliseconds, % (null when it cannot be measured). */
  readonly sample: (ms: number) => Promise<number | null>;
}

const SYSTEM_CLOCK: QuietClock = { now: () => Date.now(), sleep, sample: sampleLoad };

/**
 * Samples the machine load until it is at or below `maxLoad` % or `maxWaitMs` has passed. The
 * caller measures either way and records whether the machine was quiet.
 */
export async function waitForQuiet(
  options: {
    readonly maxLoad: number;
    readonly maxWaitMs: number;
    readonly sampleMs?: number;
    readonly pauseMs?: number;
  },
  clock: QuietClock = SYSTEM_CLOCK,
): Promise<QuietResult> {
  const sampleMs = options.sampleMs ?? 2000;
  const pauseMs = options.pauseMs ?? 3000;
  const started = clock.now();
  const samples: (number | null)[] = [];
  for (;;) {
    const load = await clock.sample(sampleMs);
    samples.push(load);
    const quiet = load !== null && load <= options.maxLoad;
    const waitedMs = clock.now() - started;
    if (quiet || waitedMs + pauseMs + sampleMs > options.maxWaitMs) {
      return { load, samples, waitedMs, quiet };
    }
    await clock.sleep(pauseMs);
  }
}
