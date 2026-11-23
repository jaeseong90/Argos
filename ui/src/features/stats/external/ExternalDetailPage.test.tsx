// SPDX-License-Identifier: Apache-2.0
//
// The external host detail in the whole app (05 §2.5-4, WO-36): the estimated trend (C-14) with
// the call, error, timeout and connection failure totals (timeouts and connection failures per
// bucket in the trend, C-222 (7)), the calling services with count and share linking to the service
// detail, the raw window, the related transactions with the "profiled only" note opening their
// detail with ts (C-206 (1)), the XLog link with the range and kind only (no host filter in the
// XLog) and its note, the host as text (T-UI-005), the empty and invalid-key states.
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../../test/apiStub';
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
import { parseUrl, XLogQ } from '../../../state/urlState';
import {
  BIG_ID,
  breakdown,
  externalDetail,
  FROM,
  FROM_ISO,
  RANGE_QUERY,
  series,
  TO,
  TO_ISO,
} from '../testing/fixtures';

await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U05' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DETAIL = 'GET /api/v1/apps/1/external/51';

let stub: ApiStub;

function answer(detail = externalDetail()): void {
  stub.on(DETAIL, () => envelope(detail, { source: 'raw' }));
  stub.on('GET /api/v1/apps/1/events', () => envelope([]));
}

async function open(query: string, path = '/apps/1/stats/external/51') {
  signedIn(stub);
  const app = renderApp(`${path}?${query}`);
  const page = await screen.findByTestId('stats-external-detail', {}, FIND);
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

function value(id: string, rowKey: string, column: string): string | null | undefined {
  return tableOf(id)
    .querySelector(
      `[data-row-key="${rowKey}"] [data-column="${column}"] [data-testid="stats-value"]`,
    )
    ?.getAttribute('data-value');
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

describe('external host detail (05 §2.5-4)', () => {
  it('C-14 asks the host for the range and kind and marks the estimated latency trend', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=ALL`);
    expect(Object.fromEntries(stub.calls(DETAIL)[0]?.query.entries() ?? [])).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'ALL',
    });
    expect(screen.getByTestId('stats-external-host')).toHaveTextContent(
      'https://payments.example:443',
    );
    expect(screen.getByTestId('stats-trend')).toBeInTheDocument();
    const latency = screen.getByTestId('stats-trend-latency').parentElement as HTMLElement;
    expect(within(latency).getByTestId('estimated-badge')).toBeInTheDocument();
    // Totals of the series (a null bucket adds nothing): 100+101+103+104+105 calls, 6 errors.
    expect(screen.getByTestId('stats-external-count')).toHaveAttribute('data-value', '513');
    expect(screen.getByTestId('stats-external-errors')).toHaveAttribute('data-value', '6');
  });

  it('C-222 (7) draws timeouts and connection failures per bucket in the trend with their totals', async () => {
    answer(
      externalDetail({
        series: series(6, {
          timeouts: [0, 2, null, 1, 0, 3],
          connect_failures: [1, 0, null, 0, 4, 0],
        }),
      }),
    );
    await open(RANGE_QUERY);
    expect(
      within(screen.getByTestId('stats-trend'))
        .getAllByTestId('chart-legend-item')
        .map((item) => item.getAttribute('data-series')),
    ).toEqual(['count', 'errors', 'timeouts', 'connect_failures']);
    expect(screen.getByTestId('stats-external-timeouts')).toHaveAttribute('data-value', '6');
    expect(screen.getByTestId('stats-external-connect-failures')).toHaveAttribute(
      'data-value',
      '5',
    );
  });

  it('C-222 (7) a server without the failure series draws calls and errors only', async () => {
    answer();
    await open(RANGE_QUERY);
    expect(
      within(screen.getByTestId('stats-trend'))
        .getAllByTestId('chart-legend-item')
        .map((item) => item.getAttribute('data-series')),
    ).toEqual(['count', 'errors']);
  });

  it('C-14 shows no estimated badge when the server says the series is exact', async () => {
    answer(externalDetail({ estimated: false }));
    await open(RANGE_QUERY);
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
  });

  it('lists calling services with count and share and the raw window, linking to the service', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    expect(value('stats-by-service', '11', 'count')).toBe('60');
    expect(value('stats-by-service', '11', 'share')).toBe('0.6');
    expect(value('stats-by-service', '12', 'share')).toBe('0.4');
    expect(screen.getByTestId('stats-raw-window')).toHaveAttribute('data-from', FROM_ISO);
    await user.click(within(tableOf('stats-by-service')).getAllByRole('link')[0] as HTMLElement);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/stats/services/11');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${FROM_ISO}`);
  });

  it('opens related transactions with ts and says only profiled ones are listed (C-206 (1))', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-related-note')).toHaveTextContent(
      'Only transactions with a captured profile',
    );
    const link = within(tableOf('stats-related-tx')).getByRole('link');
    expect(link.getAttribute('href')).toBe(
      `/apps/1/transactions/${BIG_ID}?ts=2026-11-20T03:40:00.000Z`,
    );
    await user.click(link);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe(`/apps/1/transactions/${BIG_ID}`);
    }, FIND);
  });

  it('opens the XLog with the range and kind only and says the XLog has no host filter', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=ALL&f.service=11`);
    const href = screen.getByTestId('stats-open-xlog').getAttribute('href') ?? '';
    const state = parseUrl(XLogQ, href.split('?')[1] ?? '').state;
    expect(state.filters).toEqual([]);
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: TO });
    expect(state.kind).toBe('ALL');
    expect(screen.getByTestId('stats-external-xlog-note')).toHaveTextContent(
      'cannot filter by host',
    );
    expect(screen.getByTestId('stats-back').getAttribute('href')).toContain('/stats/external?');
  });

  it('T-UI-005 renders the host and the calling services as text', async () => {
    answer(
      externalDetail({
        host: untrusted(XSS_PAYLOADS.join(' ')),
        by_service: XSS_PAYLOADS.map((payload, index) => breakdown(String(300 + index), payload)),
      }),
    );
    await open(RANGE_QUERY);
    for (const payload of XSS_PAYLOADS) {
      expect(within(tableOf('stats-by-service')).getAllByTitle(payload).length).toBeGreaterThan(0);
    }
    expect(screen.getByTestId('stats-external-host')).toHaveTextContent(
      '<script>window.__xss=1</script>',
    );
    expect(findInjectedMarkup(screen.getByTestId('stats-external-detail'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('shows the empty state with the next action when the host was not called', async () => {
    answer(
      externalDetail({
        series: series(3, { count: [0, null, 0], errors: [0, 0, 0] }),
      }),
    );
    signedIn(stub);
    renderApp(`/apps/1/stats/external/51?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('stats-external-detail-empty', {}, FIND);
    expect(empty).toHaveTextContent('was not called in the selected range');
    expect(within(empty).getByTestId('empty-state-widen')).toBeInTheDocument();
  });

  it('asks nothing for a key that is not a decimal string', async () => {
    answer();
    signedIn(stub);
    renderApp(`/apps/1/stats/external/abc?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('stats-external-detail-empty', {}, FIND);
    expect(empty).toHaveTextContent('does not name a valid item');
    expect(stub.calls('GET /api/v1/apps/1/external/abc')).toHaveLength(0);
  });
});
