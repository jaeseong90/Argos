// SPDX-License-Identifier: Apache-2.0
//
// Global search `/search` in the whole app (only the HTTP boundary is stubbed), docs/15 §9.12,
// T-ACPT-12 automated part: the kind the input is read as while it is typed (tx 20/21 digits,
// trace 32 hex, user:/req:, text), Enter writes the URL (T-UI-003 round trip of q), an id lookup
// with one match opens it at once, several matches are listed with links to known routes only,
// user:/req: over more than 24 h ask for a shorter range without a request, an empty user search
// says when the instances do not collect user ids, 429/422 messages, labels as text (T-UI-005),
// Korean (T-UI-008).
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../../components/testing/xssPayloads';
import { resetWrittenUrls } from '../../state/useUrlState';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U14' };
export const REQ_XSS = { type: 'req', description: 'SEC-60' };

const FIND = { timeout: 5000 };
const SEARCH = 'GET /api/v1/search';
const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const RANGE_1H = 'from=2026-11-24T00:00:00.000Z&to=2026-11-24T01:00:00.000Z';

let stub: ApiStub;

function match(type: string, label: string, route: string, params: Record<string, string>) {
  return { type, label, score: 1, target: { route, params } };
}

async function open(query = '', language: 'en' | 'ko' = 'en') {
  signedIn(stub);
  const app = renderApp(`/search${query === '' ? '' : `?${query}`}`, { language });
  const page = await screen.findByTestId('search', {}, FIND);
  return { app, page };
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('search: input kinds and the URL (docs/15 §9.12)', () => {
  it('shows how the input is read and Enter writes q to the URL', async () => {
    stub.on(SEARCH, () => envelope({ type: 'auto', matches: [] }));
    const { app, page } = await open();
    expect(page).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('search-start')).toBeInTheDocument();
    const input = screen.getByTestId('search-input');
    const kind = () => screen.getByTestId('search-kind').getAttribute('data-kind');
    const cases: [string, string][] = [
      ['12345678901234567890', 'tx'],
      ['123456789012345678901', 'text'],
      [TRACE, 'trace'],
      ['user:kim', 'user'],
      ['req:abc', 'request'],
      ['order', 'text'],
      ['', 'empty'],
    ];
    for (const [text, expected] of cases) {
      fireEvent.change(input, { target: { value: text } });
      expect(kind()).toBe(expected);
    }
    fireEvent.change(input, { target: { value: 'order service' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => {
      expect(app.url()).toContain('q=order%20service');
    }, FIND);
    await waitFor(() => {
      expect(stub.calls(SEARCH)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(SEARCH)[0]?.query.get('type')).toBe('auto');
    expect(await screen.findByTestId('search-empty', {}, FIND)).toHaveTextContent(
      'Try a shorter part',
    );
  });

  it('a query over 1 KB cannot be submitted', async () => {
    await open();
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'x'.repeat(1025) } });
    expect(screen.getByTestId('search-too-long')).toBeInTheDocument();
    expect(screen.getByTestId('search-submit')).toBeDisabled();
  });
});

describe('search: results (docs/14 §4.11)', () => {
  it('an id with exactly one match opens it at once', async () => {
    stub.on(SEARCH, () =>
      envelope({
        type: 'tx',
        matches: [
          match('tx', 'GET /orders · 12 ms', '/tx/:txId', {
            txId: '123',
            ts: '2026-11-24T00:10:00Z',
          }),
        ],
      }),
    );
    const { app } = await open('q=123');
    await waitFor(() => {
      expect(decodeURIComponent(app.url())).toMatch(/^\/tx\/123\?ts=2026-11-24T00:10:00\.000Z/);
    }, FIND);
    expect(stub.calls(SEARCH)[0]?.query.get('type')).toBe('tx');
    // The first open of the /tx alias route loads its chunk and catalog.
  }, 20_000);

  it('a trace id with one match opens the trace view', async () => {
    stub.on(SEARCH, () =>
      envelope({
        type: 'trace',
        matches: [match('trace', 'order · 3 transactions', '/traces/:traceId', { traceId: TRACE })],
      }),
    );
    const { app } = await open(`q=${TRACE}`);
    await waitFor(() => {
      expect(app.url()).toBe(`/traces/${TRACE}`);
    }, FIND);
  });

  it('text matches are listed with links to the known screens only; labels stay text (T-UI-005)', async () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror="window.__xss=1">';
    stub.on(SEARCH, () =>
      envelope({
        type: 'auto',
        matches: [
          match('service', payload, '/apps/:appId/services/:serviceId', {
            appId: '1',
            serviceId: '77',
          }),
          match('sql', 'SELECT 1', '/apps/:appId/sql/:sqlId', { appId: '1', sqlId: '9' }),
          match('instance', 'order-1', '/instances/:instanceId', { instanceId: '11' }),
          match('service', 'bad', 'https://evil.example/x', { appId: '1' }),
        ],
      }),
    );
    await open('q=order');
    const table = await screen.findByTestId('data-table', {}, FIND);
    expect(table).toHaveAttribute('data-table-id', 'search-results');
    const links = within(table)
      .getAllByTestId('search-open')
      .map((element) => element.getAttribute('href'));
    expect(links).toContain('/apps/1/stats/services/77');
    expect(links).toContain('/apps/1/stats/sql/9');
    expect(table).toHaveTextContent('No screen');
    expect(table).toHaveTextContent(payload);
    expect(findInjectedMarkup(screen.getByTestId('search'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('an instance match opens the instance of its application', async () => {
    stub.on(SEARCH, () =>
      envelope({
        type: 'auto',
        matches: [
          match('instance', 'order-1', '/instances/:instanceId', { instanceId: '11' }),
          match('instance', 'order-2', '/instances/:instanceId', { instanceId: '12' }),
        ],
      }),
    );
    stub.on('GET /api/v1/instances/11', () =>
      envelope({
        instance_id: 11,
        name: 'order-1',
        app: { app_id: 3, name: 'billing', env: 'prod' },
      }),
    );
    const { app } = await open('q=order');
    const buttons = await screen.findAllByTestId('search-open', {}, FIND);
    fireEvent.click(buttons[0] as HTMLElement);
    await waitFor(() => {
      expect(app.url()).toMatch(/^\/apps\/3\/instances\/11/);
    }, FIND);
  });
});

describe('search: user and request ids (T-ACPT-12)', () => {
  it('more than 24 hours asks for a shorter range without a request', async () => {
    stub.on(SEARCH, () => envelope({ type: 'user', matches: [] }));
    await open('q=user%3Akim&from=now-7d');
    expect(await screen.findByTestId('search-range-required', {}, FIND)).toBeInTheDocument();
    expect(stub.calls(SEARCH)).toHaveLength(0);
  });

  it('sends the range; no match says which instances do not collect user ids', async () => {
    stub.on(SEARCH, () => envelope({ type: 'user', matches: [] }));
    stub.on('GET /api/v1/apps/1/instances', () =>
      envelope([
        { instance_id: 11, user_id_mode: 'off' },
        { instance_id: 12, user_id_mode: 'plain' },
      ]),
    );
    stub.on('GET /api/v1/apps/2/instances', () =>
      envelope([{ instance_id: 21, user_id_mode: 'off' }]),
    );
    stub.on('GET /api/v1/apps/3/instances', () => envelope([]));
    await open(`q=user%3Akim&${RANGE_1H}`);
    const notice = await screen.findByTestId('search-not-collected', {}, FIND);
    expect(notice).toHaveAttribute('data-off', '2');
    expect(notice).toHaveAttribute('data-total', '3');
    const query = stub.calls(SEARCH)[0]?.query;
    expect(query?.get('q')).toBe('kim');
    expect(query?.get('type')).toBe('user');
    expect(query?.get('from')).toBe('2026-11-24T00:00:00.000Z');
    expect(query?.get('to')).toBe('2026-11-24T01:00:00.000Z');
  });

  it('429 and 422 user_id_hash_unavailable have their own messages; T-UI-008 Korean', async () => {
    stub.on(SEARCH, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '15' } }));
    const first = await open(`q=req%3Aabc&${RANGE_1H}`, 'ko');
    const error = await screen.findByTestId('search-error', {}, FIND);
    expect(error).toHaveAttribute('data-kind', 'rateLimited');
    expect(error).toHaveTextContent('15초 뒤');
    expect(screen.getByTestId('search-kind')).toHaveTextContent('요청 ID');
    first.app.unmount();
    stub.on(SEARCH, () => errorBody(422, 'user_id_hash_unavailable'));
    await open(`q=user%3Akim&${RANGE_1H}`);
    expect(await screen.findByTestId('search-error', {}, FIND)).toHaveAttribute(
      'data-kind',
      'hashUnavailable',
    );
  });
});
