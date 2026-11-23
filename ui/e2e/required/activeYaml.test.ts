// SPDX-License-Identifier: Apache-2.0
//
// required/active.yaml is valid and covers the docs/05 §2.6 checklist (docs/04 WO-35, 2.6-1 ..
// 2.6-6) in order, with checks that exist in required/activeChecks.ts and route variables of
// manifestVariables(); the stack and thread dump actions (remote commands, WO-26 / WO-17) are
// fixme entries that name where they are covered until then (docs/18 §13, DSN-18-10). Also the
// pure parts of the checks: the typed stub snapshot and the band rule.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { parseActivePush } from '../../src/api/active';
import { bandOf, DEFAULT_THRESHOLDS } from '../../src/features/active/model';
import type { SeedManifest } from '../stack/manifest';

import {
  ACTIVE_CHECKS,
  bandFor,
  CUSTOM_LIMITS_MS,
  CUSTOM_THRESHOLDS,
  DEFAULT_LIMITS_MS,
  MAX_AS_OF_S,
  stubSnapshot,
} from './activeChecks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'active.yaml');

describe('required/active.yaml (docs/05 §2.6)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists the parts of 2.6-1 .. 2.6-6 in checklist order', () => {
    expect(spec.screen).toBe('active');
    expect(spec.source).toBe('docs/05 §2.6');
    expect(spec.items.map((item) => item.id)).toEqual([
      '2.6-1',
      '2.6-1',
      '2.6-1',
      '2.6-2',
      '2.6-3',
      '2.6-3',
      '2.6-3',
      '2.6-4',
      '2.6-4',
      '2.6-4',
      '2.6-5',
      '2.6-6',
    ]);
    for (const item of spec.items) {
      expect(item.req, item.id).toContain('F-U04');
    }
  });

  it('runs every WO-35 item with a route and a known check of activeChecks.ts', () => {
    expect(RUNNING_OWNERS.has('WO-35')).toBe(true);
    const own = spec.items.filter((item) => item.owner === 'WO-35');
    expect(own).toHaveLength(10);
    for (const item of own) {
      expect(item.route, item.id).toBeDefined();
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks.length, `${item.id} has a named check`).toBe(1);
      for (const check of checks) {
        expect(Object.keys(ACTIVE_CHECKS), `${item.id}: check ${check}`).toContain(check);
      }
      for (const variable of templateVariables(item.route ?? '')) {
        expect(variable, `${item.id}: route variable`).toBe('primary_app_id');
      }
    }
  });

  it('compares with the real WebSocket server where the push is the oracle; 2.6-6 drives the stub', () => {
    const byCheck = new Map(
      spec.items.flatMap((item) =>
        item.assert.flatMap((a) => ('check' in a ? [[a.check, item] as const] : [])),
      ),
    );
    for (const check of [
      'activeBars',
      'activeColumns',
      'activeRefresh',
      'activePause',
      'activeSummaryAndRuleLink',
    ]) {
      expect(byCheck.get(check)?.ws, check).toBe('server');
    }
    expect(byCheck.get('activeDisconnect')?.ws).toBeUndefined();
    // The rule link is a link for roles that may edit alert rules.
    expect(byCheck.get('activeSummaryAndRuleLink')?.role).toBe('operator');
  });

  it('keeps the stack and thread dump actions as fixme of WO-26 covered by T-E2E-active-27', () => {
    const later = spec.items.filter((item) => !RUNNING_OWNERS.has(item.owner));
    expect(later.map((item) => `${item.id} ${item.owner}`)).toEqual(['2.6-4 WO-26', '2.6-4 WO-26']);
    for (const item of later) {
      expect(item.coverage, item.id).toContain('T-E2E-active-27');
      expect(item.coverage, item.id).toContain('WO-17');
      expect(item.route, item.id).toBeUndefined();
    }
    expect(later[0]?.coverage).toContain('T-E2E-active-30');
    expect(later[1]?.coverage).toContain('WO-37');
  });

  it('manifestVariables() fills every route', () => {
    const seed = {
      generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
      apps: [],
      primary_app_id: 3,
      secondary_app_id: 4,
      traffic: { app_id: 11 },
      windows: [],
      tx: {},
    } as unknown as SeedManifest;
    const vars = manifestVariables(seed);
    for (const item of spec.items.filter((i) => i.route !== undefined)) {
      expect(fillTemplate(item.route ?? '', vars), item.id).toBe('/apps/3/active?kind=ALL');
    }
  });

  it('test titles follow T-E2E-active-<n> <05 id> <title>', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-active-1 2\.6-1 /);
    expect(testTitle(spec, 11)).toMatch(/^T-E2E-active-12 2\.6-6 /);
  });
});

describe('active checks: pure parts', () => {
  it('bands use the screen rule: at or above a limit is the next band', () => {
    for (const ms of [0, 999, 1000, 2999, 3000, 7999, 8000, 20_000]) {
      expect(bandFor(ms, DEFAULT_LIMITS_MS), String(ms)).toBe(bandOf(ms, DEFAULT_THRESHOLDS));
      expect(bandFor(ms, CUSTOM_LIMITS_MS), String(ms)).toBe(
        bandOf(ms, CUSTOM_THRESHOLDS.map(Number) as [number, number, number]),
      );
    }
  });

  it('the stub snapshot is a valid active push with consistent buckets and counts', () => {
    const data = stubSnapshot({
      snapshotAt: '2030-01-01T00:00:00.000Z',
      instances: [
        { id: 1, name: 'a', state: 'UP' },
        { id: 2, name: 'b', state: 'WARN' },
        { id: 3, name: 'c', state: 'STALE', truncated: true, count: 640 },
      ],
    });
    expect(parseActivePush(data), 'the UI accepts the stub push').not.toBeNull();
    expect(data.per_instance.map((p) => p.count)).toEqual([4, 3, 640]);
    for (const p of data.per_instance) {
      const own = data.items.filter((i) => i.instance_id === p.instance_id);
      expect(
        p.buckets.reduce((a, b) => a + b, 0),
        p.name ?? '',
      ).toBe(own.length);
      expect(p.max_elapsed_ms).toBe(Math.max(...own.map((i) => i.elapsed_ms)));
      for (const item of own) {
        expect(Date.parse(item.started_at) + item.elapsed_ms).toBe(
          Date.parse('2030-01-01T00:00:00Z'),
        );
      }
    }
    const elapsed = data.items.map((i) => i.elapsed_ms);
    expect(elapsed).toEqual([...elapsed].sort((a, b) => b - a));
    expect(new Set(data.items.map((i) => i.tx_id)).size).toBe(data.items.length);
    expect(new Set(data.items.map((i) => i.state))).toEqual(
      new Set(['SQL', 'HTTP', 'DBC', 'METHOD']),
    );
  });

  it('allows "as of" up to the agent period plus the push period plus rounding', () => {
    expect(MAX_AS_OF_S).toBe(11);
  });
});
