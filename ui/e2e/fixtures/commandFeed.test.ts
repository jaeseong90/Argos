// SPDX-License-Identifier: Apache-2.0
//
// The command feed fixture (e2e/fixtures/commandFeed.ts, WO-37): its start options reach the feed's
// command line as `--app e2e-cmd --env e2e --instances e2e-cmd-1,e2e-cmd-2 --commands`, and the
// view of a started feed names the normal and the truncated instance and refuses a feed that does
// not answer commands as the specs expect.
import { describe, expect, it } from 'vitest';

import { activeFeedArgs, ActiveFeedReady, type RunningActiveFeed } from '../stack/seed/activeFeed';

import {
  COMMAND_FEED_APP,
  COMMAND_FEED_INSTANCES,
  commandFeedOptions,
  toCommandFeed,
} from './commandFeed';

const COMMANDS = {
  enabled: true as const,
  env_secret_sentinel: 'ARGOS_SEED-env-secret-value',
  env_secret_masked: '****(len=27)',
  deadlock_threads: ['order-settle-worker', 'inventory-sync-worker'],
  idle_pool_threads: 10,
  deep_frames: 64,
  xss_thread: '<img src=x onerror="window.__xss=1">ARGOS_SEED-worker',
};

function feed(overrides: Partial<RunningActiveFeed> = {}): RunningActiveFeed {
  return {
    appId: 7,
    appName: 'e2e-cmd',
    env: 'e2e',
    instances: [
      { name: 'e2e-cmd-1', instanceId: 21 },
      { name: 'e2e-cmd-2', instanceId: 22 },
    ],
    commands: COMMANDS,
    dumpTruncated: { 'e2e-cmd-1': false, 'e2e-cmd-2': true },
    scenario: [],
    feedStartMs: Date.parse('2026-11-24T00:00:00Z'),
    intervalMs: 5000,
    stop: () => Promise.resolve(),
    ...overrides,
  };
}

describe('commandFeedOptions', () => {
  it('starts the feed for app e2e-cmd with two instances and --commands', () => {
    const options = commandFeedOptions();
    expect(options.app).toEqual({ name: 'e2e-cmd', env: 'e2e' });
    expect(options.instances).toEqual(['e2e-cmd-1', 'e2e-cmd-2']);
    const args = activeFeedArgs({
      jar: 'feed.jar',
      grpcPort: 41001,
      tokenFile: '/run/secrets/active-feed-e2e-cmd-e2e.token',
      readyFile: '/run/ready.json',
      ...options,
    });
    const value = (flag: string): string | undefined => args[args.indexOf(flag) + 1];
    expect(value('--app')).toBe(COMMAND_FEED_APP.name);
    expect(value('--env')).toBe(COMMAND_FEED_APP.env);
    expect(value('--instances')).toBe(COMMAND_FEED_INSTANCES.join(','));
    expect(value('--duration')).toBe('1200s');
    expect(args.at(-1)).toBe('--commands');
  });
});

describe('toCommandFeed', () => {
  it('names the normal and the truncated instance and keeps the command expectations', () => {
    const view = toCommandFeed(feed());
    expect(view.appId).toBe(7);
    expect(view.instanceIds).toEqual({ normal: 21, truncated: 22 });
    expect(view.commands.env_secret_masked).toBe('****(len=27)');
    expect(view.commands.env_secret_masked).not.toContain(view.commands.env_secret_sentinel);
  });

  it('refuses a feed of another app, without commands or with the wrong truncation', () => {
    expect(() => toCommandFeed(feed({ appName: 'e2e-active' }))).toThrow(/not the command feed/);
    expect(() => toCommandFeed(feed({ commands: null }))).toThrow(/does not answer commands/);
    expect(() =>
      toCommandFeed(feed({ dumpTruncated: { 'e2e-cmd-1': false, 'e2e-cmd-2': false } })),
    ).toThrow(/truncate/);
    expect(() =>
      toCommandFeed(feed({ instances: [{ name: 'e2e-cmd-1', instanceId: 21 }] })),
    ).toThrow(/no instance e2e-cmd-2/);
  });

  it('reads the ready file of a --commands feed (ActiveFeedScenario.toJson with commands)', () => {
    const ready = ActiveFeedReady.parse({
      app: 'e2e-cmd',
      env: 'e2e',
      feed_start_ms: 1,
      interval_ms: 5000,
      commands: COMMANDS,
      instances: COMMAND_FEED_INSTANCES.map((name, i) => ({
        name,
        instance_id: 21 + i,
        user_id_mode: i === 0 ? 'plain' : 'off',
        ip_mode: i === 0 ? 'full' : 'off',
        dump_truncated: i === 1,
        slots: [
          {
            slot: 0,
            timing: 'fixed',
            start_ms: 0,
            elapsed_ms: null,
            kind: 'HTTP',
            service: 'GET /api/orders/{id}',
            state: 'SQL',
            state_detail: 'SELECT 1',
            thread: 'http-nio-8080-exec-#',
            virtual_thread: false,
            user_id: null,
            remote_ip: null,
          },
        ],
      })),
    });
    expect(ready.commands?.deadlock_threads).toHaveLength(2);
    expect(ready.instances.map((i) => i.dump_truncated)).toEqual([false, true]);
  });
});
