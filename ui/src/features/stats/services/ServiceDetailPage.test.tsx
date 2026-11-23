// SPDX-License-Identifier: Apache-2.0
//
// The service detail in the whole app (05 §2.5-2, WO-36): the request of the URL range and kind,
// the trend with the range's event markers (deploy markers, T-ACPT-04) and outage shading, the
// per-version p95 table equal to `by_version`, the instance distribution with the raw window, the
// cross-view tabs as URL state with rows linking to the SQL, external and error group details,
// related transactions in the XLog with `f.service` (and per version `f.version`), a chart point
// opening the XLog with the filter, collected names as text (T-UI-005) and the error state.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../../test/renderApp';
import { untrusted } from '../../../api/untrusted';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { parseUrl, ServiceDetailQ, XLogQ } from '../../../state/urlState';
import {
  breakdown,
  deployEvent,
  FROM,
  FROM_ISO,
  RANGE_QUERY,
  serviceDetail,
  TO,
  TO_ISO,
} from '../testing/fixtures';

// uPlot reads matchMedia when it is imported: install the jsdom stand-ins first.
await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U05' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DETAIL = 'GET /api/v1/apps/1/services/11';
const EVENTS = 'GET /api/v1/apps/1/events';

let stub: ApiStub;

function answer(detail = serviceDetail()): void {
  stub.on(DETAIL, () =>
    envelope(detail, {
      source: '1m',
      outages: [{ from: FROM_ISO, to: '2026-11-20T03:05:00.000Z', reason: 'session_lost' }],
    }),
  );
  stub.on(EVENTS, () =>
    envelope([
      deployEvent('ev-1', '2026-11-20T03:20:00.000Z', '1.5.0'),
      deployEvent('ev-2', '2026-11-20T03:40:00.000Z', '1.5.1'),
    ]),
  );
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/services/11?${query}`);
  const page = await screen.findByTestId('stats-service-detail', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

function tableOf(id: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(`[data-table-id="${id}"]`);
  if (element === null) {
    throw new Error(`table ${id} not rendered`);
  }
  return element;
}

function rowKeys(id: string): (string | null)[] {
  return within(tableOf(id))
    .getAllByTestId('data-table-row')
    .map((row) => row.getAttribute('data-row-key'));
}

function search(href: string | null): string {
  return (href ?? '').split('?')[1] ?? '';
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('service detail (05 §2.5-2)', () => {
  it('asks for the URL range and kind and shows the service, source and raw window', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=BATCH`);
    expect(stub.calls(DETAIL)).toHaveLength(1);
    expect(Object.fromEntries(stub.calls(DETAIL)[0]?.query.entries() ?? [])).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'BATCH',
    });
    expect(Object.fromEntries(stub.calls(EVENTS)[0]?.query.entries() ?? [])).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
    });
    expect(screen.getByTestId('stats-service-name')).toHaveTextContent('GET /api/orders/{id}');
    expect(screen.getByTestId('stats-source')).toHaveAttribute('data-source', '1m');
    expect(screen.getByTestId('stats-raw-window')).toHaveAttribute('data-from', FROM_ISO);
    const page = screen.getByTestId('stats-service-detail');
    expect(page).toHaveAttribute('data-range-from', String(FROM));
    expect(page).toHaveAttribute('data-range-to', String(TO));
  });

  it('draws the deploy markers and the outage on the trend and compares p95 per version', async () => {
    answer();
    await open(RANGE_QUERY);
    const trend = screen.getByTestId('stats-trend');
    const markers = within(trend).getAllByTestId('chart-marker');
    expect(markers.map((marker) => marker.getAttribute('data-marker-type'))).toEqual([
      'deploy',
      'deploy',
    ]);
    expect(within(trend).getAllByTestId('chart-outage')).toHaveLength(1);
    expect(
      within(screen.getByTestId('stats-trend-latency'))
        .getAllByTestId('chart-legend-item')
        .map((item) => item.getAttribute('data-series')),
    ).toEqual(['avg_ms', 'p95_ms', 'p99_ms']);
    expect(rowKeys('stats-by-version')).toEqual(['41', '42']);
    const p95 = (key: string) =>
      tableOf('stats-by-version')
        .querySelector(`[data-row-key="${key}"] [data-column="p95_ms"] [data-testid="stats-value"]`)
        ?.getAttribute('data-value');
    expect(p95('41')).toBe('520');
    expect(p95('42')).toBe('310');
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
    // A version opens the XLog of this service and version.
    const link = within(tableOf('stats-by-version')).getAllByRole('link')[1] as HTMLElement;
    expect(parseUrl(XLogQ, search(link.getAttribute('href'))).state.filters).toEqual([
      { field: 'service', op: 'eq', value: '11' },
      { field: 'version', op: 'eq', value: '42' },
    ]);
  });

  it('T-UI-007 shows the instance distribution with null p95 as the dash', async () => {
    answer();
    await open(RANGE_QUERY);
    expect(rowKeys('stats-by-instance')).toEqual(['7', '8']);
    const missing = tableOf('stats-by-instance').querySelector(
      '[data-row-key="8"] [data-column="p95_ms"] [data-testid="stats-value"]',
    );
    expect(missing).toHaveAttribute('data-missing', 'true');
    expect(missing).toHaveTextContent('—');
  });

  it('switches the cross view by URL tab and links each row to its detail with the range', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-cross-tab-sql')).toHaveAttribute('aria-pressed', 'true');
    const sqlLink = within(tableOf('stats-top-sql')).getByRole('link');
    expect(sqlLink.getAttribute('href')).toBe(`/apps/1/stats/sql/21?from=${FROM_ISO}&to=${TO_ISO}`);
    await user.click(screen.getByTestId('stats-cross-tab-external'));
    await waitFor(() => {
      expect(parseUrl(ServiceDetailQ, app.router.state.location.search).state.tab).toBe('external');
      expect(document.querySelector('[data-table-id="stats-top-external"]')).not.toBeNull();
    }, FIND);
    expect(screen.getByTestId('stats-cross-tab-external')).toHaveAttribute('aria-pressed', 'true');
    expect(within(tableOf('stats-top-external')).getByRole('link').getAttribute('href')).toBe(
      `/apps/1/stats/external/51?from=${FROM_ISO}&to=${TO_ISO}`,
    );
    await user.click(screen.getByTestId('stats-cross-tab-errors'));
    await waitFor(() => {
      expect(document.querySelector('[data-table-id="stats-top-errors"]')).not.toBeNull();
    }, FIND);
    expect(within(tableOf('stats-top-errors')).getByRole('link').getAttribute('href')).toBe(
      `/apps/1/stats/errors/61?from=${FROM_ISO}&to=${TO_ISO}`,
    );
    // The share of an error group is null: the dash, not 0 %.
    expect(
      tableOf('stats-top-errors').querySelector(
        '[data-column="share"] [data-testid="stats-value"]',
      ),
    ).toHaveAttribute('data-missing', 'true');
  });

  it('opens the related transactions in the XLog with f.service and the range', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(`${RANGE_QUERY}&kind=ALL`);
    const link = screen.getByTestId('stats-open-xlog');
    const state = parseUrl(XLogQ, search(link.getAttribute('href'))).state;
    expect(state.filters).toEqual([{ field: 'service', op: 'eq', value: '11' }]);
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: TO });
    expect(state.kind).toBe('ALL');
    await user.click(link);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/xlog');
    }, FIND);
    expect(parseUrl(XLogQ, app.router.state.location.search).state.filters).toEqual([
      { field: 'service', op: 'eq', value: '11' },
    ]);
  });

  it('opens the XLog at a trend point with the service filter (05 §2.0-5)', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const over = screen.getByTestId('stats-trend').querySelector<HTMLElement>('.u-over');
    if (over === null) {
      throw new Error('uPlot overlay not rendered');
    }
    fireEvent.mouseEnter(over, { clientX: 100, clientY: 40 });
    fireEvent.mouseMove(over, { clientX: 100, clientY: 40, movementX: 1 });
    fireEvent.mouseDown(over, { clientX: 100, clientY: 40, button: 0 });
    fireEvent.mouseUp(document, { clientX: 100, clientY: 40, button: 0 });
    fireEvent.click(over, { clientX: 100, clientY: 40, button: 0 });
    await user.click(await screen.findByTestId('chart-menu-xlog', {}, FIND));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/xlog');
    }, FIND);
    const state = parseUrl(XLogQ, app.router.state.location.search).state;
    expect(state.live).toBe(false);
    expect(state.time.kind).toBe('absolute');
    expect(state.filters).toEqual([{ field: 'service', op: 'eq', value: '11' }]);
  });

  it('T-UI-005 renders collected names of the detail as text', async () => {
    answer(
      serviceDetail({
        service: untrusted(XSS_PAYLOADS[0] ?? ''),
        by_version: XSS_PAYLOADS.map((payload, index) => breakdown(String(100 + index), payload)),
        top_sql: XSS_PAYLOADS.map((payload, index) => breakdown(String(300 + index), payload)),
      }),
    );
    await open(RANGE_QUERY);
    for (const payload of XSS_PAYLOADS) {
      expect(within(tableOf('stats-by-version')).getAllByTitle(payload).length).toBeGreaterThan(0);
      expect(within(tableOf('stats-top-sql')).getAllByTitle(payload).length).toBeGreaterThan(0);
    }
    expect(findInjectedMarkup(screen.getByTestId('stats-service-detail'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('shows the error state of an unknown service and the empty state of an idle range', async () => {
    stub.on(DETAIL, () => errorBody(404, 'not_found', { requestId: 'req-404' }));
    stub.on(EVENTS, () => envelope([]));
    signedIn(stub);
    renderApp(`/apps/1/stats/services/11?${RANGE_QUERY}`);
    const error = await screen.findByTestId('stats-service-detail-error', {}, FIND);
    expect(within(error).getByTestId('error-state-request-id')).toHaveTextContent('req-404');
  });

  it('shows the empty state with the next action when the service has no calls in the range', async () => {
    const empty = serviceDetail();
    answer({ ...empty, series: { ...empty.series, count: empty.series.count.map(() => 0) } });
    signedIn(stub);
    renderApp(`/apps/1/stats/services/11?${RANGE_QUERY}`);
    const state = await screen.findByTestId('stats-service-detail-empty', {}, FIND);
    expect(state).toHaveTextContent('no calls in the selected range');
    expect(within(state).getByTestId('empty-state-widen')).toBeInTheDocument();
  });

  it('refuses a route key that is not a decimal key without asking the server', async () => {
    stub.on(EVENTS, () => envelope([]));
    signedIn(stub);
    renderApp(`/apps/1/stats/services/abc?${RANGE_QUERY}`);
    const state = await screen.findByTestId('stats-service-detail-empty', {}, FIND);
    expect(state).toHaveTextContent('does not name a valid item');
    expect(
      stub.requests.some((request) => request.path.startsWith('/api/v1/apps/1/services/')),
    ).toBe(false);
  });
});
