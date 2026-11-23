// SPDX-License-Identifier: Apache-2.0
//
// The services list in the whole app (router, URL loader, transport, generated client; only the
// HTTP boundary is stubbed): the request of the URL state (range, kind, Top N, compare_prev), rows
// in the server's order with raw values and changes, the sort switch and header sort as the API
// sort, Top N, value include/exclude as URL filters that narrow the received rows with the scope
// note and the shell's chip, the per-version split, the server CSV with the same parameters
// (T-UI-004), collected names as text (T-UI-005), null apart from 0 (T-UI-007), the empty and
// error states with their next action, and the links to the detail and the XLog.
import { screen, waitFor, within } from '@testing-library/react';
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
import { parseUrl, StatsListQ, XLogQ } from '../../../state/urlState';
import {
  BIG_ID,
  FROM_ISO,
  RANGE_QUERY,
  serviceRow,
  serviceRows,
  TO_ISO,
} from '../testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U05' };
export const REQ_TABLE = { type: 'req', description: 'F-U16' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps/1/services/stats';

let stub: ApiStub;

function jsonCalls() {
  return stub.calls(LIST).filter((call) => call.query.get('format') !== 'csv');
}

function lastQuery(): Record<string, string> {
  return Object.fromEntries(jsonCalls().at(-1)?.query.entries() ?? []);
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/services?${query}`);
  await screen.findByTestId('stats-services', {}, FIND);
  return app;
}

async function ready(): Promise<HTMLElement> {
  const page = screen.getByTestId('stats-services');
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return page;
}

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="stats-services"]');
  if (element === null) {
    throw new Error('services table not rendered');
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

describe('services list: request and rows (docs/14 §4.8, 05 §2.5-1)', () => {
  it('T-UI-004 asks once for the URL range, kind and Top N with compare_prev and keeps the server order', async () => {
    stub.on(LIST, () => envelope(serviceRows(), { source: '1m' }));
    await open(`${RANGE_QUERY}&kind=BATCH&limit=50`);
    await ready();
    expect(jsonCalls()).toHaveLength(1);
    expect(lastQuery()).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'BATCH',
      limit: '50',
      compare_prev: 'true',
    });
    expect(rowKeys()).toEqual(['11', BIG_ID, '13']);
    expect(screen.getByTestId('stats-source')).toHaveAttribute('data-source', '1m');
    // The default sort is the server's (total time), shown pressed.
    expect(screen.getByTestId('stats-sort-sum_ms')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('stats-limit')).toHaveValue('50');
  });

  it('T-UI-007 keeps raw values and changes per cell; null is the dash, 0 a value', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    await open(RANGE_QUERY);
    await ready();
    const count = within(cell('11', 'count'));
    expect(count.getByTestId('stats-value')).toHaveAttribute('data-value', '1200');
    expect(count.getByTestId('stats-delta-count')).toHaveAttribute('data-value', '0.12');
    expect(within(cell('11', 'p95_ms')).getByTestId('stats-delta-p95_ms')).toHaveAttribute(
      'data-value',
      '-0.05',
    );
    expect(
      within(cell('11', 'error_rate')).getByTestId('stats-delta-error_rate'),
    ).toHaveTextContent('+0.4 pp');
    const p95 = within(cell(BIG_ID, 'p95_ms')).getByTestId('stats-value');
    expect(p95).toHaveAttribute('data-value', '');
    expect(p95).toHaveAttribute('data-missing', 'true');
    expect(p95).toHaveTextContent('—');
    const errors = within(cell(BIG_ID, 'errors')).getByTestId('stats-value');
    expect(errors).toHaveAttribute('data-value', '0');
    expect(errors).toHaveTextContent('0');
    expect(within(cell(BIG_ID, 'count')).getByTestId('stats-delta-count')).toHaveTextContent(
      'No comparison data',
    );
    // Service percentiles merge DD sketches: no estimated badge (C-14).
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
  });
});

describe('services list: sort, Top N, filters, CSV (T-UI-004)', () => {
  it('T-UI-004 the sort switch and the header sort ask the server with the API sort', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await ready();
    await user.click(screen.getByTestId('stats-sort-p95_ms'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('p95_ms:desc');
    }, FIND);
    expect(parseUrl(StatsListQ, app.router.state.location.search).state.sort).toBe('p95_ms:desc');
    expect(screen.getByTestId('stats-sort-p95_ms')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('stats-sort-sum_ms')).toHaveAttribute('aria-pressed', 'false');
    await user.click(within(table()).getByTestId('data-table-sort-count'));
    await waitFor(() => {
      expect(lastQuery().sort).toBe('count:desc');
    }, FIND);
    // A column the server cannot sort by has no sort button.
    expect(within(table()).queryByTestId('data-table-sort-errors')).toBeNull();
  });

  it('T-UI-004 reads the docs/15 sort -p95_ms and Top N from a shared link', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    const user = userEvent.setup();
    const app = await open(`${RANGE_QUERY}&sort=-p95_ms`);
    await ready();
    expect(lastQuery().sort).toBe('p95_ms:desc');
    await user.selectOptions(screen.getByTestId('stats-limit'), '500');
    await waitFor(() => {
      expect(lastQuery().limit).toBe('500');
    }, FIND);
    expect(app.url()).toContain('limit=500');
  });

  it('T-UI-004 value include narrows the received rows, says so and names the chip', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await ready();
    const before = jsonCalls().length;
    await user.click(within(cell(BIG_ID, 'service')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(parseUrl(StatsListQ, app.router.state.location.search).state.filters).toEqual([
        { field: 'service', op: 'eq', value: BIG_ID },
      ]);
    }, FIND);
    await waitFor(() => {
      expect(rowKeys()).toEqual([BIG_ID]);
    }, FIND);
    expect(screen.getByTestId('stats-filter-scope')).toHaveTextContent('top 3 rows');
    const chip = await screen.findByTestId('filter-chip', {}, FIND);
    expect(chip).toHaveAttribute('data-value', BIG_ID);
    expect(chip).toHaveTextContent('POST /api/orders');
    // The statistics API has no service filter: the same question is not asked again.
    expect(jsonCalls()).toHaveLength(before);
    // The XLog link carries the same key filter.
    const xlog = screen.getByTestId('stats-open-xlog');
    const state = parseUrl(XLogQ, (xlog.getAttribute('href') ?? '').split('?')[1] ?? '').state;
    expect(state.filters).toEqual([{ field: 'service', op: 'eq', value: BIG_ID }]);
  });

  it('T-UI-004 value exclude removes the row and the empty result offers to clear filters', async () => {
    stub.on(LIST, () => envelope([serviceRow()]));
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    await ready();
    await user.click(within(cell('11', 'service')).getByTestId('cell-filter-exclude'));
    const empty = await screen.findByTestId('stats-services-empty', {}, FIND);
    expect(screen.getByTestId('stats-services')).toHaveAttribute('data-state', 'empty');
    expect(empty).toHaveTextContent('No rows match the filters.');
    await user.click(within(empty).getByTestId('empty-state-clear-filters'));
    await waitFor(() => {
      expect(rowKeys()).toEqual(['11']);
    }, FIND);
  });

  it('T-UI-004 a column range filter narrows on the client', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    const user = userEvent.setup();
    const app = await open(`${RANGE_QUERY}&f.count.gte=100`);
    await ready();
    expect(rowKeys()).toEqual(['11', BIG_ID]);
    expect(app.url()).toContain('f.count.gte=100');
    await user.click(within(table()).getByTestId('data-table-filter-button-count'));
    expect(await screen.findByTestId('data-table-filter-min', {}, FIND)).toHaveValue(100);
  });

  it('T-UI-004 exports CSV through the server with the parameters of the shown list', async () => {
    stub.on(LIST, (request) =>
      // The CSV answer is not text/csv here: the screen shows the export error, nothing is saved.
      request.query.get('format') === 'csv' ? envelope([]) : envelope(serviceRows()),
    );
    const user = userEvent.setup();
    await open(`${RANGE_QUERY}&sort=count:desc&limit=10&split=1`);
    await ready();
    await user.click(within(table()).getByTestId('csv-export'));
    await screen.findByTestId('stats-csv-error', {}, FIND);
    const csv = stub.calls(LIST).filter((call) => call.query.get('format') === 'csv');
    expect(csv).toHaveLength(1);
    const csvQuery = Object.fromEntries(csv[0]?.query.entries() ?? []);
    const { format, tz, ...rest } = csvQuery;
    expect(format).toBe('csv');
    expect(tz).not.toBe('');
    expect(rest).toEqual(lastQuery());
    expect(rest).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      sort: 'count:desc',
      limit: '10',
      split_version: 'true',
      compare_prev: 'true',
    });
  });

  it('T-UI-004 the version split asks split_version and adds the version column', async () => {
    stub.on(LIST, (request) =>
      envelope(
        request.query.get('split_version') === 'true'
          ? [
              serviceRow({ version_id: '41', version: untrusted('1.4.0') }),
              serviceRow({ version_id: '42', version: untrusted('1.5.0'), p95_ms: 300 }),
            ]
          : [serviceRow()],
      ),
    );
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    await ready();
    expect(screen.getByTestId('stats-split-version')).toHaveAttribute('aria-pressed', 'false');
    await user.click(screen.getByTestId('stats-split-version'));
    await waitFor(() => {
      expect(rowKeys()).toEqual(['11:41', '11:42']);
    }, FIND);
    expect(lastQuery().split_version).toBe('true');
    expect(app.url()).toContain('split=1');
    expect(cell('11:42', 'version')).toHaveTextContent('1.5.0');
    await user.click(within(cell('11:41', 'version')).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(rowKeys()).toEqual(['11:41']);
    }, FIND);
    expect(parseUrl(StatsListQ, app.router.state.location.search).state.filters).toEqual([
      { field: 'version', op: 'eq', value: '41' },
    ]);
  });
});

describe('services list: links, states and collected text', () => {
  it('opens the service detail of a row with the range kept', async () => {
    stub.on(LIST, () => envelope(serviceRows()));
    stub.on(`GET /api/v1/apps/1/services/${BIG_ID}`, () => errorBody(404, 'not_found'));
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    const user = userEvent.setup();
    const app = await open(`${RANGE_QUERY}&kind=ALL`);
    await ready();
    await user.click(within(cell(BIG_ID, 'service')).getByRole('link'));
    await screen.findByTestId('stats-service-detail', {}, FIND);
    expect(app.router.state.location.pathname).toBe(`/apps/1/stats/services/${BIG_ID}`);
    expect(app.router.state.location.search).toContain(`from=${FROM_ISO}`);
    expect(app.router.state.location.search).toContain('kind=ALL');
  });

  it('shows the empty state with the next action for an empty range', async () => {
    stub.on(LIST, () => envelope([]));
    await open(RANGE_QUERY);
    const empty = await screen.findByTestId('stats-services-empty', {}, FIND);
    expect(screen.getByTestId('stats-services')).toHaveAttribute('data-state', 'empty');
    expect(empty).toHaveTextContent('No services in the selected range.');
    expect(empty).toHaveTextContent('Widen the range');
    expect(within(empty).getByTestId('empty-state-widen')).toBeInTheDocument();
    // The controls stay: the user can change Top N or the sort from here.
    expect(screen.getByTestId('stats-sort-count')).toBeInTheDocument();
  });

  it('shows the error state with the request id and retry', async () => {
    stub.on(LIST, () => errorBody(400, 'limit_exceeded', { requestId: 'req-svc-1' }));
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    const error = await screen.findByTestId('stats-services-error', {}, FIND);
    expect(screen.getByTestId('stats-services')).toHaveAttribute('data-state', 'error');
    expect(within(error).getByTestId('error-state-request-id')).toHaveTextContent('req-svc-1');
    stub.on(LIST, () => envelope(serviceRows()));
    await user.click(within(error).getByTestId('error-state-retry'));
    await ready();
  });

  it('T-UI-005 renders XSS payloads in service names and versions as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          serviceRow({
            service_id: String(100 + index),
            service: untrusted(payload),
            version_id: String(200 + index),
            version: untrusted(payload),
          }),
        ),
      ),
    );
    await open(`${RANGE_QUERY}&split=1&limit=50`);
    const page = await ready();
    expect(rowKeys()).toHaveLength(XSS_PAYLOADS.length);
    for (const payload of XSS_PAYLOADS) {
      // The service and version cells show the payload as text (its full value as the tooltip).
      expect(within(table()).getAllByTitle(payload).length).toBeGreaterThanOrEqual(2);
    }
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
