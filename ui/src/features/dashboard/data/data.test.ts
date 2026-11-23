// SPDX-License-Identifier: Apache-2.0
//
// Pure logic of the dashboard screen (docs/15 §9.2, §10, docs/14 §4.4, §5.2): the URL state to at
// most two requests (tileRef vs baseline), the response to tiles and series with null kept apart
// from 0 (T-UI-007), outages and markers on all nine panels (T-ACC-18 display), the counter push
// merge and invalid payloads (docs/15 §10), the XLog link of a chart point (05 §2.0-5) and the
// en/ko dashboard catalogs (T-UI-006).
import { describe, expect, it } from 'vitest';

import { dashboardQuery } from '../../../api/dashboard';
import { untrusted } from '../../../api/untrusted';
import { clickMenuRange } from '../../../charts/chartModel';
import enDashboard from '../../../locales/en/dashboard.json';
import koDashboard from '../../../locales/ko/dashboard.json';
import { parseUrl, XLogQ, type Filter } from '../../../state/urlState';
import { dashboardPanels, PANEL_IDS, seriesSummary } from '../charts/panels';
import {
  applyCounterPush,
  applyCounterPushes,
  parseCounterPush,
  type CounterPush,
} from '../live/counterPush';
import { dashboardBody, dashboardMeta, XSS_EVENT_TITLE, XSS_POOL } from '../testing/fixtures';
import { rawValue, tileSpecs } from '../tiles/DashboardTiles';

import { xlogAtRange } from './links';
import { dashboardModel } from './model';
import { dashboardPlan, dashboardQueryRange, instanceFilter, tilesCompatible } from './plan';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };

const MIN = 60_000;
const FROM = Date.parse('2026-11-20T03:00:00.000Z');
const RANGE = { from: FROM, to: FROM + 10 * MIN };

function filter(field: string, op: Filter['op'], value: string): Filter {
  return { field, op, value: untrusted(value) };
}

function model(options: Parameters<typeof dashboardBody>[0] = { from: FROM }) {
  return dashboardModel(dashboardBody(options), dashboardMeta(options.from));
}

describe('request plan (docs/14 dashboard_get, tileRef vs baseline)', () => {
  const base = { appId: 3, range: RANGE, kind: 'HTTP' as const, instances: [], excluded: [] };

  it('T-UI-007 one request when the tile basis matches the baseline, two otherwise', () => {
    const cases = [
      { baseline: 'none', tileRef: 'prev', main: null, tiles: undefined },
      { baseline: '1d', tileRef: 'prev', main: '1d', tiles: undefined },
      { baseline: '7d', tileRef: '7d', main: '7d', tiles: undefined },
      { baseline: '7d', tileRef: 'prev', main: '7d', tiles: null },
      { baseline: 'none', tileRef: '7d', main: null, tiles: '7d' },
      { baseline: '1d', tileRef: '7d', main: '1d', tiles: '7d' },
    ] as const;
    for (const c of cases) {
      const plan = dashboardPlan({ ...base, baseline: c.baseline, tileRef: c.tileRef });
      expect(plan.main.baseline).toBe(c.main);
      expect(tilesCompatible(c.baseline, c.tileRef)).toBe(c.tiles === undefined);
      if (c.tiles === undefined) {
        expect(plan.tiles).toBeNull();
      } else {
        expect(plan.tiles?.baseline).toBe(c.tiles);
        expect({ ...plan.tiles, baseline: null }).toEqual({ ...plan.main, baseline: null });
      }
    }
  });

  it('writes absolute ISO bounds, kind only when not HTTP, baseline, instances and exclusions', () => {
    const plan = dashboardPlan({
      ...base,
      kind: 'BATCH',
      instances: [12, 13],
      excluded: [15],
      baseline: '1d',
      tileRef: 'prev',
    });
    expect(dashboardQuery(plan.main)).toEqual({
      from: '2026-11-20T03:00:00.000Z',
      to: '2026-11-20T03:10:00.000Z',
      kind: 'BATCH',
      baseline: '1d',
      instance: [12, 13],
      exclude_instance: [15],
    });
    expect(
      dashboardQuery({ ...plan.main, kind: 'HTTP', baseline: null, instances: [], excluded: [] }),
    ).toEqual({
      from: '2026-11-20T03:00:00.000Z',
      to: '2026-11-20T03:10:00.000Z',
    });
    // the tiles' own request asks the same instances and exclusions
    const split = dashboardPlan({ ...base, excluded: [15], baseline: '7d', tileRef: 'prev' });
    expect(split.tiles?.excluded).toEqual([15]);
  });

  it('f.instance=<id> becomes instance[], f.instance=!<id> exclude_instance[] (C-212 (1)); other fields, ids past 100 and an excluded selected id are not applied', () => {
    const many = Array.from({ length: 102 }, (_, i) => filter('instance', 'eq', String(i + 1)));
    const result = instanceFilter([
      filter('instance', 'eq', '13'),
      filter('instance', 'ne', '15'),
      filter('instance', 'eq', '12'),
      filter('instance', 'eq', '12'),
      filter('instance', 'ne', '14'),
      filter('instance', 'ne', '14'),
      filter('instance', 'ne', '13'),
      filter('service', 'eq', '17'),
      filter('instance', 'eq', 'web-01'),
      filter('instance', 'ne', 'web-02'),
    ]);
    expect(result.ids).toEqual([12, 13]);
    expect(result.excluded).toEqual([14, 15]);
    expect(result.unapplied.map((f) => `${f.field}:${f.op}:${f.value}`)).toEqual([
      'instance:ne:13',
      'service:eq:17',
      'instance:eq:web-01',
      'instance:ne:web-02',
    ]);
    const capped = instanceFilter(many);
    expect(capped.ids).toHaveLength(100);
    expect(capped.unapplied).toHaveLength(2);
    const manyExcluded = instanceFilter(
      Array.from({ length: 101 }, (_, i) => filter('instance', 'ne', String(i + 1))),
    );
    expect(manyExcluded.excluded).toHaveLength(100);
    expect(manyExcluded.unapplied.map((f) => f.value)).toEqual(['101']);
    expect(instanceFilter([filter('instance', 'ne', '7')])).toEqual({
      ids: [],
      excluded: [7],
      unapplied: [],
    });
  });

  it('resolves a relative range on the 60-second clock (5 s while polling); absolute as is', () => {
    const now = FROM + 125_000;
    const relative = { kind: 'relative', range: '15m' } as const;
    expect(dashboardQueryRange(relative, now, 'UTC', 60_000)).toEqual({
      from: FROM + 120_000 - 15 * MIN,
      to: FROM + 120_000,
    });
    expect(dashboardQueryRange(relative, now, 'UTC', 5_000).to).toBe(FROM + 125_000);
    expect(dashboardQueryRange({ kind: 'absolute', ...RANGE }, now, 'UTC', 60_000)).toEqual(RANGE);
  });
});

describe('response model (docs/14 §4.4, T-UI-007)', () => {
  it('T-UI-007 maps tiles and series; null stays null and 0 stays 0', () => {
    const m = model();
    expect(m.ts).toHaveLength(10);
    expect(m.stepMs).toBe(MIN);
    expect(m.tiles).toEqual({
      tps: 12.5,
      avg_ms: 80,
      p95_ms: 300,
      p99_ms: null,
      error_rate: 0,
      active: 7,
    });
    expect(m.series.tps.slice(0, 4)).toEqual([10, null, 0, 13]);
    expect(m.series.active.every((v) => v === null)).toBe(true);
    expect(m.delta).toEqual({
      basis: 'prev',
      tps: 0.1,
      avg_ms: 0.08,
      p95_ms: -0.05,
      p99_ms: null,
      error_rate: 0.002,
      active: 0.4,
    });
    // system CPU (C-212 (1)): the gap stays null, never 0
    expect(m.series.system_cpu.slice(0, 3)).toEqual([0.4, null, 0.4]);
    const tiles = tileSpecs(m);
    expect(tiles.map((tile) => [tile.id, rawValue(tile.value)])).toEqual([
      ['tps', '12.5'],
      ['avg', '80'],
      ['p95', '300'],
      ['p99', 'null'],
      ['error-rate', '0'],
      ['active', '7'],
    ]);
    // TPS, average, p95, p99 and active are relative changes, the error rate an absolute
    // difference (docs/14, ADR-0010 C-212 (1)).
    expect(tiles.map((tile) => tile.delta?.mode ?? null)).toEqual([
      'relative',
      'relative',
      'relative',
      'relative',
      'absolute',
      'relative',
    ]);
    expect(tiles.map((tile) => tile.delta?.value ?? null)).toEqual([
      0.1,
      0.08,
      -0.05,
      null,
      0.002,
      0.4,
    ]);
  });

  it('takes tiles and delta from the tiles request when the basis needs one', () => {
    const main = dashboardBody({ from: FROM, baseline: '1d' });
    const tiles = dashboardBody({ from: FROM, baseline: '7d' });
    const m = dashboardModel(main, dashboardMeta(FROM), tiles);
    expect(m.delta.basis).toBe('7d');
    expect(m.delta.tps).toBe(0.25);
    expect(m.baseline?.offset).toBe('1d');
  });

  it('aligns the baseline on the buckets and draws it dashed on the transaction panels only', () => {
    const m = model({ from: FROM, baseline: '7d' });
    expect(m.baseline?.values.p95).toHaveLength(10);
    const panels = dashboardPanels(m, (key) => key);
    expect(panels.map((panel) => panel.id)).toEqual([...PANEL_IDS]);
    expect(panels.map((panel) => panel.baseline !== null)).toEqual([
      true,
      true,
      true,
      false,
      false,
      false,
      false,
      false,
      false,
    ]);
    expect(Object.keys(panels[1]?.baseline?.values ?? {})).toEqual(['avg', 'p95', 'p99']);
  });

  it('T-ACC-18 outages become shading input of every panel; events become markers', () => {
    const m = model();
    expect(m.outages).toEqual([{ from: FROM + 3 * MIN, to: FROM + 4 * MIN, reason: 'down' }]);
    expect(m.markers.map((marker) => [marker.id, marker.type, marker.title])).toEqual([
      ['ev-deploy', 'deploy', XSS_EVENT_TITLE],
      ['ev-alert', 'alert', 'p95 above 1 s'],
    ]);
    expect(dashboardPanels(m, (key) => key)).toHaveLength(9);
  });

  it('series per pool with positional keys, the collected name only as the label', () => {
    const panels = dashboardPanels(model(), (key) => key);
    const pool = panels.find((panel) => panel.id === 'pool');
    expect(pool?.series.map((s) => [s.key, s.label])).toEqual([
      ['pool-0', 'HikariPool'],
      ['pool-1', XSS_POOL],
    ]);
  });

  it('notes what a panel cannot show: active without samples, no pools, no error source', () => {
    const body = dashboardBody({ from: FROM, extErrors: false });
    body.series.pool_usage_ratio = {};
    const panels = dashboardPanels(dashboardModel(body, dashboardMeta(FROM)), (key) => key);
    const notes = Object.fromEntries(panels.map((panel) => [panel.id, panel.notes]));
    expect(notes).toMatchObject({
      active: ['activeNotMeasured'],
      cpu: [],
      pool: ['noPools'],
      ext: ['extErrorsNotMeasured'],
      tps: [],
    });
    expect(panels.find((panel) => panel.id === 'ext')?.series).toHaveLength(1);
    const measured = dashboardPanels(model({ from: FROM, active: true }), (key) => key);
    expect(measured.find((panel) => panel.id === 'active')?.notes).toEqual([]);
  });

  it('draws process and system CPU on the CPU panel (C-212 (1)), gaps kept as null', () => {
    const cpu = dashboardPanels(model(), (key) => key).find((panel) => panel.id === 'cpu');
    expect(cpu?.series.map((s) => [s.key, s.label, s.unit])).toEqual([
      ['cpu', 'cpu', 'ratio'],
      ['system_cpu', 'systemCpu', 'ratio'],
    ]);
    expect(cpu?.series[1]?.values.slice(0, 3)).toEqual([0.4, null, 0.4]);
    expect(cpu?.notes).toEqual([]);
    // the hidden summary the E2E oracle compares with the API series (gaps not counted)
    expect(JSON.parse(seriesSummary(cpu?.series ?? []))).toEqual({
      cpu: { n: 10, sum: 2.5 },
      system_cpu: { n: 9, sum: 3.6 },
    });
  });

  it('counts the transactions of the range (0 = the empty state)', () => {
    expect(model().count).toBeGreaterThan(0);
    expect(model({ from: FROM, count: 0 }).count).toBe(0);
  });
});

describe('counter push (docs/14 §5.2, docs/15 §10)', () => {
  function push(t: number, data: Record<string, unknown>): CounterPush {
    const parsed = parseCounterPush(new Date(t).toISOString(), data);
    if (parsed === null) {
      throw new Error('invalid push');
    }
    return parsed;
  }

  it('ignores a payload with a wrong type or an invalid time; tolerates unknown and missing fields', () => {
    expect(parseCounterPush('2026-11-20T03:10:00.000Z', { tps: 'x' })).toBeNull();
    expect(
      parseCounterPush('2026-11-20T03:10:00.000Z', { per_instance: [{ active: 1 }] }),
    ).toBeNull();
    expect(parseCounterPush('not a time', { tps: 1 })).toBeNull();
    const tolerated = parseCounterPush('2026-11-20T03:10:00.000Z', { tps: 3, future: 'x' });
    expect(tolerated?.tps).toBe(3);
    expect(tolerated?.p95_ms).toBeUndefined();
  });

  it('updates tiles, the bucket that holds t and per-instance active/state', () => {
    const m = model();
    const last = FROM + 9 * MIN;
    const next = applyCounterPush(
      m,
      push(last + 10_000, {
        tps: 42,
        p95_ms: null,
        active: 9,
        per_instance: [{ instance_id: 13, active: 2, state: 'UP' }],
      }),
    );
    expect(next.tiles.tps).toBe(42);
    expect(next.tiles.p95_ms).toBeNull();
    expect(next.tiles.avg_ms).toBe(80);
    expect(next.tiles.active).toBe(9);
    expect(next.ts).toEqual(m.ts);
    expect(next.series.tps.at(-1)).toBe(42);
    expect(next.series.p95.at(-1)).toBeNull();
    expect(next.series.avg.at(-1)).toBe(m.series.avg.at(-1));
    expect(next.instances.find((i) => i.instance_id === 13)).toMatchObject({
      active: 2,
      state: 'UP',
    });
    expect(next.delta).toEqual(m.delta);
  });

  it('keeps the WARN reasons while a push keeps WARN and drops them when it leaves WARN (C-216)', () => {
    const base = model();
    const m = {
      ...base,
      instances: base.instances.map((i) =>
        i.instance_id === 12 ? { ...i, state: 'WARN', warn_reasons: ['clock_skew'] } : i,
      ),
    };
    const t = FROM + 9 * MIN + 10_000;
    const kept = applyCounterPush(
      m,
      push(t, { per_instance: [{ instance_id: 12, state: 'WARN' }] }),
    );
    expect(kept.instances.find((i) => i.instance_id === 12)?.warn_reasons).toEqual(['clock_skew']);
    const gone = applyCounterPush(m, push(t, { per_instance: [{ instance_id: 12, state: 'UP' }] }));
    expect(gone.instances.find((i) => i.instance_id === 12)).toMatchObject({
      state: 'UP',
      warn_reasons: [],
    });
  });

  it('appends a new bucket past the last one (the window moves); unsent series stay gaps', () => {
    const m = model({ from: FROM, baseline: '1d' });
    const next = applyCounterPush(m, push(FROM + 10 * MIN + 30_000, { tps: 5, heap_used: 1 }));
    expect(next.ts).toHaveLength(11);
    expect(next.ts.at(-1)).toBe(FROM + 10 * MIN);
    expect(next.series.tps.at(-1)).toBe(5);
    expect(next.series.heap_used.at(-1)).toBe(1);
    expect(next.series.heap_max.at(-1)).toBeNull();
    expect(next.series.system_cpu).toHaveLength(11);
    expect(next.series.system_cpu.at(-1)).toBeNull();
    expect(next.series.pools.every((pool) => pool.values.length === 11)).toBe(true);
    expect(next.baseline?.values.tps).toHaveLength(11);
  });

  it('C-212 (1) takes the pushed heap_used, gc_pause_ms and cpu into the live bucket; null is a sent gap', () => {
    const m = model();
    const t = FROM + 9 * MIN + 10_000;
    const next = applyCounterPush(
      m,
      push(t, { heap_used: 300_000_000, gc_pause_ms: 12.5, cpu: 0.42 }),
    );
    expect(next.series.heap_used.at(-1)).toBe(300_000_000);
    expect(next.series.gc_pause_ms.at(-1)).toBe(12.5);
    expect(next.series.cpu.at(-1)).toBe(0.42);
    expect(next.series.system_cpu).toEqual(m.series.system_cpu);
    const gap = applyCounterPush(m, push(t, { heap_used: null, gc_pause_ms: null, cpu: null }));
    expect(gap.series.heap_used.at(-1)).toBeNull();
    expect(gap.series.gc_pause_ms.at(-1)).toBeNull();
    expect(gap.series.cpu.at(-1)).toBeNull();
    // not sent: the REST values stay
    const kept = applyCounterPush(m, push(t, { tps: 1 }));
    expect(kept.series.heap_used.at(-1)).toBe(m.series.heap_used.at(-1));
    expect(kept.series.cpu.at(-1)).toBe(0.25);
  });

  it('drops pushes older than the response after a REST refresh', () => {
    const m = model();
    const result = applyCounterPushes(m, [
      push(FROM + 5 * MIN, { tps: 1 }),
      push(FROM + 9 * MIN + 20_000, { tps: 3 }),
      push(FROM + 9 * MIN + 10_000, { tps: 2 }),
    ]);
    expect(result.tiles.tps).toBe(3);
    expect(result.series.tps.at(-1)).toBe(3);
    expect(result.series.tps[5]).toBe(m.series.tps[5]);
  });
});

describe('chart point → XLog (05 §2.0-5)', () => {
  it('opens the XLog at the point ± range/60 (min 1 minute) with kind and env, live off', () => {
    const point = FROM + 5 * MIN;
    const window = clickMenuRange(point, RANGE);
    expect(window).toEqual({ from: point - MIN, to: point + MIN });
    const href = xlogAtRange(3, window, { kind: 'BATCH', env: untrusted('prod') });
    const [path, query] = href.split('?');
    expect(path).toBe('/apps/3/xlog');
    const state = parseUrl(XLogQ, query ?? '').state;
    expect(state.time).toEqual({ kind: 'absolute', from: point - MIN, to: point + MIN });
    expect(state.kind).toBe('BATCH');
    expect(state.env).toBe('prod');
    expect(state.live).toBe(false);
  });
});

describe('dashboard catalogs (docs/15 §12)', () => {
  function leaves(node: unknown, prefix = ''): string[] {
    if (typeof node !== 'object' || node === null) {
      return [prefix];
    }
    return Object.entries(node).flatMap(([key, value]) =>
      leaves(value, prefix === '' ? key : `${prefix}.${key}`),
    );
  }
  const plural = /_(one|other)$/;

  it('T-UI-006 every English key has a Korean translation (Korean plurals: _other only)', () => {
    const en = new Set(leaves(enDashboard).map((key) => key.replace(plural, '')));
    const ko = new Set(leaves(koDashboard).map((key) => key.replace(plural, '')));
    expect([...en].filter((key) => !ko.has(key))).toEqual([]);
    expect([...ko].filter((key) => !en.has(key))).toEqual([]);
    expect(leaves(koDashboard).some((key) => key.endsWith('_one'))).toBe(false);
  });
});
