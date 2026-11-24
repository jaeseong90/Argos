// SPDX-License-Identifier: Apache-2.0
//
// The required-elements files are valid and cover their checklist (docs/18 §13): required/common.yaml
// has every item of docs/05 §2.0 (2.0-1 .. 2.0-10), each check it names exists, and the ownership
// split of ADR-0010 C-168 (1) holds. The schema rejects malformed entries.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import type { SeedManifest } from '../stack/manifest';

import { fillTemplate, parseRequiredFile, templateVariables, testTitle } from './schema';
import { manifestVariables } from './variables';

const DIR = dirname(fileURLToPath(import.meta.url));
const COMMON = join(DIR, 'common.yaml');
// The engine's CHECKS table imports Playwright; the names are read from its source instead (the
// XLog checks of WO-33 live in xlogChecks.ts, the dashboard checks of WO-32 in dashboardChecks.ts,
// both spread into CHECKS).
const CHECK_NAMES = [
  ...readFileSync(join(DIR, 'checks.ts'), 'utf8').matchAll(/^const (\w+): Check = /gm),
  ...readFileSync(join(DIR, 'xlogChecks.ts'), 'utf8').matchAll(/^const (\w+): XLogCheck = /gm),
  ...readFileSync(join(DIR, 'dashboardChecks.ts'), 'utf8').matchAll(
    /^const (\w+): DashboardCheck = /gm,
  ),
].map((m) => m[1]);
const XLOG = join(DIR, 'xlog.yaml');

const MANIFEST: SeedManifest = {
  manifest_version: 1,
  run_id: 'e2e-test',
  created_at: '2030-01-01T00:00:00Z',
  stack_url: 'http://127.0.0.1:40000',
  generator: {
    profile: 'tiny',
    seed: 42,
    placement: 'recent',
    loaded_at: '2030-01-01T00:00:00Z',
    range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' },
    rows: {},
  },
  traffic: {
    app_id: 11,
    range: { start: '2030-01-01T00:44:00Z', end: '2030-01-01T00:50:00Z' },
    seed_requests: 10,
    xss_service: null,
  },
  apps: [
    {
      app_id: 3,
      name: 'order-api',
      env: 'prod',
      source: 'generator',
      instances: [{ instance_id: 12, name: 'order-api-1' }],
    },
    { app_id: 4, name: 'pay-api', env: 'prod', source: 'generator', instances: [] },
  ],
  envs: ['prod'],
  primary_app_id: 3,
  secondary_app_id: 4,
  windows: [],
  tx: {
    captured_sql_stack: {
      tx_id: '1',
      app_id: 3,
      ts: '2030-01-01T00:10:00Z',
      kind: 'HTTP',
      service: null,
      facts: {},
    },
    error: {
      tx_id: '2',
      app_id: 3,
      ts: '2030-01-01T00:10:00Z',
      kind: 'HTTP',
      service: null,
      facts: {},
    },
    sampled_out: {
      tx_id: '3',
      app_id: 3,
      ts: '2030-01-01T00:10:00Z',
      kind: 'HTTP',
      service: null,
      facts: {},
    },
    batch: {
      tx_id: '4',
      app_id: 3,
      ts: '2030-01-01T00:10:00Z',
      kind: 'BATCH',
      service: null,
      facts: {},
    },
    trace_root_two_apps: {
      tx_id: '5',
      app_id: 3,
      ts: '2030-01-01T00:10:00Z',
      kind: 'HTTP',
      service: null,
      facts: {},
      trace_id: 'ab',
      apps: [3, 4],
    },
    xss: null,
  },
  events: [],
  outage: null,
  missing_optional: [],
  users: { admin: 'admin', operator: 'e2e-operator', viewer: 'e2e-viewer' },
};

describe('required-elements files', () => {
  const common = parseRequiredFile(readFileSync(COMMON, 'utf8'), COMMON);
  const xlog = parseRequiredFile(readFileSync(XLOG, 'utf8'), XLOG);

  it('required/xlog.yaml covers every item of docs/05 §2.1 with known checks and variables', () => {
    expect([...new Set(xlog.items.map((i) => i.id))]).toEqual([
      '2.1-1',
      '2.1-2',
      '2.1-3',
      '2.1-4',
      '2.1-5',
      '2.1-6',
      '2.1-7',
      '2.1-8',
      '2.1-9',
      '2.1-10',
    ]);
    expect(testTitle(xlog, 0)).toMatch(/^T-E2E-xlog-1 2\.1-1 /);
    // Every item runs: the outage shading too, on the collection outage the stack setup makes
    // since WO-22 (ADR-0010 C-205 (2), check xlogOutageShading).
    expect(xlog.items.filter((i) => i.owner !== 'WO-33').map((i) => `${i.id} ${i.owner}`)).toEqual(
      [],
    );
    expect(
      xlog.items.flatMap((i) => i.assert.flatMap((a) => ('check' in a ? [a.check] : []))),
    ).toContain('xlogOutageShading');
    const vars = manifestVariables(MANIFEST);
    for (const item of xlog.items) {
      for (const a of item.assert) {
        if ('check' in a) {
          expect(CHECK_NAMES, `${item.id} check ${a.check}`).toContain(a.check);
        }
      }
      if (item.route !== undefined) {
        expect(fillTemplate(item.route, vars)).not.toContain('{');
      }
    }
  });

  it('required/common.yaml covers every item of docs/05 §2.0', () => {
    const ids = new Set(common.items.map((i) => i.id));
    expect([...ids]).toEqual([
      '2.0-1',
      '2.0-2',
      '2.0-3',
      '2.0-4',
      '2.0-5',
      '2.0-6',
      '2.0-7',
      '2.0-8',
      '2.0-9',
      '2.0-10',
    ]);
    expect(testTitle(common, 0)).toMatch(/^T-E2E-common-1 2\.0-1 /);
  });

  it('keeps the ownership split of ADR-0010 C-168 (1)', () => {
    const owners = common.items.map((i) => `${i.id} ${i.owner}`);
    expect(owners).toContain('2.0-4 WO-33');
    expect(owners).toContain('2.0-5 WO-32');
    expect(common.items.filter((i) => i.id === '2.0-7').map((i) => i.owner)).toEqual([
      'WO-30',
      'WO-30',
      'WO-30',
      // Deep link 4: the first data screen (C-168 (1)), then the alert screens add the alert
      // transition markers to it (ADR-0010 C-226 (8), WO-38). Deep link 5: WO-33 (C-168 (1)).
      'WO-38',
      'WO-33',
    ]);
    // Every item of the checklist has at least one entry WO-30 runs, except 2.0-4 and 2.0-5.
    for (const id of ['2.0-1', '2.0-2', '2.0-3', '2.0-6', '2.0-7', '2.0-8', '2.0-9', '2.0-10']) {
      expect(
        common.items.some((i) => i.id === id && i.owner === 'WO-30'),
        id,
      ).toBe(true);
    }
  });

  it('names only existing checks and known manifest variables', () => {
    const vars = manifestVariables(MANIFEST);
    for (const item of common.items) {
      for (const a of item.assert) {
        if ('check' in a) {
          expect(CHECK_NAMES, `${item.id} check ${a.check}`).toContain(a.check);
        }
      }
      if (item.route !== undefined) {
        for (const name of templateVariables(item.route)) {
          expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
        }
        expect(fillTemplate(item.route, vars)).not.toContain('{');
      }
    }
  });

  it('rejects malformed entries', () => {
    const bad = (items: string): string => `screen: common\ntitle: x\nsource: y\nitems:\n${items}`;
    expect(() => parseRequiredFile(bad('  - id: 2.0\n    title: abc'), 'common.yaml')).toThrow(
      /item id/,
    );
    expect(() => parseRequiredFile(bad('  - id: 2.0-1\n    title: abc'), 'common.yaml')).toThrow(
      /needs a route/,
    );
    // An owner whose items do not run yet (not in RUNNING_OWNERS) must name the coverage.
    expect(() =>
      parseRequiredFile(bad('  - id: 2.0-1\n    title: abc\n    owner: WO-60'), 'common.yaml'),
    ).toThrow(/coverage/);
    // An owner whose items run (WO-33 since its XLog screen) needs a route and an assertion.
    expect(() =>
      parseRequiredFile(bad('  - id: 2.0-1\n    title: abc\n    owner: WO-33'), 'common.yaml'),
    ).toThrow(/an item owned by WO-33 needs a route/);
    expect(() =>
      parseRequiredFile(
        bad(
          '  - id: 2.0-1\n    title: abc\n    route: /x\n    assert:\n      - visible: Not_Kebab',
        ),
        'common.yaml',
      ),
    ).toThrow(/kebab/);
    expect(() =>
      parseRequiredFile(
        bad('  - id: 2.0-1\n    title: abc\n    route: /x\n    assert:\n      - visible: a'),
        'other.yaml',
      ),
    ).toThrow(/does not match/);
    expect(() => fillTemplate('/apps/{nope}', {})).toThrow(/unknown variable \{nope\}/);
  });
});
