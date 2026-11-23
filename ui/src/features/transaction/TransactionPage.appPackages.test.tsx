// SPDX-License-Identifier: Apache-2.0
//
// Application frames of the transaction detail (docs/15 §8.5, 05 §2.2-8, ADR-0010 C-207 (3)): the
// stack and error tabs put the frames of the application's `app_packages` (GET /apps/{appId}) in
// bold, look-alike packages and library frames stay plain, and with no packages set nothing is bold
// and the error tab says why. The whole app runs with the HTTP boundary stubbed (test/apiStub.ts).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }
import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../test/apiStub';
import {
  appBody,
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { untrusted } from '../../api/untrusted';
import enTx from '../../locales/en/tx.json';

import {
  FIXTURE_TS,
  FIXTURE_TX_ID,
  profileFixture,
  stepFixture,
  transactionDetailFixture,
} from './data/testing/fixtures';

const DETAIL = `GET /api/v1/transactions/${FIXTURE_TX_ID}`;
const FIND = { timeout: 5000 };

const PAY = 'com.shop.order.OrderService.pay(OrderService.java:88)';
const CONTROLLER = 'com.shop.web.OrderController.pay(OrderController.java:41)';
const FRAMES = [
  PAY,
  'org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:186)',
  'com.shopping.cart.Cart.add(Cart.java:12)',
  CONTROLLER,
];
const APP_FRAMES = [PAY, CONTROLLER];

let stub: ApiStub;

beforeAll(async () => {
  await preloadRoutes();
  await import('./TransactionPage');
}, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  signedIn(stub);
  const frames = FRAMES.map((frame) => untrusted(frame));
  stub.on(DETAIL, () =>
    envelope(
      transactionDetailFixture({
        error: true,
        error_class: untrusted('java.lang.IllegalStateException'),
        profile: profileFixture({
          steps: [
            stepFixture({ seq: 1, type: 'STACK', start_offset_ms: 1000, frames }),
            stepFixture({ seq: 2, type: 'STACK', start_offset_ms: 4000, frames }),
            stepFixture({
              seq: 3,
              type: 'ERROR',
              start_offset_ms: 4500,
              error: true,
              name: untrusted('java.lang.IllegalStateException'),
              frames,
            }),
          ],
          total_steps: 3,
        }),
      }),
    ),
  );
});

afterEach(() => {
  uninstallApiStub();
});

/** Answers GET /apps/3 (the fixture's application) with these packages. */
function packages(appPackages: readonly string[]): void {
  stub.on('GET /api/v1/apps/3', () =>
    envelope(appBody(3, 'billing', 'prod', { app_packages: appPackages })),
  );
}

async function open(tab: 'stack' | 'error'): Promise<HTMLElement> {
  renderApp(`/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}&tab=${tab}`);
  const section = await screen.findByTestId('transaction-page', {}, FIND);
  await waitFor(() => {
    expect(section).toHaveAttribute('data-state', 'success');
  }, FIND);
  return section;
}

function boldFrames(root: HTMLElement): (string | null)[] {
  return Array.from(root.querySelectorAll('.tx-frame--app')).map((frame) => frame.textContent);
}

describe('transaction detail application frames (docs/15 §8.5, C-207 (3))', () => {
  it('C-207 (3) stack tab: frames of the app_packages are bold in every capture', async () => {
    packages(['com.shop']);
    await open('stack');
    const columns = await screen.findAllByTestId('transaction-stack-column', {}, FIND);
    expect(columns).toHaveLength(2);
    for (const column of columns) {
      await waitFor(() => {
        expect(column.querySelectorAll('[data-app="true"]')).toHaveLength(2);
      }, FIND);
      const frames = within(column).getAllByTestId('transaction-frame');
      // Common frames carry the "stuck here" mark text too; compare the frame text only.
      expect(
        frames.filter((f) => f.getAttribute('data-app') === 'true').map((f) => f.textContent),
      ).toEqual(APP_FRAMES.map((frame) => expect.stringContaining(frame) as unknown));
      // com.shopping is not com.shop; Spring is a library.
      expect(frames[1]).not.toHaveAttribute('data-app', 'true');
      expect(frames[2]).not.toHaveAttribute('data-app', 'true');
    }
  });

  it('C-207 (3) error tab: frames of the app_packages are bold and the "none set" note is gone', async () => {
    packages(['com.shop']);
    const section = await open('error');
    const frames = await screen.findByTestId('transaction-error-frames', {}, FIND);
    await waitFor(() => {
      expect(boldFrames(frames)).toEqual(APP_FRAMES);
    }, FIND);
    expect(within(section).queryByTestId('transaction-error-app-frames-note')).toBeNull();
  });

  it('C-207 (3) with no app_packages nothing is bold and the error tab says why', async () => {
    packages([]);
    const section = await open('error');
    const frames = await screen.findByTestId('transaction-error-frames', {}, FIND);
    expect(within(frames).getAllByTestId('transaction-frame')).toHaveLength(FRAMES.length);
    expect(boldFrames(frames)).toEqual([]);
    expect(within(section).getByTestId('transaction-error-app-frames-note')).toHaveTextContent(
      enTx.error.appFramesUnavailable,
    );
  });

  it('C-207 (3) stack tab with no app_packages marks no frame', async () => {
    packages([]);
    await open('stack');
    const columns = await screen.findAllByTestId('transaction-stack-column', {}, FIND);
    for (const column of columns) {
      expect(boldFrames(column)).toEqual([]);
    }
  });
});
