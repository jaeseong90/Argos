// SPDX-License-Identifier: Apache-2.0
//
// required/overview.yaml is valid and covers the docs/05 §2.10 checklist (docs/04 WO-31, items
// 2.10-1 .. 2.10-6) in order, with checks that exist in required/overviewChecks.ts (docs/18 §13,
// DSN-18-10); the WO-25 entry runs the firing panel against the seed's real alert since WO-25, and
// the parts that need later work orders are fixme entries of their owners (disk and certificate
// expiry WO-28). The oracles the overview checks and
// e2e/specs/overview.spec.ts use (row comparison, sort order with pinning, server panel, padding to
// 30 rows) are tested here on their own, so a wrong oracle cannot make a wrong page pass.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import type { OverviewApp } from '../../src/api/overview';
import { MISSING_DASH } from '../../src/utils/missing';
import { t } from '../fixtures/i18n';

import {
  abnormal,
  answerMismatches,
  expectedOrder,
  expectedOverviewFiring,
  FIT_ROWS,
  firingOf,
  OVERVIEW_CHECKS,
  OVERVIEW_FIRING_ROWS,
  overviewFiringMismatches,
  PAD_ID_BASE,
  padApps,
  raw,
  rankOf,
  rowMismatches,
  serverMismatches,
  SORT_CLICKS,
  sortMix,
  sortParam,
  sparkTimes,
  syntheticApp,
  worse,
  type ShownRow,
} from './overviewChecks';
import { parseRequiredFile, RUNNING_OWNERS, templateVariables, testTitle } from './schema';

const DIR = dirname(fileURLToPath(import.meta.url));
const FILE = join(DIR, 'overview.yaml');
const TS = sparkTimes(Date.parse('2030-01-01T01:00:00Z'));

/** The row a correct page shows for `app` (what shownRows reads). */
function shownOf(app: OverviewApp): ShownRow {
  const value = (v: number | null) => ({
    value: raw(v),
    text: v === null ? MISSING_DASH : String(v),
  });
  const spark = (values: readonly (number | null)[]) => {
    const present = values.filter((v): v is number => v !== null);
    return { points: String(present.length), last: raw(present.at(-1) ?? null) };
  };
  const firing = firingOf(app.firing);
  return {
    appId: app.app_id,
    pinned: abnormal(app) ? 'true' : 'false',
    rowStatus: app.status,
    name: app.name,
    env: app.env,
    status: app.status,
    up: String(app.instances.up),
    total: String(app.instances.total),
    values: {
      tps: value(app.tps),
      p95: value(app.p95_ms),
      'error-rate': value(app.error_rate),
      active: value(app.active),
      batches: value(app.batch_running),
    },
    firing:
      firing === null
        ? { state: 'unavailable', value: 'null', text: t('overview:firing.notAvailableShort') }
        : {
            state: firing.critical + firing.warn > 0 ? 'firing' : 'none',
            value: String(firing.critical + firing.warn),
            text: String(firing.critical + firing.warn),
          },
    deploy:
      app.last_deploy === null
        ? { value: 'null', text: MISSING_DASH, version: null }
        : { value: app.last_deploy.ts, text: 'x', version: app.last_deploy.version },
    sparks: {
      tps: spark(app.sparkline.tps),
      p95: spark(app.sparkline.p95),
      err: spark(app.sparkline.err),
    },
  };
}

describe('required/overview.yaml (docs/05 §2.10)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.10-1 .. 2.10-6 in order, each with a running WO-31 entry and known checks', () => {
    expect(spec.screen).toBe('overview');
    expect(spec.source).toBe('docs/05 §2.10');
    const ids = spec.items.map((item) => item.id);
    expect([...new Set(ids)]).toEqual(['2.10-1', '2.10-2', '2.10-3', '2.10-4', '2.10-5', '2.10-6']);
    // Entries of one item stay together (checklist order).
    expect(ids).toEqual([...ids].sort());
    expect(RUNNING_OWNERS.has('WO-31')).toBe(true);
    for (const id of new Set(ids)) {
      const running = spec.items.filter((item) => item.id === id && item.owner === 'WO-31');
      expect(running.length, `${id} has a WO-31 entry`).toBeGreaterThan(0);
      for (const item of running) {
        expect(item.req, `${id} names F-U17 or F-U21`).toEqual(
          expect.arrayContaining([expect.stringMatching(/^F-U(17|21)$/)]),
        );
        const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
        expect(checks.length, `${id} has a named check`).toBe(1);
        for (const check of checks) {
          expect(Object.keys(OVERVIEW_CHECKS), `${id}: check ${check}`).toContain(check);
        }
      }
    }
    // 2.10-1: default screen, filters, card/table, kiosk.
    expect(
      spec.items
        .filter((i) => i.id === '2.10-1')
        .flatMap((i) => i.assert.flatMap((a) => ('check' in a ? [a.check] : []))),
    ).toEqual(['overviewDefaultScreen', 'overviewFilters', 'overviewViewToggle', 'overviewKiosk']);
  });

  it('runs every check of overviewChecks.ts exactly once', () => {
    const used = spec.items.flatMap((i) =>
      i.assert.flatMap((a) => ('check' in a ? [a.check] : [])),
    );
    expect([...used].sort()).toEqual(Object.keys(OVERVIEW_CHECKS).sort());
  });

  it('runs the alert list of WO-25 on /overview and names the owner of the part without data: disk and certificate expiry (WO-28)', () => {
    const others = spec.items.filter((i) => i.owner !== 'WO-31');
    expect(others.map((i) => `${i.id} ${i.owner}`)).toEqual(['2.10-4 WO-25', '2.10-4 WO-28']);
    expect(RUNNING_OWNERS.has('WO-25')).toBe(true);
    const wo25 = others.filter((i) => i.owner === 'WO-25');
    expect(
      wo25.map((i) => [i.route, i.assert.flatMap((a) => ('check' in a ? [a.check] : []))]),
    ).toEqual([['/overview', ['overviewFiringAlerts']]]);
    expect(
      spec.items
        .filter((i) => i.id === '2.10-4' && i.owner === 'WO-31')
        .flatMap((i) => i.assert.flatMap((a) => ('check' in a ? [a.check] : []))),
    ).toEqual(['overviewFiringPanel', 'overviewServerPanel']);
    for (const item of others.filter((i) => !RUNNING_OWNERS.has(i.owner))) {
      expect(item.owner).toBe('WO-28');
      expect(item.coverage, `${item.id} names its coverage`).toMatch(/component tests/);
    }
  });

  it('opens fixed routes (no manifest variables) and titles follow T-E2E-overview-<n> <05 id> <title>', () => {
    for (const item of spec.items.filter((i) => i.owner === 'WO-31')) {
      expect(item.route, item.title).toMatch(/^\/(overview)?(\?kiosk=1)?$/);
      expect(templateVariables(item.route ?? '')).toEqual([]);
    }
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-overview-1 2\.10-1 /);
    expect(testTitle(spec, spec.items.length - 1)).toMatch(
      new RegExp(`^T-E2E-overview-${String(spec.items.length)} 2\\.10-6 `),
    );
  });
});

describe('overview row oracle', () => {
  const app = syntheticApp(
    { app_id: 7, name: 'order-api', env: 'prod', tps: 0, p95_ms: null, error_rate: 0.5 },
    TS,
  );

  it('accepts the row a correct page shows', () => {
    expect(rowMismatches(shownOf(app), app)).toEqual([]);
    const deployed: OverviewApp = {
      ...app,
      last_deploy: { ts: '2030-01-01T00:30:00Z', version: app.name },
    };
    expect(rowMismatches(shownOf(deployed), deployed)).toEqual([]);
  });

  it('rejects a missing value shown as 0, a 0 shown as missing and a wrong status', () => {
    const shown = shownOf(app);
    const zeroForNull = { ...shown, values: { ...shown.values, p95: { value: '0', text: '0' } } };
    expect(rowMismatches(zeroForNull, app).join('\n')).toMatch(/p95: shown "0", API "null"/);
    const dashForZero = {
      ...shown,
      values: { ...shown.values, tps: { value: '0', text: MISSING_DASH } },
    };
    expect(rowMismatches(dashForZero, app).join('\n')).toMatch(
      /tps: the value 0 is shown as missing/,
    );
    expect(rowMismatches({ ...shown, status: 'DOWN' }, app).join('\n')).toMatch(/status/);
    expect(
      rowMismatches({ ...shown, firing: { state: 'none', value: '0', text: '0' } }, app).join('\n'),
    ).toMatch(/firing state: shown "none", API "unavailable"/);
    expect(
      rowMismatches(
        { ...shown, sparks: { ...shown.sparks, err: { points: '59', last: '1' } } },
        app,
      ),
    ).toHaveLength(2);
  });

  it('compares the set of applications of an answer', () => {
    const other = syntheticApp({ app_id: 8, name: 'pay-api', env: 'prod' }, TS);
    expect(answerMismatches([shownOf(app)], [app, other]).join('\n')).toMatch(
      /applications: shown \[7\], API \[7,8\]/,
    );
    expect(answerMismatches([shownOf(other), shownOf(app)], [app, other])).toEqual([]);
  });

  it('reads firing counts like docs/14 §4.2 and nothing else', () => {
    expect(firingOf(null)).toBeNull();
    expect(firingOf({ critical: 1 })).toEqual({ critical: 1, warn: 0 });
    expect(firingOf({ critical: -1, warn: 0 })).toBeNull();
    expect(firingOf('2')).toBeNull();
    expect(raw(0)).toBe('0');
    expect(raw(null)).toBe('null');
    expect(raw(Number.NaN)).toBe('null');
  });
});

describe('overview sort oracle', () => {
  const mix = sortMix('prod', TS);
  const names = (order: { appId: number }[]) =>
    order.map((o) => mix.find((a) => a.app_id === o.appId)?.name.replace('mix-', ''));

  it('pins abnormal applications (status not UP or firing alerts) and orders them worst first', () => {
    expect(names(expectedOrder(mix, null))).toEqual([
      'delta',
      'india',
      'charlie',
      'bravo',
      'echo',
      'foxtrot',
      'alpha',
      'golf',
      'hotel',
      'juliet',
    ]);
    expect(expectedOrder(mix, null).map((o) => o.pinned)).toEqual([
      ...Array<boolean>(6).fill(true),
      ...Array<boolean>(4).fill(false),
    ]);
  });

  it('sorts numbers inside each group with missing values last in both directions', () => {
    expect(names(expectedOrder(mix, { id: 'error_rate', desc: true }))).toEqual([
      'india',
      'echo',
      'charlie',
      'bravo',
      'delta',
      'foxtrot',
      'hotel',
      'alpha',
      'juliet',
      'golf',
    ]);
    expect(names(expectedOrder(mix, { id: 'firing', desc: true }))).toEqual([
      'echo',
      'foxtrot',
      'bravo',
      'charlie',
      'delta',
      'india',
      'golf',
      'alpha',
      'hotel',
      'juliet',
    ]);
    const p95asc = names(expectedOrder(mix, { id: 'p95', desc: false }));
    expect(p95asc.slice(0, 6)).toEqual(['echo', 'india', 'bravo', 'foxtrot', 'charlie', 'delta']);
    expect(p95asc.slice(6)).toEqual(['alpha', 'juliet', 'golf', 'hotel']);
  });

  it('clicks reverse the active field and start a new numeric field descending', () => {
    expect(SORT_CLICKS[0]).toEqual({ click: 'status', then: { id: 'status', desc: true } });
    for (const [i, step] of SORT_CLICKS.entries()) {
      const previous = i === 0 ? { id: 'status', desc: false } : SORT_CLICKS[i - 1]?.then;
      expect(step.then.id).toBe(step.click);
      expect(step.then.desc).toBe(previous?.id === step.click ? !previous.desc : true);
    }
    expect(sortParam({ id: 'error_rate', desc: false })).toBe('error_rate:asc');
  });

  it('ranks DOWN > STALE > WARN > unknown > UP', () => {
    expect(['UP', 'WARN', 'STALE', 'DOWN', 'X'].map(rankOf)).toEqual([4, 2, 1, 0, 3]);
    expect(worse('UP', 'STALE')).toBe('STALE');
    expect(worse('DOWN', 'WARN')).toBe('DOWN');
    expect(worse('UP', 'X')).toBe('UNKNOWN');
  });
});

describe('overview padding and server panel oracles', () => {
  it('pads the real rows to 30 typed rows with unique ids, real rows first', () => {
    const real = [syntheticApp({ app_id: 3, name: 'order-api', env: 'prod' }, TS)];
    const rows = padApps(real, FIT_ROWS, TS);
    expect(rows).toHaveLength(FIT_ROWS);
    expect(rows[0]).toBe(real[0]);
    expect(new Set(rows.map((r) => r.app_id)).size).toBe(FIT_ROWS);
    for (const row of rows.slice(1)) {
      expect(row.app_id).toBeGreaterThanOrEqual(PAD_ID_BASE);
      expect(row.app_id).toBeLessThanOrEqual(2_147_483_647);
      expect(row.env).toBe('prod');
      expect(row.sparkline.ts).toHaveLength(60);
      expect(row.sparkline.tps).toHaveLength(60);
      expect(abnormal(row)).toBe(false);
    }
    expect(padApps(rows, 5, TS)).toHaveLength(5);
  });

  it('expects the firing panel of an alerts answer: critical first, newest first, eight rows and "more"', () => {
    const item = (id: string, severity: string, ts: string) => ({
      event_id: id,
      ts,
      app: { id: 3, name: 'order-api' },
      target: { type: 'app', id: null, name: null },
      rule: { id: `r-${id}`, name: id },
      severity,
      state: 'FIRING',
    });
    expect(expectedOverviewFiring([])).toEqual({
      state: 'empty',
      rows: [],
      more: 0,
      critical: 0,
      warn: 0,
    });
    const answer = [
      item('a', 'warn', '2030-01-01T00:01:00Z'),
      item('b', 'CRITICAL', '2030-01-01T00:00:00Z'),
      item('c', 'warn', '2030-01-01T00:02:00Z'),
      ...Array.from({ length: OVERVIEW_FIRING_ROWS }, (_, i) =>
        item(`x${String(i)}`, 'warn', '2029-12-31T00:00:00Z'),
      ),
    ];
    const expected = expectedOverviewFiring(answer);
    expect(expected.rows.slice(0, 3).map((r) => r.id)).toEqual(['b', 'c', 'a']);
    expect(expected.rows).toHaveLength(OVERVIEW_FIRING_ROWS);
    expect(expected.rows[0]?.severity).toBe('critical');
    expect(expected.more).toBe(3);
    expect(expected.critical).toBe(1);
    expect(expected.warn).toBe(answer.length - 1);
    // Ties keep the API order (a stable sort, as the panel's).
    expect(expected.rows.slice(3).map((r) => r.id)).toEqual(['x0', 'x1', 'x2', 'x3', 'x4']);
  });

  it('compares the drawn firing panel with the alerts answer and alerts_firing_total', () => {
    const answer = [
      { event_id: 'a', severity: 'warn', ts: '2030-01-01T00:01:00Z' },
      { event_id: 'b', severity: 'critical', ts: '2030-01-01T00:00:00Z' },
    ];
    const good = {
      state: 'list',
      total: '2',
      rows: [
        { id: 'b', severity: 'critical' },
        { id: 'a', severity: 'warn' },
      ],
      more: null,
      summary: t('overview:panel.firing.summary', 'en', { critical: '1', warn: '1' }),
    };
    expect(overviewFiringMismatches(good, answer, 2)).toEqual([]);
    expect(overviewFiringMismatches(good, answer, 3).join('\n')).toMatch(
      /alerts_firing_total 3 != 2/,
    );
    expect(overviewFiringMismatches(good, answer, null).join('\n')).toMatch(/never|always/);
    const reordered = { ...good, rows: [...good.rows].reverse() };
    expect(overviewFiringMismatches(reordered, answer, 2).join('\n')).toMatch(/^rows /);
    const empty = { state: 'empty', total: '0', rows: [], more: null, summary: null };
    expect(overviewFiringMismatches(empty, [], 0)).toEqual([]);
  });

  it('compares the five server items, Not available for null', () => {
    const server = {
      ingest_lag_s: 0.4,
      disk_usage_ratio: null,
      channel_failures_24h: null,
      sessions: 0,
      cert_expiry_days: null,
    };
    const na = t('overview:panel.server.notAvailable');
    const good = [
      { id: 'ingest-lag', value: '0.4', text: '0.4 s' },
      { id: 'disk', value: 'null', text: na },
      { id: 'channel-failures', value: 'null', text: na },
      { id: 'sessions', value: '0', text: '0' },
      { id: 'cert-expiry', value: 'null', text: na },
    ];
    expect(serverMismatches(good, server)).toEqual([]);
    const zeroAsNa = good.map((s) => (s.id === 'sessions' ? { ...s, text: na } : s));
    expect(serverMismatches(zeroAsNa, server).join('\n')).toMatch(/sessions: the value 0/);
    const nullAsZero = good.map((s) => (s.id === 'disk' ? { ...s, value: '0', text: '0' } : s));
    expect(serverMismatches(nullAsZero, server)).toHaveLength(2);
  });
});
