// SPDX-License-Identifier: Apache-2.0
//
// The XLog screen in the whole app (router, URL loader, transport, generated client; only the HTTP
// boundary is stubbed and the WebSocket is a fake socket): the points request of the URL state
// (docs/14 §4.5), sampling "Showing N of M" (T-API-006 display), the response's latency threshold,
// outage shading legend and event markers, collected names as text (T-UI-005), point click ->
// transaction detail with the `ts` hint and the `back` link (ADR-0010 C-206), keyboard selection ->
// `sel=` (docs/15 §13), filters the points API cannot apply listed (C-202), error and empty states,
// and live mode: REST over the last 5 minutes plus the `xlog` topic (docs/14 §5.2).
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import { installCanvasMock, mockLayout } from '../../../test/canvasMock';
import type { FakeSockets } from '../../../test/fakeWebSocket';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { parseUrl, XLogQ } from '../../state/urlState';
import { PLOT_MARGIN } from '../../xlog/renderer';

import { SEL_UNBOUNDED_MS } from './data/selection';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const XSS_SERVICE = 'GET /q?<img src=x onerror="window.__xss=1">';
const XSS_INSTANCE = 'web-"><svg/onload=window.__xss=1>';
const FROM_ISO = '2026-11-15T03:00:00.000Z';
const TO_ISO = '2026-11-15T03:15:00.000Z';
const FROM = Date.parse(FROM_ISO);
const TO = Date.parse(TO_ISO);
const WIDTH = 1000;
const HEIGHT = 400;
const PLOT_W = WIDTH - PLOT_MARGIN.left - PLOT_MARGIN.right;
const PLOT_H = HEIGHT - PLOT_MARGIN.top - PLOT_MARGIN.bottom;
const TX = ['733415239876543210', '733415239876543211', '733415239876543212'];
const FIND = { timeout: 5000 };

let stub: ApiStub;
let sockets: FakeSockets;

function xlogBody() {
  return {
    tx_id: TX,
    ts: [FROM + 60_000, FROM + 300_000, FROM + 600_000],
    elapsed_ms: [100, 100, 100],
    status: [0, 2, 1],
    instance_id: [12, 12, 13],
    service_id: ['17', '17', '18446744073709551615'],
    kind: [1, 1, 1],
    dictionary: {
      service: { '17': XSS_SERVICE, '18446744073709551615': 'GET /b' },
      instance: { '12': XSS_INSTANCE, '13': 'web-02' },
    },
  };
}

const META = {
  from: FROM_ISO,
  to: TO_ISO,
  source: 'raw',
  shown: 3,
  total: 120_000,
  sampling: { applied: true, rule: 'error OR slow OR cityHash64(tx_id) % 2 = 0' },
  truncated: false,
  slow_threshold_ms: 1500,
  outages: [{ from: '2026-11-15T03:05:00.000Z', to: '2026-11-15T03:06:00.000Z', reason: 'down' }],
};

function answerXLog(): void {
  stub.on('GET /api/v1/apps/1/xlog', () => envelope(xlogBody(), META));
  stub.on('GET /api/v1/apps/1/events', () =>
    envelope([
      {
        event_id: 'ev-1',
        ts: '2026-11-15T03:10:00.000Z',
        type: 'deploy',
        title: 'v2',
        version: '2.0.0',
        detail: null,
        detail_truncated: false,
        instance_id: null,
        actor: null,
      },
    ]),
  );
  stub.on('POST /api/v1/apps/1/transactions/search', () =>
    envelope([], { shown: 0, total: 0, next_cursor: null }),
  );
  stub.on('POST /api/v1/apps/1/xlog/summary', () =>
    envelope({ count: 0, by_service: [], by_instance: [], by_error_class: [], by_status: [] }),
  );
}

async function openXLog(query: string) {
  signedIn(stub);
  const app = renderApp(`/apps/1/xlog?${query}`);
  await screen.findByTestId('xlog-page', {}, FIND);
  return app;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  stub = installApiStub();
  installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('XLog screen: data (docs/14 §4.5, docs/15 §9.3)', () => {
  it('T-UI-001 asks for the URL range and draws the points with sampling, threshold, outages and markers', async () => {
    answerXLog();
    await openXLog(`from=${FROM_ISO}&to=${TO_ISO}`);
    await screen.findByTestId('xlog-canvas', {}, FIND);
    const [request] = stub.calls('GET /api/v1/apps/1/xlog');
    expect(request?.query.get('from')).toBe(FROM_ISO);
    expect(request?.query.get('to')).toBe(TO_ISO);
    expect(request?.query.get('limit')).toBe('100000');
    expect(request?.query.has('kind')).toBe(false);
    expect(request?.query.has('color_by')).toBe(false);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-point-count', '3');
    }, FIND);
    // The drawn range, for the E2E suite (live window moves, pause).
    expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-range-from', String(FROM));
    expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-range-to', String(TO));
    // T-API-006 display: the server sampled the range.
    expect(screen.getByTestId('xlog-sampling-indicator')).toHaveTextContent('Showing 3 of 120,000');
    expect(screen.getByTestId('xlog-threshold')).toHaveTextContent('Latency threshold 1,500 ms');
    expect(screen.getByTestId('xlog-outage-legend')).toHaveTextContent('1 collection outage');
    expect(await screen.findByTestId('xlog-marker', {}, FIND)).toHaveAttribute(
      'data-marker-id',
      'ev-1',
    );
    expect(screen.getByTestId('partial-sampling')).toBeInTheDocument();
    // The chart becomes data-ready after the first full frame of the loaded points.
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-ready', 'true');
    }, FIND);
  });

  it('draws the alert transitions of the range with the events and emphasises the alert= one (C-226 (8))', async () => {
    answerXLog();
    stub.on('GET /api/v1/alerts/events', () =>
      envelope([
        {
          event_id: 'ae-7',
          ts: '2026-11-15T03:05:00.000Z',
          app: { id: 1, name: 'order-api' },
          env: null,
          target: { type: 'app', id: null, name: null },
          rule: { id: 'r-1', name: 'p95 high', metric: 'response_time_p95', unit: 'ms' },
          severity: 'warn',
          from_state: 'PENDING',
          state: 'FIRING',
          reason: 'condition',
          value: 1500,
          threshold: 1000,
          duration_s: null,
          acked_by: null,
          acked_at: null,
          note: null,
          deliveries: [],
          deeplink: '/apps/1/xlog?alert=ae-7',
        },
      ]),
    );
    await openXLog(`from=${FROM_ISO}&to=${TO_ISO}&alert=ae-7`);
    await screen.findByTestId('xlog-canvas', {}, FIND);
    await waitFor(() => {
      expect(
        screen
          .getAllByTestId('xlog-marker')
          .map((m) => m.getAttribute('data-marker-id'))
          .sort(),
      ).toEqual(['ae-7', 'ev-1']);
    }, FIND);
    const alert = screen
      .getAllByTestId('xlog-marker')
      .find((m) => m.getAttribute('data-marker-id') === 'ae-7');
    expect(alert).toHaveAttribute('data-marker-type', 'alert');
    expect(alert).toHaveAttribute('data-emphasized', 'true');
    const query = stub.calls('GET /api/v1/alerts/events')[0]?.query;
    expect(query?.get('app')).toBe('1');
    expect(query?.get('from')).toBe(FROM_ISO);
    expect(query?.get('to')).toBe(TO_ISO);
    const banner = await screen.findByTestId('alert-banner', {}, FIND);
    await waitFor(() => {
      expect(banner).toHaveAttribute('data-state', 'ready');
    }, FIND);
    expect(within(banner).getByTestId('alert-banner-value')).toHaveTextContent('1.5s / 1s');
  });

  it('T-UI-005 renders collected service and instance names in the legend as text only', async () => {
    answerXLog();
    const { container } = await openXLog(`from=${FROM_ISO}&to=${TO_ISO}&color=service`);
    const legend = await screen.findByTestId('xlog-legend', {}, FIND);
    await waitFor(() => {
      expect(within(legend).getByText(XSS_SERVICE)).toBeInTheDocument();
    }, FIND);
    fireEvent.click(screen.getByTestId('xlog-colorby-instance'));
    await waitFor(() => {
      expect(within(screen.getByTestId('xlog-legend')).getByText(XSS_INSTANCE)).toBeInTheDocument();
    }, FIND);
    expect(container.querySelector('.xlog-legend img, .xlog-legend svg, script')).toBeNull();
    expect(Reflect.get(window, '__xss')).toBeUndefined();
    // Changing the colour did not ask for the points again (color_by is not sent).
    expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(1);
  });

  it('T-UI-001 opens the transaction detail of a clicked point with the ts hint and the back link (C-206)', async () => {
    answerXLog();
    const app = await openXLog(`from=${FROM_ISO}&to=${TO_ISO}`);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-point-count', '3');
    }, FIND);
    const overlay = await screen.findByTestId('xlog-canvas-overlay', {}, FIND);
    // Point 0: 1 minute into the 15-minute range; all points at 100 ms with the automatic axis
    // maximum 200 ms (p99.9 × 1.2 rounded up): the middle of the plot height.
    const clientX = PLOT_MARGIN.left + (60_000 / (TO - FROM)) * PLOT_W;
    const clientY = PLOT_MARGIN.top + PLOT_H / 2;
    await act(async () => {
      overlay.dispatchEvent(
        new PointerEvent('pointerdown', {
          clientX,
          clientY,
          button: 0,
          pointerId: 1,
          bubbles: true,
        }),
      );
      overlay.dispatchEvent(
        new PointerEvent('pointerup', { clientX, clientY, button: 0, pointerId: 1, bubbles: true }),
      );
      await Promise.resolve();
    });
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe(`/apps/1/transactions/${TX[0] ?? ''}`);
    }, FIND);
    const params = new URLSearchParams(app.router.state.location.search);
    expect(params.get('ts')).toBe('2026-11-15T03:01:00.000Z');
    expect(params.get('back')).toBe(`/apps/1/xlog?from=${FROM_ISO}&to=${TO_ISO}`);
  });

  it('T-UI-001 selects a time band with the keyboard and writes it as sel= (docs/15 §13)', async () => {
    answerXLog();
    const app = await openXLog(`from=${FROM_ISO}&to=${TO_ISO}`);
    const plot = await screen.findByTestId('xlog-plot', {}, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-point-count', '3');
    }, FIND);
    act(() => {
      plot.focus();
    });
    fireEvent.keyDown(plot, { key: 'End' });
    fireEvent.keyDown(plot, { key: 'Enter' });
    await waitFor(() => {
      expect(app.router.state.location.search).toContain('sel=');
    }, FIND);
    const sel = parseUrl(XLogQ, app.router.state.location.search).state.sel;
    expect(sel).toEqual({
      msMin: 0,
      msMax: SEL_UNBOUNDED_MS,
      tsFrom: TO - 90_000,
      tsTo: TO,
    });
  });

  it('T-UI-001 sends the scatter filters and lists the ones the points API cannot apply (C-202)', async () => {
    answerXLog();
    await openXLog(`from=${FROM_ISO}&to=${TO_ISO}&f.service=17&f.status=500&kind=BATCH`);
    await screen.findByTestId('xlog-canvas', {}, FIND);
    const [request] = stub.calls('GET /api/v1/apps/1/xlog');
    expect(request?.query.getAll('service')).toEqual(['17']);
    expect(request?.query.get('kind')).toBe('BATCH');
    expect(request?.query.has('status')).toBe(false);
    const notice = screen.getByTestId('xlog-chart-unapplied');
    expect(within(notice).getByText('status').closest('li')).toHaveAttribute(
      'data-reason',
      'not_supported_here',
    );
    // Both filters are chips (the shell's FilterChipBar, 05 §2.0-4).
    const chips = screen.getAllByTestId('filter-chip');
    expect(chips.map((chip) => chip.getAttribute('data-field'))).toEqual(['service', 'status']);
  });
});

/** `sel` in URL form (the data-sel of the summary and the list). */
function selText(sel: { msMin: number; msMax: number; tsFrom: number; tsTo: number }): string {
  return `ms:${String(sel.msMin)}-${String(sel.msMax)},ts:${String(sel.tsFrom)}-${String(sel.tsTo)}`;
}

/** The URL selection, the summary, the list and its search request all show `count` points of it. */
async function expectSelectionAgrees(
  app: Awaited<ReturnType<typeof openXLog>>,
  count: number,
): Promise<{ msMin: number; msMax: number; tsFrom: number; tsTo: number }> {
  let sel: { msMin: number; msMax: number; tsFrom: number; tsTo: number } | undefined;
  await waitFor(() => {
    sel = parseUrl(XLogQ, app.router.state.location.search).state.sel;
    expect(sel).toBeDefined();
  }, FIND);
  if (sel === undefined) {
    throw new Error('no sel');
  }
  const key = selText(sel);
  await waitFor(() => {
    expect(screen.getByTestId('xlog-summary')).toHaveAttribute('data-sel', key);
    expect(screen.getByTestId('xlog-summary')).toHaveAttribute('data-client-count', String(count));
    expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-sel', key);
    expect(screen.getByTestId('xlog-list')).toHaveAttribute('data-state', 'empty');
  }, FIND);
  expect(screen.getByTestId('xlog-selection-announcer')).toHaveTextContent(
    count === 1 ? '1 point selected' : `${String(count)} points selected`,
  );
  // One polite announcement: the page's resident region, not the summary's counts too.
  expect(screen.getByTestId('xlog-summary').querySelector('[aria-live]')).toBeNull();
  // The list asks the server for exactly the URL selection (inclusive bounds, docs/15 §7.3).
  const searches = stub.calls('POST /api/v1/apps/1/transactions/search');
  const body = searches[searches.length - 1]?.body as
    { from: string; to: string; filters: { min_ms?: number; max_ms?: number } } | undefined;
  expect(body?.from).toBe(new Date(sel.tsFrom).toISOString());
  expect(body?.to).toBe(new Date(sel.tsTo + 1).toISOString());
  expect(body?.filters.min_ms).toBe(sel.msMin);
  expect(body?.filters.max_ms).toBe(sel.msMax);
  return sel;
}

describe('XLog screen: one selection for canvas, URL, summary and list', () => {
  it('T-UI-001 drag, keyboard selection and reload agree on sel, the summary count and the list request', async () => {
    answerXLog();
    const app = await openXLog(`from=${FROM_ISO}&to=${TO_ISO}`);
    const plot = await screen.findByTestId('xlog-plot', {}, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-point-count', '3');
    }, FIND);
    // Keyboard: Home puts the band on the first 10% of the range (90 s): point 0 (1 min in).
    act(() => {
      plot.focus();
    });
    fireEvent.keyDown(plot, { key: 'Home' });
    fireEvent.keyDown(plot, { key: 'Enter' });
    const keyboard = await expectSelectionAgrees(app, 1);
    expect(keyboard).toEqual({
      msMin: 0,
      msMax: SEL_UNBOUNDED_MS,
      tsFrom: FROM,
      tsTo: FROM + 90_000,
    });

    // Drag over minutes 4 to 11, full height: points 1 and 2 (5 and 10 min in, 100 ms).
    const overlay = screen.getByTestId('xlog-canvas-overlay');
    const x = (offset: number) => PLOT_MARGIN.left + (offset / (TO - FROM)) * PLOT_W;
    const down = { clientX: x(240_000), clientY: PLOT_MARGIN.top + 1, button: 0, pointerId: 2 };
    const up = {
      clientX: x(660_000),
      clientY: PLOT_MARGIN.top + PLOT_H - 1,
      button: 0,
      pointerId: 2,
    };
    await act(async () => {
      overlay.dispatchEvent(new PointerEvent('pointerdown', { ...down, bubbles: true }));
      overlay.dispatchEvent(new PointerEvent('pointermove', { ...up, bubbles: true }));
      overlay.dispatchEvent(new PointerEvent('pointerup', { ...up, bubbles: true }));
      await Promise.resolve();
    });
    await waitFor(() => {
      expect(parseUrl(XLogQ, app.router.state.location.search).state.sel).not.toEqual(keyboard);
    }, FIND);
    const dragged = await expectSelectionAgrees(app, 2);
    expect(dragged.tsFrom).toBeLessThanOrEqual(FROM + 300_000);
    expect(dragged.tsTo).toBeGreaterThanOrEqual(FROM + 600_000);
    expect(dragged.msMin).toBeLessThanOrEqual(100);
    expect(dragged.msMax).toBeGreaterThanOrEqual(100);

    // Reload the same URL: the same selection, count and request (deep link 5).
    const url = `${app.router.state.location.pathname}${app.router.state.location.search}`;
    app.unmount();
    const searchesBefore = stub.calls('POST /api/v1/apps/1/transactions/search').length;
    const reloaded = renderApp(url);
    await screen.findByTestId('xlog-page', {}, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-point-count', '3');
    }, FIND);
    expect(await expectSelectionAgrees(reloaded, 2)).toEqual(dragged);
    expect(stub.calls('POST /api/v1/apps/1/transactions/search').length).toBeGreaterThan(
      searchesBefore,
    );
  });
});

describe('XLog screen: states', () => {
  it('shows the error state: an invalid range says so, too much data offers "narrow the range"', async () => {
    stub.on('GET /api/v1/apps/1/xlog', () => errorBody(400, 'range_invalid'));
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    const view = await openXLog('from=now-24h');
    expect(await screen.findByTestId('xlog-error', {}, FIND)).toHaveAttribute(
      'data-error-kind',
      'rangeInvalid',
    );
    // A refused range offers shorter ranges next to Retry (05 §2.0 next action).
    const actions = screen.getByTestId('xlog-range-actions');
    expect(within(actions).getByTestId('xlog-range-last24h')).toBeInTheDocument();
    fireEvent.click(within(actions).getByTestId('xlog-range-last1h'));
    await waitFor(() => {
      expect(parseUrl(XLogQ, view.router.state.location.search).state.time).toEqual({
        kind: 'relative',
        range: '1h',
      });
    }, FIND);
    view.unmount();

    stub.on('GET /api/v1/apps/1/xlog', () => errorBody(400, 'limit_exceeded'));
    const app = await openXLog('from=now-24h');
    const error = await screen.findByTestId('xlog-error', {}, FIND);
    fireEvent.click(within(error).getByTestId('error-state-narrow'));
    await waitFor(() => {
      expect(parseUrl(XLogQ, app.router.state.location.search).state.time).toEqual({
        kind: 'relative',
        range: '1h',
      });
    }, FIND);
  });

  it('T-UI-001 does not ask for a range over 24 hours (7d from another screen) and offers shorter ranges', async () => {
    answerXLog();
    const app = await openXLog('from=now-7d');
    const notice = await screen.findByTestId('xlog-range-limit', {}, FIND);
    expect(notice).toHaveTextContent('The XLog shows at most 24 hours at a time.');
    expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-state', 'rangeLimit');
    // Neither the points nor the event markers of the 7 days are asked for.
    expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(0);
    expect(stub.calls('GET /api/v1/apps/1/events')).toHaveLength(0);
    // "Last 24 hours of this range": an absolute range of 24 hours ending where the 7 days end.
    const before = Date.now();
    fireEvent.click(within(notice).getByTestId('xlog-range-last24h'));
    let time = parseUrl(XLogQ, app.router.state.location.search).state.time;
    await waitFor(() => {
      time = parseUrl(XLogQ, app.router.state.location.search).state.time;
      expect(time.kind).toBe('absolute');
    }, FIND);
    if (time.kind !== 'absolute') {
      throw new Error('not absolute');
    }
    expect(time.to - time.from).toBe(24 * 60 * 60_000);
    expect(time.to).toBeLessThanOrEqual(Date.now());
    expect(time.to).toBeGreaterThan(before - 2 * 60_000);
    // The points of that range are asked for once.
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(1);
    }, FIND);
    const [request] = stub.calls('GET /api/v1/apps/1/xlog');
    expect(request?.query.get('from')).toBe(new Date(time.from).toISOString());
    expect(request?.query.get('to')).toBe(new Date(time.to).toISOString());
  });

  it('T-UI-001 offers "last 1 hour" for a range over 24 hours', async () => {
    answerXLog();
    const app = await openXLog('from=now-30d');
    const notice = await screen.findByTestId('xlog-range-limit', {}, FIND);
    fireEvent.click(within(notice).getByTestId('xlog-range-last1h'));
    await waitFor(() => {
      expect(parseUrl(XLogQ, app.router.state.location.search).state.time).toEqual({
        kind: 'relative',
        range: '1h',
      });
    }, FIND);
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(1);
    }, FIND);
  });

  it('shows the empty state with the next action when the range has no point', async () => {
    stub.on('GET /api/v1/apps/1/xlog', () =>
      envelope(
        {
          tx_id: [],
          ts: [],
          elapsed_ms: [],
          status: [],
          instance_id: [],
          service_id: [],
          kind: [],
          dictionary: { service: {}, instance: {} },
        },
        { shown: 0, total: 0 },
      ),
    );
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    // D-19: the agent excluded 7 requests of the range (collection health, docs/15 §6.7).
    stub.on('GET /api/v1/apps/1/collection-health', () =>
      envelope({ excluded: { by_reason: { path: 5, health: 2 }, ts: [], value: [] } }),
    );
    await openXLog(`from=${FROM_ISO}&to=${TO_ISO}&f.service=17`);
    const empty = await screen.findByTestId('xlog-empty', {}, FIND);
    expect(within(empty).getByTestId('empty-state-widen')).toBeInTheDocument();
    expect(within(empty).getByTestId('empty-state-clear-filters')).toBeInTheDocument();
    expect(await within(empty).findByTestId('empty-state-excluded', {}, FIND)).toHaveTextContent(
      '7',
    );
    const [health] = stub.calls('GET /api/v1/apps/1/collection-health');
    expect(health?.query.get('from')).toBe(FROM_ISO);
    expect(health?.query.get('to')).toBe(TO_ISO);
  });
});

describe('XLog screen: live (docs/15 §7.5, docs/14 §5.2)', () => {
  it('T-UI-001 turns live off and offers "resume live" after a selection over the past (WO-30 reducer)', async () => {
    answerXLog();
    const app = await openXLog('live=1');
    const plot = await screen.findByTestId('xlog-plot', {}, FIND);
    act(() => {
      plot.focus();
    });
    fireEvent.keyDown(plot, { key: 'End' });
    fireEvent.keyDown(plot, { key: 'Enter' });
    await waitFor(() => {
      expect(app.router.state.location.search).toContain('sel=');
    }, FIND);
    const state = parseUrl(XLogQ, app.router.state.location.search).state;
    expect(state.live).toBe(false);
    expect(state.time.kind).toBe('absolute');
    expect(await screen.findByTestId('live-resume', {}, FIND)).toBeInTheDocument();
    // The frozen range is queried again through REST (deep link 5: the URL holds it).
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(2);
    }, FIND);
    const frozen = stub.calls('GET /api/v1/apps/1/xlog')[1];
    if (state.time.kind !== 'absolute') {
      throw new Error('not absolute');
    }
    expect(frozen?.query.get('from')).toBe(new Date(state.time.from).toISOString());
    expect(frozen?.query.get('to')).toBe(new Date(state.time.to).toISOString());
  });

  it('T-UI-001 loads the last 5 minutes and subscribes the xlog topic with the screen parameters', async () => {
    answerXLog();
    const before = Date.now();
    await openXLog('live=1&f.instance=12');
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/apps/1/xlog')).toHaveLength(1);
    }, FIND);
    const [request] = stub.calls('GET /api/v1/apps/1/xlog');
    const from = Date.parse(request?.query.get('from') ?? '');
    const to = Date.parse(request?.query.get('to') ?? '');
    expect(to - from).toBe(5 * 60_000);
    expect(to).toBeGreaterThanOrEqual(before);
    expect(request?.query.getAll('instance')).toEqual(['12']);
    expect(screen.getByTestId('xlog-page')).toHaveAttribute('data-live', 'true');
    expect(screen.getByTestId('xlog-pause-button')).toBeInTheDocument();
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    await waitFor(() => {
      expect(socket.framesOf('subscribe').some((f) => f.topic === 'xlog')).toBe(true);
    }, FIND);
    const subscribe = socket.framesOf('subscribe').find((f) => f.topic === 'xlog');
    expect(subscribe?.params).toEqual({
      app: 1,
      kind: 'HTTP',
      service: [],
      instance: [12],
    });
  });
});
