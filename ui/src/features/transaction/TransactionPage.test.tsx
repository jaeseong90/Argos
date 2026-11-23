// SPDX-License-Identifier: Apache-2.0
//
// The transaction detail route (docs/15 §3.1, §9.4, ADR-0010 C-166, C-206): the registry loads the
// screen (no placeholder), the detail is fetched with the ts hint, the /tx alias redirects with one
// request in total, an id of another application moves to that application's route, and a missing
// transaction shows the C-50 guidance with the way back to the list. The whole app runs with the
// HTTP boundary stubbed (test/apiStub.ts).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'F-U14' }, { type: 'req', description: 'D-11' }
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import enTx from '../../locales/en/tx.json';
import { encodeQueryComponent } from '../../state/urlState';

import { FIXTURE_TS, FIXTURE_TX_ID, transactionDetailFixture } from './data/testing/fixtures';

const DETAIL = `GET /api/v1/transactions/${FIXTURE_TX_ID}`;
const FIND = { timeout: 5000 };

let stub: ApiStub;

beforeAll(async () => {
  await preloadRoutes();
  await import('./TransactionPage');
}, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  signedIn(stub);
});

afterEach(() => {
  uninstallApiStub();
});

async function page(path: string) {
  const app = renderApp(path);
  const section = await screen.findByTestId('transaction-page', {}, FIND);
  return { app, section };
}

describe('transaction detail route (docs/15 §9.4, C-166)', () => {
  it('loads the screen (not the placeholder) and fetches the detail with the ts hint', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const { section } = await page(`/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}`);
    expect(screen.queryByTestId('route-pending')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(section).toHaveAttribute('data-state', 'success');
    }, FIND);
    expect(screen.getByTestId('transaction-service')).toHaveTextContent('GET /api/orders/{id}');
    expect(screen.getByTestId('transaction-id')).toHaveTextContent(FIXTURE_TX_ID);
    expect(stub.calls(DETAIL).map((r) => r.query.get('ts'))).toEqual([FIXTURE_TS]);
  });

  it('the /tx alias redirects to the app route and the page reuses its answer (one request)', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const app = renderApp(`/tx/${FIXTURE_TX_ID}?tab=sql`);
    const section = await screen.findByTestId('transaction-page', {}, FIND);
    await waitFor(() => {
      expect(section).toHaveAttribute('data-state', 'success');
    }, FIND);
    expect(app.url()).toBe(`/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}&tab=sql`);
    expect(stub.calls(DETAIL)).toHaveLength(1);
  });

  it('moves a transaction opened under another application to its own application', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const { app } = await page(`/apps/1/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}&tab=sql`);
    await waitFor(() => {
      expect(app.url()).toBe(`/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}&tab=sql`);
    }, FIND);
  });

  it('C-50: a transaction not near its id time explains the clock and leads back to the list', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'clock_skew_possible' }] }),
    );
    const back = '/apps/3/xlog?from=now-1h&sel=ms:0-100,ts:1763078400000-1763078700000';
    const { app } = await page(
      `/apps/3/transactions/${FIXTURE_TX_ID}?back=${encodeQueryComponent(back)}`,
    );
    const notFound = await screen.findByTestId('transaction-not-found', {}, FIND);
    expect(notFound).toHaveTextContent(enTx.notFound.clockSkew);
    await user.click(screen.getByTestId('transaction-back-to-list'));
    await waitFor(() => {
      expect(app.url()).toBe(back);
    }, FIND);
  });

  it('with a ts hint the not-found text does not repeat the ts advice; without back it offers XLog', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'clock_skew_possible' }] }),
    );
    const { app } = await page(`/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}`);
    const notFound = await screen.findByTestId('transaction-not-found', {}, FIND);
    expect(notFound).toHaveTextContent(enTx.notFound.hinted);
    await user.click(screen.getByTestId('transaction-open-xlog'));
    await waitFor(() => {
      expect(app.url()).toBe('/apps/3/xlog');
    }, FIND);
  });

  it('requests nothing for a malformed id and says it is not a transaction id', async () => {
    await page('/apps/3/transactions/not-a-number');
    expect(await screen.findByTestId('transaction-not-found', {}, FIND)).toHaveTextContent(
      enTx.notFound.invalidId,
    );
    expect(stub.requests.filter((r) => r.path.startsWith('/api/v1/transactions'))).toEqual([]);
  });
});
