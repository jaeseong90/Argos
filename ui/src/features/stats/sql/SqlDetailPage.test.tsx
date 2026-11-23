// SPDX-License-Identifier: Apache-2.0
//
// The SQL statement detail in the whole app (05 §2.5-3, WO-36): totals with the connection wait
// "Not measurable" and never 0 (ADR-0010 C-178 (2), T-UI-007), the estimated trend and target
// percentiles (C-14), calling services with count and share linking to the service detail, the
// instance distribution with the raw window, the split by JDBC target, the normalized SQL and the
// SQL text as text nodes with the truncation note (T-UI-005), related transactions with the
// "profiled only" note opening their detail with ts (C-206 (1)), and the XLog with f.sql.
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
  FROM,
  FROM_ISO,
  RANGE_QUERY,
  sqlDetail,
  sqlRow,
  TO,
  TO_ISO,
} from '../testing/fixtures';

await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U06' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DETAIL = 'GET /api/v1/apps/1/sql/21';

let stub: ApiStub;

function answer(detail = sqlDetail()): void {
  stub.on(DETAIL, () => envelope(detail, { source: 'raw' }));
  stub.on('GET /api/v1/apps/1/events', () => envelope([]));
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/sql/21?${query}`);
  const page = await screen.findByTestId('stats-sql-detail', {}, FIND);
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

describe('SQL statement detail (05 §2.5-3)', () => {
  it('T-UI-007 shows the totals; the connection wait is "Not measurable", never 0 (C-178 (2))', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=BATCH`);
    expect(Object.fromEntries(stub.calls(DETAIL)[0]?.query.entries() ?? [])).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'BATCH',
    });
    expect(screen.getByTestId('stats-sql-count')).toHaveAttribute('data-value', '5000');
    expect(screen.getByTestId('stats-sql-exec')).toHaveAttribute('data-value', '20000');
    const wait = screen.getByTestId('stats-sql-wait');
    expect(wait).toHaveAttribute('data-value', '');
    expect(wait).toHaveAttribute('data-missing', 'true');
    expect(wait).toHaveTextContent('Not measurable');
    expect(wait).not.toHaveTextContent('0');
  });

  it('T-UI-007 shows a measured connection wait of 0 as a value', async () => {
    answer(sqlDetail({ wait_ms_total: 0 }));
    await open(RANGE_QUERY);
    const wait = screen.getByTestId('stats-sql-wait');
    expect(wait).toHaveAttribute('data-value', '0');
    expect(wait).not.toHaveAttribute('data-missing');
  });

  it('C-14 marks the estimated trend and target percentiles', async () => {
    answer();
    await open(RANGE_QUERY);
    const latency = screen.getByTestId('stats-trend-latency').parentElement as HTMLElement;
    expect(within(latency).getByTestId('estimated-badge')).toBeInTheDocument();
    const header = tableOf('stats-by-db').querySelector('th[data-column="p95_ms"]');
    expect(within(header as HTMLElement).getByTestId('estimated-badge')).toBeInTheDocument();
    expect(value('stats-by-db', '31', 'p95_ms')).toBe('18');
  });

  it('lists calling services with count and share, instances, and the raw window', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    expect(value('stats-by-service', '11', 'count')).toBe('3750');
    expect(value('stats-by-service', '11', 'share')).toBe('0.75');
    expect(value('stats-by-service', '12', 'share')).toBe('0.25');
    expect(
      tableOf('stats-by-service').querySelector('[data-row-key="11"] [data-column="share"]'),
    ).toHaveTextContent('75%');
    expect(screen.getByTestId('stats-raw-window')).toHaveAttribute('data-to', TO_ISO);
    expect(value('stats-by-instance', '7', 'count')).toBe('100');
    await user.click(within(tableOf('stats-by-service')).getAllByRole('link')[0] as HTMLElement);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/stats/services/11');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${FROM_ISO}`);
  });

  it('shows the normalized SQL and the text as text with the truncation note', async () => {
    answer(
      sqlDetail({
        sql_text: untrusted('SELECT * FROM orders WHERE id = 42 /* first 64 KB */'),
        sql_truncated: true,
      }),
    );
    await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-sql-normalized')).toHaveTextContent(
      'SELECT * FROM orders WHERE id = ?',
    );
    expect(screen.getByTestId('stats-sql-text')).toHaveTextContent('id = 42');
    expect(screen.getByTestId('stats-sql-truncated')).toHaveTextContent('64 KB');
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
      `/apps/1/transactions/${BIG_ID}?ts=2026-11-20T03:30:00.000Z`,
    );
    await user.click(link);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe(`/apps/1/transactions/${BIG_ID}`);
    }, FIND);
  });

  it('opens the XLog with f.sql and the range', async () => {
    answer();
    await open(RANGE_QUERY);
    const href = screen.getByTestId('stats-open-xlog').getAttribute('href') ?? '';
    const state = parseUrl(XLogQ, href.split('?')[1] ?? '').state;
    expect(state.filters).toEqual([{ field: 'sql', op: 'eq', value: '21' }]);
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: TO });
  });

  it('T-UI-005 renders the SQL text, the normalized SQL and targets as text nodes', async () => {
    const payload = XSS_PAYLOADS.join('\n');
    answer(
      sqlDetail({
        sql_normalized: untrusted(payload),
        sql_text: untrusted(payload),
        by_db: XSS_PAYLOADS.map((item, index) =>
          sqlRow({ db: { ...sqlRow().db, db_id: String(400 + index), target: untrusted(item) } }),
        ),
      }),
    );
    await open(RANGE_QUERY);
    const page = screen.getByTestId('stats-sql-detail');
    expect(screen.getByTestId('stats-sql-text')).toHaveTextContent(
      '<script>window.__xss=1</script>',
    );
    for (const item of XSS_PAYLOADS) {
      expect(within(tableOf('stats-by-db')).getAllByTitle(item).length).toBeGreaterThan(0);
    }
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('shows the empty state when the statement did not run in the range', async () => {
    answer(sqlDetail({ count: 0 }));
    signedIn(stub);
    renderApp(`/apps/1/stats/sql/21?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('stats-sql-detail-empty', {}, FIND);
    expect(empty).toHaveTextContent('did not run in the selected range');
    expect(within(empty).getByTestId('empty-state-widen')).toBeInTheDocument();
  });
});
