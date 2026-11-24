// SPDX-License-Identifier: Apache-2.0
//
// required/search.yaml is valid and covers docs/15 §9.12 (kind shown while typing, one id match
// opens at once, text results = GET /search, user and request id searches need a range of at most
// 24 h) in order, owned by WO-39, numbered from 30 (specs/search.spec.ts uses 1 .. 8), with checks
// that exist in required/searchChecks.ts and route variables of manifestVariables().
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import type { SeedManifest } from '../stack/manifest';

import { fillTemplate, parseRequiredFile, templateVariables, testTitle } from './schema';
import { matchKey, SEARCH_CHECKS } from './searchChecks';
import { manifestVariables } from './variables';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'search.yaml');

const SEED = {
  generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
  apps: [],
  primary_app_id: 3,
  secondary_app_id: 4,
  traffic: { app_id: 11 },
  windows: [],
  tx: {
    captured_sql_stack: {
      tx_id: '123456789012345678',
      app_id: 3,
      ts: '2030-01-01T00:20:00Z',
      kind: 'HTTP',
      service: 'GET /orders',
      facts: {},
    },
  },
} as unknown as SeedManifest;

describe('required/search.yaml (docs/15 §9.12)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 9.12-1 .. 9.12-4 in order, owned by WO-39, each with a known check', () => {
    expect(spec.screen).toBe('search');
    expect(spec.source).toBe('docs/15 §9.12');
    expect(spec.items.map((i) => i.id)).toEqual(['9.12-1', '9.12-2', '9.12-3', '9.12-4']);
    for (const item of spec.items) {
      expect(item.owner).toBe('WO-39');
      expect(item.role).toBe('viewer');
      expect(item.req).toContain('F-U14');
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks.length, item.id).toBe(1);
      for (const check of checks) {
        expect(Object.keys(SEARCH_CHECKS), `${item.id}: ${check}`).toContain(check);
      }
    }
  });

  it('fills the route variables (the transaction id of the seed) from the manifest', () => {
    const vars = manifestVariables(SEED);
    for (const item of spec.items) {
      for (const name of templateVariables(item.route ?? '')) {
        expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
      }
    }
    expect(fillTemplate(spec.items[1]?.route ?? '', vars)).toBe('/search?q=123456789012345678');
    expect(spec.items[3]?.route).toContain('range=7d');
  });

  it('numbers its tests from 30 (the spec owns T-E2E-search-1 .. 8)', () => {
    expect(spec.first_number).toBe(30);
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-search-30 9\.12-1 /);
    expect(testTitle(spec, 3)).toMatch(/^T-E2E-search-33 9\.12-4 /);
  });

  it('keys a match as the search screen keys its rows', () => {
    expect(
      matchKey({
        type: 'service',
        label: 'GET /orders' as never,
        score: 1,
        target: {
          route: '/apps/:appId/services/:serviceId',
          params: { appId: '3', serviceId: '7' },
        },
      } as never),
    ).toBe('service:/apps/:appId/services/:serviceId:{"appId":"3","serviceId":"7"}');
  });
});
