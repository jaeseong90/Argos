// SPDX-License-Identifier: Apache-2.0
//
// The external calls list in the whole app (05 §2.5-1/-4, WO-36): the request (compare_prev, Top N,
// the sort only when the list accepts it: p95 and timeouts), timeouts and connection failures per
// host, the path pattern null as the dash (ADR-0010 C-178 (3), T-UI-007), bucket-estimated
// percentiles with the estimated badge (C-14), null p95 apart from 0, a host include narrowing the
// received rows (scope note, chip with the host name, no new request), the server CSV with the
// list parameters (T-UI-004) and hosts as text (T-UI-005). No application map link (F-U11).
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
import { parseUrl, StatsListQ } from '../../../state/urlState';
import {
  BIG_ID,
  externalRow,
  externalRows,
  FROM_ISO,
  RANGE_QUERY,
  TO_ISO,
} from '../testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U05' };
export const REQ_TABLE = { type: 'req', description: 'F-U16' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps/1/external/stats';

let stub: ApiStub;

function jsonCalls() {
  return stub.calls(LIST).filter((call) => call.query.get('format') !== 'csv');
}

function lastQuery(): Record<string, string> {
  return Object.fromEntries(jsonCalls().at(-1)?.query.entries() ?? []);
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/external?${query}`);
  const page = await screen.findByTestId('stats-external', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="stats-external"]');
  if (element === null) {
    throw new Error('external table not rendered');
  }
  return element;
}

function rowKeys(): (string | null)[] {
  return within(table())
    .getAllByTestId('data-table-row')
    .map((row) => row.getAttribute('data-row-key'));
}

function cell(rowKey: string, column: string): HTMLElement {
  const element = table().querySelector<HTMLElement>(
    `[data-row-key="${rowKey}"] [data-column="${column}"]`,
  );
  if (element === null) {
    throw new Error(`no cell ${rowKey}/${column}`);
  }
  return element;
}

function value(rowKey: string, column: string): string | null {
  return within(cell(rowKey, column)).getByTestId('stats-value').getAttribute('data-value');
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

describe('external calls list (docs/14 §4.8, 05 §2.5-4)', () => {
  it('T-UI-004 asks with compare_prev and Top N and shows timeouts and connection failures per host', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    await open(`${RANGE_QUERY}&kind=ALL&limit=50`);
    expect(lastQuery()).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'ALL',
      limit: '50',
      compare_prev: 'true',
    });
    expect(rowKeys()).toEqual(['51', BIG_ID]);
    expect(cell('51', 'host')).toHaveTextContent('https://payments.example:443');
    expect(value('51', 'timeouts')).toBe('4');
    expect(value('51', 'connect_failures')).toBe('2');
    expect(value(BIG_ID, 'timeouts')).toBe('0');
    expect(within(cell('51', 'count')).getByTestId('stats-delta-count')).toHaveAttribute(
      'data-value',
      '0.1',
    );
    expect(within(cell('51', 'p95_ms')).getByTestId('stats-delta-p95_ms')).toHaveAttribute(
      'data-value',
      '0.2',
    );
    expect(within(cell('51', 'errors')).getByTestId('stats-delta-error_rate')).toHaveAttribute(
      'data-value',
      '-0.002',
    );
    // No application map link: F-U11 (S) has no screen in Phase 1.
    expect(screen.getByTestId('stats-external').querySelector('a[href*="/map"]')).toBeNull();
  });

  it('T-UI-007 shows the unstored path pattern as the dash (C-178 (3)) and a null p95 apart from 0', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    await open(RANGE_QUERY);
    expect(cell('51', 'path_pattern')).toHaveTextContent('—');
    const missing = within(cell(BIG_ID, 'p95_ms')).getByTestId('stats-value');
    expect(missing).toHaveAttribute('data-missing', 'true');
    expect(missing).toHaveTextContent('—');
    expect(within(cell(BIG_ID, 'count')).getByTestId('stats-delta-count')).toHaveAttribute(
      'data-missing',
      'true',
    );
    expect(within(cell(BIG_ID, 'count')).getByTestId('stats-delta-count')).toHaveTextContent(
      'No comparison data',
    );
  });

  it('C-14 marks the host percentiles as estimated, and not when the server says exact', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    await open(RANGE_QUERY);
    for (const column of ['p50_ms', 'p95_ms']) {
      const header = table().querySelector(`th[data-column="${column}"]`) as HTMLElement;
      expect(within(header).getByTestId('estimated-badge')).toBeInTheDocument();
    }
    expect(
      table().querySelector('th[data-column="timeouts"] [data-testid="estimated-badge"]'),
    ).toBeNull();
    uninstallApiStub();
    resetShellState();
    stub = installApiStub();
    stub.on(LIST, () => envelope(externalRows().map((row) => ({ ...row, estimated: false }))));
    await open(`${RANGE_QUERY}&limit=10`);
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
  });

  it('T-UI-004 the sort switch asks p95 and timeouts as API sorts', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-sort-sum_ms')).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByTestId('stats-sort-p95_ms'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('p95_ms:desc');
    }, FIND);
    await user.click(screen.getByTestId('stats-sort-timeouts'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('timeouts:desc');
    }, FIND);
    expect(screen.getByTestId('stats-sort-timeouts')).toHaveAttribute('aria-pressed', 'true');
  });

  it('T-UI-004 a host include narrows the received rows with the scope note and the host chip', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const before = jsonCalls().length;
    await user.click(within(cell(BIG_ID, 'host')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(rowKeys()).toEqual([BIG_ID]);
    }, FIND);
    expect(parseUrl(StatsListQ, app.router.state.location.search).state.filters).toEqual([
      { field: 'host', op: 'eq', value: BIG_ID },
    ]);
    expect(jsonCalls()).toHaveLength(before);
    expect(screen.getByTestId('stats-filter-scope')).toBeInTheDocument();
    const chip = await screen.findByTestId('filter-chip', {}, FIND);
    expect(chip).toHaveTextContent('http://inventory.internal:8080');
  });

  it('T-UI-004 exports CSV through the server with the list parameters', async () => {
    stub.on(LIST, (request) =>
      request.query.get('format') === 'csv' ? envelope([]) : envelope(externalRows()),
    );
    const user = userEvent.setup();
    await open(`${RANGE_QUERY}&sort=timeouts:desc&limit=10`);
    await user.click(within(table()).getByTestId('csv-export'));
    await screen.findByTestId('stats-csv-error', {}, FIND);
    const csv = stub.calls(LIST).filter((call) => call.query.get('format') === 'csv');
    expect(csv).toHaveLength(1);
    const { format, tz, ...rest } = Object.fromEntries(csv[0]?.query.entries() ?? []);
    expect(format).toBe('csv');
    expect(tz).not.toBe('');
    expect(rest).toEqual(lastQuery());
    expect(rest.sort).toBe('timeouts:desc');
    expect(rest.limit).toBe('10');
  });

  it('opens the host detail from a row link with the range kept', async () => {
    stub.on(LIST, () => envelope(externalRows()));
    stub.on('GET /api/v1/apps/1/external/51', () => envelope(null));
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await user.click(within(cell('51', 'host')).getByRole('link'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/stats/external/51');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${FROM_ISO}`);
  });

  it('T-UI-005 renders XSS payloads in hosts and path patterns as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          externalRow({
            host_id: String(100 + index),
            host: untrusted(payload),
            path_pattern: untrusted(payload),
          }),
        ),
      ),
    );
    await open(`${RANGE_QUERY}&limit=50`);
    for (const payload of XSS_PAYLOADS) {
      expect(within(table()).getAllByTitle(payload).length).toBeGreaterThanOrEqual(2);
    }
    expect(findInjectedMarkup(screen.getByTestId('stats-external'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
