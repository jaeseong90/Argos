// SPDX-License-Identifier: Apache-2.0
//
// required/dashboard.yaml is valid and covers the docs/05 §2.3 checklist (docs/04 WO-32 "05 §2.3
// 체크표" 2.3-1 .. 2.3-7) one to one, in order, with checks that exist in required/dashboardChecks.ts
// and route variables of manifestVariables() (docs/18 §13, DSN-18-10). The WO-22 entries (outage
// shading, real WebSocket server) run since the pair 7 wrap-up (ADR-0010 C-216 (2)), the WO-25
// entry (firing alerts of the engine, the seed's real alert) since WO-25; the parts the seed cannot
// show yet are fixme entries of their owners (real STALE/WARN tiles WO-39).
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  CHARTS,
  expectedSeriesCounts,
  liveTsMs,
  parseValueAttr,
} from '../fixtures/dashboardOracle';
import type { SeedManifest } from '../stack/manifest';

import {
  DASHBOARD_CHECKS,
  DASHBOARD_FIRING_ROWS,
  expectedFiringPanel,
  sortedWithNulls,
} from './dashboardChecks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';

const DIR = dirname(fileURLToPath(import.meta.url));
const FILE = join(DIR, 'dashboard.yaml');
const COMMON = join(DIR, 'common.yaml');

/** As much of the seed manifest as manifestVariables() reads. */
const SEED = {
  generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
  apps: [{ app_id: 3, name: 'order-api', env: 'prod', instances: [{ instance_id: 12 }] }],
  primary_app_id: 3,
  secondary_app_id: 4,
  traffic: { app_id: 11 },
  windows: [],
  tx: {},
} as unknown as SeedManifest;

describe('required/dashboard.yaml (docs/05 §2.3)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.3-1 .. 2.3-7 in order, each with a running WO-32 entry and a known check', () => {
    expect(spec.screen).toBe('dashboard');
    expect(spec.source).toBe('docs/05 §2.3');
    expect([...new Set(spec.items.map((item) => item.id))]).toEqual([
      '2.3-1',
      '2.3-2',
      '2.3-3',
      '2.3-4',
      '2.3-5',
      '2.3-6',
      '2.3-7',
    ]);
    // Entries of one item stay together (checklist order).
    const ids = spec.items.map((item) => item.id);
    expect(ids).toEqual([...ids].sort());
    expect(RUNNING_OWNERS.has('WO-32')).toBe(true);
    for (const id of new Set(ids)) {
      const running = spec.items.filter((item) => item.id === id && item.owner === 'WO-32');
      expect(running.length, `${id} has a WO-32 entry`).toBe(1);
      for (const item of running) {
        expect(item.req).toContain(id === '2.3-4' ? 'F-U20' : 'F-U01');
        const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
        expect(checks.length, `${id} has a named check`).toBeGreaterThan(0);
        for (const check of checks) {
          expect(Object.keys(DASHBOARD_CHECKS), `${id}: check ${check}`).toContain(check);
        }
      }
    }
  });

  it('runs the WO-22 entries (outage shading on the traffic app, real WS) and the WO-25 firing alerts, and names the owner of the part without data (STALE/WARN WO-39)', () => {
    expect(
      spec.items.filter((item) => item.owner !== 'WO-32').map((i) => `${i.id} ${i.owner}`),
    ).toEqual(['2.3-2 WO-22', '2.3-3 WO-39', '2.3-5 WO-25', '2.3-7 WO-22']);
    expect(RUNNING_OWNERS.has('WO-22')).toBe(true);
    expect(RUNNING_OWNERS.has('WO-25')).toBe(true);
    expect(RUNNING_OWNERS.has('WO-39')).toBe(false);
    const wo25 = spec.items.filter((i) => i.owner === 'WO-25');
    expect(
      wo25.map((i) => [i.id, i.route, i.assert.flatMap((a) => ('check' in a ? [a.check] : []))]),
    ).toEqual([
      [
        '2.3-5',
        '/apps/{primary_app_id}/dashboard?from={range_from}&to={range_to}',
        ['dashboardFiringAlerts'],
      ],
    ]);
    expect(Object.keys(DASHBOARD_CHECKS)).toContain('dashboardFiringAlerts');
    const wo22 = spec.items.filter((i) => i.owner === 'WO-22');
    const checksOf = (item: (typeof wo22)[number]) =>
      item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
    expect(wo22.map((i) => [i.id, i.ws ?? 'stub', checksOf(i)])).toEqual([
      ['2.3-2', 'stub', ['dashboardOutageShading']],
      ['2.3-7', 'server', ['dashboardLiveServer']],
    ]);
    for (const item of wo22) {
      for (const check of checksOf(item)) {
        expect(Object.keys(DASHBOARD_CHECKS)).toContain(check);
      }
    }
    for (const item of spec.items.filter((i) => !RUNNING_OWNERS.has(i.owner))) {
      expect(item.coverage, `${item.id} names its coverage`).toMatch(/component/);
    }
  });

  it('fills every route variable from the manifest', () => {
    const vars = manifestVariables(SEED);
    for (const item of spec.items) {
      for (const name of templateVariables(item.route ?? '')) {
        expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
      }
      expect(fillTemplate(item.route ?? '', vars), item.id).not.toContain('{');
    }
    expect(fillTemplate(spec.items[0]?.route ?? '', vars)).toBe(
      '/apps/3/dashboard?from=2030-01-01T00:00:00Z&to=2030-01-01T00:43:00Z',
    );
  });

  it('test titles follow T-E2E-dashboard-<n> <05 id> <title>', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-dashboard-1 2\.3-1 /);
    expect(testTitle(spec, spec.items.length - 1)).toMatch(
      new RegExp(`^T-E2E-dashboard-${String(spec.items.length)} 2\\.3-7 `),
    );
  });

  it('runs the WO-32 items of common.yaml on the dashboard and the WO-22 outage shading on the traffic app', () => {
    const common = parseRequiredFile(readFileSync(COMMON, 'utf8'), COMMON);
    const wo32 = common.items.filter((item) => item.owner === 'WO-32');
    expect(wo32.map((item) => item.id)).toEqual(['2.0-1', '2.0-5']);
    for (const item of wo32) {
      expect(item.route).toMatch(/^\/apps\/\{primary_app_id\}\/dashboard\?/);
      for (const a of item.assert) {
        if ('check' in a) {
          expect(Object.keys(DASHBOARD_CHECKS)).toContain(a.check);
        }
      }
    }
    const shading = common.items.filter((item) => item.id === '2.0-5' && item.owner === 'WO-22');
    expect(shading).toHaveLength(1);
    expect(shading[0]?.route).toBe('/apps/{traffic_app_id}/dashboard');
    expect(shading[0]?.assert).toEqual([{ check: 'dashboardOutageShading' }]);
  });
});

describe('dashboard oracle helpers', () => {
  it('reads null as the contract writes it, numbers exactly', () => {
    expect(parseValueAttr(null)).toBeNull();
    expect(parseValueAttr('null')).toBeNull();
    expect(parseValueAttr('')).toBeNull();
    expect(parseValueAttr('0')).toBe(0);
    expect(parseValueAttr('0.0123')).toBe(0.0123);
    expect(() => parseValueAttr('abc')).toThrow(/not a number/);
  });

  it('reads data-live-ts as ISO-8601 or epoch milliseconds', () => {
    expect(liveTsMs('2030-01-01T00:00:05.000Z')).toBe(Date.parse('2030-01-01T00:00:05Z'));
    expect(liveTsMs('1893456005000')).toBe(1_893_456_005_000);
    expect(liveTsMs(null)).toBeNull();
    expect(liveTsMs('soon')).toBeNull();
  });

  it('expects the series of docs/15 §9.2 for an API answer', () => {
    const counts = expectedSeriesCounts({
      pool_usage_ratio: { HikariPool: [0.1], '<img src=x>': [0.2] },
      ext_errors: null,
    } as never);
    expect(Object.keys(counts)).toEqual([...CHARTS]);
    expect(counts.response).toBe(3);
    expect(counts['heap-gc']).toBe(3);
    expect(counts.pool).toBe(2);
    expect(counts.ext).toBe(1);
    expect(expectedSeriesCounts({ pool_usage_ratio: {}, ext_errors: [1] } as never).ext).toBe(2);
  });

  it('expects the firing panel of an API answer: null not available, [] empty, rows in API order up to 20', () => {
    expect(expectedFiringPanel(null)).toEqual({ state: 'unavailable', count: 'null', rows: [] });
    expect(expectedFiringPanel([])).toEqual({ state: 'empty', count: '0', rows: [] });
    const item = (n: number, severity = 'warn') => ({
      event_id: `e-${String(n)}`,
      ts: '2030-01-01T00:00:00Z',
      app: { id: 3, name: 'order-api' },
      target: { type: 'app', id: null, name: null },
      rule: { id: `r-${String(n)}`, name: `rule ${String(n)}` },
      severity,
      state: 'FIRING',
    });
    const panel = expectedFiringPanel([item(2, 'CRITICAL'), item(1), { event_id: null }]);
    expect(panel.state).toBe('list');
    expect(panel.count).toBe('3');
    expect(panel.rows).toEqual([
      { id: 'e-2', severity: 'critical', rule: 'rule 2' },
      { id: 'e-1', severity: 'warn', rule: 'rule 1' },
    ]);
    const many = Array.from({ length: DASHBOARD_FIRING_ROWS + 5 }, (_, i) => item(i));
    expect(expectedFiringPanel(many).rows).toHaveLength(DASHBOARD_FIRING_ROWS);
    expect(expectedFiringPanel(many).count).toBe(String(DASHBOARD_FIRING_ROWS + 5));
  });

  it('accepts a sorted column with nulls at one end, rejects anything else', () => {
    expect(sortedWithNulls([5, 3, 3, 1, null, null], true)).toBe(true);
    expect(sortedWithNulls([null, 1, 2, 9], false)).toBe(true);
    expect(sortedWithNulls([1, 2, 9], true)).toBe(false);
    expect(sortedWithNulls([5, null, 3], true)).toBe(false);
    expect(sortedWithNulls([], true)).toBe(true);
  });
});
