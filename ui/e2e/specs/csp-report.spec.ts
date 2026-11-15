// SPDX-License-Identifier: Apache-2.0
//
// Positive controls of the stack suite's CSP check (SEC-46, ADR-0010 C-188 (2), which replaces
// docs/16 §9.5 ST-46-1): "zero CSP violations" after every stack test is judged from the browser's
// securitypolicyviolation events under the server's nonce policy (e2e/fixtures/fixtures.ts page
// hooks, e2e/fixtures/stack.ts contextCsp). A check that cannot fail proves nothing, so these tests
// cause a real violation on a page served by the stack and expect the checks to see it:
//   - unit level: in its own browser context, a page with exactly the stack options of the page
//     hooks and the context recorder; a style element carrying the server nonce passes, one without
//     it is blocked (style-src-elem), recorded and rejected, also after the document is left;
//   - fixture level (test.fail): the stack page fixture itself must fail a test whose violation the
//     page hooks cannot see any more (a reload left the document) or never saw (another tab).
// Chrome also logs a blocked style as a console error, which the console check would reject on its
// own; the controls exempt exactly that message (`ignoredConsoleErrors`), so the CSP checks are the
// only ones that can fail them.
//
// Each violation may also reach the server's report endpoint (Chromium's Reporting API sends late
// or not at all). Every control adds its possible reports to the run's record before it causes
// them, so the teardown's one-sided counter check (e2e/stack/cspCounter.ts) subtracts them.
import type { Page } from '@playwright/test';

import { installPageHooks } from '../fixtures/fixtures';
import { expect, recordContextCspViolations, stackPageHookOptions, test } from '../fixtures/stack';
import { addCspControlReports } from '../stack/cspCounter';

/**
 * Chrome's console error for a style element blocked by the server's `style-src 'self'
 * 'nonce-<nonce>'` (wording of the Chromium that Playwright 1.64 installs). If a Chromium update
 * changes it, the unit-level control fails with "Chrome logs the blocked style" and shows the text.
 */
const CSP_INLINE_STYLE_CONSOLE_ERROR =
  /^Applying inline style violates the following Content Security Policy directive 'style-src 'self' 'nonce-[A-Za-z0-9_-]{16,}''\. /;

/** The server nonce of a policy, from its style-src directive. */
const STYLE_NONCE = /style-src 'self' 'nonce-([A-Za-z0-9_-]{16,})'/;

/** Appends a style element, with the page's nonce (`<meta name="csp-nonce">`) or without one. */
async function appendStyle(page: Page, withNonce: boolean): Promise<void> {
  await page.evaluate((nonced) => {
    const style = document.createElement('style');
    if (nonced) {
      style.setAttribute(
        'nonce',
        document.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') ?? '',
      );
    }
    style.textContent = 'body { outline: 0; }';
    document.head.append(style);
  }, withNonce);
}

/** Directives recorded by the page hooks for the page's current document. */
async function pageViolations(page: Page): Promise<string[]> {
  return page.evaluate(() => window.__argosCspViolations ?? []);
}

/** Opens the sign-in page of the stack (anonymous: no session needed). */
async function openSignIn(page: Page): Promise<string> {
  const response = await page.goto('/login');
  await expect(page.getByTestId('login-form')).toBeVisible();
  return response?.headers()['content-security-policy'] ?? '';
}

test.describe('T-SEC-46 positive control, unit level (C-188 (2))', () => {
  test.use({ role: null });

  test(
    'T-SEC-46 control: on a stack page, a style element without the server nonce raises securitypolicyviolation and the CSP checks reject it',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
    async ({ page, browser, session }, testInfo) => {
      // The fixture page keeps its hooks and stays clean.
      await openSignIn(page);

      const origin = new URL(session.stack_url).origin;
      const context = await browser.newContext({ baseURL: session.stack_url });
      try {
        const recorder = await recordContextCspViolations(context);
        const probe = await context.newPage();
        const hooks = await installPageHooks(probe, session.stack_url, {
          ...stackPageHookOptions(origin),
          ignoredConsoleErrors: [CSP_INLINE_STYLE_CONSOLE_ERROR],
        });
        const policy = await openSignIn(probe);
        const nonce = STYLE_NONCE.exec(policy)?.[1];
        expect(nonce, `server CSP of the page: ${policy}`).toBeDefined();
        expect(policy).not.toContain('unsafe-inline');
        expect(
          await probe.evaluate(
            () => document.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') ?? null,
          ),
          'the page nonce is the policy nonce',
        ).toBe(nonce);

        // Negative controls: the page, and a style element carrying the server nonce, pass.
        await appendStyle(probe, true);
        await expect(hooks.verify(testInfo)).resolves.toBeUndefined();
        await expect(recorder.verify(testInfo)).resolves.toBeUndefined();

        // One possible report, recorded before the violation (the teardown subtracts it).
        addCspControlReports(session.run_id, 1);
        const consoleError = probe
          .waitForEvent('console', (m) => m.type() === 'error')
          .then((m) => m.text());
        const event = probe.evaluate(
          () =>
            new Promise<{ directive: string; blocked: string; disposition: string }>((resolve) => {
              document.addEventListener(
                'securitypolicyviolation',
                (e) => {
                  resolve({
                    directive: e.effectiveDirective,
                    blocked: e.blockedURI,
                    disposition: e.disposition,
                  });
                },
                { once: true },
              );
            }),
        );
        await appendStyle(probe, false);
        expect(await event, 'the browser blocked the style element').toEqual({
          directive: 'style-src-elem',
          blocked: 'inline',
          disposition: 'enforce',
        });
        expect(await consoleError, 'Chrome logs the blocked style').toMatch(
          CSP_INLINE_STYLE_CONSOLE_ERROR,
        );

        // The page hooks saw it, and it is the only failing check.
        await expect.poll(() => pageViolations(probe)).toEqual(['style-src-elem']);
        await expect(hooks.verify(testInfo)).rejects.toThrow(/CSP violations/);
        await probe.evaluate(() => {
          window.__argosCspViolations = [];
        });
        await expect(hooks.verify(testInfo)).resolves.toBeUndefined();

        // The context recorder saw it too, and keeps it after the document is left.
        await expect
          .poll(() => recorder.violations().map((v) => [v.directive, v.blocked]))
          .toEqual([['style-src-elem', 'inline']]);
        await probe.reload();
        await expect(probe.getByTestId('login-form')).toBeVisible();
        expect(await pageViolations(probe), 'a new document starts without violations').toEqual([]);
        expect(recorder.violations().map((v) => new URL(v.url).pathname)).toEqual(['/login']);
        await expect(recorder.verify(testInfo)).rejects.toThrow(
          /CSP violations in any document of the browser context/,
        );
      } finally {
        await context.close();
      }
    },
  );
});

test.describe('T-SEC-46 positive control, stack page fixture (C-188 (1), (2))', () => {
  // Only the console message of the blocked style is exempted (see the unit-level control).
  test.use({ role: null, ignoredConsoleErrors: [CSP_INLINE_STYLE_CONSOLE_ERROR] });

  test(
    'T-SEC-46 control: a violation before a reload fails the test through the stack page fixture',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
    async ({ page, session }) => {
      // Expected failure: the reload gives the page hooks a clean document, so only the context
      // recorder of the fixture can fail this test. If it stopped checking, the test would pass and
      // Playwright reports that as a failure.
      test.fail(true, 'positive control: the stack page fixture must fail this test');
      await openSignIn(page);
      addCspControlReports(session.run_id, 1);
      await appendStyle(page, false);
      await expect.poll(() => pageViolations(page)).toEqual(['style-src-elem']);
      await page.reload();
      await expect(page.getByTestId('login-form')).toBeVisible();
      expect(await pageViolations(page)).toEqual([]);
    },
  );

  test(
    'T-SEC-46 control: a violation in another tab of the test context fails the test through the stack page fixture',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
    async ({ page, context, session }) => {
      // Expected failure, as above: the fixture page stays clean; the other tab has no page hooks.
      test.fail(true, 'positive control: the stack page fixture must fail this test');
      await openSignIn(page);
      const other = await context.newPage();
      await openSignIn(other);
      addCspControlReports(session.run_id, 1);
      const blocked = other.evaluate(
        () =>
          new Promise<string>((resolve) => {
            document.addEventListener(
              'securitypolicyviolation',
              (e) => {
                resolve(e.effectiveDirective);
              },
              { once: true },
            );
          }),
      );
      await appendStyle(other, false);
      expect(await blocked).toBe('style-src-elem');
      await other.close();
      expect(await pageViolations(page)).toEqual([]);
    },
  );
});
