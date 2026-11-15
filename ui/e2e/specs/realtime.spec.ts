// SPDX-License-Identifier: Apache-2.0
//
// The realtime client against the docs/14 §5 WebSocket stub (ADR-0010 C-167 (3): WO-22's server is
// not in the slice; the real-server scenarios T-SEC-44 1 s close and T-SV-020 belong to WO-22):
// stale badge after 10 s without frames, recovery after a reconnect with back-off, and close 4401 /
// reauth -> sign-in page with no new connection after the close (docs/15 §10, SEC-44 client part;
// the reconnect ban while subscriptions remain is proven by src/realtime/WsManager.test.ts).
import type { Page } from '@playwright/test';

import { expect, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';
import type { WsStub } from '../fixtures/ws';

const RT = { type: 'req', description: 'SEC-44' };

async function openLive(page: Page, appId: number, ws: WsStub): Promise<void> {
  await page.goto(`/apps/${String(appId)}/dashboard?from=now-15m&live=1`);
  await expectShell(page);
  await ws.waitForSubscription();
}

test(
  'T-E2E-050 the stale badge appears after 10 s without frames and goes away with the next frame',
  { annotation: [RT, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed, ws }) => {
    await openLive(page, seed.primary_app_id, ws);
    const subscribed = Date.now();
    const badge = page.getByTestId('ws-stale-badge');
    await expect(badge).toBeHidden();
    await expect(badge).toBeVisible({ timeout: 20_000 });
    const after = Date.now() - subscribed;
    // docs/15 §5: "갱신 중단 N초" from 10 s on (the ack was the last frame).
    expect(after).toBeGreaterThanOrEqual(9_000);
    const subscription = ws.subscriptions()[0];
    expect(subscription).toBeDefined();
    expect(ws.push(subscription?.topic ?? 'counter', {})).toBeGreaterThan(0);
    await expect(badge).toBeHidden({ timeout: 5_000 });
  },
);

test(
  'T-E2E-051 a dropped connection is reconnected with back-off and every subscription is sent again',
  { annotation: RT },
  async ({ page, seed, ws }) => {
    await openLive(page, seed.primary_app_id, ws);
    const before = ws
      .subscriptions()
      .map((s) => `${s.topic} ${JSON.stringify(s.params)}`)
      .sort();
    const dropped = Date.now();
    ws.drop();
    await ws.waitForConnections(2, 15_000);
    const reconnectedAfter = ws.connections()[1]?.openedAt ?? 0;
    // Back-off 1 s with +-20 % jitter before the first retry (docs/15 §10).
    expect(reconnectedAfter - dropped).toBeGreaterThanOrEqual(700);
    await ws.waitForSubscription();
    await expect
      .poll(() =>
        ws
          .subscriptions()
          .map((s) => `${s.topic} ${JSON.stringify(s.params)}`)
          .sort(),
      )
      .toEqual(before);
    // The badge is not shown while frames arrive on the new connection.
    const subscription = ws.subscriptions()[0];
    ws.push(subscription?.topic ?? 'counter', {});
    await expect(page.getByTestId('ws-stale-badge')).toBeHidden();
  },
);

test(
  'T-E2E-052 while the server is silent (pause) the badge appears; frames after the pause clear it',
  { annotation: RT },
  async ({ page, seed, ws }) => {
    await openLive(page, seed.primary_app_id, ws);
    ws.pause(15_000);
    await expect(page.getByTestId('ws-stale-badge')).toBeVisible({ timeout: 20_000 });
    await expect
      .poll(() => ws.push(ws.subscriptions()[0]?.topic ?? 'counter', {}), { timeout: 10_000 })
      .toBeGreaterThan(0);
    await expect(page.getByTestId('ws-stale-badge')).toBeHidden({ timeout: 5_000 });
  },
);

/** Unit tests that prove the reconnect ban while subscriptions remain (E2E cannot tell it apart). */
const NO_RECONNECT_UNIT = 'src/realtime/WsManager.test.ts';
const UNIT_4401 = 'T-SEC-44 close 4401 does not reconnect and reports the expired session';
const UNIT_REAUTH =
  'T-SEC-44 the reauth frame (ADR-0010 C-167 (2)) closes, does not reconnect and expires the session';

/**
 * Watches the stub for a fixed observation window and fails as soon as the number of connections
 * differs from `expected` (the count taken before the close, so a reconnect between the close and
 * the navigation to /login counts as well).
 */
async function expectNoNewConnection(
  page: Page,
  ws: WsStub,
  expected: number,
  windowMs: number,
  what: string,
): Promise<void> {
  const end = Date.now() + windowMs;
  for (;;) {
    expect(ws.connectionCount(), what).toBe(expected);
    if (Date.now() >= end) {
      return;
    }
    await page.waitForTimeout(100);
  }
}

// After /login the shell is unmounted and holds no subscription, and the client's back-off timer
// opens a connection only while subscriptions remain. So these E2E tests catch a reconnect started
// by the close itself (or before the navigation); that 4401 / reauth also bans the back-off
// reconnect while subscriptions remain is proven by the WsManager unit tests named in the
// `coverage` annotation.
test(
  'T-SEC-44 close 4401 -> /login?next with the current URL; no new connection after the close',
  {
    tag: '@security',
    annotation: [
      RT,
      {
        type: 'coverage',
        description: `reconnect ban while subscriptions remain is proven by ${NO_RECONNECT_UNIT} (${UNIT_4401})`,
      },
    ],
  },
  async ({ page, seed, ws }) => {
    await openLive(page, seed.primary_app_id, ws);
    const path = new URL(page.url()).pathname;
    const before = ws.connectionCount();
    expect(before, 'connections of the live dashboard before the close').toBe(1);
    ws.close(4401, 'unauthenticated');
    await expect(page).toHaveURL(/\/login\?next=/, { timeout: 10_000 });
    expect((new URL(page.url()).searchParams.get('next') ?? '').startsWith(path)).toBe(true);
    // Back-off would retry after 1 s +-20 % (docs/15 §10): 5 s after the navigation cover it.
    await expectNoNewConnection(page, ws, before, 5_000, 'connections opened after close 4401');
  },
);

test(
  'T-SEC-44a the reauth frame (ADR-0010 C-167 (2)) -> /login?next with the current URL; no new connection after the frame',
  {
    tag: '@security',
    annotation: [
      RT,
      {
        type: 'coverage',
        description: `reconnect ban while subscriptions remain is proven by ${NO_RECONNECT_UNIT} (${UNIT_REAUTH})`,
      },
    ],
  },
  async ({ page, seed, ws }) => {
    await openLive(page, seed.primary_app_id, ws);
    const path = new URL(page.url()).pathname;
    const before = ws.connectionCount();
    expect(before, 'connections of the live dashboard before the reauth frame').toBe(1);
    ws.reauth();
    await expect(page).toHaveURL(/\/login\?next=/, { timeout: 10_000 });
    expect((new URL(page.url()).searchParams.get('next') ?? '').startsWith(path)).toBe(true);
    await expectNoNewConnection(
      page,
      ws,
      before,
      5_000,
      'connections opened after the reauth frame',
    );
  },
);
