// SPDX-License-Identifier: Apache-2.0
//
// required/admin.yaml is valid and covers the docs/05 §2.11 checklist (2.11-1 .. 2.11-10) and the
// docs/15 §9.10 screens it does not list (users, audit, about) one to one, in order, owned by WO-39
// with checks that exist in required/adminChecks.ts and route variables of manifestVariables()
// (docs/18 §13, DSN-18-10). The pure oracles of adminChecks.ts (token expiry badge, collection
// ranking, run suffix) are checked here too.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import type { SeedManifest } from '../stack/manifest';

import {
  ADMIN_CHECKS,
  expectedRanking,
  expiresSoon,
  runSuffix,
  TOKEN_EXPIRY_WARN_DAYS,
  type ApiCollection,
} from './adminChecks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'admin.yaml');

const SEED = {
  generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
  apps: [],
  primary_app_id: 3,
  secondary_app_id: 4,
  traffic: { app_id: 11 },
  windows: [],
  tx: {},
} as unknown as SeedManifest;

const DAY = 24 * 60 * 60_000;

describe('required/admin.yaml (docs/05 §2.11, docs/15 §9.10)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.11-1 .. 2.11-10 and the three §9.10 screens in order, owned by WO-39, running', () => {
    expect(spec.screen).toBe('admin');
    expect(spec.first_number).toBe(1);
    expect(spec.items.map((i) => i.id)).toEqual([
      '2.11-1',
      '2.11-2',
      '2.11-3',
      '2.11-4',
      '2.11-5',
      '2.11-6',
      '2.11-7',
      '2.11-8',
      '2.11-9',
      '2.11-10',
      '9.10-1',
      '9.10-2',
      '9.10-3',
    ]);
    expect(RUNNING_OWNERS.has('WO-39')).toBe(true);
    for (const item of spec.items) {
      expect(item.owner).toBe('WO-39');
      expect(item.req, item.id).toContain('F-U10');
      expect(item.route, item.id).toBeDefined();
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks.length, `${item.id} has a named check`).toBe(1);
      for (const check of checks) {
        expect(Object.keys(ADMIN_CHECKS), `${item.id}: check ${check}`).toContain(check);
      }
      expect(item.coverage, `${item.id} names the end-to-end test`).toMatch(
        /T-E2E-admin-2\d|T-API/,
      );
    }
    // Every check is used once.
    expect(
      spec.items.flatMap((i) => i.assert.flatMap((a) => ('check' in a ? [a.check] : []))).sort(),
    ).toEqual(Object.keys(ADMIN_CHECKS).sort());
  });

  it('opens each screen with the role the docs/16 §7.10 matrix gives it', () => {
    const roles = Object.fromEntries(spec.items.map((i) => [i.id, i.role]));
    expect(roles).toEqual({
      '2.11-1': 'operator',
      '2.11-2': 'operator',
      '2.11-3': 'admin',
      '2.11-4': 'admin',
      '2.11-5': 'admin',
      '2.11-6': 'admin',
      '2.11-7': 'operator',
      '2.11-8': 'operator',
      '2.11-9': 'admin',
      '2.11-10': 'viewer',
      '9.10-1': 'admin',
      '9.10-2': 'admin',
      '9.10-3': 'viewer',
    });
  });

  it('fills every route variable from the manifest', () => {
    const vars = manifestVariables(SEED);
    for (const item of spec.items) {
      for (const name of templateVariables(item.route ?? '')) {
        expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
      }
      expect(fillTemplate(item.route ?? '', vars), item.id).not.toContain('{');
    }
    expect(fillTemplate(spec.items[3]?.route ?? '', vars)).toBe('/admin/tokens?app=11');
  });

  it('test titles follow T-E2E-admin-<n> below the spec numbers (20 and up)', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-admin-1 2\.11-1 /);
    expect(testTitle(spec, spec.items.length - 1)).toMatch(/^T-E2E-admin-13 9\.10-3 /);
    expect(spec.items.length).toBeLessThan(20);
  });
});

describe('adminChecks oracles', () => {
  it('marks a live token that expires within 30 days, never an expired, revoked or endless one', () => {
    const now = Date.parse('2030-01-01T00:00:00Z');
    const at = (days: number) => new Date(now + days * DAY).toISOString();
    expect(TOKEN_EXPIRY_WARN_DAYS).toBe(30);
    expect(expiresSoon({ expires_at: at(30), state: 'active' }, now)).toBe(true);
    expect(expiresSoon({ expires_at: at(31), state: 'active' }, now)).toBe(false);
    expect(expiresSoon({ expires_at: at(1), state: 'rotating' }, now)).toBe(true);
    expect(expiresSoon({ expires_at: at(1), state: 'revoked' }, now)).toBe(false);
    expect(expiresSoon({ expires_at: at(-1), state: 'active' }, now)).toBe(false);
    expect(expiresSoon({ expires_at: null, state: 'active' }, now)).toBe(false);
  });

  it('ranks the collection health largest first, the clock offset by size, missing values last by name', () => {
    const row = (
      id: number,
      name: string,
      values: Partial<Pick<ApiCollection, 'drops' | 'lag_s' | 'skew_ms' | 'excluded'>>,
    ): ApiCollection =>
      ({
        instance_id: id,
        name,
        drops: null,
        lag_s: null,
        skew_ms: null,
        excluded: null,
        ...values,
      }) as ApiCollection;
    const rows = [
      row(1, 'b', { drops: 3, skew_ms: -900 }),
      row(2, 'a', { drops: 10, skew_ms: 100 }),
      row(3, 'd', {}),
      row(4, 'c', { drops: 3 }),
      row(5, 'a0', {}),
    ];
    expect(expectedRanking(rows, 'drops')).toEqual([2, 1, 4, 5, 3]);
    expect(expectedRanking(rows, 'skew_ms')).toEqual([1, 2, 5, 4, 3]);
  });

  it('names the run rows with the run id tail and a random part', () => {
    const suffix = runSuffix('20301231-235959-abcdef12');
    expect(suffix).toMatch(/^[a-z0-9]{6}[0-9a-f]{4}$/);
    expect(suffix.startsWith('cdef12')).toBe(true);
  });
});
