// SPDX-License-Identifier: Apache-2.0
//
// The error groups list in the whole app (05 §2.5-1/-5, WO-36): the request (compare_prev, Top N,
// sort count | first_seen | last_seen), the "new" filter (`new=1` → new_only=true) with the new
// badge, the change of the count (relative, shown only when the server sends delta, T-API-011b),
// first and last occurrence, top services and versions, a class include narrowing the received
// rows, URL key filters of the XLog vocabulary (service, version, error_group) applied to the
// rows, the server CSV with new_only (T-UI-004), and collected strings as text (T-UI-005).
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
import { ErrorsQ, parseUrl, XLogQ } from '../../../state/urlState';
import {
  BIG_ID,
  breakdown,
  errorGroupRow,
  errorGroupRows,
  FROM_ISO,
  RANGE_QUERY,
  TO_ISO,
} from '../testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U07' };
export const REQ_TABLE = { type: 'req', description: 'F-U16' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps/1/errors/groups';

let stub: ApiStub;

function jsonCalls() {
  return stub.calls(LIST).filter((call) => call.query.get('format') !== 'csv');
}

function lastQuery(): Record<string, string> {
  return Object.fromEntries(jsonCalls().at(-1)?.query.entries() ?? []);
}

/** Answers new_only like the server: only groups first seen in the range. */
function answer(rows = errorGroupRows()): void {
  stub.on(LIST, (request) => {
    if (request.query.get('format') === 'csv') {
      return envelope([]);
    }
    return envelope(
      request.query.get('new_only') === 'true' ? rows.filter((row) => row.is_new) : rows,
    );
  });
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/errors?${query}`);
  const page = await screen.findByTestId('stats-errors', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="stats-errors"]');
  if (element === null) {
    throw new Error('error table not rendered');
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

describe('error groups list (docs/14 §4.8, 05 §2.5-5)', () => {
  it('T-UI-004 asks with compare_prev and Top N and shows the group columns', async () => {
    answer();
    await open(`${RANGE_QUERY}&limit=10`);
    expect(lastQuery()).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      limit: '10',
      compare_prev: 'true',
    });
    expect(rowKeys()).toEqual(['61', BIG_ID]);
    expect(cell('61', 'error_class')).toHaveTextContent('java.lang.IllegalStateException');
    expect(cell('61', 'message_pattern')).toHaveTextContent('Order <num> is not payable');
    expect(cell('61', 'top_frame')).toHaveTextContent('OrderService.pay');
    expect(within(cell('61', 'count')).getByTestId('stats-value')).toHaveAttribute(
      'data-value',
      '40',
    );
    expect(within(cell('61', 'first_seen')).getByTestId('stats-time')).toHaveAttribute(
      'data-value',
      '2026-11-20T03:05:00.000Z',
    );
    expect(within(cell(BIG_ID, 'last_seen')).getByTestId('stats-time')).toHaveAttribute(
      'data-value',
      '2026-11-20T03:50:00.000Z',
    );
    const services = within(cell(BIG_ID, 'services')).getAllByRole('listitem');
    expect(services.map((item) => item.getAttribute('data-service-id'))).toEqual(['12', '11']);
    expect(cell(BIG_ID, 'versions')).toHaveTextContent('1.4.0');
    expect(cell(BIG_ID, 'versions')).toHaveTextContent('1.5.0');
  });

  it('marks new groups with the badge and data-new; old ones have neither', async () => {
    answer();
    await open(RANGE_QUERY);
    expect(within(cell('61', 'error_class')).getByTestId('stats-new-badge')).toHaveTextContent(
      'New',
    );
    expect(within(cell(BIG_ID, 'error_class')).queryByTestId('stats-new-badge')).toBeNull();
    expect(table().querySelector('[data-row-key="61"]')).toHaveAttribute('data-new', 'true');
    expect(table().querySelector(`[data-row-key="${BIG_ID}"]`)).not.toHaveAttribute('data-new');
  });

  it('T-UI-004 the new filter asks new_only=true, keeps new=1 in the URL and turns off again', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const toggle = screen.getByTestId('stats-errors-new');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await user.click(toggle);
    await waitFor(() => {
      expect(lastQuery().new_only).toBe('true');
    }, FIND);
    await waitFor(() => {
      expect(rowKeys()).toEqual(['61']);
    }, FIND);
    expect(parseUrl(ErrorsQ, app.router.state.location.search).state.new).toBe(true);
    expect(screen.getByTestId('stats-errors-new')).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByTestId('stats-errors-new'));
    await waitFor(() => {
      expect(rowKeys()).toEqual(['61', BIG_ID]);
    }, FIND);
    expect(lastQuery().new_only).toBeUndefined();
  });

  it('says what to do when no group is new in the range', async () => {
    answer([errorGroupRow({ is_new: false })]);
    signedIn(stub);
    renderApp(`/apps/1/stats/errors?${RANGE_QUERY}&new=1`);
    const empty = await screen.findByTestId('stats-errors-empty', {}, FIND);
    expect(empty).toHaveTextContent('No new error groups');
    expect(empty).toHaveTextContent('Turn off the new filter');
  });

  it('T-UI-004 the sort switch asks first_seen and last_seen as API sorts', async () => {
    answer();
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-sort-count')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('stats-sort-p95_ms')).toBeNull();
    await user.click(screen.getByTestId('stats-sort-first_seen'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('first_seen:desc');
    }, FIND);
    await user.click(screen.getByTestId('stats-sort-last_seen'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('last_seen:desc');
    }, FIND);
  });

  it('C-211 (3) shows the count change relative, and "No comparison data" without a basis', async () => {
    answer();
    await open(RANGE_QUERY);
    const old = within(cell(BIG_ID, 'count')).getByTestId('stats-delta-count');
    expect(old).toHaveAttribute('data-value', '0.25');
    const fresh = within(cell('61', 'count')).getByTestId('stats-delta-count');
    expect(fresh).toHaveAttribute('data-missing', 'true');
    expect(fresh).toHaveTextContent('No comparison data');
  });

  it('hides the change column when the server sends no delta', async () => {
    answer(errorGroupRows().map((row) => ({ ...row, delta: undefined })));
    await open(RANGE_QUERY);
    expect(table().querySelector('[data-testid^="stats-delta-"]')).toBeNull();
  });

  it('T-UI-004 a class include narrows the received rows; exclusion keeps the others', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const before = jsonCalls().length;
    await user.click(within(cell(BIG_ID, 'error_class')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(rowKeys()).toEqual([BIG_ID]);
    }, FIND);
    expect(parseUrl(ErrorsQ, app.router.state.location.search).state.filters).toEqual([
      { field: 'error_class', op: 'eq', value: 'java.net.SocketTimeoutException' },
    ]);
    expect(jsonCalls()).toHaveLength(before);
    expect(screen.getByTestId('stats-filter-scope')).toBeInTheDocument();
  });

  it('T-UI-004 URL key filters of the XLog vocabulary narrow the rows and go to the XLog', async () => {
    answer();
    await open(`${RANGE_QUERY}&f.service=12&f.version=41`);
    expect(rowKeys()).toEqual([BIG_ID]);
    expect(screen.queryByTestId('stats-filter-unapplied')).toBeNull();
    const chips = screen.getAllByTestId('filter-chip').map((chip) => chip.textContent);
    expect(chips.join(' ')).toContain('POST /api/orders');
    expect(chips.join(' ')).toContain('1.4.0');
    const href = screen.getByTestId('stats-open-xlog').getAttribute('href') ?? '';
    expect(parseUrl(XLogQ, href.split('?')[1] ?? '').state.filters).toEqual([
      { field: 'service', op: 'eq', value: '12' },
      { field: 'version', op: 'eq', value: '41' },
    ]);
  });

  it('T-UI-004 exports CSV through the server with new_only and the list parameters', async () => {
    answer();
    const user = userEvent.setup();
    await open(`${RANGE_QUERY}&new=1&sort=last_seen:desc`);
    await user.click(within(table()).getByTestId('csv-export'));
    await screen.findByTestId('stats-csv-error', {}, FIND);
    const csv = stub.calls(LIST).filter((call) => call.query.get('format') === 'csv');
    expect(csv).toHaveLength(1);
    const { format, tz, ...rest } = Object.fromEntries(csv[0]?.query.entries() ?? []);
    expect(format).toBe('csv');
    expect(tz).not.toBe('');
    expect(rest).toEqual(lastQuery());
    expect(rest.new_only).toBe('true');
    expect(rest.sort).toBe('last_seen:desc');
  });

  it('opens the group detail from the class link with the range kept', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await user.click(within(cell('61', 'error_class')).getByRole('link'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/stats/errors/61');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${FROM_ISO}`);
  });

  it('T-UI-005 renders XSS payloads in classes, messages, frames, services and versions as text', async () => {
    answer(
      XSS_PAYLOADS.map((payload, index) =>
        errorGroupRow({
          group_id: String(100 + index),
          error_class: untrusted(payload),
          message_pattern: untrusted(payload),
          top_frame: untrusted(payload),
          services: [breakdown(String(200 + index), payload)],
          versions: [{ version_id: String(300 + index), version: untrusted(payload) }],
        }),
      ),
    );
    await open(`${RANGE_QUERY}&limit=50`);
    for (const payload of XSS_PAYLOADS) {
      expect(within(table()).getAllByTitle(payload).length).toBeGreaterThanOrEqual(4);
    }
    expect(findInjectedMarkup(screen.getByTestId('stats-errors'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
