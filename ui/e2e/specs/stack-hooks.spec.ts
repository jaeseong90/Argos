// SPDX-License-Identifier: Apache-2.0
//
// Positive controls of the stack-mode page hooks (e2e/fixtures/fixtures.ts with the options of
// e2e/fixtures/stack.ts stackPageHookOptions): the T-SEC-40 token storage check, the WebSocket
// origin check and the console filter of expected 4xx API answers. A check that cannot fail proves
// nothing, so each control below makes the hook see a real problem and expects verify() to reject
// it, next to a negative control that must pass. They run in the `preview` project against
// `vite preview` (no server needed; skipped against ARGOS_UI_BASE_URL like global-hooks.spec.ts).
import { randomBytes } from 'node:crypto';

import { test as base, expect, type ConsoleMessage, type Page } from '@playwright/test';

import {
  IGNORED_CONSOLE_ERROR,
  installPageHooks,
  isOwnWebSocket,
  SERVES_OWN_PREVIEW,
} from '../fixtures/fixtures';
import { expectedApiError, stackPageHookOptions } from '../fixtures/stack';

const SKIP_REASON = 'needs the preview server of vite.config.ts; ARGOS_UI_BASE_URL is set';

/** Runs under the preview CSP without the server (see global-hooks.spec.ts). */
const CONTROL_PAGE = '/xlog-poc';

/** A WebSocket to another origin. Port 1 is on Chromium's unsafe-port list: nothing is sent. */
const FOREIGN_WS = 'ws://127.0.0.2:1/argos-hook-probe';

/**
 * A JWT-shaped value (header.payload.signature of base64url JSON), not tied to any cookie. Built at
 * run time: a literal would be reported by the secret scan (gitleaks rule `jwt`).
 */
const JWT_LIKE = [
  JSON.stringify({ alg: 'HS256' }),
  JSON.stringify({ sub: 'e2e-control' }),
  'signature',
]
  .map((part) => Buffer.from(part).toString('base64url'))
  .join('.');

const TOKEN_CHECK = /T-SEC-40: session tokens readable by the page/;

function originOf(baseURL: string | undefined): string {
  if (baseURL === undefined) {
    throw new Error('baseURL is not set (playwright.config.ts use.baseURL)');
  }
  return new URL(baseURL).origin;
}

/** A random cookie value like the server's opaque session tokens (>= 8 characters). */
function randomToken(): string {
  return randomBytes(18).toString('base64url');
}

/** Chrome's console error for a WebSocket whose handshake failed. */
function failedSocket(url: string): RegExp {
  return new RegExp(
    `^WebSocket connection to '${url.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}' failed`,
  );
}

async function openWebSocket(page: Page, url: string): Promise<void> {
  await page.evaluate((u) => {
    // Kept on window so the socket is not collected before the hook sees it.
    (window as unknown as { argosProbeSockets?: WebSocket[] }).argosProbeSockets ??= [];
    (window as unknown as { argosProbeSockets: WebSocket[] }).argosProbeSockets.push(
      new WebSocket(u),
    );
  }, url);
}

/** Resolves with the text of the next console error of `page`. */
async function nextConsoleError(page: Page): Promise<string> {
  const message = await page.waitForEvent('console', (m) => m.type() === 'error');
  return message.text();
}

/** A POST from the page; vite preview answers 404 for any path it does not serve. */
async function post(page: Page, path: string): Promise<number> {
  return page.evaluate(async (p) => (await fetch(p, { method: 'POST' })).status, path);
}

/** Removes the CSP header from every response (so the browser does not block the probe first). */
async function stripCsp(page: Page): Promise<void> {
  await page.route('**/*', async (route) => {
    const response = await route.fetch();
    const headers = { ...response.headers() };
    delete headers['content-security-policy'];
    await route.fulfill({ response, headers });
  });
}

/** A console message as the console listener receives it (only text and location are read). */
function consoleMessage(text: string, url: string): ConsoleMessage {
  const message: Pick<ConsoleMessage, 'text' | 'location' | 'type'> = {
    text: () => text,
    location: () => ({ url, line: 0, column: 0, lineNumber: 0, columnNumber: 0 }),
    type: () => 'error',
  };
  return message as ConsoleMessage;
}

base.describe('stack page hooks, T-SEC-40 token storage check (positive control)', () => {
  base.skip(!SERVES_OWN_PREVIEW, SKIP_REASON);

  base(
    'T-SEC-40 control: the httpOnly cookie alone passes; its value copied to localStorage is rejected',
    { annotation: { type: 'req', description: 'SEC-40' } },
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      const token = randomToken();
      await page
        .context()
        .addCookies([{ name: 'argos_at', value: token, url: origin, httpOnly: true }]);
      const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(origin));
      await page.goto(CONTROL_PAGE);
      await expect(hooks.verify(testInfo)).resolves.toBeUndefined();
      await page.evaluate((value) => {
        window.localStorage.setItem('argos.control', value);
      }, token);
      await expect(hooks.verify(testInfo)).rejects.toThrow(TOKEN_CHECK);
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        /localStorage\[argos\.control\] holds a session token/,
      );
    },
  );

  base(
    'T-SEC-40 control: a session token inside a sessionStorage value is rejected',
    { annotation: { type: 'req', description: 'SEC-40' } },
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      const token = randomToken();
      await page
        .context()
        .addCookies([{ name: 'argos_rt', value: token, url: origin, httpOnly: true }]);
      const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(origin));
      await page.goto(CONTROL_PAGE);
      await page.evaluate((value) => {
        window.sessionStorage.setItem('argos.state', JSON.stringify({ refresh: value }));
      }, token);
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        /sessionStorage\[argos\.state\] holds a session token/,
      );
    },
  );

  base(
    'T-SEC-40 control: a JWT-shaped value in localStorage is rejected without any session cookie',
    { annotation: { type: 'req', description: 'SEC-40' } },
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(origin));
      await page.goto(CONTROL_PAGE);
      expect(await page.context().cookies()).toEqual([]);
      await page.evaluate((value) => {
        window.localStorage.setItem('argos.jwt', value);
      }, JWT_LIKE);
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        /localStorage\[argos\.jwt\] holds a session token/,
      );
    },
  );

  base(
    'T-SEC-40 control: a session cookie readable through document.cookie is rejected',
    { annotation: { type: 'req', description: 'SEC-40' } },
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      await page
        .context()
        .addCookies([{ name: 'argos_rt', value: randomToken(), url: origin, httpOnly: false }]);
      const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(origin));
      await page.goto(CONTROL_PAGE);
      await expect(hooks.verify(testInfo)).rejects.toThrow(/document\.cookie holds argos_rt/);
    },
  );
});

base.describe('stack page hooks, WebSocket origin check (positive control)', () => {
  base.skip(!SERVES_OWN_PREVIEW, SKIP_REASON);

  base(
    'control: under the preview CSP a WebSocket to another origin is blocked before it exists',
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      const sockets: string[] = [];
      page.on('websocket', (socket) => sockets.push(socket.url()));
      const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(origin));
      await page.goto(CONTROL_PAGE);
      await openWebSocket(page, FOREIGN_WS);
      // connect-src 'self' blocks it: no websocket event, so the origin check below needs the
      // header removed to see a foreign socket at all.
      await expect
        .poll(() => page.evaluate(() => window.__argosCspViolations ?? []))
        .toEqual(['connect-src']);
      expect(sockets).toEqual([]);
      await expect(hooks.verify(testInfo)).rejects.toThrow(/CSP violations/);
    },
  );

  base(
    'control: a socket to the own origin passes; a socket to another origin is rejected',
    async ({ page, baseURL }, testInfo) => {
      const origin = originOf(baseURL);
      const ownWs = `${origin.replace(/^http/, 'ws')}/argos-hook-probe`;
      await stripCsp(page);
      const sockets: string[] = [];
      page.on('websocket', (socket) => sockets.push(socket.url()));
      const hooks = await installPageHooks(page, baseURL, {
        ...stackPageHookOptions(origin),
        // Without the header the CSP check has nothing to check (csp-not-verified annotation).
        requireCsp: false,
        // vite preview does not upgrade the request, and port 1 is never connected: both sockets
        // fail with a console error, exempted so the origin check is the only one that can fail.
        ignoredConsoleErrors: [failedSocket(ownWs), failedSocket(FOREIGN_WS)],
      });
      await page.goto(CONTROL_PAGE);
      await openWebSocket(page, ownWs);
      await expect.poll(() => sockets).toEqual([ownWs]);
      await expect(hooks.verify(testInfo)).resolves.toBeUndefined();
      await openWebSocket(page, FOREIGN_WS);
      await expect.poll(() => sockets).toEqual([ownWs, FOREIGN_WS]);
      await expect(hooks.verify(testInfo)).rejects.toThrow(
        `WebSocket to another origin: ${FOREIGN_WS}`,
      );
    },
  );

  base('control: the origin rule shared with the routed /ws stub (pure function)', () => {
    const page = 'http://127.0.0.1:4319';
    expect(isOwnWebSocket('ws://127.0.0.1:4319/ws', page)).toBe(true);
    expect(isOwnWebSocket('ws://127.0.0.1:4319/ws?x=1', page)).toBe(true);
    expect(isOwnWebSocket('wss://127.0.0.1:4319/ws', 'https://127.0.0.1:4319')).toBe(true);
    expect(isOwnWebSocket('wss://127.0.0.1:4319/ws', page), 'scheme of another origin').toBe(false);
    expect(isOwnWebSocket('ws://127.0.0.2:4319/ws', page), 'other host').toBe(false);
    expect(isOwnWebSocket('ws://127.0.0.1:4320/ws', page), 'other port').toBe(false);
    expect(isOwnWebSocket('ws://127.0.0.1:4319/ws', null), 'no page origin').toBe(false);
  });
});

base.describe('stack page hooks, console filter of expected API answers (positive control)', () => {
  const origin = 'http://127.0.0.1:4319';
  const failed = (status: number): string =>
    `Failed to load resource: the server responded with a status of ${String(status)} (Status)`;

  base('control: expectedApiError annotates 4xx under /api/ only (pure function)', () => {
    const filter = expectedApiError(origin);
    expect(filter(consoleMessage(failed(404), `${origin}/api/v1/apps/999`))).toBe(
      'expected API answer 404 of /api/v1/apps/999',
    );
    expect(filter(consoleMessage(failed(401), `${origin}/api/v1/auth/me`))).toBe(
      'expected API answer 401 of /api/v1/auth/me',
    );
    expect(filter(consoleMessage(failed(500), `${origin}/api/v1/apps`)), '5xx counts').toBeNull();
    expect(filter(consoleMessage(failed(503), `${origin}/api/v1/apps`)), '5xx counts').toBeNull();
    expect(
      filter(consoleMessage(failed(404), `${origin}/assets/missing.js`)),
      '4xx outside /api/ counts',
    ).toBeNull();
    expect(
      filter(consoleMessage(failed(404), 'http://127.0.0.2:4319/api/v1/apps')),
      '4xx of another origin counts',
    ).toBeNull();
    expect(
      filter(consoleMessage('TypeError: x is undefined', `${origin}/api/v1/apps`)),
      'other console errors count',
    ).toBeNull();
  });

  base.describe(() => {
    base.skip(!SERVES_OWN_PREVIEW, SKIP_REASON);

    base(
      'control: a real 404 under /api/ is annotated; a real 404 outside /api/ fails the check',
      async ({ page, baseURL }, testInfo) => {
        const pageOrigin = originOf(baseURL);
        const hooks = await installPageHooks(page, baseURL, stackPageHookOptions(pageOrigin));
        await page.goto(CONTROL_PAGE);
        const apiError = nextConsoleError(page);
        expect(await post(page, '/api/v1/argos-hook-probe')).toBe(404);
        expect(await apiError).toMatch(
          /^Failed to load resource: the server responded with a status of 404\b/,
        );
        await expect(hooks.verify(testInfo)).resolves.toBeUndefined();
        expect(
          testInfo.annotations.filter(
            (a) =>
              a.type === IGNORED_CONSOLE_ERROR &&
              (a.description ?? '').endsWith(
                '(expected API answer 404 of /api/v1/argos-hook-probe)',
              ),
          ),
        ).toHaveLength(1);
        const otherError = nextConsoleError(page);
        expect(await post(page, '/not-api/argos-hook-probe')).toBe(404);
        await otherError;
        await expect(hooks.verify(testInfo)).rejects.toThrow(
          /console error: Failed to load resource: the server responded with a status of 404/,
        );
      },
    );
  });
});
