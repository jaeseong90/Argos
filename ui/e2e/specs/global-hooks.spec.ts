// SPDX-License-Identifier: Apache-2.0
//
// Positive controls of the E2E page hooks (e2e/fixtures/fixtures.ts, docs/15 §15 "전역 훅", SEC-46):
// a check that cannot fail proves nothing, so these tests give the hooks a real CSP violation. An
// inline script inserted into the page must be blocked by the preview CSP (vite.config.ts
// `preview.headers`), recorded by the hooks' securitypolicyviolation listener and rejected by their
// checks. They need the suite's own preview server: against ARGOS_UI_BASE_URL (which may send no
// policy) they are skipped.
//
// Chrome also logs the blocked script as a console error, which the console check would reject on
// its own. The controls exempt exactly that message (`ignoredConsoleErrors`), so the CSP violation
// check is the only one that can fail them: if it were removed, the fixture-level control would
// pass, and Playwright reports a `test.fail()` test that passes as a failure.
import { test as base, expect, type Page } from '@playwright/test';

import { installPageHooks, SERVES_OWN_PREVIEW, test } from '../fixtures/fixtures';

/** Set by the injected inline script if it runs (it must not). */
const PROBE = '__argosCspProbe';
const SKIP_REASON = 'needs the preview CSP of vite.config.ts; ARGOS_UI_BASE_URL is set';

/**
 * The page the controls open: it runs under the preview CSP without the server (no API call, no
 * component-library styles; e2e/specs/xlog-poc.spec.ts holds it to zero violations). The shell
 * routes need the real server, whose per-request nonce the preview has not (WO-30: `/` opens the
 * sign-in page, tested by the stack project).
 */
const CONTROL_PAGE = '/xlog-poc';

/**
 * Chrome's console error for the injected inline script blocked by `script-src 'self'` (wording of
 * the Chromium that Playwright 1.64 installs). If a Chromium update changes it, the unit-level
 * control below fails with "Chrome logs the blocked script" and shows the new text.
 */
const CSP_INLINE_SCRIPT_CONSOLE_ERROR =
  /^Executing inline script violates the following Content Security Policy directive 'script-src 'self''\. /;

async function injectInlineScript(page: Page): Promise<void> {
  await page.evaluate((probe) => {
    const script = document.createElement('script');
    script.textContent = `window[${JSON.stringify(probe)}] = true;`;
    document.head.append(script);
  }, PROBE);
}

async function violations(page: Page): Promise<string[]> {
  return page.evaluate(() => window.__argosCspViolations ?? []);
}

/** Resolves with the text of the next console error of `page`. */
async function nextConsoleError(page: Page): Promise<string> {
  const message = await page.waitForEvent('console', (m) => m.type() === 'error');
  return message.text();
}

base.describe('E2E page hooks, positive control (unit level)', () => {
  base.skip(!SERVES_OWN_PREVIEW, SKIP_REASON);

  base(
    'records an inline script blocked by the preview CSP, and the hook check rejects it',
    async ({ page, baseURL }, testInfo) => {
      const hooks = await installPageHooks(page, baseURL);
      await page.goto(CONTROL_PAGE);
      await injectInlineScript(page);
      await expect.poll(() => violations(page)).toEqual(['script-src-elem']);
      expect(
        await page.evaluate((probe) => probe in window, PROBE),
        'the blocked inline script did not run',
      ).toBe(false);
      await expect(hooks.verify(testInfo)).rejects.toThrow(/CSP violations/);
    },
  );

  base(
    'with its console message exempted, the recorded CSP violation is the only failing check',
    async ({ page, baseURL }, testInfo) => {
      const hooks = await installPageHooks(page, baseURL, {
        ignoredConsoleErrors: [CSP_INLINE_SCRIPT_CONSOLE_ERROR],
      });
      await page.goto(CONTROL_PAGE);
      const consoleError = nextConsoleError(page);
      await injectInlineScript(page);
      expect(await consoleError, 'Chrome logs the blocked script').toMatch(
        CSP_INLINE_SCRIPT_CONSOLE_ERROR,
      );
      await expect.poll(() => violations(page)).toEqual(['script-src-elem']);
      await expect(hooks.verify(testInfo)).rejects.toThrow(/CSP violations/);
      // Without the recorded violation every other check passes: the exempted console message is
      // the only other trace of the blocked script, so this control fails for the violation alone.
      await page.evaluate(() => {
        window.__argosCspViolations = [];
      });
      await expect(hooks.verify(testInfo)).resolves.toBeUndefined();
    },
  );

  base(
    'still rejects console errors that ignoredConsoleErrors does not match',
    async ({ page, baseURL }, testInfo) => {
      const hooks = await installPageHooks(page, baseURL, {
        ignoredConsoleErrors: [CSP_INLINE_SCRIPT_CONSOLE_ERROR],
      });
      await page.goto(CONTROL_PAGE);
      const consoleError = nextConsoleError(page);
      await page.evaluate(() => {
        console.error('argos positive control: an unrelated console error');
      });
      expect(await consoleError).toBe('argos positive control: an unrelated console error');
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        /console error: argos positive control: an unrelated console error/,
      );
    },
  );

  base(
    'rejects a document served without the Content-Security-Policy header',
    async ({ page, baseURL }, testInfo) => {
      const hooks = await installPageHooks(page, baseURL);
      // Strip the header from every response: without a policy no violation can ever be reported.
      await page.route('**/*', async (route) => {
        const response = await route.fetch();
        const headers = { ...response.headers() };
        delete headers['content-security-policy'];
        await route.fulfill({ response, headers });
      });
      await page.goto(CONTROL_PAGE);
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        /without a Content-Security-Policy header/,
      );
    },
  );
});

test.describe('E2E page hooks, positive control (fixture)', () => {
  test.skip(!SERVES_OWN_PREVIEW, SKIP_REASON);
  // Only the console message of the injected script is exempted (see the unit-level control above).
  test.use({ ignoredConsoleErrors: [CSP_INLINE_SCRIPT_CONSOLE_ERROR] });

  test('a CSP violation fails the test through the page fixture', async ({ page }) => {
    // Expected failure: the fixture's "CSP violations" check after the test must fail it. If the
    // fixture stopped checking, this test would pass and Playwright reports that as a failure.
    test.fail(true, 'positive control: the page fixture must fail this test');
    await page.goto(CONTROL_PAGE);
    await injectInlineScript(page);
    await expect.poll(() => violations(page)).toEqual(['script-src-elem']);
  });
});
