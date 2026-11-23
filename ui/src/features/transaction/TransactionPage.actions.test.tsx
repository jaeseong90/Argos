// SPDX-License-Identifier: Apache-2.0
//
// The transaction detail screen with the HTTP boundary stubbed (test/apiStub.ts): tabs by keyboard
// write `tab=` (docs/14 §6, SEC-64), the comparison with the normal sample writes `compare=` and draws
// both timelines on a shared or own scale (05 §2.2-2), previous/next re-query the list of `back`
// through toTransactionSearchRequest (ADR-0010 C-206) or say why they cannot, the JSON export saves
// the server's bytes under its Content-Disposition name (C-176 (1), C-207 (1)), the canonical link
// with ts goes to the clipboard, the caller link uses caller.tx_id (C-207 (2)), the log link only
// through SafeLink (F-U18, SEC-61), the step side panel, and the 403 / 504 / C-50 states.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'F-U14' }, { type: 'req', description: 'F-U18' }, { type: 'req', description: 'SEC-64' }
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

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
import { untrusted } from '../../api/untrusted';
import enCommon from '../../locales/en/common.json';
import enTx from '../../locales/en/tx.json';
import { encodeQueryComponent, formatInstant } from '../../state/urlState';

import {
  baselineFixture,
  FIXTURE_TS,
  FIXTURE_TX_ID,
  profileFixture,
  sqlStepFixture,
  transactionDetailFixture,
} from './data/testing/fixtures';

const DETAIL = `GET /api/v1/transactions/${FIXTURE_TX_ID}`;
const BASELINE = `${DETAIL}/baseline`;
const EXPORT = `${DETAIL}/export`;
const SEARCH = 'POST /api/v1/apps/3/transactions/search';
const SAMPLE_ID = '7312345678900000001';
const PREV_ID = '7312345678900000100';
const NEXT_ID = '7312345678900000200';
const FIND = { timeout: 8000 };
const PATH = `/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}`;

let stub: ApiStub;

beforeAll(async () => {
  await preloadRoutes();
  await import('./TransactionPage');
}, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  signedIn(stub);
  stub.on(BASELINE, () => envelope(baselineFixture()));
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

async function loaded(path: string) {
  const app = renderApp(path);
  const section = await screen.findByTestId('transaction-page', {}, FIND);
  await waitFor(
    () => {
      expect(section).toHaveAttribute('data-state', 'success');
    },
    { timeout: 8000 },
  );
  return app;
}

describe('tabs (docs/15 §8.6, tab= of docs/14 §6)', () => {
  it('Right arrow and clicks switch the tab and write tab= to the URL', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const app = await loaded(PATH);
    const timeline = screen.getByTestId('transaction-tab-timeline');
    expect(timeline).toHaveAttribute('aria-selected', 'true');
    timeline.focus();
    await user.keyboard('{ArrowRight}');
    await waitFor(() => {
      expect(new URLSearchParams(app.url().split('?')[1]).get('tab')).toBe('tree');
    }, FIND);
    expect(await screen.findByTestId('transaction-tabpanel-tree')).toBeInTheDocument();
    expect(screen.getByTestId('transaction-tab-tree')).toHaveFocus();
    await user.click(screen.getByTestId('transaction-tab-sql'));
    await waitFor(() => {
      expect(new URLSearchParams(app.url().split('?')[1]).get('tab')).toBe('sql');
    }, FIND);
    await user.keyboard('{End}');
    await waitFor(() => {
      expect(new URLSearchParams(app.url().split('?')[1]).get('tab')).toBe('headers');
    }, FIND);
  });

  it('an invalid tab= falls back to the timeline (SEC-64)', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    await loaded(`${PATH}&tab=%3Cscript%3E`);
    expect(screen.getByTestId('transaction-tab-timeline')).toHaveAttribute('aria-selected', 'true');
  });

  it('a timeline step opens the side panel with the SQL text and parameters as sent', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () =>
      envelope(
        transactionDetailFixture({
          profile: profileFixture({
            steps: [
              sqlStepFixture(2, {
                params_mode: 'masked_by_role',
                params: untrusted('[String(9)]'),
              }),
            ],
          }),
        }),
      ),
    );
    await loaded(PATH);
    const rows = within(screen.getByTestId('transaction-timeline')).getAllByTestId('virtual-row');
    await user.click(rows[1] as HTMLElement);
    const panel = await screen.findByTestId('transaction-step-panel');
    expect(panel).toHaveAttribute('data-seq', '2');
    expect(within(panel).getByTestId('transaction-step-panel-sql').textContent).toContain(
      'SELECT * FROM orders WHERE id = ?',
    );
    expect(within(panel).getByTestId('transaction-params-masked-by-role')).toBeInTheDocument();
    // Never more than the server sent: one detail request, no other source of parameters.
    expect(stub.calls(DETAIL)).toHaveLength(1);
    await user.click(within(panel).getByTestId('transaction-step-panel-close'));
    expect(screen.queryByTestId('transaction-step-panel')).not.toBeInTheDocument();
  });
});

describe('compare with a normal request (05 §2.2-2)', () => {
  it('writes compare=<sample>, fetches it with the baseline time and toggles the scale', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture({ elapsed_ms: 100 })));
    stub.on(`GET /api/v1/transactions/${SAMPLE_ID}`, () =>
      envelope(
        transactionDetailFixture({
          tx_id: SAMPLE_ID,
          elapsed_ms: 40,
          ts: '2026-11-14T00:00:00.500Z',
        }),
      ),
    );
    const app = await loaded(PATH);
    expect(await screen.findByTestId('transaction-baseline-p50', {}, FIND)).toHaveAttribute(
      'data-value',
      '35',
    );
    expect(screen.getByTestId('transaction-baseline-p95')).toHaveAttribute('data-value', '180');
    expect(screen.getByTestId('transaction-baseline-percentile')).toHaveTextContent(
      'percentile 71',
    );
    await user.click(screen.getByTestId('transaction-compare-open'));
    await waitFor(() => {
      expect(new URLSearchParams(app.url().split('?')[1]).get('compare')).toBe(SAMPLE_ID);
    }, FIND);
    const other = await screen.findByTestId('transaction-compare-other', {}, FIND);
    expect(stub.calls(`GET /api/v1/transactions/${SAMPLE_ID}`)[0]?.query.get('ts')).toBe(
      '2026-11-14T00:00:00.500Z',
    );
    const self = screen.getByTestId('transaction-compare-this');
    // Shared scale: the longer of the two (100 ms) for both.
    expect(self).toHaveAttribute('data-total-ms', '100');
    expect(other).toHaveAttribute('data-total-ms', '100');
    await user.click(screen.getByTestId('transaction-compare-scale-own'));
    expect(screen.getByTestId('transaction-compare-other')).toHaveAttribute('data-total-ms', '50');
    await user.click(screen.getByTestId('transaction-compare-close'));
    await waitFor(() => {
      expect(new URLSearchParams(app.url().split('?')[1]).has('compare')).toBe(false);
    }, FIND);
  });

  it('no comparable requests: the panel explains it', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    stub.on(BASELINE, () =>
      envelope(
        baselineFixture({
          service_count: 0,
          service_p50_ms: null,
          service_p95_ms: null,
          percentile_of_this: null,
          sample_normal_tx_id: null,
          sample_normal_ts: null,
        }),
      ),
    );
    await loaded(PATH);
    expect(await screen.findByTestId('transaction-baseline-empty', {}, FIND)).toHaveTextContent(
      enTx.baseline.empty,
    );
  });
});

describe('previous / next (C-206)', () => {
  const back =
    '/apps/3/xlog?from=2026-11-13T23:00:00.000Z&to=2026-11-14T01:00:00.000Z&sel=ms:0-5000,ts:1763078400000-1763082000000';

  it('re-queries the list of back with its selection and opens the neighbour keeping back', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    stub.on(`GET /api/v1/transactions/${NEXT_ID}`, () =>
      envelope(transactionDetailFixture({ tx_id: NEXT_ID })),
    );
    const row = (txId: string, ts: string) => ({ tx_id: txId, ts, elapsed_ms: 10 });
    stub.on(SEARCH, () =>
      envelope(
        [
          row(PREV_ID, '2026-11-14T00:00:02.000Z'),
          row(FIXTURE_TX_ID, FIXTURE_TS),
          row(NEXT_ID, '2026-11-14T00:00:00.250Z'),
        ],
        { total: 3 },
      ),
    );
    const app = await loaded(`${PATH}&back=${encodeQueryComponent(back)}`);
    const next = await screen.findByTestId('transaction-next', {}, FIND);
    await waitFor(() => {
      expect(next).toBeEnabled();
    }, FIND);
    expect(screen.getByTestId('transaction-prev')).toHaveAttribute('data-target', PREV_ID);
    expect(screen.getByTestId('transaction-adjacent-position')).toHaveTextContent('Row 2 of 3');
    const body = stub.calls(SEARCH)[0]?.body as {
      from: string;
      to: string;
      filters: { min_ms: number; max_ms: number };
      sort: string;
    };
    expect(body.from).toBe(formatInstant(1763078400000));
    expect(body.to).toBe(formatInstant(1763082000001));
    expect(body.filters).toMatchObject({ min_ms: 0, max_ms: 5000 });
    expect(body.sort).toBe('elapsed_ms:desc');
    next.focus();
    await user.keyboard('{Enter}');
    await waitFor(() => {
      expect(app.url().startsWith(`/apps/3/transactions/${NEXT_ID}?`)).toBe(true);
    }, FIND);
    const params = new URLSearchParams(app.url().split('?')[1]);
    expect(params.get('ts')).toBe('2026-11-14T00:00:00.250Z');
    expect(params.get('back')).toBe(back);
  });

  it('without back the buttons are disabled and say why', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    await loaded(PATH);
    expect(screen.getByTestId('transaction-prev')).toBeDisabled();
    expect(screen.getByTestId('transaction-next')).toBeDisabled();
    expect(screen.getByTestId('transaction-adjacent-reason')).toHaveAttribute(
      'data-state',
      'no_list',
    );
    expect(stub.calls(SEARCH)).toHaveLength(0);
  });

  it('a transaction that is not in the re-queried list disables them with that reason', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    stub.on(SEARCH, () =>
      envelope([{ tx_id: PREV_ID, ts: FIXTURE_TS, elapsed_ms: 1 }], { total: 1 }),
    );
    await loaded(`${PATH}&back=${encodeQueryComponent(back)}`);
    await waitFor(() => {
      expect(screen.getByTestId('transaction-adjacent-reason')).toHaveAttribute(
        'data-state',
        'not_in_list',
      );
    }, FIND);
  });
});

describe('actions (05 §2.2-10)', () => {
  it('JSON export saves the server bytes under the Content-Disposition name', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const json = JSON.stringify({ tx_id: FIXTURE_TX_ID });
    stub.on(
      EXPORT,
      () =>
        new Response(json, {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'Content-Disposition': `attachment; filename="transaction-${FIXTURE_TX_ID}.json"`,
          },
        }),
    );
    const saved: { name: string; blob: Blob | null } = { name: '', blob: null };
    Object.assign(URL, {
      createObjectURL: vi.fn((blob: Blob) => {
        saved.blob = blob;
        return 'blob:test';
      }),
      revokeObjectURL: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      saved.name = this.download;
    });
    await loaded(PATH);
    const link = screen.getByTestId('transaction-export');
    expect(link.getAttribute('href')).toBe(
      `/api/v1/transactions/${FIXTURE_TX_ID}/export?ts=${encodeURIComponent(FIXTURE_TS)}`,
    );
    await user.click(link);
    await waitFor(() => {
      expect(screen.getByTestId('transaction-export-status')).toHaveTextContent(
        `transaction-${FIXTURE_TX_ID}.json`,
      );
    }, FIND);
    expect(saved.name).toBe(`transaction-${FIXTURE_TX_ID}.json`);
    expect(await saved.blob?.text()).toBe(json);
    expect(stub.calls(EXPORT)[0]?.query.get('ts')).toBe(FIXTURE_TS);
  });

  it('a rate-limited export says when to try again (no retry loop)', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    stub.on(EXPORT, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' } }));
    await loaded(PATH);
    await user.click(screen.getByTestId('transaction-export'));
    await waitFor(() => {
      expect(screen.getByTestId('transaction-export-status')).toHaveTextContent(
        'Try again in 30 s',
      );
    }, FIND);
    expect(stub.calls(EXPORT)).toHaveLength(1);
  });

  it('copy link puts the canonical URL with ts (and the tab) on the clipboard', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    await loaded(`${PATH}&tab=sql&back=${encodeQueryComponent('/apps/3/xlog')}`);
    await user.click(screen.getByTestId('transaction-copy-link'));
    await waitFor(() => {
      expect(screen.getByTestId('transaction-copy-link-status')).toHaveTextContent(
        enCommon.component.codeBlock.copied,
      );
    }, FIND);
    expect(await navigator.clipboard.readText()).toBe(
      `${window.location.origin}/apps/3/transactions/${FIXTURE_TX_ID}?ts=${FIXTURE_TS}&tab=sql`,
    );
  });

  it('go to caller uses caller.tx_id; without a caller it is disabled with the reason', async () => {
    stub.on(DETAIL, () =>
      envelope(
        transactionDetailFixture({
          caller: {
            app: untrusted('gateway'),
            app_id: 1,
            instance_id: 4,
            service: untrusted('POST /checkout'),
            ts: '2026-11-14T00:00:00.900Z',
            tx_id: '7312345678900000002',
          },
        }),
      ),
    );
    const { unmount } = await loaded(PATH);
    // The caller's canonical route with its ts hint and the range the detail's links carry.
    const callerUrl = new URL(
      screen.getByTestId('transaction-caller').getAttribute('href') ?? '',
      window.location.origin,
    );
    expect(callerUrl.pathname).toBe('/apps/1/transactions/7312345678900000002');
    expect(callerUrl.searchParams.get('ts')).toBe('2026-11-14T00:00:00.900Z');
    const traceUrl = new URL(
      screen.getByTestId('transaction-trace-link').getAttribute('href') ?? '',
      window.location.origin,
    );
    expect(callerUrl.searchParams.get('from')).toBe(traceUrl.searchParams.get('from'));
    expect(callerUrl.searchParams.get('to')).toBe(traceUrl.searchParams.get('to'));
    unmount();
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    await loaded(PATH);
    expect(screen.getByTestId('transaction-caller')).toBeDisabled();
    // The reason is visible text next to the disabled button (it takes no focus), and the button
    // refers to it for screen readers.
    const callerReason = screen.getByTestId('transaction-caller-reason');
    expect(callerReason).toHaveTextContent(enTx.actions.callerNone);
    expect(callerReason).not.toHaveClass('visually-hidden');
    expect(callerReason).toBeVisible();
    expect(screen.getByTestId('transaction-caller')).toHaveAttribute(
      'aria-describedby',
      callerReason.id,
    );
  });

  it('open logs only through SafeLink: javascript: stays text, https opens a new tab', async () => {
    const js = ['java', 'script:window.__xss=1'].join('');
    stub.on(DETAIL, () => envelope(transactionDetailFixture({ links: { trace: null, logs: js } })));
    const first = await loaded(PATH);
    const blocked = screen.getByTestId('transaction-logs');
    expect(blocked.tagName).toBe('SPAN');
    expect(blocked.closest('a')).toBeNull();
    first.unmount();
    stub.on(DETAIL, () =>
      envelope(
        transactionDetailFixture({
          links: { trace: null, logs: 'https://logs.example/search?q=trace_id:4bf9' },
        }),
      ),
    );
    await loaded(PATH);
    const link = screen.getByTestId('transaction-logs');
    expect(link).toHaveAttribute('href', 'https://logs.example/search?q=trace_id:4bf9');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('without a log link template the action is disabled and says so', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    await loaded(PATH);
    expect(screen.getByTestId('transaction-logs-unavailable')).toBeDisabled();
    const logsReason = screen.getByTestId('transaction-logs-reason');
    expect(logsReason).toHaveTextContent(enTx.actions.logsUnavailable);
    expect(logsReason).not.toHaveClass('visually-hidden');
    expect(logsReason).toBeVisible();
    expect(screen.getByTestId('transaction-logs-unavailable')).toHaveAttribute(
      'aria-describedby',
      logsReason.id,
    );
  });
});

describe('states (docs/15 §6.7, ADR-0007 C-50)', () => {
  it.each([
    [403, 'forbidden', enCommon.component.error.forbidden],
    [504, 'query_timeout', enCommon.component.error.timeout],
  ])('%i %s renders the error state with the next action', async (status, code, text) => {
    stub.on(DETAIL, () => errorBody(status, code));
    renderApp(PATH);
    const section = await screen.findByTestId('transaction-page', {}, FIND);
    await waitFor(() => {
      expect(section).toHaveAttribute('data-state', 'error');
    }, FIND);
    expect(screen.getByTestId('transaction-error-state')).toHaveTextContent(text);
  });

  it('C-50: not found with a ts hint offers the XLog around that time', async () => {
    const user = userEvent.setup();
    stub.on(DETAIL, () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'clock_skew_possible' }] }),
    );
    const app = renderApp(PATH);
    await screen.findByTestId('transaction-not-found', {}, FIND);
    await user.click(screen.getByTestId('transaction-not-found-xlog'));
    await waitFor(() => {
      expect(app.url()).toBe(
        '/apps/3/xlog?from=2026-11-13T23:45:00.000Z&to=2026-11-14T00:16:00.000Z&kind=ALL',
      );
    }, FIND);
  });
});
