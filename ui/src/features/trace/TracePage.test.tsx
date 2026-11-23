// SPDX-License-Identifier: Apache-2.0
//
// The trace view (docs/15 §9.5, 05 §2.8, docs/14 §4.7) with the whole app and the HTTP boundary
// stubbed (test/apiStub.ts), on typed fixtures of the contract. It covers what the E2E seed cannot
// produce before WO-14 and the profile cap work (ADR-0010 C-207 (5)): three-hop traces, external
// calls with failures, missing nodes of both reasons, LOST profiles, the D-05 violation, the
// client-side critical path and truncation; plus links, keyboard, copy, export, the 7/30-day
// searches and XSS payloads in every collected string (T-UI-005).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U03' }, { type: 'req', description: 'F-U14' }, { type: 'req', description: 'D-05' }, { type: 'req', description: 'D-07' }, { type: 'req', description: 'SEC-60' }
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
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
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../../components/testing/xssPayloads';
import enCommon from '../../locales/en/common.json';
import enTrace from '../../locales/en/trace.json';

import {
  CALLEE_TX_ID,
  FIXTURE_TRACE_ID,
  ROOT_TX_ID,
  traceFixture,
  traceNodeFixture,
} from './data/testing/fixtures';
import type { Trace } from './data/traceApi';

const TRACE = `GET /api/v1/traces/${FIXTURE_TRACE_ID}`;
const TS = '2026-11-14T00:00:01.000Z';
const FIND = { timeout: 5000 };

let stub: ApiStub;

beforeAll(async () => {
  await preloadRoutes();
  await import('./TracePage');
}, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  signedIn(stub);
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

async function openTrace(trace: Trace, meta: Record<string, unknown> = {}, query = `?ts=${TS}`) {
  stub.on(TRACE, () => envelope(trace, meta));
  const app = renderApp(`/traces/${FIXTURE_TRACE_ID}${query}`);
  const section = await screen.findByTestId('trace-page', {}, FIND);
  await waitFor(() => {
    expect(section).toHaveAttribute('data-state', 'success');
  }, FIND);
  return app;
}

/** The trace-node label of a transaction row. */
function nodeOf(txId: string): HTMLElement {
  const node = screen
    .getAllByTestId('trace-node')
    .find((element) => element.getAttribute('data-tx-id') === txId);
  if (node === undefined) {
    throw new Error(`no node ${txId}`);
  }
  return node;
}

/** The virtual-list row element that holds a label element. */
function rowOf(element: HTMLElement): HTMLElement {
  const row = element.closest<HTMLElement>('[data-testid="trace-row"]');
  if (row === null) {
    throw new Error('not inside a trace row');
  }
  return row;
}

function threeHop(): Trace {
  return traceFixture({
    nodes: [
      traceNodeFixture({ tx_id: '100', ts: '2026-11-14T00:00:01.000Z', elapsed_ms: 300 }),
      traceNodeFixture({
        tx_id: '200',
        app_id: 4,
        app: untrusted('stock-api'),
        ts: '2026-11-14T00:00:01.020Z',
        elapsed_ms: 200,
        parent_tx_id: '100',
        caller_step: { elapsed_ms: 240, network_wait_ms: 40 },
      }),
      traceNodeFixture({
        tx_id: '300',
        app_id: 5,
        app: untrusted('price-api'),
        ts: '2026-11-14T00:00:01.050Z',
        elapsed_ms: 100,
        parent_tx_id: '200',
        caller_step: { elapsed_ms: 110, network_wait_ms: 10 },
        profile_state: 'lost',
      }),
    ],
    root_tx_id: '100',
    critical_path: ['100', '200', '300'],
  });
}

describe('trace route (docs/15 §9.5)', () => {
  it('loads the screen and fetches the trace with the ts hint and window of the URL', async () => {
    await openTrace(traceFixture(), {}, `?ts=${TS}&window=24h`);
    expect(screen.queryByTestId('route-pending')).not.toBeInTheDocument();
    expect(screen.getByTestId('trace-id')).toHaveTextContent(FIXTURE_TRACE_ID);
    const query = stub.calls(TRACE)[0]?.query;
    expect(query?.get('ts')).toBe(TS);
    expect(query?.get('window')).toBe('24h');
  });

  it('a trace not in the searched range offers the last 7 days (window=7d in the URL)', async () => {
    const user = userEvent.setup();
    stub.on(TRACE, () =>
      errorBody(404, 'not_found', { details: [{ field: 'trace_id', reason: 'not_in_window' }] }),
    );
    const app = renderApp(`/traces/${FIXTURE_TRACE_ID}?ts=${TS}`);
    const notFound = await screen.findByTestId('trace-not-found', {}, FIND);
    expect(notFound).toHaveTextContent(enTrace.notFound.notInWindow);
    await user.click(screen.getByTestId('trace-search-week'));
    await waitFor(() => {
      expect(app.url()).toBe(`/traces/${FIXTURE_TRACE_ID}?window=7d`);
    }, FIND);
    await waitFor(() => {
      expect(stub.calls(TRACE).some((r) => r.query.get('window') === '7d')).toBe(true);
    }, FIND);
  });

  it('requests nothing for a malformed trace id', async () => {
    renderApp('/traces/not-a-trace');
    expect(await screen.findByTestId('trace-not-found', {}, FIND)).toHaveTextContent(
      enTrace.notFound.invalidId,
    );
    expect(stub.calls(TRACE)).toHaveLength(0);
  });

  it('a 429 of the 30-day window shows the error state with its retry time', async () => {
    stub.on(TRACE, () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' }, requestId: 'req-429' }),
    );
    renderApp(`/traces/${FIXTURE_TRACE_ID}?window=30d`);
    expect(await screen.findByTestId('error-state', {}, FIND)).toBeInTheDocument();
    expect(screen.getByTestId('trace-page')).toHaveAttribute('data-state', 'error');
  });
});

describe('05 §2.8-1 tree + timeline, node labels', () => {
  it('every node shows app (env), service, instance, time, status, error and version from the response', async () => {
    const trace = traceFixture({
      nodes: [
        traceNodeFixture({ tx_id: ROOT_TX_ID, error: true, status: 500 }),
        traceNodeFixture({
          tx_id: CALLEE_TX_ID,
          app: untrusted('stock-api'),
          app_id: 4,
          env: untrusted('stage'),
          service: untrusted('GET /api/stock/{sku}'),
          instance: untrusted('stock-01:8080'),
          version: null,
          ts: '2026-11-14T00:00:01.020Z',
          elapsed_ms: 45,
          parent_tx_id: ROOT_TX_ID,
          caller_step: { elapsed_ms: 60, network_wait_ms: 15 },
        }),
      ],
    });
    await openTrace(trace);
    expect(screen.getByTestId('trace-rows')).toBeInTheDocument();
    expect(screen.getByRole('treegrid', { name: enTrace.list.label })).toBeInTheDocument();
    const root = nodeOf(ROOT_TX_ID);
    const rootRow = rowOf(root);
    expect(within(root).getByTestId('trace-node-app')).toHaveTextContent('order-api');
    expect(within(root).getByTestId('trace-node-env')).toHaveTextContent('prod');
    expect(within(root).getByTestId('trace-node-service')).toHaveTextContent(
      'GET /api/orders/{id}',
    );
    expect(within(rootRow).getByTestId('trace-node-instance')).toHaveTextContent('web-01:8080');
    expect(within(rootRow).getByTestId('trace-node-version')).toHaveTextContent('1.4.2');
    expect(within(rootRow).getByTestId('trace-node-status')).toHaveTextContent('500');
    expect(within(rootRow).getByTestId('trace-node-error')).toHaveTextContent(enTrace.row.error);
    expect(within(rootRow).getByTestId('trace-node-elapsed')).toHaveAttribute('data-ms', '100');
    expect(rootRow).toHaveAttribute('aria-level', '1');
    const calleeRow = rowOf(nodeOf(CALLEE_TX_ID));
    expect(calleeRow).toHaveAttribute('aria-level', '2');
    expect(within(calleeRow).getByTestId('trace-node-env')).toHaveTextContent('stage');
    // A null version is the dash with "No data", never an empty cell or 0.
    expect(within(calleeRow).getByTestId('trace-node-version')).toHaveTextContent('—');
    expect(within(calleeRow).queryByTestId('trace-node-error')).not.toBeInTheDocument();
    // Summary: root, counts, critical path source.
    expect(screen.getByTestId('trace-summary-root')).toHaveAttribute('data-tx-id', ROOT_TX_ID);
    expect(screen.getByTestId('trace-summary-transactions')).toHaveTextContent('2');
    expect(screen.getByTestId('trace-summary-apps')).toHaveTextContent('2');
    expect(screen.getByTestId('trace-critical-source')).toHaveAttribute('data-source', 'server');
    // The time scale above the bars, the legend next to the colours.
    expect(screen.getByTestId('waterfall-scale')).toBeInTheDocument();
    expect(screen.getByTestId('trace-legend')).toHaveTextContent(enTrace.legend.wait);
  });

  it('three hops: each callee under its caller; → and ← collapse and expand by keyboard', async () => {
    await openTrace(threeHop());
    expect(rowOf(nodeOf('300'))).toHaveAttribute('aria-level', '3');
    const root = rowOf(nodeOf('100'));
    expect(root).toHaveAttribute('aria-expanded', 'true');
    act(() => {
      root.focus();
    });
    fireEvent.keyDown(root, { key: 'ArrowLeft' });
    await waitFor(() => {
      expect(screen.queryAllByTestId('trace-node')).toHaveLength(1);
    }, FIND);
    expect(rowOf(nodeOf('100'))).toHaveAttribute('aria-expanded', 'false');
    fireEvent.keyDown(rowOf(nodeOf('100')), { key: 'ArrowRight' });
    await waitFor(() => {
      expect(screen.queryAllByTestId('trace-node')).toHaveLength(3);
    }, FIND);
    // The caret toggles too (mouse), without opening the detail.
    const toggle = within(rowOf(nodeOf('200'))).getByTestId('trace-row-toggle');
    fireEvent.click(toggle);
    await waitFor(() => {
      expect(screen.queryAllByTestId('trace-node')).toHaveLength(2);
    }, FIND);
  });

  it('a LOST and a sampled-out profile show the D-07 reason (T-ACC-07 display)', async () => {
    const trace = threeHop();
    const nodes = trace.nodes.map((n) =>
      n.tx_id === '200' ? { ...n, profile_state: 'sampled_out' } : n,
    );
    await openTrace({ ...trace, nodes });
    expect(within(nodeOf('300')).getByTestId('trace-node-profile')).toHaveTextContent(
      enTrace.row.profileLost,
    );
    expect(within(nodeOf('200')).getByTestId('trace-node-profile')).toHaveTextContent(
      enTrace.row.profileSampledOut,
    );
    expect(within(nodeOf('100')).queryByTestId('trace-node-profile')).not.toBeInTheDocument();
    // The panel of the focused row spells the reason out (common D-07 texts).
    const row = rowOf(nodeOf('300'));
    act(() => {
      row.focus();
    });
    await waitFor(() => {
      expect(screen.getByTestId('trace-panel-profile')).toHaveTextContent(
        enCommon.component.partial.profileLost,
      );
    }, FIND);
  });

  it('virtualises a 500-transaction trace: a bounded number of rows in the DOM (DSN-15-11)', async () => {
    const nodes = [traceNodeFixture({ tx_id: '1', ts: TS, elapsed_ms: 10_000 })];
    for (let i = 2; i <= 500; i += 1) {
      nodes.push(
        traceNodeFixture({
          tx_id: String(i),
          ts: new Date(Date.parse(TS) + i).toISOString(),
          elapsed_ms: 5,
          parent_tx_id: '1',
          caller_step: { elapsed_ms: 7, network_wait_ms: 2 },
        }),
      );
    }
    await openTrace(traceFixture({ nodes, root_tx_id: '1', critical_path: ['1'] }), {
      truncated: true,
    });
    const grid = screen.getByRole('treegrid');
    // 1 root + 499 × (caller step + callee) rows.
    expect(grid).toHaveAttribute('aria-rowcount', '999');
    expect(screen.getAllByTestId('trace-row').length).toBeLessThanOrEqual(20 + 2 * 10 + 1);
    expect(screen.getByTestId('trace-partial-truncated')).toHaveTextContent(
      'more than 500 transactions',
    );
  });
});

describe('05 §2.8-2 caller step and callee both, network / wait (D-05)', () => {
  it('T-ACC-05 display: the caller step row above the callee with the grey wait part', async () => {
    await openTrace(traceFixture());
    const call = screen.getByTestId('trace-call');
    expect(call).toHaveAttribute('data-callee-tx-id', CALLEE_TX_ID);
    expect(call).toHaveAttribute('data-caller-ms', '60');
    expect(call).toHaveAttribute('data-callee-ms', '45');
    expect(call).toHaveAttribute('data-wait-ms', '15');
    expect(call).toHaveAttribute('data-clock-offset', 'false');
    expect(within(call).getByTestId('trace-call-wait')).toHaveTextContent('network / wait 15ms');
    const callRow = rowOf(call);
    expect(within(callRow).getByTestId('trace-call-elapsed')).toHaveAttribute('data-ms', '60');
    const waits = within(callRow)
      .getAllByTestId('waterfall-segment')
      .filter((s) => s.getAttribute('data-kind') === 'wait');
    expect(waits).toHaveLength(2);
    // The call row sits right above the callee at the same level.
    const calleeRow = rowOf(nodeOf(CALLEE_TX_ID));
    expect(Number(callRow.getAttribute('data-row-index')) + 1).toBe(
      Number(calleeRow.getAttribute('data-row-index')),
    );
    expect(callRow.getAttribute('aria-level')).toBe(calleeRow.getAttribute('aria-level'));
  });

  it('a caller step shorter than the callee: both raw values and the D-05 mismatch note, no wait bar', async () => {
    const trace = traceFixture({
      nodes: [
        traceNodeFixture({ tx_id: ROOT_TX_ID }),
        traceNodeFixture({
          tx_id: CALLEE_TX_ID,
          app_id: 4,
          ts: '2026-11-14T00:00:01.020Z',
          elapsed_ms: 45,
          parent_tx_id: ROOT_TX_ID,
          caller_step: { elapsed_ms: 30, network_wait_ms: 0 },
        }),
      ],
    });
    await openTrace(trace);
    const call = screen.getByTestId('trace-call');
    expect(call).toHaveAttribute('data-clock-offset', 'true');
    expect(call).not.toHaveAttribute('data-wait-ms');
    const mismatch = within(call).getByTestId('trace-call-clock-offset');
    expect(mismatch).toHaveTextContent('Caller step 30ms shorter than callee 45ms');
    // Durations come from each host's monotonic clock: the note names no cause (D-05).
    expect(mismatch).not.toHaveTextContent(/clock/i);
    expect(within(call).queryByTestId('trace-call-wait')).not.toBeInTheDocument();
    const segments = within(rowOf(call)).queryAllByTestId('waterfall-segment');
    expect(segments).toHaveLength(0);
    expect(screen.getByTestId('trace-partial-clock-offset')).toBeInTheDocument();
  });
});

describe('05 §2.8-3 missing nodes of three kinds', () => {
  it('an outgoing call to an uninstrumented host is an external node with host, path, status', async () => {
    const trace = traceFixture({
      external_nodes: [
        {
          caller_tx_id: ROOT_TX_ID,
          host: untrusted('https://pay.example:443'),
          path_pattern: untrusted('/v1/pay/{id}'),
          elapsed_ms: 80,
          status: 504,
          error: true,
        },
        {
          caller_tx_id: ROOT_TX_ID,
          host: untrusted('http://slow.example:80'),
          path_pattern: null,
          elapsed_ms: 3000,
          status: null,
          error: true,
        },
      ],
    });
    await openTrace(trace);
    const externals = screen.getAllByTestId('trace-external');
    expect(externals).toHaveLength(2);
    const [first, second] = externals.map(rowOf);
    if (first === undefined || second === undefined) throw new Error('rows');
    expect(within(first).getByTestId('trace-external-host')).toHaveTextContent(
      'https://pay.example:443',
    );
    expect(within(first).getByTestId('trace-external-path')).toHaveTextContent('/v1/pay/{id}');
    expect(within(first).getByTestId('trace-external-status')).toHaveTextContent('504');
    expect(within(first).getByTestId('trace-external-error')).toBeInTheDocument();
    // A failure before any response (timeout / connect failure): "No response", path dash.
    expect(within(second).getByTestId('trace-external-status')).toHaveTextContent(
      enTrace.row.noResponse,
    );
    expect(within(second).getByTestId('trace-external-path')).toHaveTextContent('—');
    expect(screen.getByTestId('trace-summary-external')).toHaveTextContent('2');
  });

  it('"not collected yet" has Query again (row and banner) which refetches; not_instrumented has none', async () => {
    const trace = traceFixture({
      nodes: [
        traceNodeFixture({ tx_id: ROOT_TX_ID }),
        traceNodeFixture({ tx_id: '77', ts: '2026-11-14T00:00:02.000Z', parent_tx_id: null }),
        traceNodeFixture({ tx_id: '88', ts: '2026-11-14T00:00:03.000Z', parent_tx_id: null }),
      ],
      missing: [
        { parent_tx_id: null, tx_id: '77', reason: 'not_yet_collected' },
        { parent_tx_id: null, tx_id: '88', reason: 'not_instrumented' },
      ],
    });
    await openTrace(trace);
    const missing = screen.getAllByTestId('trace-missing');
    expect(missing.map((m) => m.getAttribute('data-reason'))).toEqual([
      'not_yet_collected',
      'not_instrumented',
    ]);
    expect(missing[0]).toHaveTextContent(enTrace.row.missingCallerNotYet);
    expect(missing[1]).toHaveTextContent(enTrace.row.missingCallerNotInstrumented);
    expect(within(missing[1] as HTMLElement).queryByTestId('trace-missing-query-again')).toBeNull();
    // The transaction whose caller is missing is drawn under the placeholder.
    expect(rowOf(nodeOf('77'))).toHaveAttribute('aria-level', '2');
    const before = stub.calls(TRACE).length;
    fireEvent.click(within(missing[0] as HTMLElement).getByTestId('trace-missing-query-again'));
    await waitFor(() => {
      expect(stub.calls(TRACE).length).toBe(before + 1);
    }, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('trace-partial-query-again')).toBeEnabled();
    }, FIND);
    fireEvent.click(screen.getByTestId('trace-partial-query-again'));
    await waitFor(() => {
      expect(stub.calls(TRACE).length).toBe(before + 2);
    }, FIND);
    expect(screen.getByTestId('trace-partial-not-collected')).toHaveTextContent(
      '1 transaction of this trace is not collected yet',
    );
  });
});

describe('05 §2.8-4 critical path, node → detail, app → dashboard', () => {
  it('critical-path rows have the bold outline; without critical_path the browser computes it (recorded)', async () => {
    await openTrace(threeHop());
    for (const id of ['100', '200', '300']) {
      expect(nodeOf(id)).toHaveAttribute('data-critical', 'true');
      const bar = within(rowOf(nodeOf(id))).getByTestId('waterfall-bar');
      expect(bar).toHaveClass('waterfall-bar--critical');
    }
    expect(screen.getAllByTestId('trace-node-critical')).toHaveLength(3);
  });

  it('records a client-computed critical path', async () => {
    await openTrace({ ...threeHop(), critical_path: [] });
    expect(screen.getByTestId('trace-critical-source')).toHaveAttribute('data-source', 'client');
    expect(screen.getByTestId('trace-critical-source')).toHaveTextContent(
      enTrace.summary.criticalClient,
    );
    expect(nodeOf('300')).toHaveAttribute('data-critical', 'true');
  });

  it('a node click opens its transaction detail on the canonical route with the ts hint', async () => {
    const app = await openTrace(traceFixture());
    fireEvent.click(rowOf(nodeOf(CALLEE_TX_ID)));
    await waitFor(() => {
      expect(app.url()).toBe(`/apps/4/transactions/${CALLEE_TX_ID}?ts=2026-11-14T00:00:01.020Z`);
    }, FIND);
  });

  it('Enter on a focused node opens the detail; the panel offers the same links by keyboard', async () => {
    const app = await openTrace(traceFixture());
    const row = rowOf(nodeOf(ROOT_TX_ID));
    act(() => {
      row.focus();
    });
    const panel = await screen.findByTestId('trace-node-panel', {}, FIND);
    expect(within(panel).getByTestId('trace-panel-open-detail')).toHaveAttribute(
      'href',
      `/apps/3/transactions/${ROOT_TX_ID}?ts=2026-11-14T00:00:01.000Z`,
    );
    fireEvent.keyDown(row, { key: 'Enter' });
    await waitFor(() => {
      expect(app.url()).toBe(`/apps/3/transactions/${ROOT_TX_ID}?ts=2026-11-14T00:00:01.000Z`);
    }, FIND);
  });

  it('an app click opens that app dashboard for the trace time range (absolute from/to), not the detail', async () => {
    const app = await openTrace(traceFixture());
    const link = within(nodeOf(CALLEE_TX_ID)).getByTestId('trace-node-app');
    const from = '2026-11-13T23:00:01.000Z';
    const to = '2026-11-14T01:00:01.000Z';
    expect(link).toHaveAttribute('href', `/apps/4/dashboard?from=${from}&to=${to}`);
    fireEvent.click(link);
    await waitFor(() => {
      expect(app.url()).toBe(`/apps/4/dashboard?from=${from}&to=${to}`);
    }, FIND);
  });
});

describe('05 §2.8-5 copy, logs, JSON export; searched range', () => {
  it('copies the trace id', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    try {
      await openTrace(traceFixture());
      fireEvent.click(screen.getByTestId('trace-copy-id'));
      await waitFor(() => {
        expect(writeText).toHaveBeenCalledWith(FIXTURE_TRACE_ID);
      }, FIND);
      expect(await screen.findByText(enTrace.actions.copied, {}, FIND)).toBeInTheDocument();
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('exports the loaded response as JSON without a request (ADR-0010 C-207 (1))', async () => {
    const blobs: Blob[] = [];
    const names: string[] = [];
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: (blob: Blob) => {
        blobs.push(blob);
        return 'blob:http://localhost:3000/t';
      },
    });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => undefined });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    try {
      await openTrace(traceFixture());
      const before = stub.requests.length;
      fireEvent.click(screen.getByTestId('trace-export-json'));
      expect(names).toEqual([`argos-trace-${FIXTURE_TRACE_ID}.json`]);
      const saved = JSON.parse((await blobs[0]?.text()) ?? '') as { data: Trace };
      expect(saved.data.trace_id).toBe(FIXTURE_TRACE_ID);
      expect(saved.data.nodes).toHaveLength(2);
      expect(stub.requests.length).toBe(before);
      expect(screen.getByTestId('trace-action-status')).toHaveTextContent(
        `Saved argos-trace-${FIXTURE_TRACE_ID}.json`,
      );
    } finally {
      Reflect.deleteProperty(URL, 'createObjectURL');
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });

  it('Open logs is disabled with a visible reason when no log link of the root is known (the trace response has none)', async () => {
    await openTrace(traceFixture());
    expect(screen.queryByTestId('trace-open-logs')).not.toBeInTheDocument();
    const button = screen.getByTestId('trace-open-logs-unavailable');
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent(enTrace.actions.openLogs);
    const reason = screen.getByTestId('trace-open-logs-reason');
    expect(reason).toHaveTextContent(enTrace.actions.openLogsUnavailable);
    expect(reason).not.toHaveClass('visually-hidden');
    expect(reason).toBeVisible();
    expect(button).toHaveAttribute('aria-describedby', reason.id);
    // No link is built without a server-built log link.
    expect(within(screen.getByTestId('trace-actions')).queryByRole('link')).toBeNull();
  });

  it('shows the searched range and its basis; 30 days asks first, then sets window=30d', async () => {
    const user = userEvent.setup();
    const app = await openTrace(traceFixture());
    const window = screen.getByTestId('trace-window');
    expect(window).toHaveAttribute('data-basis', 'ts_hint');
    expect(screen.getByTestId('trace-window-basis')).toHaveTextContent(enTrace.window.basis.tsHint);
    expect(screen.getByTestId('trace-window-hint')).toBeInTheDocument();
    await user.click(screen.getByTestId('trace-window-30d'));
    expect(screen.getByTestId('trace-window-30d-dialog')).toHaveTextContent(
      enTrace.window.confirmBody,
    );
    await user.click(screen.getByTestId('trace-window-30d-cancel'));
    expect(app.url()).toBe(`/traces/${FIXTURE_TRACE_ID}?ts=${TS}`);
    await user.click(screen.getByTestId('trace-window-30d'));
    await user.click(screen.getByTestId('trace-window-30d-confirm'));
    await waitFor(() => {
      expect(app.url()).toBe(`/traces/${FIXTURE_TRACE_ID}?window=30d`);
    }, FIND);
    await waitFor(() => {
      expect(stub.calls(TRACE).some((r) => r.query.get('window') === '30d')).toBe(true);
    }, FIND);
  });
});

describe('T-UI-005 collected strings of the trace render as text (SEC-60)', () => {
  it.each(XSS_PAYLOADS)(
    'XSS payload %j in app, env, service, instance, version, host and path stays text',
    async (payload) => {
      const value = untrusted(payload);
      const trace = traceFixture({
        nodes: [
          traceNodeFixture({
            tx_id: ROOT_TX_ID,
            app: value,
            env: value,
            service: value,
            instance: value,
            version: value,
          }),
          traceNodeFixture({
            tx_id: CALLEE_TX_ID,
            app: value,
            app_id: 4,
            ts: '2026-11-14T00:00:01.020Z',
            elapsed_ms: 45,
            parent_tx_id: ROOT_TX_ID,
            caller_step: { elapsed_ms: 60, network_wait_ms: 15 },
          }),
        ],
        external_nodes: [
          {
            caller_tx_id: ROOT_TX_ID,
            host: value,
            path_pattern: value,
            elapsed_ms: 1,
            status: 200,
            error: false,
          },
        ],
      });
      await openTrace(trace);
      const page = screen.getByTestId('trace-page');
      expect(findInjectedMarkup(page)).toEqual([]);
      const root = nodeOf(ROOT_TX_ID);
      expect(within(root).getByTestId('trace-node-service').textContent).toBe(payload);
      expect(within(root).getByTestId('trace-node-app').textContent).toBe(payload);
      expect(within(rowOf(root)).getByTestId('trace-node-instance').textContent).toBe(payload);
      expect(screen.getByTestId('trace-external-host').textContent).toBe(payload);
      expect(screen.getByTestId('trace-external-path').textContent).toBe(payload);
      // The tooltip and the node panel render the same strings as text.
      act(() => {
        rowOf(root).focus();
      });
      expect(await screen.findByTestId('trace-tooltip', {}, FIND)).toBeInTheDocument();
      expect(findInjectedMarkup(page)).toEqual([]);
      expect(xssFired()).toBe(false);
    },
  );
});
