// SPDX-License-Identifier: Apache-2.0
//
// The collection outage seed (e2e/stack/seed/outage.ts) cuts until the API reports STALE, restores
// the path, waits for UP and records the API's outage only when it matches the cut.
import { describe, expect, it } from 'vitest';

import { runOutage, type OutageApi } from './outage';

const T0 = Date.parse('2030-01-01T00:00:00.000Z');

interface Fake {
  readonly api: OutageApi;
  readonly proxy: { freeze(): void; thaw(): void };
  readonly events: string[];
  now(): number;
  sleep(ms: number): Promise<void>;
  queries: { path: string; query: unknown }[];
}

/**
 * A fake stack: the instance turns STALE `staleAfterMs` after the freeze and UP `upAfterMs` after
 * the thaw; the xlog API answers `outages(freezeAt, thawAt)`.
 */
function fake(
  staleAfterMs: number,
  upAfterMs: number,
  outages: (freezeAt: number, staleAt: number, thawAt: number) => unknown[],
  initial = 'UP',
): Fake {
  let clock = T0;
  let freezeAt: number | null = null;
  let thawAt: number | null = null;
  const events: string[] = [];
  const queries: { path: string; query: unknown }[] = [];
  const state = (): string => {
    if (freezeAt === null) {
      return initial;
    }
    if (thawAt === null) {
      return clock - freezeAt >= staleAfterMs ? 'STALE' : 'UP';
    }
    return clock - thawAt >= upAfterMs ? 'UP' : 'STALE';
  };
  const api: OutageApi = {
    query(path: string, query?: unknown) {
      queries.push({ path, query });
      if (path.endsWith('/instances')) {
        return Promise.resolve({
          data: [
            { instance_id: 4, name: 'other', state: 'DOWN' },
            { instance_id: 7, name: 'e2e-shop-1', state: state() },
          ],
          meta: {},
        });
      }
      return Promise.resolve({
        data: {},
        meta: {
          outages: outages(freezeAt ?? 0, (freezeAt ?? 0) + staleAfterMs, thawAt ?? 0),
        },
      });
    },
  };
  return {
    api,
    proxy: {
      freeze() {
        freezeAt = clock;
        events.push(`freeze@${String(clock - T0)}`);
      },
      thaw() {
        thawAt = clock;
        events.push(`thaw@${String(clock - T0)}`);
      },
    },
    events,
    now: () => clock,
    sleep(ms) {
      clock += ms;
      return Promise.resolve();
    },
    queries,
  };
}

const matching = (freezeAt: number, staleAt: number, thawAt: number): unknown[] => [
  // A server outage is not this instance's (the API filters the instances by instance[]).
  { from: new Date(freezeAt - 30_000).toISOString(), to: null, reason: 'server_down' },
  {
    from: new Date(staleAt).toISOString(),
    to: new Date(thawAt + 1000).toISOString(),
    reason: 'session_lost',
  },
];

function options(f: Fake) {
  return {
    api: f.api,
    appId: 9,
    instanceName: 'e2e-shop-1',
    proxy: f.proxy,
    log: () => undefined,
    now: () => f.now(),
    sleep: (ms: number) => f.sleep(ms),
  };
}

describe('collection outage seed (WO-22, 05 §2.1-10)', () => {
  it('cuts until STALE, thaws, waits for UP and records the matching API outage', async () => {
    const f = fake(46_000, 3000, matching);
    const outage = await runOutage(options(f));
    expect(f.events).toEqual(['freeze@0', 'thaw@46000']);
    expect(outage).toEqual({
      app_id: 9,
      instance_id: 7,
      instance_name: 'e2e-shop-1',
      cut_at: '2030-01-01T00:00:00.000Z',
      stale_seen_at: '2030-01-01T00:00:46.000Z',
      thawed_at: '2030-01-01T00:00:46.000Z',
      up_seen_at: '2030-01-01T00:00:50.000Z',
      outage_from: '2030-01-01T00:00:46.000Z',
      outage_to: '2030-01-01T00:00:47.000Z',
      reason: 'session_lost',
    });
    // The outage is read for [cut - 1 min, UP + 61 s) at whole seconds.
    expect(f.queries.at(-1)).toEqual({
      path: '/apps/9/xlog',
      query: {
        from: '2029-12-31T23:59:00Z',
        to: '2030-01-01T00:01:51Z',
        kind: 'ALL',
        limit: 1,
        instance: ['7'],
      },
    });
  });

  it('refuses to cut an instance that is not live', async () => {
    const f = fake(1000, 1000, matching, 'DOWN');
    await expect(runOutage(options(f))).rejects.toThrow(/is DOWN before the cut/);
    expect(f.events).toEqual([]);
  });

  it('thaws and fails when the instance never becomes STALE', async () => {
    const f = fake(10 * 60_000, 1000, matching);
    await expect(runOutage({ ...options(f), staleTimeoutMs: 20_000 })).rejects.toThrow(
      /not STALE within 20 s \(state UP\)/,
    );
    expect(f.events).toEqual(['freeze@0', 'thaw@20000']);
  });

  it('fails when the API outage does not match the cut', async () => {
    const wrongReason = (_freezeAt: number, staleAt: number, thawAt: number): unknown[] => [
      {
        from: new Date(staleAt).toISOString(),
        to: new Date(thawAt).toISOString(),
        reason: 'queue_overflow',
      },
    ];
    await expect(runOutage(options(fake(20_000, 0, wrongReason)))).rejects.toThrow(
      /reason queue_overflow/,
    );
    const late = (_freezeAt: number, staleAt: number, thawAt: number): unknown[] => [
      {
        from: new Date(staleAt).toISOString(),
        to: new Date(thawAt + 60_000).toISOString(),
        reason: 'session_lost',
      },
    ];
    await expect(runOutage(options(fake(20_000, 0, late)))).rejects.toThrow(/to .* outside/);
    await expect(runOutage(options(fake(20_000, 0, () => [])))).rejects.toThrow(
      /lists 0 outages of instance 7/,
    );
  });
});
