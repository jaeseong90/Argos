// SPDX-License-Identifier: Apache-2.0
//
// T-UI-005 on the whole transaction detail screen (SEC-60, SEC-61): every docs/16 §9.4 / docs/18 §4.3
// XSS seed payload in the collected strings of the steps (METHOD name, SQL text, parameters and
// target, HTTP host and path, DBC pool and target, ERROR class and frames, STACK frames), of the
// summary (service, thread) and of the baseline renders as text along the screen's flow: the
// timeline rows, each step's side panel, the call tree tab and its side panel, and the comparison
// with a normal request whose service and steps carry the payload too. At each stage the screen has
// no injected element, event attribute or non-http link, window.__xss stays unset and the payload is
// in the text. The HTTP boundary is stubbed (test/apiStub.ts); the screen runs unchanged.
// Requirements (test/README.md §5): { type: 'req', description: 'T-UI-005' }, { type: 'req', description: 'SEC-60' }, { type: 'req', description: 'SEC-61' }
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { untrusted, type Untrusted } from '../../api/untrusted';
import { findInjectedMarkup, XSS_PAYLOADS } from '../../components/testing/xssPayloads';

import {
  baselineFixture,
  FIXTURE_TS,
  FIXTURE_TX_ID,
  profileFixture,
  sqlStepFixture,
  stepFixture,
  transactionDetailFixture,
} from './data/testing/fixtures';
import type { TransactionDetail, TransactionStep } from './data/transactionApi';

const DETAIL = `GET /api/v1/transactions/${FIXTURE_TX_ID}`;
const BASELINE = `${DETAIL}/baseline`;
const SAMPLE_ID = '7312345678900000001';
const SAMPLE_TS = '2026-11-14T00:00:00.500Z';
const PATH = `/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}`;
const FIND = { timeout: 8000 };

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
  Reflect.deleteProperty(window, '__xss');
});

/** Steps of every type with the payload in each collected string (start order = seq order). */
function payloadSteps(value: Untrusted): TransactionStep[] {
  return [
    stepFixture({
      seq: 1,
      type: 'METHOD',
      start_offset_ms: 0,
      elapsed_ms: 90,
      name: value,
      name_id: '1',
    }),
    stepFixture({
      seq: 2,
      parent_seq: 1,
      type: 'DBC',
      start_offset_ms: 2,
      elapsed_ms: 3,
      name: value,
      target: value,
    }),
    sqlStepFixture(3, {
      parent_seq: 1,
      start_offset_ms: 5,
      elapsed_ms: 10,
      sql: value,
      params: value,
      params_mode: 'partial',
      target: value,
    }),
    stepFixture({
      seq: 4,
      parent_seq: 1,
      type: 'HTTP',
      start_offset_ms: 20,
      elapsed_ms: 10,
      name: value,
      url: value,
      name_id: '5',
      status: 502,
      error: true,
    }),
    stepFixture({
      seq: 5,
      parent_seq: 1,
      type: 'ERROR',
      start_offset_ms: 80,
      name: value,
      frames: [value, untrusted('java.lang.Thread.run(Thread.java)')],
      error: true,
    }),
    stepFixture({ seq: 6, type: 'STACK', start_offset_ms: 50, frames: [value] }),
  ];
}

function payloadDetail(value: Untrusted, overrides: Partial<TransactionDetail> = {}) {
  return transactionDetailFixture({
    service: value,
    thread: value,
    error: true,
    error_class: value,
    profile: profileFixture({ steps: payloadSteps(value), self_time_ms: 10, self_time_ratio: 0.1 }),
    ...overrides,
  });
}

/** The screen is inert and shows the payload as text. */
function expectInert(payload: string, stage: string): void {
  const page = screen.getByTestId('transaction-page');
  expect(findInjectedMarkup(page), stage).toEqual([]);
  expect((window as { __xss?: unknown }).__xss, stage).toBeUndefined();
  expect(page.textContent, stage).toContain(payload);
}

describe('T-UI-005: the detail screen renders collected strings as text along its flow', () => {
  it.each(XSS_PAYLOADS)(
    'payload %j in timeline rows, side panels, the call tree and the comparison',
    async (payload) => {
      const user = userEvent.setup();
      const value = untrusted(payload);
      stub.on(DETAIL, () => envelope(payloadDetail(value)));
      stub.on(BASELINE, () =>
        envelope(
          baselineFixture({
            service: value,
            sample_normal_tx_id: SAMPLE_ID,
            sample_normal_ts: SAMPLE_TS,
          }),
        ),
      );
      stub.on(`GET /api/v1/transactions/${SAMPLE_ID}`, () =>
        envelope(payloadDetail(value, { tx_id: SAMPLE_ID, ts: SAMPLE_TS, error: false })),
      );
      const app = renderApp(PATH);
      const section = await screen.findByTestId('transaction-page', {}, FIND);
      await waitFor(() => {
        expect(section).toHaveAttribute('data-state', 'success');
      }, FIND);
      expectInert(payload, 'loaded');

      // Timeline: every step row opens its side panel.
      const timeline = screen.getByTestId('transaction-timeline');
      const stepRows = within(timeline).getAllByTestId('virtual-row').slice(1);
      expect(stepRows.length).toBeGreaterThanOrEqual(6);
      for (const row of stepRows) {
        await user.click(row);
        const panel = await screen.findByTestId('transaction-step-panel', {}, FIND);
        expectInert(payload, `timeline panel of step ${panel.getAttribute('data-seq') ?? ''}`);
      }

      // Call tree tab: rows and a step's side panel.
      await user.click(screen.getByTestId('transaction-tab-tree'));
      const tree = await screen.findByTestId('transaction-tree', {}, FIND);
      expectInert(payload, 'tree');
      const treeRows = within(tree).getAllByTestId('virtual-row');
      await user.click(treeRows[treeRows.length - 1] as HTMLElement);
      await screen.findByTestId('transaction-step-panel', {}, FIND);
      expectInert(payload, 'tree panel');

      // Comparison with the normal request (its service and steps carry the payload).
      await user.click(await screen.findByTestId('transaction-compare-open', {}, FIND));
      await waitFor(() => {
        expect(new URLSearchParams(app.url().split('?')[1]).get('compare')).toBe(SAMPLE_ID);
      }, FIND);
      const other = await screen.findByTestId('transaction-compare-other', {}, FIND);
      expect(within(other).getAllByTestId('virtual-row').length).toBeGreaterThan(1);
      expect(screen.getByTestId('transaction-compare').textContent).toContain(payload);
      expectInert(payload, 'compare');
    },
    30_000,
  );
});
