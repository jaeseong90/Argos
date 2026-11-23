// SPDX-License-Identifier: Apache-2.0
//
// required/stats.yaml is valid and covers the docs/05 §2.5 checklist (docs/04 WO-36 "05 §2.5 전부"
// 2.5-1 .. 2.5-5) in order: 2.5-1 once per list (services, SQL, external, errors), the details of
// 2.5-2 .. 2.5-5 with checks that exist in required/statsChecks.ts and route variables of
// manifestVariables() (docs/18 §13, DSN-18-10). The parts without a screen or data yet are fixme
// entries of their owners (application map node F-U11 / WO-70, application frames WO-26). Also the
// pure helpers of e2e/fixtures/statsOracle.ts (CSV parsing, sort spelling, URL question, row
// comparison).
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  appIdOf,
  leadingId,
  listQueryOf,
  normalizeSort,
  parseCsv,
  rowMismatches,
  squash,
  statsDetailPath,
  statsPath,
  type ShownRow,
} from '../fixtures/statsOracle';
import type { SeedManifest } from '../stack/manifest';

import { CHECKS } from './checks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { LISTS, STATS_CHECKS } from './statsChecks';
import { manifestVariables } from './variables';

const DIR = dirname(fileURLToPath(import.meta.url));
const FILE = join(DIR, 'stats.yaml');

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

function checksOf(item: { assert: readonly object[] }): string[] {
  return item.assert.flatMap((a) => ('check' in a ? [(a as { check: string }).check] : []));
}

describe('required/stats.yaml (docs/05 §2.5)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.5-1 .. 2.5-5 in checklist order, owned by the running WO-36', () => {
    expect(spec.screen).toBe('stats');
    expect(spec.source).toBe('docs/05 §2.5');
    const ids = spec.items.map((item) => item.id);
    expect([...new Set(ids)]).toEqual(['2.5-1', '2.5-2', '2.5-3', '2.5-4', '2.5-5']);
    expect(ids).toEqual([...ids].sort());
    expect(RUNNING_OWNERS.has('WO-36')).toBe(true);
    for (const id of new Set(ids)) {
      expect(
        spec.items.filter((item) => item.id === id && item.owner === 'WO-36').length,
        `${id} has a WO-36 entry`,
      ).toBeGreaterThan(0);
    }
  });

  it('runs 2.5-1 once per list, each on its own screen with its own table check', () => {
    const lists = spec.items.filter((item) => item.id === '2.5-1');
    expect(lists.map((item) => [item.route?.split('?')[0], checksOf(item)])).toEqual([
      ['/apps/{primary_app_id}/stats/services', ['statsServicesTable']],
      ['/apps/{primary_app_id}/stats/sql', ['statsSqlTable']],
      ['/apps/{primary_app_id}/stats/external', ['statsExternalTable']],
      ['/apps/{primary_app_id}/stats/errors', ['statsErrorsTable']],
    ]);
    for (const item of lists) {
      expect(item.req).toContain('F-U16');
    }
  });

  it('runs the details of 2.5-2 .. 2.5-5 from their lists with a known check', () => {
    const details = spec.items.filter((item) => item.id !== '2.5-1' && item.owner === 'WO-36');
    expect(details.map((item) => [item.id, checksOf(item)])).toEqual([
      ['2.5-2', ['statsServiceDetail']],
      ['2.5-3', ['statsSqlDetail']],
      ['2.5-4', ['statsExternalDetail']],
      ['2.5-5', ['statsErrorDetail']],
    ]);
    for (const item of spec.items) {
      for (const check of checksOf(item)) {
        expect(Object.keys(STATS_CHECKS), `${item.id}: ${check}`).toContain(check);
        expect(CHECKS[check], `${check} is registered in checks.ts`).toBe(STATS_CHECKS[check]);
      }
    }
  });

  it('names the owners of the parts without a screen or data (map node WO-70, app frames WO-26)', () => {
    const later = spec.items.filter((item) => !RUNNING_OWNERS.has(item.owner));
    expect(later.map((item) => `${item.id} ${item.owner} ${item.req.join(',')}`)).toEqual([
      '2.5-4 WO-70 F-U11',
      '2.5-5 WO-26 F-U07',
    ]);
    expect(later[0]?.coverage).toMatch(/F-U11 \(S\)/);
    expect(later[1]?.coverage).toMatch(/component test .*FrameList .*appPackages/);
    expect(later[1]?.coverage).toMatch(/C-207 \(3\)/);
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
      '/apps/3/stats/services?from=2030-01-01T00:00:00Z&to=2030-01-01T00:43:00Z',
    );
  });

  it('test titles follow T-E2E-stats-<n> <05 id> <title>, below the spec numbers (20+)', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-stats-1 2\.5-1 services table/);
    expect(testTitle(spec, 3)).toMatch(/^T-E2E-stats-4 2\.5-1 error group table/);
    expect(testTitle(spec, 4)).toMatch(/^T-E2E-stats-5 2\.5-2 /);
    expect(spec.items.length).toBeLessThan(20);
  });

  it('list specs: sort segments of 05 §2.5-1, estimates only on SQL and external (C-14)', () => {
    for (const screen of ['services', 'sql', 'external'] as const) {
      const fields = LISTS[screen].sortFields;
      expect(fields.slice(0, 4), screen).toEqual(['sum_ms', 'count', 'avg_ms', 'p95_ms']);
      expect(fields[4], screen).toMatch(/^error/);
      expect(LISTS[screen].deltas.slice(0, 2)).toEqual(['count', 'p95_ms']);
    }
    expect(LISTS.services.deltas).toContain('error_rate');
    expect(LISTS.errors.sortFields).toEqual(['count', 'first_seen', 'last_seen']);
    expect(LISTS.errors.deltas).toEqual(['count']);
    expect(LISTS.services.estimated).toEqual([]);
    expect(LISTS.errors.estimated).toEqual([]);
    expect(LISTS.sql.estimated).toContain('p95_ms');
    expect(LISTS.external.estimated).toContain('p95_ms');
    expect(LISTS.external.values.required).toEqual(
      expect.arrayContaining(['timeouts', 'connect_failures']),
    );
  });

  it('list specs: the SQL DB value goes to the server as db_id, other values stay on the client', () => {
    const rows = [
      { db: { db_id: '77', target: 'mysql://db1:3306/shop' } },
      { db: { db_id: '78', target: 'postgresql://db2:5432/pay' } },
    ] as never[];
    expect(LISTS.sql.serverFilter?.('db', 'mysql://db1:3306/shop', rows)).toEqual({ db_id: '77' });
    expect(LISTS.sql.serverFilter?.('db_id', '78', rows)).toEqual({ db_id: '78' });
    expect(LISTS.sql.serverFilter?.('sql', '1', rows)).toBeNull();
    expect(LISTS.services.serverFilter).toBeUndefined();
  });
});

describe('statistics oracle helpers', () => {
  it('parses the server CSV: quoted cells, doubled quotes, CRLF, empty cells', () => {
    expect(parseCsv('"sql_id","sql_normalized"\r\n"1","SELECT ""a"", b"\r\n"2",""\r\n')).toEqual([
      ['sql_id', 'sql_normalized'],
      ['1', 'SELECT "a", b'],
      ['2', ''],
    ]);
    expect(parseCsv('"a"\r\n"x\r\ny"\r\n')).toEqual([['a'], ['x\r\ny']]);
    expect(() => parseCsv('"open')).toThrow(/quoted/);
  });

  it('reads both sort spellings', () => {
    expect(normalizeSort('-p95_ms')).toBe('p95_ms:desc');
    expect(normalizeSort('count')).toBe('count:asc');
    expect(normalizeSort('sum_ms:desc')).toBe('sum_ms:desc');
    expect(normalizeSort(null)).toBeNull();
    expect(normalizeSort('')).toBeNull();
  });

  it('reads the list question of an address (range, kind, sort, Top N, flags)', () => {
    const q = listQueryOf(
      'http://x/apps/3/stats/errors?from=2030-01-01T00:00:00Z&to=2030-01-01T01:00:00Z&kind=batch&sort=-first_seen&limit=10&new=1',
    );
    expect(q).toEqual({
      from: '2030-01-01T00:00:00.000Z',
      to: '2030-01-01T01:00:00.000Z',
      kind: 'BATCH',
      sort: 'first_seen:desc',
      limit: 10,
      new_only: true,
    });
    expect(
      listQueryOf(
        '/apps/3/stats/services?from=2030-01-01T00:00:00Z&to=2030-01-01T01:00:00Z&split=1',
      ),
    ).toEqual({
      from: '2030-01-01T00:00:00.000Z',
      to: '2030-01-01T01:00:00.000Z',
      kind: 'HTTP',
      limit: 100,
      split_version: true,
    });
    expect(() => listQueryOf('/apps/3/stats/sql')).toThrow(/absolute range/);
    expect(appIdOf('http://x/apps/42/stats/sql/7')).toBe(42);
    expect(() => appIdOf('/overview')).toThrow(/not an application route/);
  });

  it('builds the routes with an absolute range', () => {
    expect(statsPath(3, 'sql', '2030-01-01T00:00:00Z', '2030-01-01T01:00:00Z')).toBe(
      '/apps/3/stats/sql?from=2030-01-01T00%3A00%3A00Z&to=2030-01-01T01%3A00%3A00Z',
    );
    expect(statsDetailPath(3, 'errors', '18446744073709551615', 'a', 'b', { kind: 'ALL' })).toBe(
      '/apps/3/stats/errors/18446744073709551615?from=a&to=b&kind=ALL',
    );
  });

  it('compares the leading list key of composite row keys and squashes text', () => {
    expect(leadingId('123')).toBe('123');
    expect(leadingId('123:45')).toBe('123');
    expect(leadingId('abc')).toBe('abc');
    expect(squash(' SELECT  *\n FROM t ')).toBe('SELECT*FROMt');
  });

  it('row comparison: exact values, null is never 0, required columns and changes must be shown', () => {
    const shown: ShownRow[] = [
      {
        key: '1',
        values: { count: '10', p95_ms: '', error_rate: '0.5' },
        deltas: { count: '0.25', p95_ms: '' },
        texts: {},
      },
    ];
    const api = [
      {
        count: 10,
        p95_ms: null,
        error_rate: 0.5,
        delta: { count: 0.25, p95_ms: null, error_rate: null },
      },
    ];
    const fields = { required: ['count', 'p95_ms'], optional: ['error_rate', 'apdex'] };
    expect(rowMismatches(shown, api, fields, ['count', 'p95_ms'])).toEqual([]);
    expect(rowMismatches(shown, [{ ...api[0], p95_ms: 0 }], fields, ['count', 'p95_ms'])).toEqual([
      'row 0 (1): p95_ms shown null, API 0',
    ]);
    expect(rowMismatches(shown, api, { required: ['avg_ms'] }, [])).toContain(
      'row 0 (1): avg_ms not shown',
    );
    expect(rowMismatches(shown, api, fields, ['error_rate'])).toContain(
      'row 0 (1): delta error_rate not shown',
    );
    expect(rowMismatches([], api, fields)).toEqual(['rows: shown 0, API 1']);
  });
});
