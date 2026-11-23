// SPDX-License-Identifier: Apache-2.0
//
// The overview screen in the whole app (router, URL loader, transport, generated client; only the
// HTTP boundary is stubbed and the WebSocket is a fake socket), docs/15 §9.1 and 05 §2.10: the
// request of the URL question (env, group, kind), every cell of a row against the same-question
// response with null apart from 0 (T-UI-007), status colours and the pinned rows (T-UI-008,
// T-UI-004), collected strings as text (T-UI-005), the card/table switch remembered per browser
// (storage failures tolerated), the firing and server panels ("Not available" while the server
// sends null, ADR-0010 C-212 (2)), links that keep the global keys, the `overview` push merged
// into the rows on the shell's shared subscription (docs/14 §5.2, C-213 (3)), kiosk, and the
// empty and error states.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
  appBody,
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import type { OverviewApp } from '../../api/overview';
import { untrusted } from '../../api/untrusted';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../../components/testing/xssPayloads';
import { MISSING_DASH } from '../../utils/missing';

import { overviewApp, overviewBody, overviewMeta } from './testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U17' };
export const REQ_KIOSK = { type: 'req', description: 'F-U21' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const OVERVIEW = 'GET /api/v1/overview';
const ALERTS = 'GET /api/v1/overview/alerts';
const REST_AT = '2026-11-23T10:00:00.000Z';

let stub: ApiStub;
let sockets: FakeSockets;

/** Four applications: UP with values (0 and null mixed), DOWN, STALE and WARN with alerts. */
function apps(): OverviewApp[] {
  return [
    overviewApp(1, 'order-api', {
      group: 'core',
      tps: 12.5,
      p95_ms: 0,
      error_rate: null,
      active: 3,
      batch_running: null,
      firing: null,
      last_deploy: { ts: '2026-11-22T10:00:00.000Z', version: untrusted('1.4.2') },
    }),
    overviewApp(2, 'billing', {
      group: 'pay',
      status: 'DOWN',
      instances: { up: 0, total: 2 },
      tps: null,
      p95_ms: null,
      error_rate: null,
      active: null,
    }),
    overviewApp(3, 'search', {
      env: 'stage',
      status: 'STALE',
      instances: { up: 1, total: 2 },
      p95_ms: 900,
    }),
    overviewApp(4, 'payments', {
      group: 'pay',
      status: 'WARN',
      firing: { critical: 1, warn: 2 },
      error_rate: 0,
      batch_running: 2,
    }),
    overviewApp(5, 'audit', { tps: 0, error_rate: 0.002 }),
  ];
}

function answer(
  rows: readonly OverviewApp[] = apps(),
  options: Parameters<typeof overviewBody>[1] = {},
  meta: Record<string, unknown> = {},
): void {
  stub.on(OVERVIEW, () => envelope(overviewBody(rows, options), overviewMeta(REST_AT, meta)));
}

/** Opens /overview signed in; `afterSignIn` overrides the shell's default answers. */
async function openOverview(
  query = '',
  language: 'en' | 'ko' = 'en',
  afterSignIn: () => void = () => undefined,
) {
  signedIn(stub);
  afterSignIn();
  const app = renderApp(`/overview${query === '' ? '' : `?${query}`}`, { language });
  const page = await screen.findByTestId('overview-page', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return { app, page };
}

function rowIds(): string[] {
  return [...document.querySelectorAll<HTMLElement>('[data-testid^="overview-row-"]')].map(
    (row) => row.getAttribute('data-app-id') ?? '',
  );
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

describe('overview: request and rows (docs/14 §4.2, 05 §2.10)', () => {
  it('T-UI-007 asks once with the URL question and shows every cell of the response; null is the dash, 0 a value', async () => {
    answer();
    const { page } = await openOverview('env=prod&group=pay&kind=BATCH');
    const calls = stub.calls(OVERVIEW);
    expect(calls.length).toBeGreaterThanOrEqual(1);
    expect(calls[0]?.query.get('env')).toBe('prod');
    expect(calls[0]?.query.get('group')).toBe('pay');
    expect(calls[0]?.query.get('kind')).toBe('BATCH');
    expect(page).toHaveAttribute('data-view', 'table');
    expect(screen.getByTestId('overview-table')).toBeInTheDocument();

    // order-api: p95 0 is a value, error rate and batches null are the dash.
    expect(screen.getByTestId('overview-cell-tps-1')).toHaveAttribute('data-value', '12.5');
    expect(screen.getByTestId('overview-cell-p95-1')).toHaveAttribute('data-value', '0');
    expect(screen.getByTestId('overview-cell-p95-1')).not.toHaveTextContent(MISSING_DASH);
    expect(screen.getByTestId('overview-cell-error-rate-1')).toHaveAttribute('data-value', 'null');
    expect(screen.getByTestId('overview-cell-error-rate-1')).toHaveTextContent(MISSING_DASH);
    expect(screen.getByTestId('overview-cell-batches-1')).toHaveTextContent(MISSING_DASH);
    expect(screen.getByTestId('overview-cell-active-1')).toHaveAttribute('data-value', '3');
    expect(screen.getByTestId('overview-cell-instances-1')).toHaveAttribute('data-up', '2');
    expect(screen.getByTestId('overview-cell-instances-1')).toHaveAttribute('data-total', '2');
    expect(screen.getByTestId('overview-cell-instances-1')).toHaveTextContent('2/2');
    expect(screen.getByTestId('overview-cell-deploy-1')).toHaveAttribute(
      'data-value',
      '2026-11-22T10:00:00.000Z',
    );
    expect(screen.getByTestId('overview-cell-deploy-1')).toHaveTextContent('1.4.2');
    // firing null: not available, never 0 (C-212 (2)).
    const firing = screen.getByTestId('overview-cell-firing-1');
    expect(firing).toHaveAttribute('data-state', 'unavailable');
    expect(firing).toHaveTextContent('n/a');
    expect(firing).not.toHaveTextContent('0');
    // payments: zero errors is 0; alerts by severity.
    expect(screen.getByTestId('overview-cell-error-rate-4')).toHaveAttribute('data-value', '0');
    expect(screen.getByTestId('overview-cell-error-rate-4')).not.toHaveTextContent(MISSING_DASH);
    expect(screen.getByTestId('overview-cell-firing-4')).toHaveAttribute('data-critical', '1');
    expect(screen.getByTestId('overview-cell-firing-4')).toHaveAttribute('data-warn', '2');
    expect(screen.getByTestId('overview-cell-batches-4')).toHaveAttribute('data-value', '2');
    // audit: TPS 0 is a value; no deploy is the dash.
    expect(screen.getByTestId('overview-cell-tps-5')).toHaveAttribute('data-value', '0');
    expect(screen.getByTestId('overview-cell-deploy-5')).toHaveTextContent(MISSING_DASH);
    // billing: every missing value is the dash.
    for (const field of ['tps', 'p95', 'error-rate', 'active']) {
      expect(screen.getByTestId(`overview-cell-${field}-2`)).toHaveTextContent(MISSING_DASH);
    }
    // The three one-hour sparklines with the 60 points of the response.
    for (const series of ['tps', 'p95', 'err']) {
      expect(screen.getByTestId(`overview-spark-${series}-1`)).toHaveAttribute('data-points', '60');
    }
    // The data basis is on screen (the API takes no range).
    expect(screen.getByTestId('overview-basis')).toHaveTextContent('last 5 minutes');
  });

  it('T-UI-008 colours the status badges and pins abnormal applications on top (T-UI-004)', async () => {
    answer();
    await openOverview();
    // Default order: abnormal (DOWN, STALE, WARN) first, worst first, then the rest by name.
    expect(rowIds()).toEqual(['2', '3', '4', '5', '1']);
    const expected: Record<string, [string, string]> = {
      '1': ['UP', 'false'],
      '2': ['DOWN', 'true'],
      '3': ['STALE', 'true'],
      '4': ['WARN', 'true'],
      '5': ['UP', 'false'],
    };
    for (const [id, [status, pinned]] of Object.entries(expected)) {
      const row = screen.getByTestId(`overview-row-${id}`);
      expect(row).toHaveAttribute('data-status', status);
      expect(row).toHaveAttribute('data-pinned', pinned);
      const badge = within(row).getByTestId(`overview-status-${id}`);
      expect(badge).toHaveClass(`status-badge--${status.toLowerCase()}`);
    }
  });

  it('T-UI-004 sorts by error rate, p95 and alerts through the URL; abnormal apps stay pinned', async () => {
    answer();
    const { app } = await openOverview();
    fireEvent.click(screen.getByTestId('overview-sort-p95'));
    await waitFor(() => {
      expect(app.url()).toContain('sort=p95:desc');
    }, FIND);
    // Pinned group first (each sorted by p95 desc, missing last), then the others. The URL
    // changes before the rows re-render, so the order is awaited too.
    await waitFor(() => {
      expect(rowIds()).toEqual(['3', '4', '2', '5', '1']);
    }, FIND);
    expect(screen.getByTestId('overview-sort-p95')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('overview-sort-p95')).toHaveAttribute('data-desc', 'true');
    fireEvent.click(screen.getByTestId('overview-sort-error-rate'));
    await waitFor(() => {
      expect(app.url()).toContain('sort=error_rate:desc');
    }, FIND);
    await waitFor(() => {
      expect(rowIds()).toEqual(['3', '4', '2', '5', '1']);
    }, FIND);
    fireEvent.click(screen.getByTestId('overview-sort-firing'));
    await waitFor(() => {
      expect(app.url()).toContain('sort=firing:desc');
    }, FIND);
    await waitFor(() => {
      expect(rowIds().slice(0, 3)).toEqual(['4', '2', '3']);
    }, FIND);
    for (const id of ['2', '3', '4']) {
      expect(screen.getByTestId(`overview-row-${id}`)).toHaveAttribute('data-pinned', 'true');
    }
  }, 20_000);

  it('opens the dashboard of a row with the global keys (time range, kind, env, live)', async () => {
    const user = userEvent.setup();
    answer();
    const query = 'from=2026-11-23T08:00:00.000Z&to=2026-11-23T09:00:00.000Z&env=prod&kind=ALL';
    const { app } = await openOverview(query);
    const link = screen.getByTestId('overview-link-1');
    const href = link.getAttribute('href') ?? '';
    expect(href.startsWith('/apps/1/dashboard?')).toBe(true);
    const params = new URLSearchParams(href.split('?')[1]);
    expect(params.get('from')).toBe('2026-11-23T08:00:00.000Z');
    expect(params.get('to')).toBe('2026-11-23T09:00:00.000Z');
    expect(params.get('env')).toBe('prod');
    expect(params.get('kind')).toBe('ALL');
    // A click on the row (not the link) opens the same place.
    await user.click(screen.getByTestId('overview-cell-tps-5'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/5/dashboard');
    }, FIND);
    const search = new URLSearchParams(app.router.state.location.search);
    expect(search.get('from')).toBe('2026-11-23T08:00:00.000Z');
    expect(search.get('kind')).toBe('ALL');
    expect(search.get('env')).toBe('prod');
  });

  it('keeps live in the dashboard link of a live overview', async () => {
    answer();
    await openOverview('from=now-1h&live=1');
    const href = screen.getByTestId('overview-link-4').getAttribute('href') ?? '';
    const params = new URLSearchParams(href.split('?')[1]);
    expect(href.startsWith('/apps/4/dashboard?')).toBe(true);
    expect(params.get('live')).toBe('1');
    expect(params.get('from')).toBe('now-1h');
  });
});

describe('overview: filters (05 §2.10 "환경·앱 그룹 필터")', () => {
  it('lists the groups of the applications and writes the chosen group to the URL', async () => {
    answer();
    const { app } = await openOverview('env=prod', 'en', () => {
      stub.on('GET /api/v1/apps', () =>
        envelope([
          appBody(1, 'order-api', 'prod', { group: 'core' }),
          appBody(2, 'billing', 'prod', { group: 'pay' }),
          appBody(4, 'payments', 'prod', { group: 'pay' }),
          appBody(3, 'search', 'stage', { group: 'stage-only' }),
        ]),
      );
    });
    const select = screen.getByTestId('overview-group-select');
    expect(select).toHaveAccessibleName('Application group');
    // Groups of the chosen environment only, "All groups" first.
    await waitFor(() => {
      const labels = [...select.querySelectorAll('option')].map((node) => node.textContent);
      expect(labels).toEqual(['All groups', 'core', 'pay']);
    }, FIND);
    fireEvent.change(select, { target: { value: 'pay' } });
    await waitFor(() => {
      expect(app.url()).toContain('group=pay');
    }, FIND);
    await waitFor(() => {
      expect(stub.calls(OVERVIEW).some((call) => call.query.get('group') === 'pay')).toBe(true);
    }, FIND);
    expect(app.url()).toContain('env=prod');
  }, 20_000);

  it('says why nothing is shown for a filter and clears env and group', async () => {
    const user = userEvent.setup();
    answer([]);
    const { app, page } = await openOverview('env=prod&group=pay');
    expect(page).toHaveAttribute('data-state', 'empty');
    const empty = screen.getByTestId('overview-empty');
    expect(empty).toHaveTextContent('No applications to show.');
    await user.click(within(empty).getByRole('button'));
    await waitFor(() => {
      expect(app.url()).not.toContain('group=');
    }, FIND);
    expect(app.url()).not.toContain('env=');
  });

  it('says that no application has connected yet without a filter', async () => {
    answer([]);
    await openOverview();
    expect(screen.getByTestId('overview-empty')).toHaveTextContent('No application has connected');
  });

  it('shows the error state with a retry when the overview cannot be loaded', async () => {
    const user = userEvent.setup();
    stub.on(OVERVIEW, () => errorBody(503, 'storage_unavailable'));
    const { page } = await openOverview();
    expect(page).toHaveAttribute('data-state', 'error');
    expect(screen.getByTestId('overview-error')).toBeInTheDocument();
    answer();
    const [retry] = within(screen.getByTestId('overview-error')).getAllByRole('button');
    if (retry === undefined) {
      throw new Error('no retry button');
    }
    await user.click(retry);
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'ready');
    }, FIND);
  });

  it('says when the response is cut at 500 applications', async () => {
    answer(apps(), {}, { truncated: true });
    await openOverview();
    expect(screen.getByTestId('overview-truncated')).toHaveTextContent('first 5 applications');
  });
});

describe('overview: card and table views (05 §2.10 "카드/표 전환")', () => {
  it('switches to cards and remembers the choice in this browser', async () => {
    const user = userEvent.setup();
    answer();
    const first = await openOverview();
    await user.click(screen.getByTestId('overview-view-cards'));
    expect(first.page).toHaveAttribute('data-view', 'cards');
    const cards = screen.getByTestId('overview-cards');
    // Same order, same pinning, same cells.
    expect(
      [...cards.querySelectorAll('[data-testid^="overview-card-"]')].map((card) =>
        card.getAttribute('data-app-id'),
      ),
    ).toEqual(['2', '3', '4', '5', '1']);
    expect(screen.getByTestId('overview-card-2')).toHaveAttribute('data-pinned', 'true');
    expect(screen.getByTestId('overview-cell-tps-1')).toHaveAttribute('data-value', '12.5');
    expect(screen.getByTestId('overview-card-1').getAttribute('href')).toMatch(
      /^\/apps\/1\/dashboard/,
    );
    expect(screen.queryByTestId('overview-table')).not.toBeInTheDocument();
    expect(localStorage.getItem('argos.ui.overviewView')).toBe('cards');
    // A new page (reload) opens in cards.
    first.app.unmount();
    const second = await openOverview();
    expect(second.page).toHaveAttribute('data-view', 'cards');
    await user.click(screen.getByTestId('overview-view-table'));
    expect(screen.getByTestId('overview-table')).toBeInTheDocument();
    expect(localStorage.getItem('argos.ui.overviewView')).toBe('table');
  });

  it('falls back to the table and still switches when storage throws', async () => {
    const user = userEvent.setup();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    answer();
    const { page } = await openOverview();
    expect(page).toHaveAttribute('data-view', 'table');
    await user.click(screen.getByTestId('overview-view-cards'));
    expect(page).toHaveAttribute('data-view', 'cards');
    expect(screen.getByTestId('overview-cards')).toBeInTheDocument();
  });
});

describe('overview: panels (05 §2.10, ADR-0010 C-212 (2))', () => {
  it('T-UI-007 says "Not available" for alerts and unmeasured server values, and requests no alert list', async () => {
    answer(apps(), { alerts_firing_total: null, server: { ingest_lag_s: 0, sessions: 7 } });
    await openOverview();
    const firing = screen.getByTestId('overview-firing-panel');
    expect(firing).toHaveAttribute('data-state', 'unavailable');
    expect(firing).toHaveTextContent('Not available');
    expect(firing).not.toHaveTextContent('No alerts are firing.');
    // The shell's alert badge asks once per environment; the panel adds no request of its own.
    const panelCalls = stub.calls(ALERTS).length;
    expect(within(firing).getByTestId('overview-firing-history')).toHaveAttribute(
      'href',
      '/alerts?state=firing',
    );
    expect(screen.getByTestId('overview-server-ingest-lag')).toHaveAttribute('data-value', '0');
    expect(screen.getByTestId('overview-server-ingest-lag')).not.toHaveTextContent('Not available');
    expect(screen.getByTestId('overview-server-sessions')).toHaveAttribute('data-value', '7');
    expect(screen.getByTestId('overview-server-sessions')).toHaveTextContent('7');
    for (const id of ['disk', 'channel-failures', 'cert-expiry']) {
      const item = screen.getByTestId(`overview-server-${id}`);
      expect(item).toHaveAttribute('data-value', 'null');
      expect(item).toHaveTextContent('Not available');
    }
    expect(stub.calls(ALERTS).length).toBe(panelCalls);
  });

  it('lists the active alerts of the engine (FIRING and ACKED, server rules too) critical first, as many as alerts_firing_total, with links to the history', async () => {
    answer(apps(), {
      alerts_firing_total: 3,
      server: { disk_usage_ratio: 0.91, channel_failures_24h: 2, cert_expiry_days: 12 },
    });
    signedIn(stub);
    // The alert engine's items (WO-25 schema, ADR-0010 C-212 (2)): uuid ids, `ts` = firing start,
    // app {id, name} or null (the server's own rule), target {type, id, name}, rule {id, name}.
    stub.on(ALERTS, (request) =>
      envelope(
        request.query.has('state')
          ? []
          : [
              {
                event_id: '0f9a3c1e-0000-4000-8000-000000000011',
                ts: '2026-11-23T09:00:00.000Z',
                app: { id: 4, name: 'payments' },
                env: 'prod',
                target: { type: 'app', id: null, name: null },
                rule: { id: '7d1c0000-0000-4000-8000-000000000001', name: 'p95 high' },
                severity: 'warn',
                state: 'ACKED',
                value: 3400,
                threshold: 3000,
                duration_s: 600,
                acked_by: 'operator',
                acked_at: '2026-11-23T09:05:00.000Z',
                deeplink: 'http://argos.example/apps/4/dashboard?alert=0f9a3c1e',
              },
              {
                event_id: '0f9a3c1e-0000-4000-8000-000000000012',
                ts: '2026-11-23T08:00:00.000Z',
                app: { id: 3, name: 'billing' },
                env: 'prod',
                target: { type: 'instance', id: '31', name: 'billing-1' },
                rule: { id: '7d1c0000-0000-4000-8000-000000000002', name: 'instance down' },
                severity: 'critical',
                state: 'FIRING',
                value: null,
                threshold: null,
                duration_s: 3600,
                deeplink: 'http://argos.example/apps/3/dashboard?alert=0f9a3c1e',
              },
              {
                event_id: '0f9a3c1e-0000-4000-8000-000000000013',
                ts: '2026-11-23T09:30:00.000Z',
                app: null,
                env: null,
                target: { type: 'server', id: null, name: 'ops-webhook' },
                rule: { id: '7d1c0000-0000-4000-8000-000000000003', name: 'channel failure' },
                severity: 'critical',
                state: 'FIRING',
                value: 3,
                threshold: 1,
                duration_s: 30,
                deeplink: 'http://argos.example/alerts',
              },
            ],
      ),
    );
    renderApp('/overview?env=prod');
    const panel = await screen.findByTestId('overview-firing-panel', {}, FIND);
    await waitFor(() => {
      expect(panel).toHaveAttribute('data-state', 'list');
    }, FIND);
    expect(panel).toHaveAttribute('data-total', '3');
    const rows = within(panel).getAllByTestId('overview-firing-row');
    // Every item validates: as many rows as alerts_firing_total, nothing counted as "more".
    expect(rows).toHaveLength(3);
    expect(within(panel).queryByTestId('overview-firing-more')).toBeNull();
    expect(rows.map((row) => row.getAttribute('data-alert-id'))).toEqual([
      '0f9a3c1e-0000-4000-8000-000000000013',
      '0f9a3c1e-0000-4000-8000-000000000012',
      '0f9a3c1e-0000-4000-8000-000000000011',
    ]);
    expect(rows[1]).toHaveTextContent('billing');
    expect(rows[1]).toHaveTextContent('instance down');
    expect(rows[1]).toHaveTextContent('billing-1');
    // The server's own rule has no application: the rule and its target, no app name.
    expect(rows[0]).toHaveTextContent('channel failure');
    expect(rows[0]).toHaveTextContent('ops-webhook');
    // An acknowledged alert stays listed with its state.
    expect(within(rows[2] as HTMLElement).getByTestId('alert-state')).toHaveAttribute(
      'data-state',
      'ACKED',
    );
    const [first] = rows;
    if (first === undefined) {
      throw new Error('no firing row');
    }
    const link = within(first).getByRole('link').getAttribute('href') ?? '';
    const params = new URLSearchParams(link.split('?')[1]);
    expect(link.startsWith('/alerts?')).toBe(true);
    expect(params.get('state')).toBe('firing');
    expect(params.get('alert')).toBe('0f9a3c1e-0000-4000-8000-000000000013');
    expect(params.get('env')).toBe('prod');
    expect(within(panel).getByTestId('overview-firing-summary')).toHaveTextContent(
      'Critical 2 · warning 1',
    );
    // The panel asks without a state filter (FIRING and ACKED, the list alerts_firing_total
    // counts); the shell's badge asks state=FIRING on its own.
    const panelCalls = stub.calls(ALERTS).filter((call) => !call.query.has('state'));
    expect(panelCalls.length).toBeGreaterThan(0);
    expect(panelCalls.every((call) => call.query.get('env') === 'prod')).toBe(true);
    // Server values: channel failures of the last 24 h are a count that warns; disk and certificate warn.
    const failures = screen.getByTestId('overview-server-channel-failures');
    expect(failures).toHaveAttribute('data-value', '2');
    expect(failures).toHaveAttribute('data-warn', 'true');
    expect(failures).toHaveTextContent('2');
    expect(screen.getByTestId('overview-server-disk')).toHaveAttribute('data-warn', 'true');
    expect(screen.getByTestId('overview-server-cert-expiry')).toHaveTextContent('12 days');
  });

  it('T-UI-007 zero active alerts is "no alerts" and zero channel failures is the value 0', async () => {
    answer(apps(), { alerts_firing_total: 0, server: { channel_failures_24h: 0 } });
    signedIn(stub);
    stub.on(ALERTS, () => envelope([]));
    renderApp('/overview');
    const panel = await screen.findByTestId('overview-firing-panel', {}, FIND);
    await waitFor(() => {
      expect(panel).toHaveAttribute('data-state', 'empty');
    }, FIND);
    expect(panel).toHaveAttribute('data-total', '0');
    expect(screen.getByTestId('overview-firing-empty')).toHaveTextContent('No alerts are firing.');
    const failures = screen.getByTestId('overview-server-channel-failures');
    expect(failures).toHaveAttribute('data-value', '0');
    expect(failures).not.toHaveAttribute('data-warn');
    expect(failures).toHaveTextContent('0');
  });

  it('T-E2E-alerts-2 opens an acknowledged row in the acknowledged history and keeps the rule name whole (C-226 (8), C-227 (4))', async () => {
    const longRule = 'E2E seed error rate of the checkout flow on every order service instance';
    answer(apps(), { alerts_firing_total: 2 });
    signedIn(stub);
    stub.on(ALERTS, (request) =>
      envelope(
        request.query.has('state')
          ? []
          : [
              {
                event_id: '0f9a3c1e-0000-4000-8000-000000000021',
                ts: '2026-11-23T09:00:00.000Z',
                app: { id: 4, name: 'payments' },
                env: 'prod',
                target: { type: 'app', id: null, name: null },
                rule: { id: '7d1c0000-0000-4000-8000-000000000021', name: 'p95 high' },
                severity: 'warn',
                state: 'ACKED',
                deeplink: 'http://argos.example/apps/4/dashboard?alert=0f9a3c1e',
              },
              {
                event_id: '0f9a3c1e-0000-4000-8000-000000000022',
                ts: '2026-11-23T09:10:00.000Z',
                app: { id: 3, name: 'billing-with-a-long-application-name' },
                env: 'prod',
                target: { type: 'instance', id: '31', name: 'billing-1.prod.internal.example' },
                rule: { id: '7d1c0000-0000-4000-8000-000000000022', name: longRule },
                severity: 'warn',
                state: 'FIRING',
                deeplink: 'http://argos.example/apps/3/dashboard?alert=0f9a3c1e',
              },
            ],
      ),
    );
    renderApp('/overview?env=prod');
    const panel = await screen.findByTestId('overview-firing-panel', {}, FIND);
    await waitFor(() => {
      expect(panel).toHaveAttribute('data-state', 'list');
    }, FIND);
    const linkOf = (id: string): URLSearchParams => {
      const row = panel.querySelector(`[data-alert-id="${id}"]`);
      const href = row?.querySelector('a')?.getAttribute('href') ?? '';
      expect(href.startsWith('/alerts?')).toBe(true);
      return new URLSearchParams(href.split('?')[1]);
    };
    const acked = linkOf('0f9a3c1e-0000-4000-8000-000000000021');
    expect(acked.get('state')).toBe('acked');
    expect(acked.get('alert')).toBe('0f9a3c1e-0000-4000-8000-000000000021');
    const firing = linkOf('0f9a3c1e-0000-4000-8000-000000000022');
    expect(firing.get('state')).toBe('firing');
    expect(firing.get('alert')).toBe('0f9a3c1e-0000-4000-8000-000000000022');
    // The panel link (all firing alerts) stays the firing list.
    expect(
      new URLSearchParams(
        within(panel).getByTestId('overview-firing-history').getAttribute('href')?.split('?')[1],
      ).get('state'),
    ).toBe('firing');
    // The rule name has its own line and its full text (shared alert row, C-227 (4)).
    const rule = within(
      panel.querySelector('[data-alert-id="0f9a3c1e-0000-4000-8000-000000000022"]') as HTMLElement,
    ).getByTestId('alert-row-rule');
    expect(rule).toHaveTextContent(longRule);
    expect(rule).toHaveAttribute('title', longRule);
  });
});

describe('overview: collected strings (T-UI-005, SEC-61)', () => {
  it('T-UI-005 renders XSS payloads in names, environments, groups and versions as text', async () => {
    const rows = XSS_PAYLOADS.map((payload, index) =>
      overviewApp(100 + index, payload, {
        env: payload,
        group: payload,
        status: index % 2 === 0 ? 'UP' : 'DOWN',
        last_deploy: { ts: '2026-11-22T10:00:00.000Z', version: untrusted(payload) },
      }),
    );
    answer(rows);
    const user = userEvent.setup();
    await openOverview('', 'en', () => {
      stub.on('GET /api/v1/apps', () =>
        envelope(
          XSS_PAYLOADS.map((payload, index) =>
            appBody(100 + index, payload, 'prod', { group: payload }),
          ),
        ),
      );
    });
    const page = screen.getByTestId('overview-page');
    for (const [index, payload] of XSS_PAYLOADS.entries()) {
      const row = screen.getByTestId(`overview-row-${String(100 + index)}`);
      // SafeText shows control characters visibly; the payload text itself is never markup.
      const visible = payload
        .replaceAll(String.fromCharCode(0), '')
        .replaceAll(String.fromCharCode(0x202e), '');
      expect(row.textContent).toContain(visible.slice(-6));
      expect(
        within(row).getByTestId(`overview-deploy-version-${String(100 + index)}`).textContent,
      ).toContain(visible.slice(0, 6));
    }
    expect(findInjectedMarkup(page)).toEqual([]);
    // Cards show the group too.
    await user.click(screen.getByTestId('overview-view-cards'));
    expect(screen.getByTestId('overview-card-100').textContent).toContain(
      XSS_PAYLOADS[0]?.slice(0, 8) ?? 'missing',
    );
    expect(findInjectedMarkup(page)).toEqual([]);
    // The group list offers only names the URL can carry (OverviewQ group pattern): none of these.
    const select = screen.getByTestId('overview-group-select');
    expect(findInjectedMarkup(select)).toEqual([]);
    expect([...select.querySelectorAll('option')].map((node) => node.textContent)).toEqual([
      'All groups',
    ]);
    expect(xssFired()).toBe(false);
  });
});

describe('overview: live (docs/14 §5.2 overview, ADR-0010 C-213 (3))', () => {
  it('merges overview pushes into the rows on the shell subscription without a REST refetch', async () => {
    answer();
    const { page } = await openOverview('live=1&env=prod');
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    await waitFor(() => {
      expect(socket.framesOf('subscribe').some((f) => f.topic === 'overview')).toBe(true);
    }, FIND);
    const subscriptions = socket.framesOf('subscribe').filter((f) => f.topic === 'overview');
    // The page and the shell share one server subscription with the shell's parameters.
    expect(subscriptions).toHaveLength(1);
    expect(subscriptions[0]?.params).toEqual({ env: 'prod' });
    act(() => {
      socket.ackAll();
    });
    const id = subscriptions[0]?.id;
    const restCalls = stub.calls(OVERVIEW).length;
    const t1 = '2026-11-23T10:00:05.000Z';
    // An invalid payload is ignored as a whole.
    act(() => {
      socket.serverSend({ op: 'push', id, topic: 'overview', t: t1, data: { apps: 'broken' } });
    });
    expect(page).not.toHaveAttribute('data-live');
    act(() => {
      socket.serverSend({
        op: 'push',
        id,
        topic: 'overview',
        t: t1,
        data: {
          apps: [
            {
              app_id: 1,
              status: 'UP',
              tps: 99.5,
              p95_ms: 420,
              error_rate: 0.25,
              active: 8,
              firing: null,
            },
            // The push counts connected instances only: the REST DOWN stays (worse of the two).
            { app_id: 2, status: 'UP', tps: 1, p95_ms: 10, error_rate: 0, active: 0, firing: null },
            {
              app_id: 5,
              status: 'WARN',
              tps: 2,
              p95_ms: 30,
              error_rate: 0,
              active: 1,
              firing: null,
            },
          ],
        },
      });
    });
    await waitFor(() => {
      expect(page).toHaveAttribute('data-live', t1);
    }, FIND);
    expect(screen.getByTestId('overview-cell-tps-1')).toHaveAttribute('data-value', '99.5');
    expect(screen.getByTestId('overview-cell-p95-1')).toHaveAttribute('data-value', '420');
    expect(screen.getByTestId('overview-cell-error-rate-1')).toHaveAttribute('data-value', '0.25');
    expect(screen.getByTestId('overview-cell-active-1')).toHaveAttribute('data-value', '8');
    expect(screen.getByTestId('overview-cell-firing-1')).toHaveAttribute(
      'data-state',
      'unavailable',
    );
    expect(screen.getByTestId('overview-row-2')).toHaveAttribute('data-status', 'DOWN');
    expect(screen.getByTestId('overview-cell-tps-2')).toHaveAttribute('data-value', '1');
    // A WARN push pins audit; an application absent from the push keeps its REST values.
    expect(screen.getByTestId('overview-row-5')).toHaveAttribute('data-pinned', 'true');
    expect(screen.getByTestId('overview-cell-tps-4')).toHaveAttribute('data-value', '1.5');
    expect(stub.calls(OVERVIEW).length).toBe(restCalls);
  });

  it('subscribes nothing when live is off', async () => {
    answer();
    await openOverview('from=now-1h');
    expect(sockets.sockets).toHaveLength(0);
  });

  it('kiosk shows the rows without the top bar and the menu (F-U21)', async () => {
    answer();
    await openOverview('kiosk=1');
    expect(screen.getByTestId('overview-row-2')).toBeInTheDocument();
    expect(screen.getByTestId('overview-firing-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('topbar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('side-menu')).not.toBeInTheDocument();
    expect(screen.getByTestId('kiosk-badge')).toBeInTheDocument();
  });
});

describe('overview: Korean (T-UI-006)', () => {
  it('renders the table headers and panels from the ko catalog', async () => {
    answer();
    await openOverview('', 'ko');
    const table = screen.getByTestId('overview-table');
    expect(table.textContent).not.toContain('overview:');
    expect(screen.getByTestId('overview-server-panel').textContent).not.toContain('overview:');
    expect(screen.getByTestId('overview-server-disk')).not.toHaveTextContent('Not available');
  });
});
