// SPDX-License-Identifier: Apache-2.0
//
// The dashboard's lower part in the whole app (router, URL state, generated client; only the HTTP
// boundary is stubbed, the WebSocket is a fake socket): the comparison asked for the dashboard's
// range and kind, its sort in the URL, the instance value filter → f.instance chip → the dashboard
// asked again for that instance (T-UI-004 path, 05 §2.0-4), the server CSV export, the excluded
// reasons asked only when requests were excluded, the tile click to the instance detail in the
// same range, and the `counter` per-instance state on the instance tiles (docs/14 §5.2).
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getI18n } from 'react-i18next';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import type { FakeSockets } from '../../../../test/fakeWebSocket';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../../test/renderApp';
import enDashboard from '../../../locales/en/dashboard.json';
import koDashboard from '../../../locales/ko/dashboard.json';
import { DashboardQ, parseUrl } from '../../../state/urlState';
import {
  compareRows,
  dashboardBody,
  dashboardMeta,
  type DashboardFixture,
} from '../testing/fixtures';

// uPlot reads matchMedia when it is imported and draws on a 2D context: install the jsdom
// stand-ins before the lazily loaded dashboard chunk imports it.
await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U20' };
export const REQ_DASHBOARD = { type: 'req', description: 'F-U01' };

const MIN = 60_000;
const FROM_ISO = '2026-11-20T03:00:00.000Z';
const TO_ISO = '2026-11-20T03:10:00.000Z';
const FROM = Date.parse(FROM_ISO);
const RANGE_QUERY = `from=${FROM_ISO}&to=${TO_ISO}`;
const FIND = { timeout: 5000 };
/**
 * Budget of one whole-app test (ADR-0010 C-224 (4)): a test chains up to five FIND waits of 5 s,
 * each one a re-render of the whole dashboard (charts included) after a URL change or a refetch.
 * The 5 s default of Vitest was the budget of a single wait, so a full build (every worker busy,
 * about four times slower than a lone run: 1.4 s alone, 5.1 s in the full suite) timed the test out
 * while every wait was still within its own 5 s. No assertion depends on this budget.
 */
const TEST_TIMEOUT_MS = 20_000;
const DASHBOARD = 'GET /api/v1/apps/1/dashboard';
const COMPARE = 'GET /api/v1/apps/1/instances/compare';
const HEALTH = 'GET /api/v1/apps/1/collection-health';

let stub: ApiStub;
let sockets: FakeSockets;

function addDashboardCatalogs(): void {
  const i18n = getI18n();
  i18n.addResourceBundle('en', 'dashboard', enDashboard, true, true);
  i18n.addResourceBundle('ko', 'dashboard', koDashboard, true, true);
}

function answer(fixture: Partial<DashboardFixture> = {}, from = FROM): void {
  stub.on(DASHBOARD, () => envelope(dashboardBody({ from, ...fixture }), dashboardMeta(from)));
  stub.on(COMPARE, (request) =>
    request.query.get('format') === 'csv'
      ? errorBody(429, 'rate_limited', { headers: { 'Retry-After': '7' } })
      : envelope(compareRows(), dashboardMeta(from)),
  );
  stub.on(HEALTH, () =>
    envelope(
      {
        agent_drops: { ts: [], value: [] },
        server_drops: { ts: [], value: [] },
        server_drops_quota: { ts: [], value: [] },
        ingest_lag_s: { ts: [], value: [] },
        excluded: { ts: [], value: [], by_reason: { health_check: 1200, static_asset: 4 } },
        late_rows: null,
        clock_skew: null,
        outages: [],
        per_instance: [],
      },
      dashboardMeta(from),
    ),
  );
}

async function open(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/dashboard?${query}`);
  addDashboardCatalogs();
  await screen.findByTestId('dashboard-page', {}, FIND);
  await waitFor(() => {
    expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

async function compareTable(): Promise<HTMLElement> {
  const table = await screen.findByTestId('dashboard-compare-table', {}, FIND);
  await waitFor(() => {
    expect(within(table).getAllByTestId('data-table-row')).toHaveLength(3);
  }, FIND);
  return table;
}

// The dashboard route chunk (uPlot, charts, sections) is transformed once here rather than inside
// the first test's budget (C-224 (4): the cold transformation is not what the tests check).
beforeAll(async () => {
  await preloadRoutes();
  await import('../index');
}, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

describe('dashboard sections in the app (05 §2.3-3..6)', { timeout: TEST_TIMEOUT_MS }, () => {
  it('T-UI-004 asks the comparison for the dashboard range and kind; heap and CPU reach the tiles', async () => {
    answer();
    await open(`${RANGE_QUERY}&kind=BATCH`);
    await compareTable();
    const calls = stub.calls(COMPARE);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.query.get('from')).toBe(FROM_ISO);
    expect(calls[0]?.query.get('to')).toBe(TO_ISO);
    expect(calls[0]?.query.get('kind')).toBe('BATCH');
    const tile = screen
      .getAllByTestId('dashboard-instance-tile')
      .find((node) => node.getAttribute('data-instance-id') === '12');
    await waitFor(() => {
      expect(tile).toHaveAttribute('data-heap', '0.42');
    }, FIND);
    expect(tile).toHaveAttribute('data-cpu', '0.31');
    expect(screen.getByTestId('dashboard-firing-panel')).toHaveAttribute(
      'data-state',
      'unavailable',
    );
    expect(screen.getByTestId('dashboard-collection-excluded')).toHaveAttribute(
      'data-value',
      '1204',
    );
  });

  it('T-UI-004 keeps the sort in the URL and the instance value filter asks the dashboard again', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const table = await compareTable();
    await user.click(within(table).getByTestId('data-table-sort-tps'));
    await waitFor(() => {
      expect(parseUrl(DashboardQ, app.router.state.location.search).state.sort).toBe('tps:desc');
    }, FIND);
    await waitFor(() => {
      expect(
        within(table)
          .getAllByTestId('data-table-row')
          .map((row) => row.getAttribute('data-row-key')),
      ).toEqual(['14', '12', '13']);
    }, FIND);

    const before = stub.calls(DASHBOARD).length;
    const cell = table.querySelector<HTMLElement>('[data-row-key="14"] [data-column="instance"]');
    await user.click(within(cell as HTMLElement).getByTestId('cell-filter-include'));
    await waitFor(() => {
      const filters = parseUrl(DashboardQ, app.router.state.location.search).state.filters;
      expect(filters).toEqual([{ field: 'instance', op: 'eq', value: '14' }]);
    }, FIND);
    // The shell's FilterChipBar shows the chip; the dashboard asks for that instance.
    const chip = await screen.findByTestId('filter-chip', {}, FIND);
    expect(chip).toHaveAttribute('data-field', 'instance');
    expect(chip).toHaveAttribute('data-value', '14');
    await waitFor(() => {
      expect(stub.calls(DASHBOARD).length).toBeGreaterThan(before);
    }, FIND);
    expect(stub.calls(DASHBOARD).at(-1)?.query.getAll('instance')).toEqual(['14']);
  });

  it('T-UI-004 exports the comparison through the server (format=csv) and shows its error', async () => {
    answer();
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    const table = await compareTable();
    await user.click(within(table).getByTestId('csv-export'));
    await screen.findByTestId('dashboard-compare-csv-error', {}, FIND);
    const csv = stub.calls(COMPARE).filter((call) => call.query.get('format') === 'csv');
    expect(csv).toHaveLength(1);
    expect(csv[0]?.query.get('from')).toBe(FROM_ISO);
  });

  it('T-UI-007 asks for the excluded reasons only when requests were excluded', async () => {
    answer();
    const user = userEvent.setup();
    await open(RANGE_QUERY);
    await waitFor(() => {
      expect(stub.calls(HEALTH)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(HEALTH)[0]?.query.get('from')).toBe(FROM_ISO);
    await user.hover(screen.getByTestId('dashboard-collection-excluded-reasons'));
    const reasons = await screen.findByTestId('dashboard-collection-reasons', {}, FIND);
    expect(reasons).toHaveTextContent('health_check');
    expect(reasons).toHaveTextContent('1,200');
  });

  it('T-UI-008 a tile opens the instance detail in the same range', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(RANGE_QUERY);
    const tile = screen
      .getAllByTestId('dashboard-instance-tile')
      .find((node) => node.getAttribute('data-instance-id') === '13');
    await user.click(tile as HTMLElement);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/instances/13');
    }, FIND);
    const state = parseUrl(DashboardQ, app.router.state.location.search).state;
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: FROM + 10 * MIN });
  });

  it('T-UI-008 the counter push changes the instance tile state and active count', async () => {
    const lastBucket = Math.floor(Date.now() / MIN) * MIN - MIN;
    answer({}, lastBucket - 9 * MIN);
    await open('live=1');
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    await waitFor(() => {
      expect(socket.framesOf('subscribe').some((f) => f.topic === 'counter')).toBe(true);
    }, FIND);
    act(() => {
      socket.ackAll();
    });
    const id = socket.framesOf('subscribe').find((f) => f.topic === 'counter')?.id;
    const tile = () =>
      screen
        .getAllByTestId('dashboard-instance-tile')
        .find((node) => node.getAttribute('data-instance-id') === '13');
    expect(tile()).toHaveAttribute('data-state', 'DOWN');
    act(() => {
      socket.serverSend({
        op: 'push',
        id,
        topic: 'counter',
        t: new Date(lastBucket + 20_000).toISOString(),
        data: { tps: 5, per_instance: [{ instance_id: 13, active: 4, state: 'UP' }] },
      });
    });
    await waitFor(() => {
      expect(tile()).toHaveAttribute('data-state', 'UP');
    }, FIND);
    expect(tile()).toHaveAttribute('data-active', '4');
    // The comparison row of that instance follows the live state too.
    const row = (await compareTable()).querySelector('[data-row-key="13"] [data-column="state"]');
    expect(row?.querySelector('[data-status]')).toHaveAttribute('data-status', 'UP');
  });
});
