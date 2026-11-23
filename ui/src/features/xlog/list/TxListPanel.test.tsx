// SPDX-License-Identifier: Apache-2.0
//
// XLog transaction list (05 §2.1-2, -8, -9, docs/15 §7.4, §9.3): the server search of the URL
// selection (the request built by toTransactionSearchRequest), server order, "top 1,000" when the
// search has more rows, missing values as the dash (T-UI-007), the user id column only when
// collected, include/exclude -> URL filters (05 §2.0-4), keyboard Up/Down/Enter to the detail link
// with `ts` and `back` and the remembered list order (C-206), the server CSV export with its retry
// hint (C-205 (1), C-176), filters the search cannot express listed, and collected strings as text
// (T-UI-005). The network is stubbed at the fetch boundary; everything above it is real.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'SEC-61' }, { type: 'req', description: 'SEC-63' }
import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import { createQueryClient } from '../../../api/queryClient';
import { toTransactionSearchRequest } from '../../../api/transactionSearch';
import { untrusted } from '../../../api/untrusted';
import { withI18n } from '../../../components/testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS } from '../../../components/testing/xssPayloads';
import { MISSING_DASH } from '../../../utils/missing';
import { STATUS_ERROR, XLogBuffer } from '../../../xlog/XLogBuffer';
import { LocalDictionary } from '../data/dictionary';

import type { TxRow } from './columns';
import { clearListContexts, listNeighbours } from './listContext';
import {
  TxListPanel,
  type ListPreviewSource,
  type XLogListChange,
  type XLogListQuery,
} from './TxListPanel';

const SEARCH = 'POST /api/v1/apps/7/transactions/search';
const HOUR = Math.floor(Date.now() / 3_600_000) * 3_600_000;
const SEL = { msMin: 100, msMax: 9000, tsFrom: HOUR - 600_000, tsTo: HOUR - 300_000 };
const BACK = `/apps/7/xlog?from=${new Date(HOUR - 900_000).toISOString()}&to=${new Date(HOUR).toISOString()}&sel=ms:100-9000,ts:${String(SEL.tsFrom)}-${String(SEL.tsTo)}`;

function row(i: number, partial: Partial<TxRow> = {}): TxRow {
  return {
    tx_id: `73120000000000000${String(10 + i)}`,
    ts: new Date(SEL.tsFrom + i * 1000).toISOString(),
    service: untrusted(`GET /api/orders/${String(i)}`),
    service_id: `1844674407370955161${String(i % 6)}`,
    elapsed_ms: 5000 - i * 100,
    http_status: 200,
    error: false,
    error_class: null,
    error_class_id: null,
    instance: untrusted(`web-0${String(i % 3)}:8080`),
    instance_id: 10 + (i % 3),
    sql_count: i,
    sql_time_ms: i * 10,
    ext_count: 0,
    ext_time_ms: 0,
    user_id: null,
    user_id_mode: 'off',
    has_profile: i % 2 === 0,
    kind: 'HTTP',
    version: untrusted('1.4.2'),
    version_id: '33',
    ...partial,
  };
}

function query(partial: Partial<XLogListQuery> = {}): XLogListQuery {
  return {
    time: { kind: 'absolute', from: HOUR - 900_000, to: HOUR },
    kind: 'HTTP',
    filters: [],
    sort: undefined,
    sel: SEL,
    cols: undefined,
    ...partial,
  };
}

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
  clearListContexts();
});

afterEach(() => {
  uninstallApiStub();
});

interface Harness {
  readonly onQueryChange: ReturnType<typeof vi.fn<(change: XLogListChange) => void>>;
  readonly onOpen: ReturnType<typeof vi.fn<(row: TxRow, href: string) => void>>;
  rerender(next: XLogListQuery): void;
}

function renderList(initial: XLogListQuery, preview: ListPreviewSource | null = null): Harness {
  const client = createQueryClient();
  const onQueryChange = vi.fn<(change: XLogListChange) => void>();
  const onOpen = vi.fn<(row: TxRow, href: string) => void>();
  const current = initial;
  let setQuery: ((q: XLogListQuery) => void) | null = null;
  function Host(): ReactNode {
    const [q, set] = useState(current);
    setQuery = set;
    return (
      <TxListPanel
        appId={7}
        query={q}
        onQueryChange={onQueryChange}
        back={BACK}
        onOpen={onOpen}
        preview={preview}
      />
    );
  }
  const router = createMemoryRouter([{ path: '*', element: <Host /> }], {
    initialEntries: [BACK],
  });
  const I18n = withI18n('en');
  render(
    <I18n>
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18n>,
  );
  return {
    onQueryChange,
    onOpen,
    rerender(next) {
      act(() => {
        setQuery?.(next);
      });
    },
  };
}

function bodyRows(): HTMLElement[] {
  return screen.getAllByTestId('data-table-row');
}

function cellOf(rowElement: HTMLElement, column: string): HTMLElement {
  const found = rowElement.querySelector<HTMLElement>(`[data-column="${column}"]`);
  if (found === null) {
    throw new Error(`no cell ${column}`);
  }
  return found;
}

describe('TxListPanel: server search of the selection', () => {
  it('T-UI-001 shows nothing to search without a selection and sends no request', () => {
    renderList(query({ sel: undefined }));
    expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'idle');
    expect(screen.getByTestId('xlog-list-idle')).toHaveTextContent(/keyboard/);
    expect(stub.calls(SEARCH)).toHaveLength(0);
  });

  it('T-UI-001 posts toTransactionSearchRequest of the selection and shows the rows in server order', async () => {
    const rows = [row(0), row(1), row(2)];
    stub.on(SEARCH, () => envelope(rows, { shown: 3, total: 3, next_cursor: null }));
    renderList(query());
    await waitFor(() => {
      expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'ready');
    });
    const expected = toTransactionSearchRequest(7, query()).body;
    expect(stub.calls(SEARCH)[0]?.body).toEqual(expected);
    expect(expected.sort).toBe('elapsed_ms:desc');
    expect(expected.limit).toBe(1000);
    expect(bodyRows().map((r) => r.getAttribute('data-row-key'))).toEqual(rows.map((r) => r.tx_id));
    expect(screen.getByTestId('xlog-list-count')).toHaveTextContent('3 transactions');
    expect(screen.queryByTestId('xlog-list-top')).toBeNull();
    expect(screen.getByTestId('xlog-list')).toHaveAttribute(
      'data-sel',
      `ms:100-9000,ts:${String(SEL.tsFrom)}-${String(SEL.tsTo)}`,
    );
  });

  it('T-UI-001 says "top 1,000" when the search has more rows than the page', async () => {
    const rows = Array.from({ length: 1000 }, (_, i) => row(i, { elapsed_ms: 10_000 - i }));
    stub.on(SEARCH, () => envelope(rows, { shown: 1000, total: 4321, next_cursor: 'next' }));
    renderList(query());
    const top = await screen.findByTestId('xlog-list-top');
    expect(top).toHaveTextContent('Slowest 1,000 of 4,321');
    expect(top).toHaveAttribute('data-total', '4321');
    // 1,000 rows are virtualized (docs/15 §6.3): only a window is in the DOM.
    expect(screen.getByTestId('data-table').querySelector('[data-virtual="true"]')).not.toBeNull();
    expect(bodyRows().length).toBeLessThan(100);
  });

  it('T-UI-007 renders missing values as the dash, not 0; batch rows have no HTTP status', async () => {
    stub.on(SEARCH, () =>
      envelope([
        row(0, { service: null, service_id: null, version: null, version_id: null }),
        row(1, {
          kind: 'BATCH',
          http_status: 1,
          error: true,
          error_class: untrusted('JobFailed'),
          error_class_id: '5',
        }),
      ]),
    );
    renderList(query({ kind: 'ALL' }));
    await waitFor(() => {
      expect(bodyRows()).toHaveLength(2);
    });
    const [first, second] = bodyRows() as [HTMLElement, HTMLElement];
    expect(cellOf(first, 'service')).toHaveTextContent(MISSING_DASH);
    expect(cellOf(first, 'version')).toHaveTextContent(MISSING_DASH);
    expect(cellOf(first, 'error_class')).toHaveTextContent(MISSING_DASH);
    expect(cellOf(first, 'ext_count')).toHaveTextContent('0');
    expect(cellOf(second, 'http_status')).toHaveTextContent(MISSING_DASH);
    expect(cellOf(second, 'error_class')).toHaveTextContent('JobFailed');
    expect(cellOf(second, 'kind')).toHaveTextContent('Batch');
    // No include/exclude on a cell without a key.
    expect(within(cellOf(first, 'service')).queryByTestId('cell-filter-include')).toBeNull();
  });

  it('T-UI-007 hides the user id column with a note unless the application collects it', async () => {
    stub.on(SEARCH, () => envelope([row(0), row(1)]));
    renderList(query());
    await screen.findByTestId('xlog-list-user-note');
    expect(screen.queryByTestId('column-chooser-user_id')).toBeNull();
    expect((bodyRows()[0] as HTMLElement).querySelector('[data-column="user_id"]')).toBeNull();
  });

  it('T-UI-007 shows the user id column when user ids are collected', async () => {
    stub.on(SEARCH, () =>
      envelope([row(0, { user_id: untrusted('alice'), user_id_mode: 'plain' }), row(1)]),
    );
    renderList(query());
    await waitFor(() => {
      expect(bodyRows()).toHaveLength(2);
    });
    expect(screen.queryByTestId('xlog-list-user-note')).toBeNull();
    expect(cellOf(bodyRows()[0] as HTMLElement, 'user_id')).toHaveTextContent('alice');
    expect(cellOf(bodyRows()[1] as HTMLElement, 'user_id')).toHaveTextContent(MISSING_DASH);
  });

  it('T-UI-001 lists the filters the search cannot express and does not emulate them', async () => {
    stub.on(SEARCH, () => envelope([row(0)]));
    renderList(
      query({
        filters: [
          { field: 'service', op: 'ne', value: untrusted('<img src=x onerror=window.__xss=1>') },
          { field: 'status', op: 'eq', value: untrusted('500') },
        ],
      }),
    );
    const notice = await screen.findByTestId('xlog-list-unapplied');
    const items = notice.querySelectorAll('li');
    expect(items).toHaveLength(1);
    expect(items[0]).toHaveAttribute('data-reason', 'exclude');
    expect(items[0]).toHaveTextContent('<img src=x onerror=window.__xss=1>');
    expect(findInjectedMarkup(notice)).toEqual([]);
    await waitFor(() => {
      expect(stub.calls(SEARCH)).toHaveLength(1);
    });
    expect(stub.calls(SEARCH)[0]?.body).toMatchObject({ filters: { http_status: [500] } });
  });

  it('T-UI-001 shows the empty state without a request when the filters match nothing', () => {
    renderList(query({ filters: [{ field: 'service', op: 'eq', value: untrusted('not-a-key') }] }));
    expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('xlog-list-empty')).toBeInTheDocument();
    expect(stub.calls(SEARCH)).toHaveLength(0);
  });

  it('T-UI-001 links the excluded requests of the range in the empty state (D-19)', async () => {
    stub.on(SEARCH, () => envelope([], { shown: 0, total: 0 }));
    stub.on('GET /api/v1/apps/7/collection-health', () =>
      envelope({
        agent_drops: { ts: [], value: [] },
        clock_skew: null,
        excluded: { by_reason: { health_check: 40, static: 2 }, ts: [], value: [] },
        ingest_lag_s: { ts: [], value: [] },
        late_rows: null,
        outages: [],
        per_instance: [],
        server_drops: { ts: [], value: [] },
        server_drops_quota: { ts: [], value: [] },
      }),
    );
    renderList(query());
    const link = await screen.findByTestId('empty-state-excluded');
    expect(link).toHaveTextContent('42');
    const health = stub.calls('GET /api/v1/apps/7/collection-health')[0];
    expect(health?.query.get('from')).toBe(new Date(SEL.tsFrom).toISOString());
    expect(health?.query.get('to')).toBe(new Date(SEL.tsTo + 1).toISOString());
  });

  it('T-UI-005 renders collected strings of every column as text', async () => {
    const rows = XSS_PAYLOADS.map((payload, i) =>
      row(i, {
        service: untrusted(payload),
        instance: untrusted(payload),
        error_class: untrusted(payload),
        error_class_id: String(i + 1),
        version: untrusted(payload),
        user_id: untrusted(payload),
        user_id_mode: 'plain',
      }),
    );
    stub.on(SEARCH, () => envelope(rows));
    renderList(query());
    await waitFor(() => {
      expect(bodyRows()).toHaveLength(XSS_PAYLOADS.length);
    });
    const table = screen.getByTestId('data-table');
    for (const [i, payload] of XSS_PAYLOADS.entries()) {
      const r = bodyRows()[i] as HTMLElement;
      for (const column of ['service', 'instance', 'error_class', 'version', 'user_id']) {
        expect(cellOf(r, column).textContent, `${column} ${String(i)}`).toContain(
          payload.slice(0, 20),
        );
      }
    }
    expect(findInjectedMarkup(table)).toEqual([]);
    expect((window as unknown as { __xss?: unknown }).__xss).toBeUndefined();
  });
});

describe('TxListPanel: interaction', () => {
  it('T-UI-004 include/exclude from a cell writes f= filters; an included kind sets the global kind', async () => {
    const user = userEvent.setup();
    stub.on(SEARCH, () => envelope([row(0), row(1)]));
    const harness = renderList(query({ kind: 'ALL' }));
    await waitFor(() => {
      expect(bodyRows()).toHaveLength(2);
    });
    const first = bodyRows()[0] as HTMLElement;
    await user.click(within(cellOf(first, 'service')).getByTestId('cell-filter-include'));
    expect(harness.onQueryChange).toHaveBeenLastCalledWith({
      filters: [{ field: 'service', op: 'eq', value: '18446744073709551610' }],
    });
    await user.click(within(cellOf(first, 'instance')).getByTestId('cell-filter-exclude'));
    expect(harness.onQueryChange).toHaveBeenLastCalledWith({
      filters: [{ field: 'instance', op: 'ne', value: '10' }],
    });
    await user.click(within(cellOf(first, 'http_status')).getByTestId('cell-filter-include'));
    expect(harness.onQueryChange).toHaveBeenLastCalledWith({
      filters: [{ field: 'status', op: 'eq', value: '200' }],
    });
    await user.click(within(cellOf(first, 'kind')).getByTestId('cell-filter-include'));
    expect(harness.onQueryChange).toHaveBeenLastCalledWith({ kind: 'HTTP' });
    // The selection is never part of what the list writes.
    for (const [change] of harness.onQueryChange.mock.calls) {
      expect(change).not.toHaveProperty('sel');
    }
  });

  it('T-UI-004 sorts on the server: the time header asks for ts:desc', async () => {
    const user = userEvent.setup();
    stub.on(SEARCH, () => envelope([row(0)]));
    const harness = renderList(query());
    await screen.findByTestId('data-table-sort-ts');
    await user.click(screen.getByTestId('data-table-sort-ts'));
    expect(harness.onQueryChange).toHaveBeenLastCalledWith({ sort: 'ts:desc' });
    expect(screen.getByTestId('data-table-sort-ts').closest('th')).not.toHaveAttribute('aria-sort');
    harness.rerender(query({ sort: 'ts:desc' }));
    await waitFor(() => {
      expect(stub.calls(SEARCH).at(-1)?.body).toMatchObject({ sort: 'ts:desc' });
    });
    expect(screen.getByTestId('data-table-sort-ts').closest('th')).toHaveAttribute(
      'aria-sort',
      'descending',
    );
  });

  it('T-UI-004 keeps the column choice in cols=', async () => {
    const user = userEvent.setup();
    stub.on(SEARCH, () => envelope([row(0)]));
    const harness = renderList(query());
    await screen.findByTestId('column-chooser');
    await user.click(screen.getByTestId('column-chooser'));
    await user.click(screen.getByTestId('column-chooser-version'));
    const last = harness.onQueryChange.mock.calls.at(-1)?.[0];
    expect(last?.cols?.split(',')).not.toContain('version');
    expect(last?.cols?.split(',')).toContain('service');
  });

  it('T-UI-001 Up/Down/Enter opens the detail with ts and back and remembers the list order', async () => {
    const user = userEvent.setup();
    const rows = [row(0), row(1), row(2)];
    stub.on(SEARCH, () => envelope(rows, { shown: 3, total: 3, next_cursor: null }));
    const harness = renderList(query());
    await waitFor(() => {
      expect(bodyRows()).toHaveLength(3);
    });
    (bodyRows()[0] as HTMLElement).focus();
    await user.keyboard('{ArrowDown}');
    await user.keyboard('{Enter}');
    expect(harness.onOpen).toHaveBeenCalledTimes(1);
    const [opened, href] = harness.onOpen.mock.calls[0] ?? [];
    expect(opened?.tx_id).toBe(rows[1]?.tx_id);
    const [path, search] = (href ?? '').split('?');
    expect(path).toBe(`/apps/7/transactions/${rows[1]?.tx_id ?? ''}`);
    const params = new URLSearchParams(search);
    expect(params.get('ts')).toBe(rows[1]?.ts);
    expect(params.get('back')).toBe(BACK);
    expect(listNeighbours(BACK, rows[1]?.tx_id ?? '')).toMatchObject({
      index: 1,
      previous: { txId: rows[0]?.tx_id },
      next: { txId: rows[2]?.tx_id },
    });
  });

  it('T-UI-004 exports CSV through the server and shows the Retry-After hint of a 429 (no retry)', async () => {
    const user = userEvent.setup();
    let csvCalls = 0;
    stub.on(SEARCH, (request) => {
      if (request.query.get('format') === 'csv') {
        csvCalls += 1;
        return errorBody(429, 'rate_limited', { headers: { 'Retry-After': '12' } });
      }
      return envelope([row(0)]);
    });
    renderList(query());
    await screen.findByTestId('csv-export');
    await user.click(screen.getByTestId('csv-export'));
    const error = await screen.findByTestId('xlog-list-csv-error');
    expect(error).toHaveTextContent('Try again in 12 s');
    expect(csvCalls).toBe(1);
    const csvRequest = stub.calls(SEARCH).find((r) => r.query.get('format') === 'csv');
    expect(csvRequest?.body).not.toHaveProperty('cursor');
    expect(csvRequest?.body).toMatchObject({ filters: { min_ms: 100, max_ms: 9000 } });
  });

  it('T-UI-004 explains an export over 10,000 rows', async () => {
    const user = userEvent.setup();
    stub.on(SEARCH, (request) =>
      request.query.get('format') === 'csv' ? errorBody(400, 'limit_exceeded') : envelope([row(0)]),
    );
    renderList(query());
    await user.click(await screen.findByTestId('csv-export'));
    expect(await screen.findByTestId('xlog-list-csv-error')).toHaveTextContent('10,000');
  });

  it('T-UI-001 shows a server error with the request id and retries only on request', async () => {
    const user = userEvent.setup();
    let calls = 0;
    stub.on(SEARCH, () => {
      calls += 1;
      return calls === 1
        ? errorBody(504, 'query_timeout', { requestId: 'r-42' })
        : envelope([row(0)]);
    });
    renderList(query());
    const error = await screen.findByTestId('error-state');
    expect(within(error).getByTestId('error-state-request-id')).toHaveTextContent('r-42');
    expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'error');
    expect(calls).toBe(1);
    await user.click(within(error).getByTestId('error-state-retry'));
    await waitFor(() => {
      expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'ready');
    });
    expect(calls).toBe(2);
  });
});

/** Chart points for the preview: 30 points in SEL, the slowest at the end of the buffer. */
function previewSource(partial: Partial<ListPreviewSource> = {}): ListPreviewSource {
  const dictionary = new LocalDictionary();
  dictionary.serviceIndex('17');
  dictionary.instanceIndex(12);
  dictionary.nameServices({ '17': XSS_PAYLOADS[0] ?? '' });
  dictionary.nameInstances({ '12': 'web-01' });
  const buffer = new XLogBuffer(64);
  buffer.append(
    Array.from({ length: 30 }, (_, i) => ({
      ts: SEL.tsFrom + i * 1000,
      elapsed: 200 + i * 10,
      status: i === 29 ? STATUS_ERROR : 0,
      instance: 0,
      service: 0,
      txIdLo: 1000 + i,
      txIdHi: 0,
      kind: 1,
      httpStatus: 0,
      errorClass: 0,
    })),
  );
  return { buffer, dictionary, applicable: true, sampled: false, ...partial };
}

describe('TxListPanel: preview from the chart points (ADR-0010 C-204)', () => {
  it('T-UI-001 shows the slowest points of the selection while the search runs, then the server rows', async () => {
    let answer: (response: Response) => void = () => undefined;
    stub.on(
      SEARCH,
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    );
    renderList(query(), previewSource());
    const list = screen.getByTestId('xlog-list');
    await waitFor(() => {
      expect(list).toHaveAttribute('data-state', 'preview');
    });
    // 20 rows (one viewport), slowest first: points 29, 28, ... (elapsed 490, 480, ...).
    expect(list).toHaveAttribute('data-preview-rows', '20');
    expect(list).toHaveAttribute('data-rows', '0');
    expect(screen.getByTestId('xlog-list-preview')).toBeInTheDocument();
    const keys = bodyRows().map((r) => r.getAttribute('data-row-key'));
    expect(keys.slice(0, 3)).toEqual(['1029', '1028', '1027']);
    const first = bodyRows()[0] as HTMLElement;
    expect(cellOf(first, 'elapsed_ms')).toHaveTextContent('490');
    // The collected name is text (T-UI-005); columns the points lack are pending, not the dash.
    expect(cellOf(first, 'service')).toHaveTextContent(XSS_PAYLOADS[0] ?? '');
    expect(findInjectedMarkup(cellOf(first, 'service'))).toEqual([]);
    expect(within(cellOf(first, 'sql_count')).getByTestId('xlog-list-pending')).toBeInTheDocument();
    expect(cellOf(first, 'sql_count')).not.toHaveTextContent(MISSING_DASH);
    expect(cellOf(first, 'http_status')).not.toHaveTextContent(MISSING_DASH);
    expect(screen.queryByTestId('csv-export')).toBeNull();
    expect(screen.getByRole('table')).toHaveAttribute('aria-busy', 'true');

    answer(envelope([row(0), row(1)], { shown: 2, total: 2, next_cursor: null }));
    await waitFor(() => {
      expect(list).toHaveAttribute('data-state', 'ready');
    });
    expect(list).not.toHaveAttribute('data-preview-rows');
    expect(bodyRows().map((r) => r.getAttribute('data-row-key'))).toEqual([
      row(0).tx_id,
      row(1).tx_id,
    ]);
    expect(screen.queryByTestId('xlog-list-pending')).toBeNull();
    expect(screen.queryByTestId('xlog-list-preview')).toBeNull();
  });

  it('T-UI-001 previews nothing when the points do not answer the same question', async () => {
    stub.on(SEARCH, () => new Promise<Response>(() => undefined));
    // New points are loading (or a filter only the search applies): the plain loading state.
    renderList(query(), previewSource({ applicable: false }));
    await waitFor(() => {
      expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'loading');
    });
    expect(screen.queryAllByTestId('data-table-row')).toHaveLength(0);
  });

  it('T-UI-001 previews sampled points only in the response time order (A-5 keeps every slow point)', async () => {
    stub.on(SEARCH, () => new Promise<Response>(() => undefined));
    renderList(query({ sort: 'ts:desc' }), previewSource({ sampled: true }));
    await waitFor(() => {
      expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'loading');
    });
    expect(screen.queryAllByTestId('data-table-row')).toHaveLength(0);
  });

  it('T-UI-001 previews only the slow points of a sampled response (the server keeps all of them)', async () => {
    stub.on(SEARCH, () => new Promise<Response>(() => undefined));
    // Elapsed 200..490 ms, threshold 450 ms: 490, 480, 470, 460, 450 are exact; below it the
    // sample may have left out the server's next rows.
    renderList(query(), previewSource({ sampled: true, truncated: false, slowThresholdMs: 450 }));
    const list = screen.getByTestId('xlog-list');
    await waitFor(() => {
      expect(list).toHaveAttribute('data-state', 'preview');
    });
    expect(list).toHaveAttribute('data-preview-rows', '5');
    expect(bodyRows().map((r) => r.getAttribute('data-row-key'))).toEqual([
      '1029',
      '1028',
      '1027',
      '1026',
      '1025',
    ]);
  });

  it('T-UI-001 previews nothing for truncated points or a sample without its threshold', async () => {
    stub.on(SEARCH, () => new Promise<Response>(() => undefined));
    for (const partial of [
      { sampled: true, truncated: true, slowThresholdMs: 450 },
      { sampled: true },
      { sampled: true, truncated: false, slowThresholdMs: 1000 },
    ]) {
      renderList(query(), previewSource(partial));
      await waitFor(() => {
        expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'loading');
      });
      expect(screen.queryAllByTestId('data-table-row')).toHaveLength(0);
      cleanup();
    }
  });

  it('T-UI-001 previews nothing when the search cannot apply a filter either (exclusion)', async () => {
    stub.on(SEARCH, () => new Promise<Response>(() => undefined));
    renderList(
      query({ filters: [{ field: 'service', op: 'ne', value: untrusted('17') }] }),
      previewSource(),
    );
    await waitFor(() => {
      expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'loading');
    });
    expect(screen.queryAllByTestId('data-table-row')).toHaveLength(0);
  });
});
