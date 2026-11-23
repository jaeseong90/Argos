// SPDX-License-Identifier: Apache-2.0
//
// Test fixtures of the `stack` project (docs/18 §13): every spec runs against the real server of
// the E2E stack (e2e/stack/) with real ClickHouse and PostgreSQL holding the seed data. Fixtures:
//   session   the stack of this run (ui/e2e/.stack/session.json, written by stack-setup)
//   seed      the typed seed manifest (absolute range, apps, totals, representative tx ids)
//   role      'admin' | 'operator' | 'viewer' (default) | null (anonymous); test.use({ role })
//   storageState / page
//             logged in through the real API as `role` (a fresh session per test: tests stay
//             independent and refresh-token rotation never invalidates a shared session), with the
//             page hooks of fixtures.ts in stack mode (CSP header required on documents, requests
//             and WebSockets only to the stack origin, T-SEC-40 token-free storage, expected 4xx
//             resource lines annotated)
//   contextCsp
//             every securitypolicyviolation of every document of the test's browser context,
//             reported to Node as it happens (ADR-0010 C-188 (1)): the page hooks read the
//             violations of the page's current document only, so this also covers documents left
//             by a navigation or reload and other tabs of the context (context.newPage, popups).
//             Any recorded violation fails the test after the page hooks
//   ignoredConsoleErrors
//             console errors the page hooks do not count (default none); for the positive controls
//             of e2e/specs/csp-report.spec.ts only (fixtures.ts PageHookOptions)
//   api       apiAs(role): a Node API session of a role, for setup steps and cross-checks
//   ws        the docs/14 §5 WebSocket fixture (auto: every stack page gets it), e2e/fixtures/ws.ts
//   wsMode    'stub' (default: the fixture answers as the docs/14 §5 server) | 'server' (every
//             socket is forwarded to the stack's real /ws of WO-22 and recorded);
//             test.use({ wsMode: 'server' })
// It also keeps the WO-04 teardown flake (B-3) under observation: when Playwright's context close
// fails after the test, the browser's recent stderr lines are attached to the report.
import { readFileSync } from 'node:fs';

import {
  test as base,
  expect,
  type BrowserContext,
  type BrowserContextOptions,
  type ConsoleMessage,
  type TestInfo,
} from '@playwright/test';

import { ApiSession } from '../stack/api';
import { readManifest, type Role, type SeedManifest } from '../stack/manifest';
import { credentialFile } from '../stack/paths';
import { readSession, type StackSession } from '../stack/session';

import { installPageHooks, isOwnWebSocket, type PageHookOptions } from './fixtures';
import { installWsStub, type WsMode, type WsStub } from './ws';

export { expect };

type StorageState = Exclude<BrowserContextOptions['storageState'], string | undefined>;

/** Password of a seeded role (the run's secrets file; never printed). */
export function rolePassword(seed: SeedManifest, role: Role): string {
  return readFileSync(credentialFile(seed.run_id, role), 'utf8');
}

/** A logged-in API session of `role`. */
export async function apiAs(
  session: StackSession,
  seed: SeedManifest,
  role: Role,
): Promise<ApiSession> {
  const api = new ApiSession(session.stack_url);
  await api.login(seed.users[role], rolePassword(seed, role));
  return api;
}

/** The session cookies of an API login as a Playwright storage state (host-only 127.0.0.1). */
export function storageStateOf(api: ApiSession): StorageState {
  const host = new URL(api.baseUrl).hostname;
  return {
    cookies: api.cookies().map((c) => ({
      name: c.name,
      value: c.value,
      domain: host,
      path: c.path,
      expires: c.expires,
      httpOnly: c.httpOnly,
      secure: c.secure,
      sameSite: c.sameSite,
    })),
    origins: [],
  };
}

/**
 * Chrome logs every 4xx/5xx resource load as a console error. An expected 4xx answer of the API
 * (401 before login, 404 of an unknown app, 429 of a heavy request) is annotated, not counted; 5xx
 * and every other console error still fail the test.
 */
export function expectedApiError(origin: string) {
  return (message: ConsoleMessage): string | null => {
    const text = message.text();
    const location = message.location().url;
    const status = /^Failed to load resource: the server responded with a status of (4\d\d)\b/.exec(
      text,
    );
    if (status !== null && location.startsWith(`${origin}/api/`)) {
      return `expected API answer ${status[1] ?? ''} of ${new URL(location).pathname}`;
    }
    return null;
  };
}

/**
 * The page-hook options of every stack page (CSP header required, expected 4xx API lines annotated,
 * T-SEC-40 token storage check). e2e/specs/stack-hooks.spec.ts runs its positive controls with
 * exactly these options.
 */
export function stackPageHookOptions(origin: string): PageHookOptions {
  return {
    requireCsp: true,
    consoleFilter: expectedApiError(origin),
    checkTokenStorage: true,
  };
}

/** Name of the page binding through which every document reports its CSP violations to Node. */
const CSP_BINDING = '__argosReportCspViolation';

/** A securitypolicyviolation event of some document of a browser context. */
export interface ContextCspViolation {
  /** URL of the document (frame) that fired it. */
  readonly url: string;
  readonly directive: string;
  /** blockedURI of the event (`inline`, `eval` or a URL). */
  readonly blocked: string;
}

export interface ContextCspRecorder {
  /** The violations recorded so far, in order. */
  violations(): readonly ContextCspViolation[];
  /**
   * Fails (Playwright `expect`) when any document of the context reported a violation. Round-trips
   * to every open page first, so a report sent before this call has arrived.
   */
  verify(testInfo: TestInfo): Promise<void>;
}

/**
 * Records every securitypolicyviolation of every document of `context` from now on (ADR-0010
 * C-188 (1)): a context-level init script forwards each event through a binding as it happens, so
 * a violation is kept when its document is left (navigation, reload) or its tab is closed, and
 * pages opened later (context.newPage, popups) are covered too. Call before the pages navigate.
 */
export async function recordContextCspViolations(
  context: BrowserContext,
): Promise<ContextCspRecorder> {
  const seen: ContextCspViolation[] = [];
  await context.exposeBinding(CSP_BINDING, (source, directive: unknown, blocked: unknown) => {
    seen.push({ url: source.frame.url(), directive: String(directive), blocked: String(blocked) });
  });
  await context.addInitScript((binding) => {
    document.addEventListener('securitypolicyviolation', (event) => {
      const report = (window as unknown as Record<string, unknown>)[binding];
      if (typeof report === 'function') {
        void (report as (directive: string, blocked: string) => Promise<void>)(
          event.effectiveDirective,
          event.blockedURI,
        );
      }
    });
  }, CSP_BINDING);
  return {
    violations: () => [...seen],
    async verify(testInfo: TestInfo): Promise<void> {
      if (testInfo.status === 'skipped') {
        return;
      }
      for (const open of context.pages()) {
        if (!open.isClosed()) {
          await open.evaluate(() => 0).catch(() => undefined);
        }
      }
      expect(
        seen.map((v) => `${v.directive} (blocked ${v.blocked}) in ${v.url}`),
        'CSP violations in any document of the browser context (also before a navigation and in other tabs)',
      ).toEqual([]);
    },
  };
}

/** Bounded buffer of the browser's log lines (B-3 observation). */
class BrowserLog {
  private readonly lines: string[] = [];
  push(line: string): void {
    this.lines.push(line);
    if (this.lines.length > 400) {
      this.lines.splice(0, this.lines.length - 400);
    }
  }
  text(): string {
    return this.lines.join('\n');
  }
}

interface StackWorkerFixtures {
  session: StackSession;
  seed: SeedManifest;
  browserLog: BrowserLog;
}

interface StackTestFixtures {
  role: Role | null;
  ignoredConsoleErrors: readonly RegExp[];
  contextCsp: ContextCspRecorder;
  ws: WsStub;
  wsMode: WsMode;
  apiAs: (role: Role) => Promise<ApiSession>;
  teardownGuard: undefined;
}

export const test = base.extend<StackTestFixtures, StackWorkerFixtures>({
  session: [
    // eslint-disable-next-line no-empty-pattern -- Playwright fixtures take their dependencies as the first argument
    async ({}, use) => {
      await use(readSession());
    },
    { scope: 'worker' },
  ],
  seed: [
    // eslint-disable-next-line no-empty-pattern -- see above
    async ({}, use) => {
      await use(readManifest());
    },
    { scope: 'worker' },
  ],
  browserLog: [
    // eslint-disable-next-line no-empty-pattern -- see above
    async ({}, use) => {
      await use(new BrowserLog());
    },
    { scope: 'worker' },
  ],
  launchOptions: [
    async ({ launchOptions, browserLog }, use) => {
      await use({
        ...launchOptions,
        logger: {
          isEnabled: (name) => name === 'browser',
          log: (name, severity, message) => {
            browserLog.push(`${new Date().toISOString()} ${name} ${severity} ${String(message)}`);
          },
        },
      });
    },
    { scope: 'worker' },
  ],
  baseURL: async ({ session }, use) => {
    await use(session.stack_url);
  },
  role: ['viewer', { option: true }],
  ignoredConsoleErrors: [[], { option: true }],
  contextCsp: async ({ context }, use) => {
    await use(await recordContextCspViolations(context));
  },
  storageState: async ({ role, session, seed }, use) => {
    if (role === null) {
      await use({ cookies: [], origins: [] });
      return;
    }
    const api = await apiAs(session, seed, role);
    await use(storageStateOf(api));
  },
  apiAs: async ({ session, seed }, use) => {
    await use((role) => apiAs(session, seed, role));
  },
  wsMode: ['stub', { option: true }],
  // Every stack page gets the WebSocket fixture before it navigates.
  ws: [
    async ({ page, session, wsMode }, use) => {
      const stub = await installWsStub(page, wsMode);
      await use(stub);
      expect(
        stub.violations(),
        'WebSocket frames that break docs/14 §5.2 (server mode: also server frames the UI rejects)',
      ).toEqual([]);
      // Routed sockets emit no `websocket` event, so the page hook cannot see them: same rule here.
      const origin = new URL(session.stack_url).origin;
      expect(
        stub
          .connections()
          .map((c) => c.url)
          .filter((url) => !isOwnWebSocket(url, origin)),
        'WebSocket to another origin (routed by the ws fixture)',
      ).toEqual([]);
    },
    { auto: true },
  ],
  page: async ({ page, session, contextCsp, ignoredConsoleErrors }, use, testInfo) => {
    const origin = new URL(session.stack_url).origin;
    const hooks = await installPageHooks(page, session.stack_url, {
      ...stackPageHookOptions(origin),
      ignoredConsoleErrors,
    });
    await use(page);
    await hooks.verify(testInfo);
    await contextCsp.verify(testInfo);
  },
  // Set up before the context (auto fixtures first), so it is torn down after the context closed.
  teardownGuard: [
    async ({ browserLog }, use, testInfo) => {
      await use(undefined);
      const closeFailure = testInfo.errors.some((e) =>
        (e.message ?? '').includes('browserContext.close'),
      );
      if (closeFailure) {
        testInfo.annotations.push({
          type: 'b3-teardown-flake',
          description: 'browserContext.close failed (WO-04 B-3)',
        });
        await testInfo.attach('browser-stderr.txt', {
          body: browserLog.text(),
          contentType: 'text/plain',
        });
      }
    },
    { auto: true },
  ],
});

/** The ISO-8601 instant `ms` without milliseconds when they are zero. */
export function iso(ms: number): string {
  return new Date(ms).toISOString().replace('.000Z', 'Z');
}
