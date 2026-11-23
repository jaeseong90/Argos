// SPDX-License-Identifier: Apache-2.0
//
// The error group detail in the whole app (05 §2.5-5, WO-36): the class with the new badge, count
// and first / last occurrence, the masked message pattern, the representative stack through the
// transaction detail's FrameList with no application frame in bold while the API has no
// app_packages (ADR-0010 C-207 (3)) and a note saying so, the occurrence trend with deploy
// markers, the distributions by version (each version → XLog with the group and version), by
// service and by instance (raw window), the last 10 transactions opening their detail with ts
// (C-206 (1)), the XLog with f.error_group, collected strings as text (T-UI-005) and the states.
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
import { parseUrl, XLogQ } from '../../../state/urlState';
import {
  BIG_ID,
  deployEvent,
  errorGroupDetail,
  errorGroupRow,
  FROM,
  FROM_ISO,
  RANGE_QUERY,
  SAMPLE_STACK,
  TO,
  TO_ISO,
} from '../testing/fixtures';

await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U07' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DETAIL = 'GET /api/v1/apps/1/errors/groups/61';

let stub: ApiStub;

function answer(detail = errorGroupDetail()): void {
  stub.on(DETAIL, () => envelope(detail, { source: '1m' }));
  stub.on('GET /api/v1/apps/1/events', () =>
    envelope([deployEvent('900', '2026-11-20T03:20:00.000Z', '1.5.0')]),
  );
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/stats/errors/61?${query}`);
  const page = await screen.findByTestId('stats-error-detail', {}, FIND);
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

function xlogState(href: string | null) {
  return parseUrl(XLogQ, (href ?? '').split('?')[1] ?? '').state;
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

describe('error group detail (05 §2.5-5)', () => {
  it('asks the group for the range and kind and shows class, new badge, count and times', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=ALL`);
    expect(Object.fromEntries(stub.calls(DETAIL)[0]?.query.entries() ?? [])).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'ALL',
    });
    expect(screen.getByTestId('stats-error-class')).toHaveTextContent(
      'java.lang.IllegalStateException',
    );
    expect(screen.getByTestId('stats-new-badge')).toBeInTheDocument();
    expect(screen.getByTestId('stats-error-count')).toHaveAttribute('data-value', '40');
    expect(screen.getByTestId('stats-error-first-seen')).toHaveAttribute(
      'data-value',
      '2026-11-20T03:05:00.000Z',
    );
    expect(screen.getByTestId('stats-error-last-seen')).toHaveAttribute(
      'data-value',
      '2026-11-20T03:55:00.000Z',
    );
    expect(screen.getByTestId('stats-error-message')).toHaveTextContent(
      'Order <num> is not payable',
    );
  });

  it('T-UI-007 shows an unknown first-seen-ever as the dash, never a date', async () => {
    answer(errorGroupDetail({ group: errorGroupRow({ first_seen_ever: null, is_new: false }) }));
    await open(RANGE_QUERY);
    const ever = screen.getByTestId('stats-error-first-seen-ever');
    expect(ever).toHaveAttribute('data-missing', 'true');
    expect(ever).toHaveTextContent('—');
    expect(screen.queryByTestId('stats-new-badge')).toBeNull();
  });

  it('C-207 (3) lists the representative stack in order with no frame in bold and says why', async () => {
    answer();
    await open(RANGE_QUERY);
    const stack = screen.getByTestId('stats-error-stack');
    const frames = within(stack).getAllByTestId('transaction-frame');
    expect(frames.map((frame) => frame.textContent)).toEqual([...SAMPLE_STACK]);
    expect(stack.querySelectorAll('[data-app="true"]')).toHaveLength(0);
    expect(stack.querySelectorAll('.tx-frame--app')).toHaveLength(0);
    expect(screen.getByTestId('stats-error-app-frames-note')).toHaveTextContent(
      'application packages are not configured yet',
    );
  });

  it('says there is no stack when the group has no sample', async () => {
    answer(errorGroupDetail({ sample_stack: null }));
    await open(RANGE_QUERY);
    const stack = screen.getByTestId('stats-error-stack');
    expect(stack.tagName).toBe('P');
    expect(within(stack).queryAllByTestId('transaction-frame')).toHaveLength(0);
  });

  it('draws the occurrence trend with the deploy marker and no latency chart', async () => {
    answer();
    await open(RANGE_QUERY);
    const trend = screen.getByTestId('stats-trend');
    await waitFor(() => {
      expect(within(trend).getAllByTestId('chart-marker').length).toBeGreaterThan(0);
    }, FIND);
    expect(within(trend).getAllByTestId('chart-marker')[0]).toHaveAttribute(
      'data-marker-type',
      'deploy',
    );
    expect(screen.queryByTestId('stats-trend-latency')).toBeNull();
    expect(screen.queryByTestId('estimated-badge')).toBeNull();
  });

  it('shows the version, service and instance distributions; a version opens the XLog with it', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    expect(value('stats-by-version', '41', 'count')).toBe('10');
    expect(value('stats-by-version', '42', 'share')).toBe('0.75');
    expect(value('stats-by-service', '11', 'count')).toBe('40');
    expect(value('stats-by-instance', '7', 'count')).toBe('40');
    expect(screen.getByTestId('stats-raw-window')).toHaveAttribute('data-to', TO_ISO);
    const version = within(tableOf('stats-by-version')).getAllByRole('link')[0] as HTMLElement;
    expect(xlogState(version.getAttribute('href')).filters).toEqual([
      { field: 'error_group', op: 'eq', value: '61' },
      { field: 'version', op: 'eq', value: '41' },
    ]);
    await user.click(within(tableOf('stats-by-service')).getByRole('link'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/stats/services/11');
    }, FIND);
  });

  it('lists the last transactions that open their detail with ts (C-206 (1))', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const recent = tableOf('stats-recent-tx');
    expect(
      within(recent)
        .getAllByTestId('data-table-row')
        .map((row) => row.getAttribute('data-row-key')),
    ).toEqual([BIG_ID, '9001']);
    expect(screen.queryByTestId('stats-related-note')).toBeNull();
    const link = within(recent).getAllByRole('link')[0] as HTMLElement;
    expect(link.getAttribute('href')).toBe(
      `/apps/1/transactions/${BIG_ID}?ts=2026-11-20T03:55:00.000Z`,
    );
    await user.click(link);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe(`/apps/1/transactions/${BIG_ID}`);
    }, FIND);
  });

  it('opens the XLog with f.error_group and the range', async () => {
    answer();
    await open(RANGE_QUERY);
    const state = xlogState(screen.getByTestId('stats-open-xlog').getAttribute('href'));
    expect(state.filters).toEqual([{ field: 'error_group', op: 'eq', value: '61' }]);
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: TO });
  });

  it('T-UI-005 renders the class, message, stack frames and versions as text nodes', async () => {
    const payload = XSS_PAYLOADS.join(' ');
    answer(
      errorGroupDetail({
        group: errorGroupRow({
          error_class: untrusted(payload),
          message_pattern: untrusted(payload),
        }),
        sample_stack: XSS_PAYLOADS.map((item) => untrusted(item)),
        by_version: XSS_PAYLOADS.map((item, index) => ({
          id: String(500 + index),
          name: untrusted(item),
          count: 1,
          share: null,
          avg_ms: null,
          p95_ms: null,
          errors: null,
        })),
      }),
    );
    await open(RANGE_QUERY);
    expect(screen.getByTestId('stats-error-message')).toHaveTextContent(
      '<script>window.__xss=1</script>',
    );
    expect(
      within(screen.getByTestId('stats-error-stack'))
        .getAllByTestId('transaction-frame')
        .map((frame) => frame.textContent),
    ).toEqual([...XSS_PAYLOADS]);
    expect(findInjectedMarkup(screen.getByTestId('stats-error-detail'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('shows the empty state when the group did not occur in the range', async () => {
    answer(errorGroupDetail({ group: errorGroupRow({ count: 0 }) }));
    signedIn(stub);
    renderApp(`/apps/1/stats/errors/61?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('stats-error-detail-empty', {}, FIND);
    expect(empty).toHaveTextContent('did not occur in the selected range');
    expect(within(empty).getByTestId('empty-state-widen')).toBeInTheDocument();
  });

  it('shows the error state with the request id and retry', async () => {
    stub.on(DETAIL, () => errorBody(400, 'limit_exceeded', { requestId: 'req-err-1' }));
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    signedIn(stub);
    renderApp(`/apps/1/stats/errors/61?${RANGE_QUERY}`);
    const error = await screen.findByTestId('stats-error-detail-error', {}, FIND);
    expect(within(error).getByTestId('error-state-retry')).toBeInTheDocument();
    expect(within(error).getByTestId('error-state-request-id')).toHaveTextContent('req-err-1');
  });
});
