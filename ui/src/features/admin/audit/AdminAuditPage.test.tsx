// SPDX-License-Identifier: Apache-2.0
//
// Audit log `/admin/audit` in the whole app (only the HTTP boundary is stubbed), docs/15 §9.10,
// docs/16 §9.6, §11: the filters (URL keys) → the request parameters with the global range, the
// next page by cursor, before/after as JSON text, the server CSV of the same filters and the 429
// retry message, actor and target names as text (T-UI-005), '—' for a missing IP (T-UI-007) and
// Korean labels (T-UI-008).
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
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
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { resetWrittenUrls } from '../../../state/useUrlState';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-80' };

const FIND = { timeout: 5000 };
const RANGE = 'from=2026-11-24T00:00:00.000Z&to=2026-11-24T06:00:00.000Z';
const LIST = 'GET /api/v1/admin/audit';

let stub: ApiStub;

function record(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    ts: '2026-11-24T01:00:00.000Z',
    actor: { type: 'user', id: 'u-1', name: 'alice' },
    ip: '10.0.0.5',
    user_agent: null,
    action: 'KILL_SWITCH_ON',
    target: { type: 'app', id: '1', name: 'order-api' },
    result: 'success',
    request_id: 'req-1',
    reason: 'incident 42',
    before: { enabled: true },
    after: { enabled: false },
    pii_subject: null,
    ...overrides,
  };
}

async function open(query = RANGE, language: 'en' | 'ko' = 'en') {
  signedIn(stub, { roles: ['admin'] });
  const app = renderApp(`/admin/audit?${query}`, { language });
  const page = await screen.findByTestId('admin-audit', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return { app, page };
}

function row(key: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="admin-audit"] [data-row-key="${key}"]`,
  );
  if (element === null) {
    throw new Error(`no row ${key}`);
  }
  return element;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('admin audit: filters, pages and details (docs/15 §9.10)', () => {
  it('sends the URL filters with the range; applying a filter writes the URL and asks again', async () => {
    stub.on(LIST, () => envelope([record('a-1')], { next_cursor: null }));
    const { app } = await open(`${RANGE}&actor=alice`);
    const first = stub.calls(LIST)[0]?.query;
    expect(first?.get('from')).toBe('2026-11-24T00:00:00.000Z');
    expect(first?.get('to')).toBe('2026-11-24T06:00:00.000Z');
    expect(first?.get('actor')).toBe('alice');
    expect(first?.get('limit')).toBe('500');
    expect(screen.getByTestId('admin-audit-filter-actor')).toHaveValue('alice');
    fireEvent.change(screen.getByTestId('admin-audit-filter-action'), {
      target: { value: 'CONFIG_PUSH' },
    });
    fireEvent.change(screen.getByTestId('admin-audit-filter-target_type'), {
      target: { value: 'app' },
    });
    fireEvent.change(screen.getByTestId('admin-audit-filter-target_id'), {
      target: { value: '1' },
    });
    fireEvent.click(screen.getByTestId('admin-audit-apply'));
    await waitFor(() => {
      expect(app.url()).toContain('action=CONFIG_PUSH');
    }, FIND);
    expect(app.url()).toContain('target_type=app');
    expect(app.url()).toContain('target_id=1');
    await waitFor(() => {
      expect(stub.calls(LIST).at(-1)?.query.get('action')).toBe('CONFIG_PUSH');
    }, FIND);
    const last = stub.calls(LIST).at(-1)?.query;
    expect(last?.get('target_type')).toBe('app');
    expect(last?.get('target_id')).toBe('1');
    expect(last?.get('actor')).toBe('alice');
  });

  it('loads the next page by cursor and shows before/after as JSON text', async () => {
    stub.on(LIST, (request) =>
      request.query.get('cursor') === 'c-2'
        ? envelope([record('a-2', { action: 'TOKEN_ISSUED', ip: null })], { next_cursor: null })
        : envelope([record('a-1')], { next_cursor: 'c-2' }),
    );
    await open();
    fireEvent.click(await screen.findByTestId('admin-audit-more', {}, FIND));
    await waitFor(() => {
      expect(row('a-2')).toHaveAttribute('data-action', 'TOKEN_ISSUED');
    }, FIND);
    expect(stub.calls(LIST).at(-1)?.query.get('cursor')).toBe('c-2');
    // T-UI-007: a missing IP is the dash.
    expect(row('a-2').querySelector('[data-column="ip"]')).toHaveTextContent('—');
    expect(screen.queryByTestId('admin-audit-more')).not.toBeInTheDocument();
    fireEvent.click(within(row('a-1')).getByTestId('admin-audit-open'));
    const detail = await screen.findByTestId('admin-audit-detail', {}, FIND);
    expect(within(detail).getByTestId('admin-audit-before')).toHaveTextContent('"enabled": true');
    expect(within(detail).getByTestId('admin-audit-after')).toHaveTextContent('"enabled": false');
    expect(detail).toHaveTextContent('incident 42');
  });

  it('exports the server CSV of the same filters and says when to retry after a 429', async () => {
    stub.on(LIST, () => envelope([record('a-1')], { next_cursor: null }));
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:audit');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    stub.on(
      'GET /api/v1/admin/audit/export',
      () =>
        new Response('﻿"ts"\n', {
          status: 200,
          headers: { 'Content-Type': 'text/csv; charset=utf-8' },
        }),
    );
    await open(`${RANGE}&action=KILL_SWITCH_ON`);
    fireEvent.click(screen.getByTestId('admin-audit-export'));
    await waitFor(() => {
      expect(createUrl).toHaveBeenCalledTimes(1);
    }, FIND);
    const query = stub.calls('GET /api/v1/admin/audit/export')[0]?.query;
    expect(query?.get('action')).toBe('KILL_SWITCH_ON');
    expect(query?.get('from')).toBe('2026-11-24T00:00:00.000Z');
    expect(query?.get('format')).toBe('csv');
    stub.on('GET /api/v1/admin/audit/export', () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '20' } }),
    );
    fireEvent.click(screen.getByTestId('admin-audit-export'));
    expect(await screen.findByTestId('admin-audit-export-error', {}, FIND)).toHaveTextContent(
      'Try again in 20 s',
    );
  });
});

describe('admin audit: states and text rendering', () => {
  it('T-UI-005 actor, target and reason with markup stay text; T-UI-008 Korean labels', async () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror="window.__xss=1">';
    stub.on(LIST, () =>
      envelope(
        [
          record('a-1', {
            actor: { type: 'user', id: 'u-1', name: payload },
            target: { type: 'app', id: '1', name: payload },
            reason: payload,
            result: 'rejected',
          }),
        ],
        { next_cursor: null },
      ),
    );
    await open(RANGE, 'ko');
    await waitFor(() => {
      expect(row('a-1')).toHaveTextContent(payload);
    }, FIND);
    expect(row('a-1').querySelector('[data-column="result"]')).toHaveTextContent('거부');
    expect(row('a-1').querySelector('[data-column="actor"]')).toHaveTextContent('사용자');
    fireEvent.click(within(row('a-1')).getByTestId('admin-audit-open'));
    await screen.findByTestId('admin-audit-detail', {}, FIND);
    expect(findInjectedMarkup(screen.getByTestId('admin-audit'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('empty with filters says to clear them; a failing request shows the error state', async () => {
    stub.on(LIST, () => envelope([], { next_cursor: null }));
    const first = await open(`${RANGE}&actor=nobody`);
    expect(first.page).toHaveAttribute('data-state', 'empty');
    expect(first.page).toHaveTextContent('Clear a filter');
    first.app.unmount();
    stub.on(LIST, () => errorBody(500, 'internal'));
    const second = await open();
    expect(second.page).toHaveAttribute('data-state', 'error');
  });
});
