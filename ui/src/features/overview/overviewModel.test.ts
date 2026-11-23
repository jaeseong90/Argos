// SPDX-License-Identifier: Apache-2.0
//
// Pure parts of the overview (docs/15 §9.1, 05 §2.10): the sort of the four fields with abnormal
// applications pinned on top (T-UI-004), the status rank and "worse of" rule, the firing counts
// (null = not available, never 0: T-UI-007), the `overview` push merge (docs/14 §5.2, ADR-0010
// C-213 (3), plan assumptions 2 and 5), the firing panel rows and the sparkline geometry.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U17' }
import { describe, expect, it } from 'vitest';

import type { OverviewApp } from '../../api/overview';

import { firingAlerts } from './firingAlerts';
import { mergeLive, parseOverviewPush, type OverviewPush } from './mergeLive';
import { firingCounts, firingTotal, isAbnormal, statusRank, worseStatus } from './model';
import {
  nextOverviewSort,
  overviewSortFromUrl,
  sortOverview,
  type OverviewSort,
} from './sortOverview';
import { sparkSegments } from './Sparkline';
import { overviewApp } from './testing/fixtures';

export const REQ = { type: 'req', description: 'F-U17' };

const ids = (rows: { app: OverviewApp }[]): number[] => rows.map((row) => row.app.app_id);

describe('overview: status and firing model', () => {
  it('T-UI-008 ranks DOWN > STALE > WARN > unknown > UP and takes the worse of two', () => {
    expect(['UP', 'WARN', 'STALE', 'DOWN', 'odd'].map(statusRank)).toEqual([4, 2, 1, 0, 3]);
    expect(worseStatus('UP', 'WARN')).toBe('WARN');
    expect(worseStatus('stale', 'DOWN')).toBe('DOWN');
    expect(worseStatus('DOWN', 'UP')).toBe('DOWN');
    expect(worseStatus('up', 'up')).toBe('UP');
  });

  it('T-UI-007 firing null or an unknown shape is "not available", zero alerts is 0', () => {
    expect(firingCounts(null)).toBeNull();
    expect(firingCounts(undefined)).toBeNull();
    expect(firingCounts({ critical: 'x' })).toBeNull();
    expect(firingCounts([])).toBeNull();
    expect(firingCounts({ critical: 0, warn: 0 })).toEqual({ critical: 0, warn: 0 });
    expect(firingCounts({ warn: 2 })).toEqual({ critical: 0, warn: 2 });
    expect(firingTotal({ critical: 1, warn: 2 })).toBe(3);
    expect(firingTotal(null)).toBeNull();
  });

  it('pins every status but UP and an UP application with firing alerts', () => {
    expect(isAbnormal({ status: 'UP', firing: null })).toBe(false);
    expect(isAbnormal({ status: 'UP', firing: { critical: 0, warn: 0 } })).toBe(false);
    expect(isAbnormal({ status: 'UP', firing: { critical: 0, warn: 1 } })).toBe(true);
    for (const status of ['WARN', 'STALE', 'DOWN', 'unknown']) {
      expect(isAbnormal({ status, firing: null })).toBe(true);
    }
  });
});

describe('overview: sort and pin (T-UI-004, 05 §2.10)', () => {
  const apps: OverviewApp[] = [
    overviewApp(1, 'alpha', { status: 'UP', error_rate: 0.05, p95_ms: 900 }),
    overviewApp(2, 'bravo', { status: 'UP', error_rate: 0.001, p95_ms: 120 }),
    overviewApp(3, 'charlie', { status: 'DOWN', error_rate: null, p95_ms: null }),
    overviewApp(4, 'delta', { status: 'UP', error_rate: null, p95_ms: 300 }),
    overviewApp(5, 'echo', { status: 'WARN', error_rate: 0.02, p95_ms: 50 }),
    overviewApp(6, 'foxtrot', { status: 'UP', firing: { critical: 0, warn: 2 }, p95_ms: 10 }),
    overviewApp(7, 'golf', { status: 'STALE', error_rate: 0.3, p95_ms: 2000 }),
    overviewApp(8, 'hotel', { status: 'UP', firing: { critical: 1, warn: 0 }, p95_ms: 20 }),
  ];

  it('T-UI-004 default order: abnormal first by worst status, then the rest by name', () => {
    const rows = sortOverview(apps, null);
    expect(ids(rows)).toEqual([3, 7, 5, 6, 8, 1, 2, 4]);
    expect(rows.map((row) => row.pinned)).toEqual([
      true,
      true,
      true,
      true,
      true,
      false,
      false,
      false,
    ]);
  });

  it('T-UI-004 error rate descending keeps abnormal apps pinned; missing values last', () => {
    const rows = sortOverview(apps, { id: 'error_rate', desc: true });
    // foxtrot and hotel have the fixture's 0.01 and tie (name decides); charlie has none.
    const pinned = rows.filter((row) => row.pinned).map((row) => row.app.app_id);
    const others = rows.filter((row) => !row.pinned).map((row) => row.app.app_id);
    expect(ids(rows).slice(0, pinned.length)).toEqual(pinned);
    expect(pinned).toEqual([7, 5, 6, 8, 3]);
    expect(others).toEqual([1, 2, 4]);
  });

  it('T-UI-004 error rate ascending: missing values still last in each group', () => {
    const rows = sortOverview(apps, { id: 'error_rate', desc: false });
    expect(ids(rows)).toEqual([6, 8, 5, 7, 3, 2, 1, 4]);
  });

  it('T-UI-004 p95 both directions inside the groups', () => {
    expect(ids(sortOverview(apps, { id: 'p95', desc: true }))).toEqual([7, 5, 8, 6, 3, 1, 4, 2]);
    expect(ids(sortOverview(apps, { id: 'p95', desc: false }))).toEqual([6, 8, 5, 7, 3, 2, 4, 1]);
  });

  it('T-UI-004 alerts: critical weighs before warnings, "not available" last', () => {
    const rows = sortOverview(apps, { id: 'firing', desc: true });
    expect(ids(rows)).toEqual([8, 6, 3, 5, 7, 1, 2, 4]);
  });

  it('T-UI-004 status ascending is worst first; descending puts UP-with-alerts first in the pinned group', () => {
    expect(ids(sortOverview(apps, { id: 'status', desc: false }))).toEqual([
      3, 7, 5, 6, 8, 1, 2, 4,
    ]);
    expect(ids(sortOverview(apps, { id: 'status', desc: true }))).toEqual([6, 8, 5, 7, 3, 1, 2, 4]);
  });

  it('ties break by name, environment and id (stable between refreshes)', () => {
    const twins = [
      overviewApp(12, 'order-api', { env: 'stage' }),
      overviewApp(11, 'order-api', { env: 'prod' }),
      overviewApp(10, 'Order-api', { env: 'prod' }),
    ];
    expect(ids(sortOverview(twins, null))).toEqual([10, 11, 12]);
  });

  it('reads the URL sort and toggles like the table headers', () => {
    expect(overviewSortFromUrl('error_rate:desc')).toEqual({ id: 'error_rate', desc: true });
    expect(overviewSortFromUrl('-p95')).toEqual({ id: 'p95', desc: true });
    expect(overviewSortFromUrl('tps:desc')).toBeNull();
    expect(overviewSortFromUrl(undefined)).toBeNull();
    const status: OverviewSort = { id: 'status', desc: false };
    expect(nextOverviewSort(status, 'status')).toEqual({ id: 'status', desc: true });
    expect(nextOverviewSort(status, 'p95')).toEqual({ id: 'p95', desc: true });
    expect(nextOverviewSort(null, 'status')).toEqual({ id: 'status', desc: false });
  });
});

describe('overview: live merge (docs/14 §5.2 overview, C-213 (3))', () => {
  const T0 = Date.parse('2026-11-23T10:00:00.000Z');
  const rest = [
    overviewApp(1, 'alpha', { status: 'DOWN', tps: 10, p95_ms: 100, error_rate: 0.1, active: 5 }),
    overviewApp(2, 'bravo', { status: 'UP', tps: 20, p95_ms: 200, error_rate: 0, active: 1 }),
    overviewApp(3, 'charlie', { status: 'UP', tps: 30, firing: { critical: 1, warn: 0 } }),
  ];
  const push = (data: unknown, t = T0 + 5_000): OverviewPush => {
    const parsed = parseOverviewPush(new Date(t).toISOString(), data);
    if (parsed === null) {
      throw new Error('invalid push');
    }
    return parsed;
  };

  it('takes tps, p95, error rate and active from the push and keeps absent apps', () => {
    const merged = mergeLive(
      rest,
      push({
        apps: [{ app_id: 2, status: 'UP', tps: 42.5, p95_ms: null, error_rate: 0.5, active: 0 }],
      }),
      T0,
    );
    expect(merged[1]).toMatchObject({ tps: 42.5, p95_ms: null, error_rate: 0.5, active: 0 });
    expect(merged[0]).toBe(rest[0]);
    expect(merged[2]).toBe(rest[2]);
  });

  it('shows the worse of the push and REST status (the push ignores never-connected instances)', () => {
    const merged = mergeLive(
      rest,
      push({
        apps: [
          { app_id: 1, status: 'UP' },
          { app_id: 2, status: 'STALE' },
          { app_id: 3, status: 'bogus' },
        ],
      }),
      T0,
    );
    expect(merged.map((app) => app.status)).toEqual(['DOWN', 'STALE', 'UP']);
  });

  it('ignores a null firing, applies a firing object, and keeps fields the push leaves out', () => {
    const merged = mergeLive(
      rest,
      push({
        apps: [
          { app_id: 3, firing: null },
          { app_id: 2, firing: { critical: 0, warn: 3 } },
        ],
      }),
      T0,
    );
    expect(merged[2]?.firing).toEqual({ critical: 1, warn: 0 });
    expect(merged[2]?.tps).toBe(30);
    expect(merged[1]?.firing).toEqual({ critical: 0, warn: 3 });
  });

  it('does not apply a push older than the REST answer', () => {
    const old = push({ apps: [{ app_id: 2, tps: 99 }] }, T0 - 1_000);
    expect(mergeLive(rest, old, T0)[1]?.tps).toBe(20);
    expect(mergeLive(rest, old, null)[1]?.tps).toBe(99);
  });

  it('drops a payload with a wrong type or a bad time as a whole', () => {
    expect(
      parseOverviewPush('2026-11-23T10:00:05Z', { apps: [{ app_id: 2, tps: 'x' }] }),
    ).toBeNull();
    expect(parseOverviewPush('2026-11-23T10:00:05Z', { apps: 'none' })).toBeNull();
    expect(parseOverviewPush('2026-11-23T10:00:05Z', { apps: [{ app_id: -1 }] })).toBeNull();
    expect(parseOverviewPush('not a time', { apps: [] })).toBeNull();
    expect(
      parseOverviewPush('2026-11-23T10:00:05Z', { apps: [{ app_id: 2, extra: true }], more: 1 }),
    ).not.toBeNull();
  });
});

describe('overview: firing panel rows and sparkline geometry', () => {
  it('lists critical first, newest first, and counts items that do not validate', () => {
    const result = firingAlerts([
      { event_id: 1, ts: '2026-11-23T09:00:00Z', severity: 'warn', rule: { name: 'p95' } },
      { event_id: 2, ts: '2026-11-23T08:00:00Z', severity: 'CRITICAL', app: { name: 'order' } },
      { event_id: 3, ts: '2026-11-23T09:30:00Z', severity: 'warn', app: 'billing' },
      { event_id: { nested: true } },
    ]);
    expect(result.rows.map((row) => row.id)).toEqual(['2', '3', '1']);
    expect(result.invalid).toBe(1);
    expect(result.critical).toBe(1);
    expect(result.warn).toBe(2);
    expect(result.rows[0]?.app).toBe('order');
    expect(result.rows[1]?.app).toBe('billing');
  });

  it('reads the alert engine items (WO-25): nulls are missing values, never invalid items', () => {
    const result = firingAlerts([
      {
        event_id: '5b2e0000-0000-4000-8000-000000000001',
        ts: '2026-11-23T09:00:00Z',
        app: null,
        env: null,
        target: { type: 'server', id: null, name: null },
        rule: { id: '5b2e0000-0000-4000-8000-0000000000aa', name: 'ingest lag' },
        severity: 'warn',
        state: 'FIRING',
        value: null,
        threshold: 30,
        duration_s: 300,
        deeplink: 'http://argos.example/alerts',
      },
      {
        event_id: '5b2e0000-0000-4000-8000-000000000002',
        ts: '2026-11-23T08:00:00Z',
        app: { id: 3, name: 'billing' },
        env: 'prod',
        target: { type: 'instance', id: '31', name: 'billing-1' },
        rule: { id: '5b2e0000-0000-4000-8000-0000000000bb', name: 'instance down' },
        severity: 'critical',
        state: 'ACKED',
        acked_by: 'operator',
        acked_at: '2026-11-23T08:05:00Z',
        note: 'on it',
        deeplink: 'http://argos.example/apps/3/dashboard',
      },
    ]);
    expect(result.invalid).toBe(0);
    expect(result.rows.map((row) => row.id)).toEqual([
      '5b2e0000-0000-4000-8000-000000000002',
      '5b2e0000-0000-4000-8000-000000000001',
    ]);
    expect(result.rows[0]).toMatchObject({
      severity: 'critical',
      state: 'ACKED',
      app: 'billing',
      rule: 'instance down',
      target: 'billing-1',
      since: Date.parse('2026-11-23T08:00:00Z'),
    });
    expect(result.rows[1]).toMatchObject({
      severity: 'warn',
      state: 'FIRING',
      app: null,
      rule: 'ingest lag',
      target: null,
    });
    expect(result.critical).toBe(1);
    expect(result.warn).toBe(1);
  });

  it('T-UI-007 breaks the line at missing minutes instead of drawing 0', () => {
    expect(sparkSegments([null, null])).toEqual([]);
    const segments = sparkSegments([1, 2, null, 3, 4], 40, 14);
    expect(segments).toHaveLength(2);
    expect(segments[0]?.split(' ')).toHaveLength(2);
    // A flat series sits in the middle.
    expect(sparkSegments([5, 5], 10, 14)).toEqual(['0.0,7.0 10.0,7.0']);
    // The highest value is at the top (inset), the lowest at the bottom.
    expect(sparkSegments([0, 10], 10, 14)).toEqual(['0.0,12.5 10.0,1.5']);
  });
});
