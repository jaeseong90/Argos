// SPDX-License-Identifier: Apache-2.0
//
// Common page hooks of every E2E test (docs/15 §15 "전역 훅", docs/18 §13, SEC-46, SEC-62): after the
// test `window.__xss` is undefined, and no dialog, page error, console error, CSP violation or
// request to another origin happened. The XLog PoC data carries XSS payloads in service, instance
// and error class names; they must only ever be text.
//
// "No CSP violation" means something only under a policy. When the suite serves dist/ itself
// (`vite preview` with the `preview.headers` CSP of vite.config.ts), every main-frame document must
// carry a Content-Security-Policy header. Against a server named by ARGOS_UI_BASE_URL (for example
// the dev server, which sends no policy) the header is not required; such a test gets a
// `csp-not-verified` annotation. e2e/specs/global-hooks.spec.ts is the positive control: an inline
// script injected into the page must be recorded as a violation and fail these checks.
//
// The `ignoredConsoleErrors` option (default: none) exempts console errors that match one of its
// patterns. It exists for the positive control only: Chrome also logs a blocked inline script as a
// console error, so without the exemption the control would fail on the console check even if the
// CSP violation check were gone. Every exempted message is listed in an `ignored-console-error`
// annotation of the test.
import { test as base, expect, type Page, type TestInfo } from '@playwright/test';

import { uiTarget } from './uiServer';

declare global {
  interface Window {
    /** Set by an XSS payload that was parsed as markup. */
    __xss?: unknown;
    /** CSP violations seen by the page (directive names). */
    __argosCspViolations?: string[];
  }
}

/** Playwright serves dist/ itself with the preview CSP (ARGOS_UI_BASE_URL is not set). */
export const SERVES_OWN_PREVIEW = uiTarget(process.env).webServer !== undefined;

/** Annotation type of a test whose server's CSP header was not checked (external target). */
export const CSP_NOT_VERIFIED = 'csp-not-verified';

/** Annotation type listing a console error exempted by `ignoredConsoleErrors`. */
export const IGNORED_CONSOLE_ERROR = 'ignored-console-error';

export interface PageHookOptions {
  /**
   * Console errors whose text matches one of these patterns are not counted as problems. Only for
   * positive controls that provoke a known console error; ordinary tests leave it empty.
   */
  readonly ignoredConsoleErrors?: readonly RegExp[];
}

interface DocumentResponse {
  readonly url: string;
  /** The Content-Security-Policy header, or null when the response has none. */
  readonly csp: string | null;
}

export interface PageHooks {
  /** Checks every hook condition; throws (Playwright `expect`) on the first one that is broken. */
  verify(testInfo: TestInfo): Promise<void>;
}

/** Starts recording on `page`; call before the page navigates. */
export async function installPageHooks(
  page: Page,
  baseURL: string | undefined,
  options: PageHookOptions = {},
): Promise<PageHooks> {
  const ignoredPatterns = options.ignoredConsoleErrors ?? [];
  const problems: string[] = [];
  const ignored: string[] = [];
  const documents: Promise<DocumentResponse>[] = [];
  const origin = baseURL === undefined ? null : new URL(baseURL).origin;
  page.on('dialog', (dialog) => {
    problems.push(`dialog: ${dialog.type()} ${dialog.message()}`);
    void dialog.dismiss();
  });
  page.on('pageerror', (error) => {
    problems.push(`page error: ${error.message}`);
  });
  page.on('console', (message) => {
    if (message.type() !== 'error') {
      return;
    }
    const text = message.text();
    // String#search ignores the g and y flags and leaves lastIndex alone, unlike RegExp#test.
    if (ignoredPatterns.some((pattern) => text.search(pattern) !== -1)) {
      ignored.push(text);
    } else {
      problems.push(`console error: ${text}`);
    }
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.protocol !== 'data:' && url.protocol !== 'blob:' && url.origin !== origin) {
      problems.push(`request to another origin: ${request.url()}`);
    }
  });
  page.on('response', (response) => {
    const request = response.request();
    if (request.resourceType() !== 'document' || request.frame() !== page.mainFrame()) {
      return;
    }
    const url = response.url();
    documents.push(
      response.allHeaders().then(
        (headers) => ({ url, csp: headers['content-security-policy'] ?? null }),
        () => ({ url, csp: null }),
      ),
    );
  });
  await page.addInitScript(() => {
    window.__argosCspViolations = [];
    document.addEventListener('securitypolicyviolation', (event) => {
      window.__argosCspViolations?.push(event.violatedDirective);
    });
  });

  return {
    async verify(testInfo: TestInfo): Promise<void> {
      // Each exempted message is annotated once, also when verify runs more than once.
      for (const text of ignored.splice(0)) {
        testInfo.annotations.push({ type: IGNORED_CONSOLE_ERROR, description: text });
      }
      const responses = await Promise.all(documents);
      if (SERVES_OWN_PREVIEW) {
        expect(
          responses.length,
          'main-frame documents loaded (every E2E test opens the UI)',
        ).toBeGreaterThan(0);
        expect(
          responses.filter((r) => r.csp === null || r.csp.trim() === '').map((r) => r.url),
          'main-frame documents without a Content-Security-Policy header (vite.config.ts preview.headers)',
        ).toEqual([]);
      } else {
        testInfo.annotations.push({
          type: CSP_NOT_VERIFIED,
          description: `${origin ?? 'external server'} (ARGOS_UI_BASE_URL): its CSP header is not checked; CSP violations are counted only under the policy it sends`,
        });
      }
      if (!page.isClosed()) {
        const state = await page.evaluate(() => ({
          xss: window.__xss === undefined ? null : JSON.stringify(window.__xss),
          csp: window.__argosCspViolations ?? [],
        }));
        expect(state.xss, 'window.__xss must stay undefined (an XSS payload ran)').toBeNull();
        expect(state.csp, 'CSP violations').toEqual([]);
      }
      expect(problems, 'dialogs, page errors, console errors, foreign requests').toEqual([]);
    },
  };
}

export interface PageHookFixtureOptions {
  /** See PageHookOptions; set with `test.use({ ignoredConsoleErrors: [...] })`. */
  readonly ignoredConsoleErrors: readonly RegExp[];
}

export const test = base.extend<PageHookFixtureOptions>({
  ignoredConsoleErrors: [[], { option: true }],
  page: async ({ page, baseURL, ignoredConsoleErrors }, use, testInfo) => {
    const hooks = await installPageHooks(page, baseURL, { ignoredConsoleErrors });
    await use(page);
    await hooks.verify(testInfo);
  },
});

export { expect };
