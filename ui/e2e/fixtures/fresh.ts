// SPDX-License-Identifier: Apache-2.0
//
// Test fixtures of the fresh-install project (WO-46, ADR-0010 C-192 (1), e2e/specs/fresh/): the
// fresh stack of fresh-setup (e2e/stack/fresh.ts: no administrator, the server's setup token in the
// run's secrets directory) and every page with the page hooks of the stack project (CSP header on
// every document, requests and WebSockets to the stack origin only, no session token readable by
// the page, window.__xss undefined, no dialog, page error, console error or CSP violation in any
// document of the context). No WebSocket stub: the wizard's pages talk to the real server only.
import { readFileSync } from 'node:fs';

import { test as base, expect } from '@playwright/test';

import { FRESH_SESSION_FILE, setupTokenFile } from '../stack/fresh';
import { readSession, type StackSession } from '../stack/session';

import { installPageHooks } from './fixtures';
import { recordContextCspViolations, stackPageHookOptions, type ContextCspRecorder } from './stack';

export { expect };

/** The setup token of the fresh stack (the run's secrets file; never printed). */
export function freshSetupToken(session: StackSession): string {
  return readFileSync(setupTokenFile(session.run_id), 'utf8').trim();
}

interface FreshWorkerFixtures {
  freshSession: StackSession;
}

interface FreshTestFixtures {
  contextCsp: ContextCspRecorder;
}

export const test = base.extend<FreshTestFixtures, FreshWorkerFixtures>({
  freshSession: [
    // eslint-disable-next-line no-empty-pattern -- Playwright fixtures take their dependencies as the first argument
    async ({}, use) => {
      await use(readSession(FRESH_SESSION_FILE));
    },
    { scope: 'worker' },
  ],
  baseURL: async ({ freshSession }, use) => {
    await use(freshSession.stack_url);
  },
  contextCsp: async ({ context }, use) => {
    await use(await recordContextCspViolations(context));
  },
  page: async ({ page, freshSession, contextCsp }, use, testInfo) => {
    const origin = new URL(freshSession.stack_url).origin;
    const hooks = await installPageHooks(
      page,
      freshSession.stack_url,
      stackPageHookOptions(origin),
    );
    await use(page);
    await hooks.verify(testInfo);
    await contextCsp.verify(testInfo);
  },
});
