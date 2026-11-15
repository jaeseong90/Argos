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
//
// The E2E stack (WO-30, e2e/fixtures/stack.ts) uses the same hooks against the real server with
// three more options: `requireCsp` (the server's nonce CSP must be on every document, T-SEC-46),
// `consoleFilter` (Chrome's "Failed to load resource" line of an expected 4xx API answer, e.g. 401
// before login, is annotated instead of counted; 5xx and everything else still fail) and
// `checkTokenStorage` (T-SEC-40: neither document.cookie nor localStorage nor sessionStorage holds a
// session token). WebSocket connections must go to the page's own origin as well (the stack's
// routed /ws stub emits no `websocket` event; e2e/fixtures/stack.ts checks its connections with the
// same rule). e2e/specs/stack-hooks.spec.ts holds the positive controls of these stack options.
import {
  test as base,
  expect,
  type ConsoleMessage,
  type Page,
  type TestInfo,
} from '@playwright/test';

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
  /** Every main-frame document must carry a CSP header (default: when the suite serves dist/). */
  readonly requireCsp?: boolean;
  /**
   * Returns why a console error is expected (annotated, not counted), or null to count it. For the
   * stack: Chrome's resource-load line of an expected 4xx answer of the API.
   */
  readonly consoleFilter?: (message: ConsoleMessage) => string | null;
  /** T-SEC-40: no session token in document.cookie, localStorage or sessionStorage. */
  readonly checkTokenStorage?: boolean;
}

/** Names of the httpOnly session cookies (WO-24 SessionCookies): never readable by the page. */
export const SESSION_TOKEN_COOKIES: readonly string[] = ['argos_at', 'argos_rt'];

/** A JWT-shaped string (header.payload of a base64url JSON object). */
const JWT_SHAPE = /eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}/;

/** The WebSocket origin (ws: / wss:) that belongs to an http(s) origin. */
export function webSocketOrigin(origin: string): string {
  return origin.replace(/^http(s?):/, 'ws$1:');
}

/**
 * Whether a WebSocket URL goes to the page's own origin (ws: for http:, wss: for https:, same host
 * and port). Used by the page hook below and by the stack's WebSocket stub (e2e/fixtures/stack.ts):
 * a socket answered by page.routeWebSocket emits no `websocket` event, so the stub checks its own
 * connections with the same rule.
 */
export function isOwnWebSocket(url: string, pageOrigin: string | null): boolean {
  return pageOrigin !== null && new URL(url).origin === webSocketOrigin(pageOrigin);
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
  const requireCsp = options.requireCsp ?? SERVES_OWN_PREVIEW;
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
    const expected = options.consoleFilter?.(message) ?? null;
    // String#search ignores the g and y flags and leaves lastIndex alone, unlike RegExp#test.
    if (ignoredPatterns.some((pattern) => text.search(pattern) !== -1)) {
      ignored.push(text);
    } else if (expected !== null) {
      ignored.push(`${text} (${expected})`);
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
  page.on('websocket', (socket) => {
    if (!isOwnWebSocket(socket.url(), origin)) {
      problems.push(`WebSocket to another origin: ${socket.url()}`);
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
      // A skipped test (e.g. a screenshot test outside its Linux image) opened no page.
      if (testInfo.status === 'skipped') {
        return;
      }
      // Each exempted message is annotated once, also when verify runs more than once.
      for (const text of ignored.splice(0)) {
        testInfo.annotations.push({ type: IGNORED_CONSOLE_ERROR, description: text });
      }
      const responses = await Promise.all(documents);
      if (requireCsp) {
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
        if (options.checkTokenStorage === true) {
          const tokens = (await page.context().cookies())
            .filter((c) => SESSION_TOKEN_COOKIES.includes(c.name))
            .map((c) => c.value)
            .filter((v) => v.length >= 8);
          const leaks = await page.evaluate(
            ({ names, values, jwt }) => {
              const found: string[] = [];
              const shape = new RegExp(jwt);
              for (const name of names) {
                if (document.cookie.split(';').some((c) => c.trim().startsWith(`${name}=`))) {
                  found.push(`document.cookie holds ${name}`);
                }
              }
              for (const [label, storage] of [
                ['localStorage', window.localStorage],
                ['sessionStorage', window.sessionStorage],
              ] as const) {
                for (let i = 0; i < storage.length; i += 1) {
                  const key = storage.key(i) ?? '';
                  const value = storage.getItem(key) ?? '';
                  if (
                    values.some((v) => value.includes(v) || key.includes(v)) ||
                    shape.test(value)
                  ) {
                    found.push(`${label}[${key}] holds a session token`);
                  }
                }
              }
              return found;
            },
            { names: [...SESSION_TOKEN_COOKIES], values: tokens, jwt: JWT_SHAPE.source },
          );
          expect(leaks, 'T-SEC-40: session tokens readable by the page').toEqual([]);
        }
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
