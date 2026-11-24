// SPDX-License-Identifier: Apache-2.0
//
// The command-answering feed of the instance detail and thread dump E2E tests (WO-37, docs/05
// §2.7-3~5): the dev-only active feed (e2e/stack/seed/activeFeed.ts, `./gradlew
// :server:activeFeedJar`) with `--commands`, for its own app `e2e-cmd` (env `e2e`) and two
// instances. Each instance is a protocol-level fake agent session against the stack's real
// collector: it sends the deterministic active snapshots of ActiveFeedScenario and answers remote
// commands deterministically, so a spec can run the whole command round trip (REST request →
// session stream → agent answer → encrypted result in PostgreSQL → REST result → screen):
//
// - `e2e-cmd-1` (normal): thread_dump = JSON dump with the tx_id of every running transaction,
//   collapsed idle pool threads, one deadlock, virtual threads, a 64-frame stack and XSS payloads;
//   thread_stack of a running tx (FAILED for an unknown one); self_diagnosis with effective config
//   sources and modules; env_dump masked (`****(len=N)`, never the raw sentinel); reload_config.
// - `e2e-cmd-2` (truncated): the same, but its thread dump result is reported truncated.
//
// The real agent's answers are WO-17's; the real-agent round trip spec switches on in the pair 13
// wrap-up. Inside the Playwright image of e2e:docker there is no JVM: ensureCommandFeed resolves
// with null there and the specs skip their round-trip tests with SKIP_NO_COMMAND_FEED.
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { retireInstances } from '../stack/retire';
import {
  type ActiveFeedCommands,
  type FeedApp,
  type RunningActiveFeed,
  type ScenarioRow,
  startActiveFeed,
} from '../stack/seed/activeFeed';

/** The app of the command feed (its own, so e2e-active and the seed oracles stay untouched). */
export const COMMAND_FEED_APP: FeedApp = { name: 'e2e-cmd', env: 'e2e' };

/** Instance names: the first answers normally, the second reports a truncated thread dump. */
export const COMMAND_FEED_INSTANCES = ['e2e-cmd-1', 'e2e-cmd-2'] as const;

/** Lifetime of a command feed process (it also stops at worker exit). */
export const COMMAND_FEED_DURATION_S = 20 * 60;

/** The role that finds the app and its instances (any role that sees the app). */
export const COMMAND_FEED_ORACLE_ROLE = 'viewer' as const;

/** The skip reason where no JVM is available. */
export const SKIP_NO_COMMAND_FEED =
  'no JVM for the command feed in the Playwright image of e2e:docker (the server half is ActiveFeedIT T-SV-041j)';

/** A running command feed, as the instance and dump specs use it. */
export interface CommandFeed {
  readonly appId: number;
  readonly appName: string;
  /** Instance ids: `normal` = e2e-cmd-1, `truncated` = e2e-cmd-2 (thread dump truncated). */
  readonly instanceIds: { readonly normal: number; readonly truncated: number };
  /** The running transactions of the scenario (tx matching of the dump viewer, active screen). */
  readonly scenario: readonly ScenarioRow[];
  /** What the command answers contain (raw secret sentinel, masked form, deadlock names, ...). */
  readonly commands: ActiveFeedCommands;
  /** The underlying feed. */
  readonly feed: RunningActiveFeed;
  /** Ends the feed process. */
  stop(): Promise<void>;
}

/** The start options of the command feed (pure; the unit test checks them). */
export function commandFeedOptions(): {
  readonly app: FeedApp;
  readonly instances: readonly string[];
  readonly commands: true;
  readonly durationS: number;
} {
  return {
    app: COMMAND_FEED_APP,
    instances: COMMAND_FEED_INSTANCES,
    commands: true,
    durationS: COMMAND_FEED_DURATION_S,
  };
}

/** The command feed view of a started feed; throws when it does not answer commands as expected. */
export function toCommandFeed(feed: RunningActiveFeed): CommandFeed {
  if (feed.appName !== COMMAND_FEED_APP.name || feed.env !== COMMAND_FEED_APP.env) {
    throw new Error(`not the command feed app: ${feed.appName}/${feed.env}`);
  }
  if (feed.commands === null) {
    throw new Error('the command feed does not answer commands (no --commands in its ready file)');
  }
  const id = (name: string): number => {
    const found = feed.instances.find((i) => i.name === name);
    if (found === undefined) {
      throw new Error(`the command feed has no instance ${name}`);
    }
    return found.instanceId;
  };
  const [normalName, truncatedName] = COMMAND_FEED_INSTANCES;
  if (feed.dumpTruncated[normalName] === true || feed.dumpTruncated[truncatedName] !== true) {
    throw new Error(
      `the command feed must truncate the dump of ${truncatedName} only: ${JSON.stringify(feed.dumpTruncated)}`,
    );
  }
  return {
    appId: feed.appId,
    appName: feed.appName,
    instanceIds: { normal: id(normalName), truncated: id(truncatedName) },
    scenario: feed.scenario,
    commands: feed.commands,
    feed,
    stop: () => feed.stop(),
  };
}

let shared: { readonly runId: string; readonly feed: Promise<CommandFeed | null> } | null = null;
const feedLog: string[] = [];

/**
 * The running command feed of this worker for the stack of `seed` (started on first use), or null
 * where no JVM is available (e2e:docker). A failed start is not cached: the next caller tries again.
 */
export function ensureCommandFeed(
  seed: SeedManifest,
  apiAs: (role: Role) => Promise<ApiSession>,
): Promise<CommandFeed | null> {
  if (shared?.runId === seed.run_id) {
    return shared.feed;
  }
  const feed = (async () => {
    const api = await apiAs(COMMAND_FEED_ORACLE_ROLE);
    const started = await startActiveFeed({
      stack: { runId: seed.run_id, api },
      log: (line) => {
        feedLog.push(`${new Date().toISOString()} ${line}`);
      },
      ...commandFeedOptions(),
    });
    if (started === null) {
      return null;
    }
    // stop() sends the kill signal before its first await: a worker exit still ends the feed.
    process.once('exit', () => {
      void started.stop();
    });
    try {
      return toCommandFeed(started);
    } catch (error) {
      await started.stop();
      throw error;
    }
  })();
  const entry = { runId: seed.run_id, feed };
  shared = entry;
  feed.catch(() => {
    if (shared === entry) {
      shared = null;
    }
  });
  return feed;
}

/**
 * Stops the shared command feed (a spec's afterAll) and archives its instances with an operator
 * session of `operator` (ADR-0010 C-241 (7): a stopped feed instance never stays FIRING as
 * instance_down); the next ensureCommandFeed starts anew, whose reconnect lists them again.
 */
export async function stopCommandFeed(operator: () => Promise<ApiSession>): Promise<void> {
  const entry = shared;
  shared = null;
  const feed = await entry?.feed.catch(() => null);
  if (feed === null || feed === undefined) {
    return;
  }
  await feed.stop();
  const retired = await retireInstances(await operator(), [
    feed.instanceIds.normal,
    feed.instanceIds.truncated,
  ]);
  feedLog.push(
    `${new Date().toISOString()} command feed stopped, instances archived: ${[...retired].map(([id, o]) => `${String(id)}=${o}`).join(' ')}`,
  );
}

/** The feed's progress lines so far (attached by the spec; never a token). */
export function commandFeedLog(): string {
  return feedLog.join('\n');
}
