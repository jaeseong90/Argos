// SPDX-License-Identifier: Apache-2.0
//
// required/trace.yaml is valid and covers the docs/05 §2.8 checklist (docs/04 WO-34 "05 §2.8
// 체크표" 2.8-1 .. 2.8-5) one to one, in order, with checks that exist in required/traceChecks.ts
// and route variables of manifestVariables() in required/variables.ts, which the shared engine fills
// (docs/18 §13, DSN-18-10).
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import type { SeedManifest } from '../stack/manifest';

import { fillTemplate, parseRequiredFile, templateVariables, testTitle } from './schema';
import { TRACE_CHECKS } from './traceChecks';
import { manifestVariables } from './variables';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'trace.yaml');

/** Variables of manifestVariables() that trace routes use (with the trace id of the category). */
const KNOWN_VARIABLES = new Set([
  'tx.trace_root_two_apps.trace_id',
  'tx.trace_root_two_apps.ts',
  'tx.trace_root_two_apps.tx_id',
  'tx.trace_root_two_apps.app_id',
]);

describe('required/trace.yaml (docs/05 §2.8)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.8-1 .. 2.8-5 in order, owned by WO-34, each with a route and a known check', () => {
    expect(spec.screen).toBe('trace');
    expect(spec.source).toBe('docs/05 §2.8');
    expect(spec.items.map((item) => item.id)).toEqual([
      '2.8-1',
      '2.8-2',
      '2.8-3',
      '2.8-4',
      '2.8-5',
    ]);
    for (const item of spec.items) {
      expect(item.owner).toBe('WO-34');
      expect(item.route).toBeDefined();
      expect(item.req).toContain('F-U03');
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks.length, `${item.id} has a named check`).toBeGreaterThan(0);
      for (const check of checks) {
        expect(Object.keys(TRACE_CHECKS), `${item.id}: check ${check}`).toContain(check);
      }
      for (const variable of templateVariables(item.route ?? '')) {
        expect(KNOWN_VARIABLES.has(variable), `${item.id}: variable {${variable}}`).toBe(true);
      }
    }
  });

  it('manifestVariables() fills every route variable, the trace id included', () => {
    // As much of the seed manifest as manifestVariables() reads.
    const seed = {
      generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
      apps: [],
      primary_app_id: 3,
      secondary_app_id: 4,
      traffic: { app_id: 11 },
      windows: [],
      tx: {
        trace_root_two_apps: {
          tx_id: '5',
          app_id: 3,
          ts: '2030-01-01T00:20:00Z',
          trace_id: '0123456789abcdef0123456789abcdef',
          apps: [3, 4],
        },
      },
    } as unknown as SeedManifest;
    const vars = manifestVariables(seed);
    expect(vars['tx.trace_root_two_apps.trace_id']).toBe('0123456789abcdef0123456789abcdef');
    for (const item of spec.items) {
      expect(fillTemplate(item.route ?? '', vars), item.id).not.toContain('{');
    }
  });

  it('test titles follow T-E2E-trace-<n> <05 id> <title>', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-trace-1 2\.8-1 /);
    expect(testTitle(spec, 4)).toMatch(/^T-E2E-trace-5 2\.8-5 /);
  });
});
