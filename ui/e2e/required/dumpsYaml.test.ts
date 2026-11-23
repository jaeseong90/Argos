// SPDX-License-Identifier: Apache-2.0
//
// required/dumps.yaml is valid and covers docs/05 §2.7-4 (commands tab) and §2.7-5 (thread dump
// viewer) one entry per bullet, so that T-E2E-dumps-1 and -2 carry them, with checks that exist in
// required/dumpsChecks.ts and route variables of manifestVariables() (docs/18 §13, DSN-18-10, docs/04
// WO-37). Also the pure oracle helpers of dumpsChecks.ts: what the viewer must show for a result
// (independent of the UI's model, cross-checked on the fixed dump), the rate slots, the stub list of
// more than 20 dumps and the masked environment dump.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { ADMIN_COMMANDS, OPERATOR_COMMANDS } from '../../src/api/commands';
import type { CommandResultView } from '../../src/api/commands';
import { buildDumpModel, MAX_DUMP_FRAMES } from '../../src/features/instances/dumps/dumpModel';
import type { SeedManifest } from '../stack/manifest';

import { CHECKS } from './checks';
import {
  COMMAND_BUTTONS,
  COMMANDS_PER_MINUTE,
  DUMP_GROUPS,
  DUMPS_CHECKS,
  expectedDump,
  fakeCommandId,
  FEED_THREAD_STATE,
  fixedDumpResult,
  fixedDumpView,
  groupOf,
  lockIdentity,
  envDumpOf,
  maskedEntries,
  MAX_FRAMES,
  olderDumps,
  rateState,
  RATE_WINDOW_MS,
  recentDoneDumps,
  RECENT_DUMPS,
  searchMatches,
  selfDiagnosisOf,
  transactionLinks,
  type CommandViewApi,
  type ThreadApi,
} from './dumpsChecks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'dumps.yaml');
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('required/dumps.yaml (docs/05 §2.7-4, §2.7-5)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists the commands tab and the dump viewer in checklist order', () => {
    expect(spec.screen).toBe('dumps');
    expect(spec.items.map((item) => item.id)).toEqual(['2.7-4', '2.7-5']);
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-dumps-1 2\.7-4 /);
    expect(testTitle(spec, 1)).toMatch(/^T-E2E-dumps-2 2\.7-5 /);
    for (const item of spec.items) {
      expect(item.req, item.id).toContain('F-U08');
    }
  });

  it('runs both items (WO-37) with a route and one known check of dumpsChecks.ts', () => {
    expect(RUNNING_OWNERS.has('WO-37')).toBe(true);
    const checks = spec.items.map((item) => {
      expect(item.owner, item.id).toBe('WO-37');
      expect(item.route, item.id).toBeDefined();
      for (const variable of templateVariables(item.route ?? '')) {
        expect(variable, `${item.id}: route variable`).toBe('traffic_app_id');
      }
      return item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
    });
    expect(checks).toEqual([['commandsTabElements'], ['dumpViewerElements']]);
    for (const check of checks.flat()) {
      expect(Object.keys(DUMPS_CHECKS)).toContain(check);
      expect(Object.keys(CHECKS), 'registered in checks.ts').toContain(check);
    }
  });

  it('asks as the administrator (five buttons, audit link) and as the operator (own dump)', () => {
    expect(spec.items.map((item) => item.role)).toEqual(['admin', 'operator']);
    expect(spec.items[0]?.req).toEqual(expect.arrayContaining(['SEC-21', 'SEC-50']));
    expect(spec.items[1]?.req).toContain('SEC-60');
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
    for (const item of spec.items) {
      expect(fillTemplate(item.route ?? '', vars), item.id).toBe('/apps/11/instances');
    }
  });
});

describe('dumps checks: pure parts', () => {
  it('groups by the Thread.State word; NEW, TERMINATED and none are OTHER', () => {
    expect(groupOf('RUNNABLE')).toBe('RUNNABLE');
    expect(groupOf('TIMED_WAITING (sleeping)')).toBe('TIMED_WAITING');
    expect(groupOf(' waiting (parking)')).toBe('WAITING');
    expect(groupOf('BLOCKED (on object monitor)')).toBe('BLOCKED');
    expect(groupOf('NEW')).toBe('OTHER');
    expect(groupOf('TERMINATED')).toBe('OTHER');
    expect(groupOf(null)).toBe('OTHER');
    expect(groupOf('')).toBe('OTHER');
  });

  it('identifies a lock by the hex of <0x…> or by its text', () => {
    expect(lockIdentity('<0x000000076AB0C0D8> (a java.lang.Object)')).toBe('0x000000076ab0c0d8');
    expect(lockIdentity(' com.example.shop.Settlement@6d1e7a10 ')).toBe(
      'com.example.shop.Settlement@6d1e7a10',
    );
  });

  it('the oracle of the fixed dump agrees with the viewer model of the UI', () => {
    for (const truncated of [false, true]) {
      const result = fixedDumpResult(7, fakeCommandId(1), truncated);
      const want = expectedDump(result);
      const model = buildDumpModel(result as unknown as CommandResultView);
      expect(want.total).toBe(model.total);
      expect(want.counts).toEqual(model.counts);
      expect(
        want.sections.map((s) => ({ group: s.group, count: s.count, sizes: [...s.sizes] })),
      ).toEqual(
        model.sections.map((s) => ({
          group: s.group,
          count: s.count,
          sizes: s.stacks.map((g) => g.threads.length).sort((a, b) => b - a),
        })),
      );
      expect(want.locks.map((l) => ({ lock: l.lock, waiters: l.waiters }))).toEqual(
        model.locks
          .map((l) => ({ lock: l.lock, waiters: l.waiters.length }))
          .sort((a, b) => a.lock.localeCompare(b.lock)),
      );
      expect(want.virtual).toBe(model.virtualCount);
      expect(want.deadlocks).toBe(model.deadlocks.length);
      expect(want.txIds).toEqual(model.txThreads.map((th) => th.txId));
      expect(want.truncated || want.depthCut).toBe(model.truncated);
      expect(want.truncated).toBe(truncated);
    }
  });

  it('the fixed dump covers every element the viewer shows', () => {
    const want = expectedDump(fixedDumpResult(7, fakeCommandId(1), false));
    expect(want.sections.map((s) => s.group)).toEqual([...DUMP_GROUPS]);
    expect(Math.max(...want.sections.flatMap((s) => s.sizes))).toBe(6);
    expect(want.deadlocks).toBe(1);
    expect(want.deadlockNames).toEqual([['order-settle-worker', 'inventory-sync-worker']]);
    expect(want.virtual).toBe(2);
    expect(want.txIds).toEqual(['4611686018427387905']);
    expect(want.locks.filter((l) => l.held)).toHaveLength(2);
    expect(transactionLinks(7, want.txIds)).toEqual(['/apps/7/transactions/4611686018427387905']);
  });

  it('counts a 64-frame stack as cut at the depth limit', () => {
    expect(MAX_FRAMES).toBe(MAX_DUMP_FRAMES);
    const deep: ThreadApi = {
      id: 1,
      name: 'deep',
      state: 'RUNNABLE',
      virtual: false,
      locks_held: [],
      waiting_on: null,
      frames: Array.from({ length: MAX_FRAMES }, (_, i) => `a.B.c(B.java:${String(i)})`),
      tx_id: null,
    };
    const want = expectedDump({ threads: [deep], deadlocks: [], truncated: false });
    expect(want.depthCut).toBe(true);
    expect(want.truncated).toBe(false);
  });

  it('searches thread names and frames (class and method) in any case', () => {
    const threads = fixedDumpResult(7, fakeCommandId(1), false).threads ?? [];
    expect(searchMatches(threads, 'linkedblockingqueue.take')).toBe(6);
    expect(searchMatches(threads, 'ORDER-SETTLE')).toBe(1);
    expect(searchMatches(threads, 'OrderService.find')).toBe(1);
    expect(searchMatches(threads, 'no-such-thread')).toBe(0);
  });

  it('keeps the last 20 done thread dumps of a history, newest first', () => {
    const base = fixedDumpView(7, fakeCommandId(0), 'u1', false);
    const older = olderDumps(base, 25);
    const failed: CommandViewApi = { ...base, command_id: fakeCommandId(99), status: 'failed' };
    const stack: CommandViewApi = { ...base, command_id: fakeCommandId(98), type: 'thread_stack' };
    const list = [base, failed, stack, ...older];
    const ids = recentDoneDumps(list);
    expect(ids).toHaveLength(RECENT_DUMPS);
    expect(ids[0]).toBe(base.command_id);
    expect(ids).not.toContain(failed.command_id);
    expect(ids).not.toContain(stack.command_id);
    expect(ids.slice(1)).toEqual(older.slice(0, RECENT_DUMPS - 1).map((c) => c.command_id));
    for (const c of older) {
      expect(c.command_id).toMatch(UUID_V4);
      expect(Date.parse(c.requested_at)).toBeLessThan(Date.parse(base.requested_at));
    }
  });

  it('a slot needs no running command and room in the last minute', () => {
    const now = Date.parse('2030-01-01T09:10:00Z');
    const view = (seconds: number, status = 'done'): CommandViewApi => ({
      ...fixedDumpView(7, fakeCommandId(seconds), 'u1', false),
      status,
      requested_at: new Date(now - seconds * 1000).toISOString(),
    });
    expect(rateState([view(5), view(30), view(70)], now)).toEqual({
      recent: 2,
      oldestRecentMs: now - 30_000,
      running: false,
    });
    expect(rateState([view(2, 'sent')], now).running).toBe(true);
    expect(RATE_WINDOW_MS).toBeGreaterThan(60_000);
    expect(COMMANDS_PER_MINUTE).toBe(10);
  });

  it('the role buttons are the UI command lists (SEC-21) and the feed states cover every active state', () => {
    expect(COMMAND_BUTTONS.viewer).toEqual([]);
    expect(COMMAND_BUTTONS.operator).toEqual([...OPERATOR_COMMANDS]);
    expect(COMMAND_BUTTONS.admin).toEqual([...OPERATOR_COMMANDS, ...ADMIN_COMMANDS]);
    expect(Object.keys(FEED_THREAD_STATE).sort()).toEqual([
      'ASYNC',
      'DBC',
      'HTTP',
      'METHOD',
      'SQL',
    ]);
  });

  it('counts the masked values of an environment dump and reads a self diagnosis', () => {
    const masked = '****(len=27)';
    // The agent's ENV_DUMP text (C-234 (1)).
    const env = [
      '# env (4)',
      `ARGOS_TOKEN=${masked}`,
      `DB_PASSWORD=${masked}`,
      'JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64',
      'PATH=/bin',
      '',
      '# properties (2)',
      'java.version=21',
      `spring.datasource.password=${masked}`,
      '',
      '# args (2)',
      '-Xmx512m',
      `-Dspring.datasource.password=${masked}`,
      '',
    ].join('\n');
    expect(maskedEntries(env, masked)).toEqual({ env: 2, properties: 1 });
    expect(envDumpOf(env)).toEqual({
      env: {
        ARGOS_TOKEN: masked,
        DB_PASSWORD: masked,
        JAVA_HOME: '/usr/lib/jvm/java-21-openjdk-amd64',
        PATH: '/bin',
      },
      properties: { 'java.version': '21', 'spring.datasource.password': masked },
      args: ['-Xmx512m', `-Dspring.datasource.password=${masked}`],
    });
    // The agent's SELF_DIAGNOSTICS layout: effective_config is a map (C-234 (1)).
    const diag = JSON.stringify({
      format: 1,
      modules: [
        { id: 0, name: 'servlet', state: 'ENABLED', errors: 0 },
        { id: 4, name: 'executor', state: 'DISABLED_BY_CONFIG', errors: 0, reason: 'cfg' },
      ],
      effective_config: { 'server.tls': { value: 'false', source: 'env' } },
    });
    expect(selfDiagnosisOf(diag)).toEqual({
      modules: [
        { name: 'servlet', state: 'ENABLED', errors: 0, reason: null },
        { name: 'executor', state: 'DISABLED_BY_CONFIG', errors: 0, reason: 'cfg' },
      ],
      config: [{ key: 'server.tls', value: 'false', source: 'env' }],
    });
  });
});
