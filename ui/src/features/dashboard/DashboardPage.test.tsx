// SPDX-License-Identifier: Apache-2.0
//
// The dashboard screen in the whole app (router, URL loader, transport, generated client; only the
// HTTP boundary is stubbed and the WebSocket is a fake socket): the request of the URL state and
// the tile basis (one or two requests), tiles with null apart from 0 (T-UI-007), the nine charts
// with markers (alert= emphasised), outage shading and legend on every chart (T-ACC-18 display),
// collected strings as text (T-UI-005), drag → absolute URL with live off and point click → XLog
// (T-UI-002 path, 05 §2.0-5), the `counter` push on tiles and the live timestamp with an invalid
// frame ignored and the shell's subscription shared (docs/14 §5.2, docs/15 §10), and the error and
// empty states.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getI18n } from 'react-i18next';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import type { FakeSockets } from '../../../test/fakeWebSocket';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { findInjectedMarkup, xssFired } from '../../components/testing/xssPayloads';
import enDashboard from '../../locales/en/dashboard.json';
import koDashboard from '../../locales/ko/dashboard.json';
import { DashboardQ, parseUrl, XLogQ } from '../../state/urlState';

import { PANEL_IDS } from './charts/panels';
import {
  dashboardBody,
  dashboardMeta,
  XSS_EVENT_TITLE,
  XSS_POOL,
  type DashboardFixture,
} from './testing/fixtures';

// uPlot reads matchMedia when it is imported and draws on a 2D context: install the jsdom
// stand-ins before the lazily loaded dashboard chunk imports it.
await vi.hoisted(async () => {
  (await import('../../charts/testing/uplotDom')).installUplotDom();
});

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };
export const REQ_BASELINE = { type: 'req', description: 'F-U19' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const MIN = 60_000;
const FROM_ISO = '2026-11-20T03:00:00.000Z';
const TO_ISO = '2026-11-20T03:10:00.000Z';
const FROM = Date.parse(FROM_ISO);
const RANGE_QUERY = `from=${FROM_ISO}&to=${TO_ISO}`;
const FIND = { timeout: 5000 };
const DASHBOARD = 'GET /api/v1/apps/1/dashboard';

let stub: ApiStub;
let sockets: FakeSockets;

/**
 * The dashboard catalogs on the app's i18next instance, whichever way the app loads them (bundled
 * or per screen, ADR-0010 C-209): added before the lazily loaded screen renders.
 */
function addDashboardCatalogs(): void {
  const i18n = getI18n();
  i18n.addResourceBundle('en', 'dashboard', enDashboard, true, true);
  i18n.addResourceBundle('ko', 'dashboard', koDashboard, true, true);
}

/** Answers every dashboard request with the fixture of its baseline parameter. */
function answerDashboard(fixture: Partial<DashboardFixture> = {}, from = FROM): void {
  stub.on(DASHBOARD, (request) => {
    const baseline = request.query.get('baseline');
    const body = dashboardBody({
      from,
      ...fixture,
      baseline: baseline === '1d' || baseline === '7d' ? baseline : null,
    });
    return envelope(body, dashboardMeta(from));
  });
}

async function openDashboard(query: string, language: 'en' | 'ko' = 'en') {
  signedIn(stub);
  const app = renderApp(`/apps/1/dashboard?${query}`, { language });
  addDashboardCatalogs();
  await screen.findByTestId('dashboard-page', {}, FIND);
  return app;
}

async function ready(): Promise<HTMLElement> {
  const page = screen.getByTestId('dashboard-page');
  await waitFor(() => {
    expect(['ready', 'empty']).toContain(page.getAttribute('data-state'));
  }, FIND);
  // uPlot reports `ready` after the first commit; the overlay portals mount then.
  await act(async () => {
    await Promise.resolve();
  });
  return page;
}

function plotOver(chart: HTMLElement): HTMLElement {
  const over = chart.querySelector<HTMLElement>('.u-over');
  if (over === null) {
    throw new Error('uPlot overlay not rendered');
  }
  return over;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('dashboard: request and tiles (docs/14 §4.4, 05 §2.3-1)', () => {
  it('T-UI-007 asks once for the URL range and shows the tiles; null is the dash, 0 a value', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY);
    const page = await ready();
    const calls = stub.calls(DASHBOARD);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.query.get('from')).toBe(FROM_ISO);
    expect(calls[0]?.query.get('to')).toBe(TO_ISO);
    expect(calls[0]?.query.has('baseline')).toBe(false);
    expect(calls[0]?.query.has('kind')).toBe(false);
    expect(page).toHaveAttribute('data-request-count', '1');
    expect(screen.getByTestId('dashboard-tile-tps')).toHaveAttribute('data-value', '12.5');
    const p99 = screen.getByTestId('dashboard-tile-p99');
    expect(p99).toHaveAttribute('data-value', 'null');
    expect(within(p99).getByTestId('dashboard-tile-p99-stat')).toHaveTextContent('—');
    const errorRate = screen.getByTestId('dashboard-tile-error-rate');
    expect(errorRate).toHaveAttribute('data-value', '0');
    expect(within(errorRate).getByTestId('dashboard-tile-error-rate-stat')).toHaveTextContent('0%');
    expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-basis', 'prev');
    expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-value', '0.1');
    expect(screen.getByTestId('dashboard-tile-error-rate-delta')).toHaveAttribute(
      'data-value',
      '0.002',
    );
    // C-212 (1): average, p99 and active carry their relative change; p99 has none here.
    expect(screen.getByTestId('dashboard-tile-avg-delta')).toHaveAttribute('data-value', '0.08');
    expect(screen.getByTestId('dashboard-tile-p99-delta')).toHaveAttribute('data-value', 'null');
    expect(screen.getByTestId('dashboard-tile-active-delta')).toHaveAttribute('data-value', '0.4');
    expect(screen.getByTestId('dashboard-tile-active-delta')).toHaveAttribute('data-basis', 'prev');
    expect(within(p99).getByTestId('dashboard-tile-p99-stat')).toHaveTextContent(
      'No comparison data',
    );
    // No active-transaction gauge samples in the range (C-178): the panel says so.
    expect(screen.getByTestId('dashboard-chart-active-note')).toHaveTextContent(
      'No active-transaction samples in this range.',
    );
  });

  it('T-UI-007 tileRef=7d with no baseline asks a second time with baseline=7d for the tiles', async () => {
    answerDashboard();
    const user = userEvent.setup();
    const app = await openDashboard(RANGE_QUERY);
    await ready();
    await user.click(screen.getByTestId('dashboard-tile-ref-7d'));
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-basis', '7d');
    }, FIND);
    expect(parseUrl(DashboardQ, app.router.state.location.search).state.tileRef).toBe('7d');
    expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-value', '0.25');
    expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-request-count', '2');
    const baselines = stub.calls(DASHBOARD).map((call) => call.query.get('baseline'));
    expect(baselines).toEqual([null, '7d']);
    // The charts keep no baseline (only the tiles' request has one).
    expect(
      within(screen.getByTestId('dashboard-chart-response'))
        .getAllByTestId('chart-legend-item')
        .map((item) => item.getAttribute('data-series')),
    ).toEqual(['avg', 'p95', 'p99']);
  });

  it('T-UI-007 baseline=7d with tileRef=7d is one request; with tileRef=prev two', async () => {
    answerDashboard();
    await openDashboard(`${RANGE_QUERY}&baseline=7d&tileRef=7d`);
    await ready();
    expect(stub.calls(DASHBOARD).map((call) => call.query.get('baseline'))).toEqual(['7d']);
    expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-basis', '7d');
    const user = userEvent.setup();
    await user.click(screen.getByTestId('dashboard-tile-ref-prev'));
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-basis', 'prev');
    }, FIND);
    expect(stub.calls(DASHBOARD).map((call) => call.query.get('baseline'))).toEqual(['7d', null]);
  });

  it('T-UI-002 the baseline toggle adds the dashed baseline series to the transaction charts', async () => {
    answerDashboard();
    const user = userEvent.setup();
    const app = await openDashboard(RANGE_QUERY);
    await ready();
    await user.click(screen.getByTestId('dashboard-baseline-1d'));
    await waitFor(() => {
      expect(
        within(screen.getByTestId('dashboard-chart-response'))
          .getAllByTestId('chart-legend-item')
          .map((item) => item.getAttribute('data-series')),
      ).toEqual(['avg', 'p95', 'p99', 'avg:baseline', 'p95:baseline', 'p99:baseline']);
    }, FIND);
    expect(parseUrl(DashboardQ, app.router.state.location.search).state.baseline).toBe('1d');
    expect(stub.calls(DASHBOARD).at(-1)?.query.get('baseline')).toBe('1d');
    // The CPU chart has no baseline: process and system CPU only.
    expect(
      within(screen.getByTestId('dashboard-chart-cpu'))
        .getAllByTestId('chart-legend-item')
        .map((item) => item.getAttribute('data-series')),
    ).toEqual(['cpu', 'system_cpu']);
  });

  it('applies f.instance as instance[], f.instance=! as exclude_instance[] (C-212 (1)) and lists filters the dashboard cannot apply', async () => {
    answerDashboard();
    await openDashboard(
      `${RANGE_QUERY}&f.instance=12&f.instance=!13&f.instance=!12&f.service=17&kind=BATCH`,
    );
    await ready();
    const [call] = stub.calls(DASHBOARD);
    expect(call?.query.getAll('instance')).toEqual(['12']);
    expect(call?.query.getAll('exclude_instance')).toEqual(['13']);
    expect(call?.query.get('kind')).toBe('BATCH');
    const unapplied = screen.getByTestId('dashboard-unapplied');
    expect(unapplied).toHaveTextContent('12');
    expect(unapplied).toHaveTextContent('17');
    expect(unapplied).not.toHaveTextContent('13');
  });

  it('applies an exclusion alone without listing it as not applied', async () => {
    answerDashboard();
    await openDashboard(`${RANGE_QUERY}&f.instance=!13`);
    await ready();
    const [call] = stub.calls(DASHBOARD);
    expect(call?.query.getAll('instance')).toEqual([]);
    expect(call?.query.getAll('exclude_instance')).toEqual(['13']);
    expect(screen.queryByTestId('dashboard-unapplied')).toBeNull();
  });
});

describe('dashboard: charts (05 §2.3-2, docs/15 §6.1)', () => {
  it('draws the nine panels with their series and markers; CPU has process and system series', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY);
    await ready();
    const counts = PANEL_IDS.map(
      (id) =>
        within(screen.getByTestId(`dashboard-chart-${id}`)).queryAllByTestId('chart-legend-item')
          .length,
    );
    expect(counts).toEqual([1, 3, 1, 1, 3, 2, 1, 2, 2]);
    for (const id of PANEL_IDS) {
      const markers = within(screen.getByTestId(`dashboard-chart-${id}`)).getAllByTestId(
        'chart-marker',
      );
      expect(markers.map((m) => m.getAttribute('data-marker-id'))).toEqual([
        'ev-deploy',
        'ev-alert',
      ]);
    }
    // C-212 (1): the system CPU series replaced the "process CPU only" note.
    expect(screen.queryByTestId('dashboard-chart-cpu-note')).toBeNull();
  });

  it('T-ACC-18 shades the outage on all nine charts with the "1 collection gap" legend', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY);
    await ready();
    for (const id of PANEL_IDS) {
      const chart = screen.getByTestId(`dashboard-chart-${id}`);
      expect(within(chart).getByTestId('chart-outage-legend')).toHaveTextContent(
        '1 collection gap',
      );
      expect(within(chart).getAllByTestId('chart-outage')).toHaveLength(1);
    }
  });

  it('T-ACC-18 no outage: no shading and no legend (a gap is not drawn as 0)', async () => {
    answerDashboard({ withOutage: false });
    await openDashboard(RANGE_QUERY);
    await ready();
    expect(screen.queryAllByTestId('chart-outage')).toHaveLength(0);
    expect(screen.queryAllByTestId('chart-outage-legend')).toHaveLength(0);
  });

  it('emphasises the event of the alert= deep link on every chart (deep link 4)', async () => {
    answerDashboard();
    await openDashboard(`${RANGE_QUERY}&alert=ev-alert`);
    await ready();
    const emphasized = screen
      .getAllByTestId('chart-marker')
      .filter((marker) => marker.getAttribute('data-emphasized') === 'true');
    expect(emphasized).toHaveLength(PANEL_IDS.length);
    expect(emphasized.every((m) => m.getAttribute('data-marker-id') === 'ev-alert')).toBe(true);
  });

  it('adds the alert transitions of the range and emphasises and summarises the alert= one (C-226 (8))', async () => {
    answerDashboard();
    stub.on('GET /api/v1/alerts/events', () =>
      envelope([
        {
          event_id: 'ae-1',
          ts: '2026-11-20T03:09:00.000Z',
          app: { id: 1, name: 'order-api' },
          env: 'prod',
          target: { type: 'app', id: null, name: null },
          rule: { id: 'r-1', name: 'Error rate', metric: 'error_rate', unit: 'ratio' },
          severity: 'critical',
          from_state: 'PENDING',
          state: 'FIRING',
          reason: 'condition',
          value: 0.071,
          threshold: 0.05,
          duration_s: null,
          acked_by: null,
          acked_at: null,
          note: null,
          deliveries: [],
          deeplink: '/apps/1/dashboard?alert=ae-1',
        },
      ]),
    );
    await openDashboard(`${RANGE_QUERY}&alert=ae-1`);
    await ready();
    await waitFor(() => {
      const markers = within(screen.getByTestId(`dashboard-chart-${PANEL_IDS[0]}`)).getAllByTestId(
        'chart-marker',
      );
      expect(markers.map((m) => m.getAttribute('data-marker-id'))).toEqual([
        'ev-deploy',
        'ev-alert',
        'ae-1',
      ]);
    }, FIND);
    const query = stub.calls('GET /api/v1/alerts/events')[0]?.query;
    expect(query?.get('app')).toBe('1');
    expect(query?.get('limit')).toBe('500');
    const emphasized = screen
      .getAllByTestId('chart-marker')
      .filter((marker) => marker.getAttribute('data-emphasized') === 'true');
    expect(emphasized).toHaveLength(PANEL_IDS.length);
    expect(emphasized.every((m) => m.getAttribute('data-marker-id') === 'ae-1')).toBe(true);
    expect(emphasized.every((m) => m.getAttribute('data-marker-type') === 'alert')).toBe(true);
    const banner = screen.getByTestId('alert-banner');
    expect(banner).toHaveAttribute('data-state', 'ready');
    expect(within(banner).getByTestId('alert-banner-value')).toHaveTextContent('7.1% / 5%');
  });

  it('T-UI-005 renders pool names and event titles as text', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY);
    await ready();
    const pool = screen.getByTestId('dashboard-chart-pool');
    expect(within(pool).getAllByTestId('chart-legend-item')[1]).toHaveTextContent(XSS_POOL);
    const marker = within(pool).getAllByTestId('chart-marker')[0];
    expect(marker?.getAttribute('aria-label')).toContain(XSS_EVENT_TITLE);
    expect(findInjectedMarkup(screen.getByTestId('dashboard-page'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-002 a drag sets the global absolute range and turns live off', async () => {
    answerDashboard({}, Math.floor(Date.now() / MIN) * MIN - 15 * MIN);
    const app = await openDashboard('live=1');
    await ready();
    expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-live', 'true');
    const over = plotOver(screen.getByTestId('dashboard-chart-tps'));
    fireEvent.mouseDown(over, { clientX: 50, clientY: 40, button: 0 });
    fireEvent.mouseMove(over, { clientX: 150, clientY: 40, button: 0, buttons: 1, movementX: 100 });
    fireEvent.mouseUp(document, { clientX: 150, clientY: 40, button: 0 });
    await waitFor(() => {
      expect(parseUrl(DashboardQ, app.router.state.location.search).state.live).toBe(false);
    }, FIND);
    const state = parseUrl(DashboardQ, app.router.state.location.search).state;
    expect(state.time.kind).toBe('absolute');
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-live', 'false');
    }, FIND);
    if (state.time.kind === 'absolute') {
      const last = stub.calls(DASHBOARD).at(-1);
      expect(last?.query.get('from')).toBe(new Date(state.time.from).toISOString());
      expect(last?.query.get('to')).toBe(new Date(state.time.to).toISOString());
    }
  });

  it('opens the XLog at a chart point ± range/60 (min 1 minute), keeping kind', async () => {
    answerDashboard();
    const user = userEvent.setup();
    const app = await openDashboard(`${RANGE_QUERY}&kind=BATCH`);
    await ready();
    const over = plotOver(screen.getByTestId('dashboard-chart-tps'));
    fireEvent.mouseEnter(over, { clientX: 100, clientY: 40 });
    fireEvent.mouseMove(over, { clientX: 100, clientY: 40, movementX: 1 });
    fireEvent.mouseDown(over, { clientX: 100, clientY: 40, button: 0 });
    fireEvent.mouseUp(document, { clientX: 100, clientY: 40, button: 0 });
    fireEvent.click(over, { clientX: 100, clientY: 40, button: 0 });
    await user.click(await screen.findByTestId('chart-menu-xlog', {}, FIND));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/xlog');
    }, FIND);
    const state = parseUrl(XLogQ, app.router.state.location.search).state;
    expect(state.kind).toBe('BATCH');
    expect(state.live).toBe(false);
    if (state.time.kind !== 'absolute') {
      throw new Error('not absolute');
    }
    // A 10-minute range: range/60 is 10 s, so the window is the 1-minute minimum on each side.
    expect(state.time.to - state.time.from).toBe(2 * MIN);
    expect((state.time.from + state.time.to) / 2 - FROM).toBeGreaterThanOrEqual(0);
  });
});

describe('dashboard: live (docs/14 §5.2 counter, docs/15 §10)', () => {
  it('T-UI-002 applies counter pushes to the tiles, ignores an invalid frame and shares the shell subscription', async () => {
    const lastBucket = Math.floor(Date.now() / MIN) * MIN - MIN;
    answerDashboard({}, lastBucket - 9 * MIN);
    await openDashboard('live=1');
    const page = await ready();
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    await waitFor(() => {
      expect(socket.framesOf('subscribe').some((f) => f.topic === 'counter')).toBe(true);
    }, FIND);
    const counter = socket.framesOf('subscribe').filter((f) => f.topic === 'counter');
    // The page and the shell share one server subscription with the shell's parameters.
    expect(counter).toHaveLength(1);
    expect(counter[0]?.params).toEqual({ app: 1 });
    act(() => {
      socket.ackAll();
    });
    const id = counter[0]?.id;
    const t1 = new Date(lastBucket + 20_000).toISOString();
    act(() => {
      socket.serverSend({ op: 'push', id, topic: 'counter', t: t1, data: { tps: 'broken' } });
    });
    expect(page).not.toHaveAttribute('data-live-ts');
    expect(screen.getByTestId('dashboard-tile-tps')).toHaveAttribute('data-value', '12.5');
    act(() => {
      socket.serverSend({
        op: 'push',
        id,
        topic: 'counter',
        t: t1,
        data: { tps: 41.5, avg_ms: 90, p95_ms: 410, p99_ms: 1200, error_rate: 0.012, active: 37 },
      });
    });
    await waitFor(() => {
      expect(page).toHaveAttribute('data-live-ts', t1);
    }, FIND);
    expect(screen.getByTestId('dashboard-tile-tps')).toHaveAttribute('data-value', '41.5');
    expect(screen.getByTestId('dashboard-tile-p99')).toHaveAttribute('data-value', '1200');
    expect(screen.getByTestId('dashboard-tile-active')).toHaveAttribute('data-value', '37');
    // The REST delta basis stays (the push has no basis).
    expect(screen.getByTestId('dashboard-tile-tps-delta')).toHaveAttribute('data-basis', 'prev');
  });

  it('asks the REST API again after a reconnect (WsManager onResync, docs/14 §5.3)', async () => {
    answerDashboard({}, Math.floor(Date.now() / MIN) * MIN - 10 * MIN);
    await openDashboard('live=1');
    await ready();
    const first = sockets.last();
    act(() => {
      first.serverOpen();
    });
    await waitFor(() => {
      expect(first.framesOf('subscribe').some((f) => f.topic === 'counter')).toBe(true);
    }, FIND);
    const before = stub.calls(DASHBOARD).length;
    act(() => {
      first.serverClose(1012);
    });
    // Disconnected: REST polling every 5 s fills in until the socket is back (docs/15 §10).
    expect(screen.getByTestId('dashboard-polling')).toBeInTheDocument();
    // Reconnect after the first backoff step (1 s ± 20 %).
    await waitFor(() => {
      expect(sockets.sockets.length).toBe(2);
    }, FIND);
    act(() => {
      sockets.last().serverOpen();
    });
    await waitFor(() => {
      expect(stub.calls(DASHBOARD).length).toBeGreaterThan(before);
    }, FIND);
  });

  it('subscribes nothing for a past range and applies no push', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY);
    const page = await ready();
    expect(page).toHaveAttribute('data-live', 'false');
    expect(sockets.sockets).toHaveLength(0);
  });
});

describe('dashboard: states (docs/15 §7, 05 §2.0-8)', () => {
  it('shows the error with its request id and retries', async () => {
    let fail = true;
    stub.on(DASHBOARD, () =>
      fail
        ? errorBody(500, 'internal', { requestId: 'req-500' })
        : envelope(dashboardBody({ from: FROM }), dashboardMeta(FROM)),
    );
    await openDashboard(RANGE_QUERY);
    const error = await screen.findByTestId('dashboard-error', {}, FIND);
    expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-state', 'error');
    expect(error).toHaveTextContent('req-500');
    fail = false;
    await userEvent.setup().click(within(error).getByRole('button', { name: /retry/i }));
    await ready();
    expect(screen.getByTestId('dashboard-page')).toHaveAttribute('data-state', 'ready');
  });

  it('T-UI-007 empty range: the next action and the excluded requests, the charts stay', async () => {
    answerDashboard({ count: 0 });
    await openDashboard(RANGE_QUERY);
    const page = await ready();
    expect(page).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('dashboard-empty')).toHaveTextContent('Widen the range');
    expect(screen.getByTestId('dashboard-empty-excluded')).toHaveTextContent(
      '1,204 requests excluded in this range',
    );
    expect(screen.getByTestId('dashboard-tile-tps')).toHaveAttribute('data-value', 'null');
    expect(screen.getByTestId('dashboard-chart-heap-gc')).toBeInTheDocument();
  });

  it('T-UI-006 renders in Korean from the dashboard catalog', async () => {
    answerDashboard();
    await openDashboard(RANGE_QUERY, 'ko');
    await ready();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('대시보드');
    expect(screen.getByTestId('dashboard-tile-error-rate')).toHaveTextContent('에러율');
    expect(screen.getByTestId('dashboard-chart-active-note')).toHaveTextContent(
      '액티브 트랜잭션 표본이 없습니다',
    );
  });
});
