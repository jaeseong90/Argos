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
// The engine's CHECKS table imports Playwright; the names are read from its source instead.
const CHECK_NAMES = [
  ...readFileSync(join(DIR, 'checks.ts'), 'utf8').matchAll(/^const (\w+): Check = /gm),
].map((m) => m[1]);

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
  missing_optional: [],
  users: { admin: 'admin', operator: 'e2e-operator', viewer: 'e2e-viewer' },
};

describe('required-elements files', () => {
  const common = parseRequiredFile(readFileSync(COMMON, 'utf8'), COMMON);

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
      // Deep links 4 and 5: the first data screen completes them (C-168 (1)).
      'WO-33',
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
    expect(() =>
      parseRequiredFile(bad('  - id: 2.0-1\n    title: abc\n    owner: WO-33'), 'common.yaml'),
    ).toThrow(/coverage/);
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
