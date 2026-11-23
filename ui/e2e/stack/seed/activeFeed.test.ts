// SPDX-License-Identifier: Apache-2.0
//
// The WO-35 active feed seed (e2e/stack/seed/activeFeed.ts): the feed's command line, the app and
// token SQL (traffic.ts tokenSql generalized by app, the traffic app's SQL unchanged), the ready
// file schema and the scenario helpers the specs compare the screen with.
import { describe, expect, it } from 'vitest';

import type { RunState } from '../runState';

import {
  ACTIVE_FEED_APP,
  ACTIVE_FEED_TOKEN_FILE,
  ActiveFeedReady,
  activeFeedArgs,
  activeFeedProblem,
  elapsedBand,
  expectedBuckets,
  expectedElapsedMs,
  feedJarStale,
  feedLogFile,
  feedTokenFile,
  scenarioRows,
} from './activeFeed';
import { tokenSql } from './traffic';

const HASH = 'a'.repeat(64);
const PREFIX = 'argos_AbCd-_12';
const START = Date.parse('2026-11-23T10:00:00.000Z');

/** A ready file as ActiveFeedScenario.toJson writes it (two instances, abbreviated slots). */
const READY = {
  app: 'e2e-active',
  env: 'e2e',
  feed_start_ms: START,
  interval_ms: 5000,
  instances: [
    {
      name: 'e2e-active-1',
      instance_id: 11,
      user_id_mode: 'plain',
      ip_mode: 'full',
      slots: [
        {
          slot: 0,
          timing: 'fixed',
          start_ms: START - 20_000,
          elapsed_ms: null,
          kind: 'BATCH',
          service: 'nightly-settlement',
          state: 'SQL',
          state_detail: 'UPDATE settlement s SET s.status = ? WHERE s.batch_id = ?',
          thread: 'batch-worker-#',
          virtual_thread: false,
          user_id: null,
          remote_ip: null,
        },
        {
          slot: 3,
          timing: 'fresh',
          start_ms: null,
          elapsed_ms: 5000,
          kind: 'HTTP',
          service: 'POST /api/orders',
          state: 'DBC',
          state_detail: 'jdbc:postgresql://db.internal:5432/shop',
          thread: 'http-nio-8080-exec-#',
          virtual_thread: false,
          user_id: 'alice',
          remote_ip: '10.1.2.4',
        },
        {
          slot: 4,
          timing: 'fresh',
          start_ms: null,
          elapsed_ms: 2000,
          kind: 'HTTP',
          service: 'GET /api/orders/{id}',
          state: 'SQL',
          state_detail:
            "SELECT r.id FROM report r WHERE r.title = '<script>window.__xss=1</script>ARGOS_SEED'",
          thread: 'http-nio-8080-exec-#',
          virtual_thread: false,
          user_id: 'alice',
          remote_ip: '10.1.2.5',
        },
        {
          slot: 5,
          timing: 'fresh',
          start_ms: null,
          elapsed_ms: 300,
          kind: 'HTTP',
          service: 'GET /api/products',
          state: 'METHOD',
          state_detail: null,
          thread: 'virtual-#',
          virtual_thread: true,
          user_id: 'carol',
          remote_ip: '10.1.2.6',
        },
      ],
    },
    {
      name: 'e2e-active-2',
      instance_id: 12,
      user_id_mode: 'off',
      ip_mode: 'off',
      slots: [
        {
          slot: 0,
          timing: 'fixed',
          start_ms: START - 10_000,
          elapsed_ms: null,
          kind: 'HTTP',
          service: 'GET /api/orders/{id}',
          state: 'SQL',
          state_detail: 'SELECT o.id, o.total FROM orders o WHERE o.id = ?',
          thread: 'http-nio-8080-exec-#',
          virtual_thread: false,
          user_id: null,
          remote_ip: null,
        },
      ],
    },
  ],
};

function runState(overrides: Partial<RunState> = {}): RunState {
  return {
    run_id: 'run-1',
    created_at: '2026-11-23T10:00:00Z',
    owner_pid: 1,
    reused: false,
    stack_url: 'http://127.0.0.1:41000',
    management_url: null,
    grpc_port: 41001,
    clickhouse_url: null,
    postgres_jdbc_url: null,
    containers: {
      network: 'argos-e2e-run-1',
      postgres: 'argos-e2e-run-1-pg',
      clickhouse: 'argos-e2e-run-1-ch',
      server: 'argos-e2e-run-1-server',
    },
    csp_violations_at_start: null,
    timings: {},
    ...overrides,
  };
}

describe('activeFeedArgs', () => {
  it('runs the jar against the plaintext collector with the token file, instances and 5 s interval', () => {
    expect(
      activeFeedArgs({
        jar: '/repo/server/build/active-feed/active-feed.jar',
        grpcPort: 41001,
        tokenFile: '/run/secrets/active-feed.token',
        readyFile: '/run/active-feed/ready.json',
      }),
    ).toEqual([
      '-Xmx256m',
      '-jar',
      '/repo/server/build/active-feed/active-feed.jar',
      '--target',
      '127.0.0.1:41001',
      '--token-file',
      '/run/secrets/active-feed.token',
      '--instances',
      'e2e-active-1,e2e-active-2',
      '--ready-file',
      '/run/active-feed/ready.json',
      '--duration',
      '1800s',
      '--interval',
      '5s',
      '--app',
      'e2e-active',
      '--env',
      'e2e',
    ]);
  });

  it('takes other instances, duration and interval and rejects invalid values', () => {
    const base = { jar: 'j', grpcPort: 9100, tokenFile: 't', readyFile: 'r' };
    const args = activeFeedArgs({ ...base, instances: ['a'], durationS: 60, intervalS: 2 });
    expect(args.slice(args.indexOf('--instances'), args.indexOf('--instances') + 2)).toEqual([
      '--instances',
      'a',
    ]);
    expect(args).toContain('60s');
    expect(args).toContain('2s');
    expect(() => activeFeedArgs({ ...base, grpcPort: 0 })).toThrow(/gRPC port/);
    expect(() => activeFeedArgs({ ...base, instances: [] })).toThrow(/instance/);
    expect(() => activeFeedArgs({ ...base, instances: ['a b'] })).toThrow(/instance/);
    expect(() => activeFeedArgs({ ...base, durationS: 0 })).toThrow(/duration/);
    expect(() => activeFeedArgs({ ...base, intervalS: 3601 })).toThrow(/interval/);
  });

  it('never carries a token value, only the token file', () => {
    const args = activeFeedArgs({ jar: 'j', grpcPort: 9100, tokenFile: 'tf', readyFile: 'r' });
    expect(args.join(' ')).not.toMatch(/argos_[A-Za-z0-9_-]{20,}/);
  });

  it('adds --commands only when asked (WO-37) and takes another app', () => {
    const base = { jar: 'j', grpcPort: 9100, tokenFile: 't', readyFile: 'r' };
    expect(activeFeedArgs(base)).not.toContain('--commands');
    expect(activeFeedArgs({ ...base, commands: false })).not.toContain('--commands');
    const args = activeFeedArgs({
      ...base,
      commands: true,
      app: { name: 'e2e-cmd', env: 'e2e' },
      instances: ['e2e-cmd-1', 'e2e-cmd-2'],
    });
    expect(args.slice(-5)).toEqual(['--app', 'e2e-cmd', '--env', 'e2e', '--commands']);
    expect(() => activeFeedArgs({ ...base, app: { name: 'a b', env: 'e2e' } })).toThrow(/app/);
    expect(() => activeFeedArgs({ ...base, app: { name: 'a', env: "e';--" } })).toThrow(/app/);
  });
});

describe('feedJarStale', () => {
  it('rebuilds a jar older than any fixture source (a WO-35 jar has no --commands)', () => {
    expect(feedJarStale(1000, [900, 999, 1000])).toBe(false);
    expect(feedJarStale(1000, [900, 1001])).toBe(true);
    expect(feedJarStale(1000, [])).toBe(false);
  });
});

describe('feed token and log files', () => {
  it('keeps the WO-35 names for e2e-active and gives every other app its own files', () => {
    expect(feedTokenFile(ACTIVE_FEED_APP)).toBe(ACTIVE_FEED_TOKEN_FILE);
    expect(feedLogFile(ACTIVE_FEED_APP)).toBe('active-feed.log');
    expect(feedTokenFile({ name: 'e2e-cmd', env: 'e2e' })).toBe('active-feed-e2e-cmd-e2e.token');
    expect(feedLogFile({ name: 'e2e-cmd', env: 'e2e' })).toBe('active-feed-e2e-cmd.log');
  });
});

describe('tokenSql', () => {
  it('keeps the traffic app SQL by default', () => {
    expect(tokenSql(HASH, PREFIX)).toBe(
      [
        "INSERT INTO argos.apps (name, env) VALUES ('e2e-shop', 'e2e') ON CONFLICT (name, env) DO NOTHING;",
        'INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, expires_at, grace_until, revoked_at, status)',
        `  SELECT gen_random_uuid(), id, '${HASH}', '${PREFIX}', NULL, NULL, NULL, 'ACTIVE'`,
        "  FROM argos.apps WHERE name = 'e2e-shop' AND env = 'e2e';",
        '',
      ].join('\n'),
    );
  });

  it('creates the active feed app idempotently and its ACTIVE token', () => {
    const sql = tokenSql(HASH, PREFIX, ACTIVE_FEED_APP);
    expect(sql).toContain(
      "INSERT INTO argos.apps (name, env) VALUES ('e2e-active', 'e2e') ON CONFLICT (name, env) DO NOTHING;",
    );
    expect(sql).toContain("FROM argos.apps WHERE name = 'e2e-active' AND env = 'e2e';");
    expect(sql).toContain("'ACTIVE'");
    expect(sql).not.toContain('e2e-shop');
  });

  it('rejects app names that are not plain identifiers', () => {
    expect(() =>
      tokenSql(HASH, PREFIX, { name: "x'; DROP TABLE argos.apps; --", env: 'e2e' }),
    ).toThrow(/app name/);
    expect(() => tokenSql(HASH, PREFIX, { name: 'ok', env: 'e 2 e' })).toThrow(/app name/);
    expect(() => tokenSql('xyz', PREFIX, ACTIVE_FEED_APP)).toThrow(/token hash/);
  });
});

describe('ready file and scenario', () => {
  const ready = ActiveFeedReady.parse(READY);
  const rows = scenarioRows(ready);

  it('flattens the instances into rows with their id and modes', () => {
    expect(rows).toHaveLength(5);
    expect(rows[0]).toMatchObject({
      instance: 'e2e-active-1',
      instance_id: 11,
      user_id_mode: 'plain',
      ip_mode: 'full',
      slot: 0,
      kind: 'BATCH',
    });
    expect(rows[4]).toMatchObject({
      instance: 'e2e-active-2',
      user_id_mode: 'off',
      ip_mode: 'off',
    });
  });

  it('reads a WO-35 ready file without commands as commands null (absent or null)', () => {
    expect(ready.commands ?? null).toBeNull();
    expect(ActiveFeedReady.parse({ ...READY, commands: null }).commands).toBeNull();
  });

  it('reads the commands block of a --commands feed and rejects an unmasked secret form', () => {
    const commands = {
      enabled: true,
      env_secret_sentinel: 'ARGOS_SEED-env-secret-value',
      env_secret_masked: '****(len=27)',
      deadlock_threads: ['order-settle-worker', 'inventory-sync-worker'],
      idle_pool_threads: 10,
      deep_frames: 64,
      xss_thread: 'x',
    };
    const withCommands = ActiveFeedReady.parse({ ...READY, commands });
    expect(withCommands.commands?.env_secret_masked).toBe('****(len=27)');
    expect(() =>
      ActiveFeedReady.parse({
        ...READY,
        commands: { ...commands, env_secret_masked: 'ARGOS_SEED-env-secret-value' },
      }),
    ).toThrow();
  });

  it('rejects a ready file with an unknown state', () => {
    const bad = structuredClone(READY);
    (bad.instances[0]?.slots[0] as { state: string }).state = 'SLEEP';
    expect(() => ActiveFeedReady.parse(bad)).toThrow();
  });

  it('lets fixed rows grow with the snapshot time and keeps fresh rows at their elapsed', () => {
    const fixed = rows[0];
    const fresh = rows[1];
    if (fixed === undefined || fresh === undefined) {
      throw new Error('rows missing');
    }
    expect(expectedElapsedMs(fixed, START)).toBe(20_000);
    expect(expectedElapsedMs(fixed, START + 5000)).toBe(25_000);
    expect(expectedElapsedMs(fresh, START)).toBe(5000);
    expect(expectedElapsedMs(fresh, START + 60_000)).toBe(5000);
    expect(() => expectedElapsedMs({ ...fixed, start_ms: null }, START)).toThrow(/start_ms/);
  });

  it('bands elapsed times with the default 1/3/8 s limits and custom ones', () => {
    expect([0, 999, 1000, 2999, 3000, 7999, 8000, 60_000].map((e) => elapsedBand(e))).toEqual([
      0, 0, 1, 1, 2, 2, 3, 3,
    ]);
    expect(
      [1999, 2000, 4999, 5000, 9999, 10_000].map((e) => elapsedBand(e, [2000, 5000, 10_000])),
    ).toEqual([0, 1, 1, 2, 2, 3]);
  });

  it('counts the buckets of an instance as the server does', () => {
    expect(expectedBuckets(rows, 'e2e-active-1', START)).toEqual([1, 1, 1, 1]);
    expect(expectedBuckets(rows, 'e2e-active-2', START)).toEqual([0, 0, 0, 1]);
    expect(expectedBuckets(rows, 'e2e-active-1', START, [2000, 5000, 10_000])).toEqual([
      1, 1, 1, 1,
    ]);
    expect(expectedBuckets(rows, 'e2e-active-1', START, [2500, 6000, 30_000])).toEqual([
      2, 1, 1, 0,
    ]);
  });
});

describe('activeFeedProblem', () => {
  it('has none for the described stack on a host', () => {
    expect(activeFeedProblem('run-1', runState(), {})).toBeNull();
  });

  it('skips inside the e2e:docker image (no JVM)', () => {
    expect(
      activeFeedProblem('run-1', runState(), { ARGOS_E2E_LINUX_IMAGE: 'img@sha256:x' }),
    ).toMatch(/no JVM/);
  });

  it('needs run.json of the same run with a gRPC port and a PostgreSQL container', () => {
    expect(activeFeedProblem('run-1', null, {})).toMatch(/run.json/);
    expect(activeFeedProblem('run-2', runState(), {})).toMatch(/run-2/);
    expect(activeFeedProblem('run-1', runState({ grpc_port: null }), {})).toMatch(/gRPC port/);
    expect(activeFeedProblem('run-1', runState({ containers: null }), {})).toMatch(/PostgreSQL/);
  });
});
