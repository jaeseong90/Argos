// SPDX-License-Identifier: Apache-2.0
//
// The SQL list in the whole app (05 §2.5-1/-3, WO-36): the request (compare_prev, Top N, the sort
// only when the list accepts it), bucket-estimated percentiles with the estimated badge (C-14),
// null p95 apart from 0 (T-UI-007), a JDBC target include as the server's db_id (no scope note)
// and a statement include on the client (scope note, XLog f.sql), unique row keys for a statement
// on two targets, the server CSV with db_id (T-UI-004) and statements and targets as text
// (T-UI-005).
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
import { parseUrl, StatsListQ, XLogQ } from '../../../state/urlState';
import { FROM_ISO, RANGE_QUERY, sqlRow, sqlRows, TO_ISO } from '../testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U06' };
export const REQ_TABLE = { type: 'req', description: 'F-U16' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps/1/sql/stats';

let stub: ApiStub;

function jsonCalls() {
  return stub.calls(LIST).filter((call) => call.query.get('format') !== 'csv');
}

function lastQuery(): Record<string, string> {
  return Object.fromEntries(jsonCalls().at(-1)?.query.entries() ?? []);
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/sql?${query}`);
  const page = await screen.findByTestId('stats-sql', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="stats-sql"]');
  if (element === null) {
    throw new Error('SQL table not rendered');
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

describe('SQL list (docs/14 §4.8, 05 §2.5-1)', () => {
  it('T-UI-004 asks with compare_prev and Top N and keeps the server order', async () => {
    stub.on(LIST, () => envelope(sqlRows()));
    await open(`${RANGE_QUERY}&kind=ALL&limit=10`);
    expect(lastQuery()).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'ALL',
      limit: '10',
      compare_prev: 'true',
    });
    expect(rowKeys()).toEqual(['21', '22']);
    expect(cell('21', 'db')).toHaveTextContent('jdbc:postgresql://db:5432/shop');
    expect(within(cell('21', 'count')).getByTestId('stats-delta-count')).toHaveAttribute(
      'data-value',
      '0.2',
    );
    expect(within(cell('21', 'errors')).getByTestId('stats-delta-error_rate')).toHaveAttribute(
      'data-value',
      '0.001',
    );
  });

  it('C-14 marks the bucket percentiles as estimated; T-UI-007 null p95 is the dash', async () => {
    stub.on(LIST, () => envelope(sqlRows()));
    await open(RANGE_QUERY);
    const header = table().querySelector('th[data-column="p95_ms"]');
    expect(within(header as HTMLElement).getByTestId('estimated-badge')).toBeInTheDocument();
    expect(
      table().querySelector('th[data-column="count"] [data-testid="estimated-badge"]'),
    ).toBeNull();
    const missing = within(cell('22', 'p95_ms')).getByTestId('stats-value');
    expect(missing).toHaveAttribute('data-missing', 'true');
    expect(missing).toHaveTextContent('—');
    expect(within(cell('21', 'p95_ms')).getByTestId('stats-value')).toHaveAttribute(
      'data-value',
      '18',
    );
  });

  it('C-14 shows no estimated badge when the server says the values are exact', async () => {
    stub.on(LIST, () => envelope(sqlRows().map((row) => ({ ...row, estimated: false }))));
    await open(RANGE_QUERY);
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
  });

  it('T-UI-004 the sort switch asks p95 and errors as API sorts', async () => {
    stub.on(LIST, () => envelope(sqlRows()));
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    await user.click(screen.getByTestId('stats-sort-p95_ms'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('p95_ms:desc');
    }, FIND);
    await user.click(screen.getByTestId('stats-sort-errors'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('errors:desc');
    }, FIND);
    expect(screen.getByTestId('stats-sort-errors')).toHaveAttribute('aria-pressed', 'true');
  });

  it('T-UI-004 a target include asks the server for that db_id and needs no scope note', async () => {
    stub.on(LIST, (request) =>
      envelope(request.query.get('db_id') === '32' ? [sqlRows()[1]] : sqlRows()),
    );
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await user.click(within(cell('22', 'db')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(lastQuery().db_id).toBe('32');
    }, FIND);
    expect(parseUrl(StatsListQ, app.router.state.location.search).state.filters).toEqual([
      { field: 'db', op: 'eq', value: '32' },
    ]);
    await waitFor(() => {
      expect(rowKeys()).toEqual(['22']);
    }, FIND);
    expect(screen.queryByTestId('stats-filter-scope')).toBeNull();
    const chip = await screen.findByTestId('filter-chip', {}, FIND);
    expect(chip).toHaveTextContent('jdbc:mysql://inventory:3306/stock');
  });

  it('T-UI-004 a statement include narrows on the client and goes to the XLog as f.sql', async () => {
    stub.on(LIST, () => envelope(sqlRows()));
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    const before = jsonCalls().length;
    await user.click(within(cell('21', 'sql')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(rowKeys()).toEqual(['21']);
    }, FIND);
    expect(jsonCalls()).toHaveLength(before);
    expect(screen.getByTestId('stats-filter-scope')).toBeInTheDocument();
    const href = screen.getByTestId('stats-open-xlog').getAttribute('href') ?? '';
    expect(parseUrl(XLogQ, href.split('?')[1] ?? '').state.filters).toEqual([
      { field: 'sql', op: 'eq', value: '21' },
    ]);
  });

  it('keeps row keys unique for a statement that runs on two targets', async () => {
    stub.on(LIST, () =>
      envelope([
        sqlRow(),
        sqlRow({
          db: { ...sqlRow().db, db_id: '32', target: untrusted('jdbc:postgresql://replica/shop') },
        }),
      ]),
    );
    await open(RANGE_QUERY);
    expect(rowKeys()).toEqual(['21:31', '21:32']);
    const row = table().querySelector('[data-row-key="21:32"]');
    expect(row).toHaveAttribute('data-sql-id', '21');
    expect(row).toHaveAttribute('data-db-id', '32');
  });

  it('T-UI-004 exports CSV through the server with db_id and the list parameters', async () => {
    stub.on(LIST, (request) =>
      request.query.get('format') === 'csv' ? envelope([]) : envelope(sqlRows()),
    );
    const user = userEvent.setup();
    await open(`${RANGE_QUERY}&f.db=31&sort=count:desc`);
    await user.click(within(table()).getByTestId('csv-export'));
    await screen.findByTestId('stats-csv-error', {}, FIND);
    const csv = stub.calls(LIST).filter((call) => call.query.get('format') === 'csv');
    expect(csv).toHaveLength(1);
    const { format, tz, ...rest } = Object.fromEntries(csv[0]?.query.entries() ?? []);
    expect(format).toBe('csv');
    expect(tz).not.toBe('');
    expect(rest).toEqual(lastQuery());
    expect(rest.db_id).toBe('31');
    expect(rest.sort).toBe('count:desc');
  });

  it('T-UI-005 renders XSS payloads in statements and targets as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          sqlRow({
            sql_id: String(100 + index),
            sql_normalized: untrusted(payload),
            db: { ...sqlRow().db, db_id: String(200 + index), target: untrusted(payload) },
          }),
        ),
      ),
    );
    await open(`${RANGE_QUERY}&limit=50`);
    for (const payload of XSS_PAYLOADS) {
      expect(within(table()).getAllByTitle(payload).length).toBeGreaterThanOrEqual(2);
    }
    expect(findInjectedMarkup(screen.getByTestId('stats-sql'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
