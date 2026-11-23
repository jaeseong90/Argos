// SPDX-License-Identifier: Apache-2.0
//
// The shell (docs/15 §3, §5, §10, §13; 05 §2.0): route tree with placeholders, top bar, side menu
// filtered by role, URL restore notice, global keys carried across screens, kind filter, global
// search, shortcuts, the shell's live subscription, kiosk layout, WebSocket 4401 and logout. The
// whole app runs with the HTTP boundary stubbed (test/apiStub.ts) and a fake WebSocket.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'F-U21' }, { type: 'req', description: 'SEC-44' }, { type: 'req', description: 'SEC-64' }
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  meBody,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';
import type { FakeSockets } from '../../test/fakeWebSocket';
import {
  appBody,
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
  signedOut,
} from '../../test/renderApp';
import { createQueryClient } from '../api/queryClient';
import { queryKeys } from '../api/queryKeys';
import { transactionDetailFixture } from '../features/transaction/data/testing/fixtures';
import { createI18n } from '../i18n';
import enShell from '../locales/en/shell.json';
import enTx from '../locales/en/tx.json';
import { useConnectionStore } from '../realtime/connectionStore';
import { useAuthStore } from '../state/authStore';
import { useUiStore } from '../state/uiStore';
import { parseUrl, XLogQ } from '../state/urlState';

import { shellLiveSubscription } from './liveStatus';
import { hrefWithState, screenForAppSwitch } from './navigation';
import { kioskApplies, screenById, SCREENS, screenPath } from './screens';
import { KioskLayout } from './Shell';
import { StaleBadge } from './TopBar';

let sockets: FakeSockets;
let stub: ApiStub;

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

const FIND = { timeout: 5000 };

async function shellAt(path: string, roles: string[] = ['viewer']) {
  signedIn(stub, { roles });
  const app = renderApp(path);
  await screen.findByTestId('app-shell', {}, FIND);
  return app;
}

describe('shell: route tree and placeholders (docs/15 §3.1)', () => {
  it('renders the shell: top bar, side menu and the placeholder of a later screen', async () => {
    // /alerts got its screen (WO-38); the administration screens are still placeholders (WO-39).
    await shellAt('/admin/apps', ['admin']);
    expect(screen.getByTestId('topbar')).toBeInTheDocument();
    expect(screen.getByTestId('side-menu')).toBeInTheDocument();
    const pending = await screen.findByTestId('route-pending', {}, FIND);
    expect(pending).toHaveAttribute('data-owner', 'WO-39');
    // Placeholders show no data: only the empty state with the next action.
    expect(within(pending).getByTestId('empty-state')).toBeInTheDocument();
    expect(screen.getByTestId('time-range-picker')).toBeInTheDocument();
    expect(screen.getByTestId('kind-filter')).toBeInTheDocument();
    expect(screen.getByTestId('global-search')).toBeInTheDocument();
    expect(screen.getByTestId('account-menu')).toBeInTheDocument();
  });

  // Screens with a component (`load`: /account, the WO-34 detail and trace) are not placeholders.
  it.each(SCREENS.filter((s) => s.load === undefined).map((s) => [s.id, s] as const))(
    'renders the placeholder of %s for an administrator',
    async (_id, def) => {
      const path = screenPath(def, 1).replace(/:[A-Za-z]+/g, '7');
      await shellAt(path, ['admin']);
      const pending = await screen.findByTestId('route-pending', {}, FIND);
      expect(pending).toHaveAttribute('data-screen', def.id);
      expect(pending).toHaveAttribute('data-owner', def.owner);
    },
  );

  it('opens the overview (WO-31) at /overview with the application selector disabled (docs/15 §3.2, C-189 (2))', async () => {
    const app = await shellAt('/overview');
    expect(await screen.findByTestId('overview-page', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('route-pending')).not.toBeInTheDocument();
    const selector = screen.getByTestId('app-select');
    expect(selector.querySelector('.ant-select-disabled')).not.toBeNull();
    // On an application route the selector is active again.
    act(() => {
      void app.router.navigate('/apps/1/active');
    });
    await waitFor(() => {
      expect(screen.getByTestId('app-select').querySelector('.ant-select-disabled')).toBeNull();
    }, FIND);
  });

  it('opens /account (built by WO-30) inside the shell', async () => {
    await shellAt('/account');
    expect(await screen.findByTestId('account-page', {}, FIND)).toBeInTheDocument();
  });

  it('shows a 403 state instead of a screen the role cannot open', async () => {
    await shellAt('/admin/users', ['viewer']);
    expect(await screen.findByTestId('route-forbidden', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('route-pending')).not.toBeInTheDocument();
  });

  it('says that an unknown application does not exist', async () => {
    stub.on('GET /api/v1/apps/99', () => errorBody(404, 'not_found'));
    await shellAt('/apps/99/dashboard');
    expect(await screen.findByTestId('app-not-found', {}, FIND)).toBeInTheDocument();
  });
});

describe('shell: side menu (docs/15 §3.3)', () => {
  it('lists the global screens a viewer may open and hides administration', async () => {
    await shellAt('/overview');
    const menu = screen.getByTestId('side-menu');
    expect(within(menu).getByTestId('side-menu-overview')).toBeInTheDocument();
    expect(within(menu).getByTestId('side-menu-alerts')).toBeInTheDocument();
    expect(within(menu).queryByTestId('side-menu-alert-rules')).not.toBeInTheDocument();
    expect(within(menu).queryByTestId('side-menu-admin-users')).not.toBeInTheDocument();
    // Application screens appear only on application routes.
    expect(within(menu).queryByTestId('side-menu-dashboard')).not.toBeInTheDocument();
  });

  it('lists administration for an administrator and the application screens on app routes', async () => {
    await shellAt('/apps/1/dashboard', ['admin']);
    const menu = screen.getByTestId('side-menu');
    for (const id of ['dashboard', 'xlog', 'active', 'services', 'sql', 'external', 'errors']) {
      expect(within(menu).getByTestId(`side-menu-${id}`)).toBeInTheDocument();
    }
    expect(within(menu).getByTestId('side-menu-admin-users')).toBeInTheDocument();
    expect(within(menu).getByTestId('side-menu-admin-tokens')).toBeInTheDocument();
  });

  it('remembers the collapsed state in argos.ui.sidebar', async () => {
    const user = userEvent.setup();
    await shellAt('/overview');
    await user.click(screen.getByTestId('side-menu-toggle'));
    expect(screen.getByTestId('side-menu')).toHaveAttribute('data-collapsed', 'true');
    expect(localStorage.getItem('argos.ui.sidebar')).toBe('collapsed');
  });

  it('carries time, live, kind and env to other screens and drops sel, color and tab (docs/15 §5)', async () => {
    await shellAt(
      '/apps/1/xlog?from=now-1h&live=1&kind=BATCH&sel=ms:0-100,ts:1000-2000&color=service',
    );
    const link = screen.getByTestId('side-menu-dashboard');
    expect(link).toHaveAttribute('href', '/apps/1/dashboard?from=now-1h&live=1&kind=BATCH');
    expect(screen.getByTestId('side-menu-overview')).toHaveAttribute(
      'href',
      '/overview?from=now-1h&live=1&kind=BATCH',
    );
  });
});

describe('T-UI-003 shell and the URL state (docs/15 §4.2, SEC-64)', () => {
  it('T-SEC-64 rewrites a tampered link to defaults item by item and shows one notice', async () => {
    const app = await shellAt(
      '/apps/1/dashboard?from=yesterday-ish&to=2000-13-45T99:99:99Z&kind=DROP%20TABLE&live=yes&f.instance=7&f.Bad-Field=x',
    );
    const notices = await screen.findAllByTestId('url-restore-notice', {}, FIND);
    expect(notices).toHaveLength(1);
    expect(app.url()).toBe('/apps/1/dashboard?f.instance=7');
    expect(screen.getAllByTestId('filter-chip')).toHaveLength(1);
    // Dismissing removes it.
    await userEvent.click(within(notices[0] as HTMLElement).getByRole('button'));
    expect(screen.queryByTestId('url-restore-notice')).not.toBeInTheDocument();
  });

  it('T-SEC-64 the notice belongs to the opened link and ends on another screen', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/apps/1/dashboard?kind=nope');
    expect(await screen.findByTestId('url-restore-notice', {}, FIND)).toBeInTheDocument();
    await user.click(screen.getByTestId('side-menu-xlog'));
    // The XLog route and its parts load lazily: under a loaded full run that takes more than the
    // default second (WO-33 review r1-new-4).
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog');
    }, FIND);
    // The notice is hidden in the render that sees the new path; the store is cleared in the
    // passive effect of that commit, which a loaded run may flush after the DOM check passed.
    await waitFor(() => {
      expect(screen.queryByTestId('url-restore-notice')).not.toBeInTheDocument();
      expect(useUiStore.getState().restoreNotice).toBeNull();
    }, FIND);
  });

  it('T-SEC-64 the start page redirect also reports invalid items', async () => {
    const app = await shellAt('/?kind=nope&from=now-1h');
    expect(await screen.findByTestId('url-restore-notice', {}, FIND)).toBeInTheDocument();
    expect(app.url()).toBe('/overview?from=now-1h');
  });

  it('disables the batch kind for an application without batch jobs in the last 7 days (C-205 (4))', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/apps/3', () =>
      envelope(appBody(3, 'billing', 'prod', { kinds: ['HTTP'] })),
    );
    stub.on('GET /api/v1/apps/1', () =>
      envelope(appBody(1, 'order-api', 'prod', { kinds: ['BATCH', 'HTTP'] })),
    );
    const app = renderApp('/apps/3/dashboard?from=now-1h');
    await screen.findByTestId('app-shell', {}, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('kind-filter-batch')).toHaveAttribute('aria-disabled', 'true');
    }, FIND);
    fireEvent.click(screen.getByTestId('kind-filter-batch'));
    expect(app.url()).toBe('/apps/3/dashboard?from=now-1h');
    // An application with batch jobs (and one whose kinds are unknown) keeps the option.
    act(() => {
      void app.router.navigate('/apps/1/dashboard?from=now-1h');
    });
    await waitFor(() => {
      expect(screen.getByTestId('kind-filter-batch')).not.toHaveAttribute('aria-disabled');
    }, FIND);
    act(() => {
      void app.router.navigate('/apps/2/dashboard?from=now-1h');
    });
    await waitFor(() => {
      expect(screen.getByTestId('kind-filter-batch')).not.toHaveAttribute('aria-disabled');
    }, FIND);
  });

  it('writes the kind filter to the URL as a new history entry', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/overview?from=now-1h');
    await user.click(screen.getByTestId('kind-filter-batch'));
    await waitFor(() => {
      expect(app.url()).toBe('/overview?from=now-1h&kind=BATCH');
    });
    act(() => {
      void app.router.navigate(-1);
    });
    await waitFor(() => {
      expect(app.url()).toBe('/overview?from=now-1h');
    });
  });

  it('removes a filter chip and keeps the time range (deep link 3)', async () => {
    const user = userEvent.setup();
    const app = await shellAt(
      '/apps/1/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:15:00.000Z&f.instance=7&f.status=!200',
    );
    const chips = screen.getAllByTestId('filter-chip');
    expect(chips).toHaveLength(2);
    await user.click(within(chips[0] as HTMLElement).getByTestId('filter-chip-remove'));
    await waitFor(() => {
      expect(app.url()).toBe(
        '/apps/1/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:15:00.000Z&f.status=!200',
      );
    });
  });

  it('turns live off for an absolute deep link (deep link 2)', async () => {
    const app = await shellAt(
      '/apps/1/dashboard?from=2026-11-14T00:00:00Z&to=2026-11-14T00:15:00Z&live=1',
    );
    await waitFor(() => {
      expect(app.url()).toBe(
        '/apps/1/dashboard?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:15:00.000Z',
      );
    });
    expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'false');
  });
});

describe('shell: global search and shortcuts (docs/15 §3.2, §13)', () => {
  it('Ctrl+K focuses the search; Enter opens /search?q= with the global keys', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/overview?from=now-6h&kind=ALL');
    await user.keyboard('{Control>}k{/Control}');
    const input = screen.getByTestId('global-search');
    expect(input).toHaveFocus();
    await user.type(input, 'GET /api/orders{Enter}');
    await waitFor(() => {
      expect(app.url()).toBe('/search?from=now-6h&kind=ALL&q=GET%20/api/orders');
    });
  });

  it('[ moves the range back and turns live off; n returns to now; ? opens the help', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/apps/1/dashboard?from=now-1h&live=1');
    fireEvent.keyDown(window, { key: '[' });
    await waitFor(() => {
      expect(parseUrl(XLogQ, app.url().split('?')[1] ?? '').state.time.kind).toBe('absolute');
    });
    expect(app.url()).not.toContain('live=1');
    fireEvent.keyDown(window, { key: 'n' });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/dashboard?from=now-1h');
    });
    await user.keyboard('?');
    expect(await screen.findByTestId('shortcut-help', {}, FIND)).toBeInTheDocument();
  });

  it('g x opens the XLog of the current application, carrying the global keys', async () => {
    const app = await shellAt('/apps/1/dashboard?kind=BATCH');
    fireEvent.keyDown(window, { key: 'g' });
    fireEvent.keyDown(window, { key: 'x' });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?kind=BATCH');
    });
  });

  it('t 3 selects the last hour and keeps live (docs/15 §5); the help lists it', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/apps/1/dashboard?from=now-15m&live=1');
    fireEvent.keyDown(window, { key: 't' });
    fireEvent.keyDown(window, { key: '3' });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/dashboard?from=now-1h&live=1');
    });
    await user.keyboard('?');
    const help = await screen.findByTestId('shortcut-help', {}, FIND);
    expect(within(help).getByText('t 1–9')).toBeInTheDocument();
    expect(
      within(help).getByText('Choose a preset range (1 = last 5 minutes … 9 = yesterday)'),
    ).toBeInTheDocument();
  });

  it('T-UI-002 a screen without live mode: a carried live turns off (l, toggle), "now" does not restore it', async () => {
    const user = userEvent.setup();
    // live=1 carried from a live screen (docs/15 §5 "앱·화면 전환: live 유지").
    const app = await shellAt('/apps/1/stats/services?from=now-1h&live=1');
    expect(screen.getByTestId('live-toggle')).toBeEnabled();
    expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.keyDown(window, { key: 'l' });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/stats/services?from=now-1h');
    });
    await waitFor(() => {
      expect(screen.getByTestId('live-toggle')).toBeDisabled();
    });
    // A "resume live" offer left over from a drag on a live screen is not shown here.
    act(() => {
      useUiStore.setState({ resumeAvailable: true });
    });
    expect(screen.queryByTestId('live-resume')).not.toBeInTheDocument();
    // l cannot turn it on again here, and "now" does not restore the remembered live state.
    localStorage.setItem('argos.ui.live', '1');
    fireEvent.keyDown(window, { key: 'l' });
    fireEvent.keyDown(window, { key: '[' });
    await waitFor(() => {
      expect(parseUrl(XLogQ, app.url().split('?')[1] ?? '').state.time.kind).toBe('absolute');
    });
    fireEvent.keyDown(window, { key: 'n' });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/stats/services?from=now-1h');
    });
    expect(localStorage.getItem('argos.ui.live')).toBe('1');
    // The toggle turns a carried live off as well.
    await act(async () => {
      await app.router.navigate('/apps/1/stats/services?from=now-1h&live=1');
    });
    await waitFor(() => {
      expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'true');
    });
    await user.click(screen.getByTestId('live-toggle'));
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/stats/services?from=now-1h');
    });
  });
});

describe('T-SEC-44 shell realtime (docs/15 §3.4, §10)', () => {
  it('subscribes to the app counter while a live app screen is in live mode', async () => {
    await shellAt('/apps/1/dashboard?live=1');
    await waitFor(() => {
      expect(sockets.sockets).toHaveLength(1);
    });
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    expect(socket.framesOf('subscribe')).toEqual([
      { op: 'subscribe', id: 's1', topic: 'counter', params: { app: 1 } },
    ]);
  });

  it('opens no socket when live is off', async () => {
    await shellAt('/apps/1/dashboard');
    // The dashboard screen (WO-32) renders in place of the placeholder; not live, no socket.
    await screen.findByTestId('dashboard-page', {}, FIND);
    expect(sockets.sockets).toHaveLength(0);
  });

  it('close 4401 sends the user to sign-in with the current URL and does not reconnect', async () => {
    const app = await shellAt('/apps/1/dashboard?live=1');
    await waitFor(() => {
      expect(sockets.sockets).toHaveLength(1);
    });
    stub.on('GET /api/v1/setup/status', () =>
      envelope({ needs_setup: false, server_version: '1' }),
    );
    act(() => {
      sockets.last().serverOpen();
      sockets.last().serverClose(4401);
    });
    await screen.findByTestId('login-form', {}, FIND);
    expect(app.url()).toBe(`/login?next=${encodeURIComponent('/apps/1/dashboard?live=1')}`);
    expect(screen.getByTestId('login-session-ended')).toBeInTheDocument();
    expect(sockets.sockets).toHaveLength(1);
  });

  it('logout closes the WebSocket before it ends the session, then opens the sign-in page', async () => {
    const user = userEvent.setup();
    let socketClosedBeforeLogout: boolean | null = null;
    stub.on('POST /api/v1/auth/logout', (request) => {
      socketClosedBeforeLogout = sockets.last().closedBy !== null;
      expect(request.headers.get('X-Argos-CSRF')).toBe('csrf-token-1');
      return noContent();
    });
    stub.on('GET /api/v1/setup/status', () =>
      envelope({ needs_setup: false, server_version: '1' }),
    );
    const app = await shellAt('/apps/1/dashboard?live=1');
    await waitFor(() => {
      expect(sockets.sockets).toHaveLength(1);
    });
    act(() => {
      sockets.last().serverOpen();
    });
    await user.click(screen.getByTestId('account-menu'));
    await user.click(await screen.findByTestId('logout'));
    await screen.findByTestId('login-form', {}, FIND);
    expect(socketClosedBeforeLogout).toBe(true);
    expect(app.url()).toBe('/login');
    expect(stub.calls('POST /api/v1/auth/logout')).toHaveLength(1);
    expect(sockets.sockets).toHaveLength(1);
  });

  it('kiosk mode hides the top bar and menu and shows the live badge', async () => {
    const soon = new Date(Date.now() + 5 * 60_000).toISOString();
    signedIn(stub, { session_expires_at: soon });
    renderApp('/overview?kiosk=1');
    expect(await screen.findByTestId('kiosk-badge', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('topbar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('side-menu')).not.toBeInTheDocument();
    // Kiosk keeps the overview topic for the "updated N s ago" badge.
    await waitFor(() => {
      expect(sockets.sockets).toHaveLength(1);
    });
    // The session ends within 10 minutes: the badge warns (docs/15 §3.4, §18 #7).
    expect(await screen.findByTestId('kiosk-expiry', {}, FIND)).toBeInTheDocument();
  });

  it('the kiosk badge names the environment and the application of an app screen', async () => {
    signedIn(stub);
    renderApp('/apps/2/dashboard?kiosk=1');
    expect(await screen.findByTestId('kiosk-app', {}, FIND)).toHaveTextContent('order-api');
    expect(screen.getByTestId('kiosk-env')).toHaveTextContent('stage');
    // No expiry warning while the session lasts longer than 10 minutes.
    expect(screen.queryByTestId('kiosk-expiry')).not.toBeInTheDocument();
  });

  it('kiosk mode without a session shows the full-screen notice instead of a login form', async () => {
    signedOut(stub);
    renderApp('/overview?kiosk=1');
    expect(await screen.findByTestId('session-expired', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('login-form')).not.toBeInTheDocument();
  });
});

describe('shell: top bar data (docs/15 §3.2)', () => {
  it('shows the count of firing alerts and links to /alerts?state=firing', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/overview/alerts', (request) => {
      expect(request.query.get('state')).toBe('FIRING');
      return envelope([{ id: 'a' }, { id: 'b' }]);
    });
    renderApp('/overview');
    const badge = await screen.findByTestId('alerts-badge', {}, FIND);
    await waitFor(() => {
      expect(badge).toHaveAttribute('data-count', '2');
    });
    expect(badge).toHaveAttribute('href', '/alerts?state=firing');
  });

  it('wraps the badge and its number bubble in the masked badge area (ADR-0010 C-227 (2))', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/overview/alerts', () => envelope([{ id: 'a' }, { id: 'b' }, { id: 'c' }]));
    renderApp('/overview');
    const area = await screen.findByTestId('alerts-badge-area', {}, FIND);
    const badge = within(area).getByTestId('alerts-badge');
    await waitFor(() => {
      expect(badge).toHaveAttribute('data-count', '3');
    });
    const bubble = area.querySelector('.alerts-badge__count');
    expect(bubble).not.toBeNull();
    expect(bubble).toHaveTextContent('3');
    expect(area.contains(bubble)).toBe(true);
    // The area's box reaches over the bubble (shell.css: top -2px, right -4px of the link)
    // without moving the link: padding on those sides, cancelled by the same negative margin.
    expect(area.style.paddingTop).toBe('2px');
    expect(area.style.paddingRight).toBe('4px');
    expect(area.style.marginTop).toBe('-2px');
    expect(area.style.marginRight).toBe('-4px');
  });

  it('hides the alerts badge when the API is missing', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/overview/alerts', () => errorBody(404, 'not_found'));
    renderApp('/overview');
    await screen.findByTestId('app-shell', {}, FIND);
    await waitFor(() => {
      expect(screen.queryByTestId('alerts-badge')).not.toBeInTheDocument();
    });
    expect(screen.queryByTestId('alerts-badge-area')).not.toBeInTheDocument();
  });

  it('shows the role in the account menu', async () => {
    const user = userEvent.setup();
    await shellAt('/overview', ['operator']);
    await user.click(screen.getByTestId('account-menu'));
    expect(await screen.findByTestId('account-menu-role', {}, FIND)).toHaveTextContent('Operator');
  });
});

describe('shell: /tx/:txId alias (ADR-0010 C-166)', () => {
  it('looks up the application and replaces the URL with the app-scoped route', async () => {
    // A complete detail (the detail screen renders it after the redirect, WO-34).
    stub.on('GET /api/v1/transactions/18446744073709551615', () =>
      envelope(
        transactionDetailFixture({
          tx_id: '18446744073709551615',
          app: { ...transactionDetailFixture().app, app_id: 3 },
          ts: '2026-11-14T00:00:01.000Z',
        }),
      ),
    );
    const app = await shellAt('/tx/18446744073709551615?tab=sql');
    await waitFor(() => {
      expect(app.url()).toBe(
        '/apps/3/transactions/18446744073709551615?ts=2026-11-14T00:00:01.000Z&tab=sql',
      );
    });
  });

  it('guides to the list or a time hint when the transaction is not near its id time', async () => {
    stub.on('GET /api/v1/transactions/42', () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'clock_skew_possible' }] }),
    );
    await shellAt('/tx/42');
    expect(await screen.findByTestId('transaction-not-found', {}, FIND)).toHaveTextContent(
      enShell.transactionLink.notFoundDescription,
    );
  });

  it('with a ts hint the not-found text does not repeat the ts advice (C-50)', async () => {
    stub.on('GET /api/v1/transactions/42', () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'not_found' }] }),
    );
    await shellAt('/tx/42?ts=2026-11-14T00:00:01.000Z');
    expect(await screen.findByTestId('transaction-not-found', {}, FIND)).toHaveTextContent(
      enTx.notFound.hinted,
    );
  });
});

describe('navigation helpers (docs/15 §5 "앱·화면 전환")', () => {
  it('opens the same screen in another application, or the list of a detail screen', () => {
    expect(screenForAppSwitch(screenById('xlog')).id).toBe('xlog');
    expect(screenForAppSwitch(screenById('transaction')).id).toBe('xlog');
    expect(screenForAppSwitch(screenById('instance')).id).toBe('instances');
    expect(screenForAppSwitch(screenById('overview')).id).toBe('dashboard');
    expect(screenForAppSwitch(null).id).toBe('dashboard');
  });

  it('builds links with the carried keys only', () => {
    const state = parseUrl(
      XLogQ,
      'from=now-1h&env=prod&live=1&kind=BATCH&kiosk=1&f.service=1&sel=ms:0-1,ts:1-2&color=service&sort=a:asc',
    ).state;
    expect(hrefWithState('/apps/2/xlog', state)).toBe(
      '/apps/2/xlog?from=now-1h&env=prod&live=1&kind=BATCH&kiosk=1',
    );
  });

  it('subscribes the shell to counter on live app screens and to overview on the overview', () => {
    const dashboard = screenById('dashboard');
    expect(
      shellLiveSubscription({ screen: dashboard, appId: 3, live: true, kiosk: false }),
    ).toEqual({ topic: 'counter', params: { app: 3 } });
    expect(
      shellLiveSubscription({ screen: dashboard, appId: 3, live: false, kiosk: false }),
    ).toBeNull();
    expect(
      shellLiveSubscription({
        screen: screenById('overview'),
        appId: null,
        live: false,
        kiosk: true,
        env: 'prod',
      }),
    ).toEqual({ topic: 'overview', params: { env: 'prod' } });
    expect(
      shellLiveSubscription({ screen: screenById('services'), appId: 3, live: true, kiosk: false }),
    ).toBeNull();
  });
});

describe('shell: document title per screen (WCAG 2.4.2)', () => {
  it('titles each screen, follows navigation and the language, and falls back on unmount', async () => {
    const user = userEvent.setup();
    const app = await shellAt('/overview');
    await waitFor(() => {
      expect(document.title).toBe('Overview · Argos APM');
    });
    await user.click(screen.getByTestId('side-menu-alerts'));
    await waitFor(() => {
      expect(document.title).toBe('Alert history · Argos APM');
    });
    await user.click(screen.getByTestId('account-menu'));
    await user.selectOptions(await screen.findByTestId('language-select', {}, FIND), 'ko');
    await waitFor(() => {
      expect(document.title).toBe('알림 이력 · Argos APM');
    });
    act(() => {
      void app.router.navigate('/apps/1/dashboard');
    });
    await waitFor(() => {
      expect(document.title).toBe('대시보드 · Argos APM');
    });
    app.unmount();
    expect(document.title).toBe('Argos APM');
  });

  it('titles the sign-in page and follows its language buttons', async () => {
    signedOut(stub);
    renderApp('/login');
    await screen.findByTestId('login-form', {}, FIND);
    await waitFor(() => {
      expect(document.title).toBe('Sign in · Argos APM');
    });
    const korean = screen.getByTestId('login-language').querySelector('button[lang="ko"]');
    expect(korean).not.toBeNull();
    await userEvent.click(korean as HTMLElement);
    await waitFor(() => {
      expect(document.title).toBe('로그인 · Argos APM');
    });
  });

  it('titles the setup page', async () => {
    signedOut(stub);
    renderApp('/setup');
    await screen.findByTestId('setup-not-found', {}, FIND);
    await waitFor(() => {
      expect(document.title).toBe('Setup · Argos APM');
    });
  });

  it('titles the account page, a refused screen and an unknown application', async () => {
    const account = await shellAt('/account');
    await screen.findByTestId('account-page', {}, FIND);
    await waitFor(() => {
      expect(document.title).toBe('My account · Argos APM');
    });
    account.unmount();
    const refused = await shellAt('/admin/users', ['viewer']);
    await screen.findByTestId('route-forbidden', {}, FIND);
    await waitFor(() => {
      expect(document.title).toBe('Users · Argos APM');
    });
    refused.unmount();
    stub.on('GET /api/v1/apps/99', () => errorBody(404, 'not_found'));
    await shellAt('/apps/99/dashboard');
    await screen.findByTestId('app-not-found', {}, FIND);
    await waitFor(() => {
      expect(document.title).toBe('Page not found · Argos APM');
    });
  });
});

describe('shell: live regions announce state changes only (WCAG 4.1.3)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('the stale badge counts every second while its live region keeps one fixed sentence', () => {
    vi.useFakeTimers();
    const now = Date.now();
    useConnectionStore.setState({
      subscriptions: 1,
      activeSince: now - 30_000,
      lastMessageAt: now - 12_000,
    });
    render(
      <I18nextProvider i18n={createI18n('en')}>
        <StaleBadge />
      </I18nextProvider>,
    );
    const badge = screen.getByTestId('ws-stale-badge');
    const status = screen.getByRole('status');
    expect(badge).not.toHaveAttribute('role');
    expect(within(badge).getByText('No updates for 12 s')).toHaveAttribute('aria-hidden', 'true');
    expect(status).toHaveTextContent(enShell.realtime.staleAnnounce);
    const announced = status.textContent;
    for (const seconds of [13, 14, 15]) {
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(badge).toHaveTextContent(`No updates for ${String(seconds)} s`);
      expect(status.textContent).toBe(announced);
    }
    // The next frame ends the silence: the badge goes and the region empties (nothing to read).
    act(() => {
      useConnectionStore.setState({ lastMessageAt: Date.now() });
    });
    expect(screen.queryByTestId('ws-stale-badge')).not.toBeInTheDocument();
    expect(status.textContent).toBe('');
  });

  it('the kiosk badge keeps its counter out of the live region and announces the expiry once', () => {
    vi.useFakeTimers();
    const now = Date.now();
    useAuthStore.setState({ status: 'authenticated', sessionExpiresAt: now + 5 * 60_000 });
    useConnectionStore.setState({
      subscriptions: 1,
      activeSince: now - 10_000,
      lastMessageAt: now - 2_000,
    });
    const queryClient = createQueryClient();
    // The applications are cached, so the badge renders without a request.
    queryClient.setQueryData(queryKeys.apps.list(), {
      data: [],
      meta: { request_id: 'req-test', generated_at: '2026-01-01T00:00:00.000Z' },
    });
    const router = createMemoryRouter([{ path: '/overview', element: <KioskLayout /> }], {
      initialEntries: ['/overview?kiosk=1'],
    });
    render(
      <I18nextProvider i18n={createI18n('en')}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </I18nextProvider>,
    );
    const badge = screen.getByTestId('kiosk-badge');
    expect(badge).not.toHaveAttribute('role');
    const status = within(badge).getByRole('status');
    expect(status).toHaveTextContent(
      enShell.kiosk.expiringAnnounce_other.replace('{{count}}', '10'),
    );
    expect(within(badge).getByText('Live · updated 2 seconds ago')).toHaveAttribute(
      'aria-hidden',
      'true',
    );
    expect(screen.getByTestId('kiosk-expiry')).toHaveTextContent(
      'The session expires in 5 minutes',
    );
    const announced = status.textContent;
    for (const seconds of [3, 4, 5]) {
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(badge).toHaveTextContent(`updated ${String(seconds)} seconds ago`);
      expect(status.textContent).toBe(announced);
    }
    // A minute later the visible warning counts down; the announced sentence stays the same.
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByTestId('kiosk-expiry')).toHaveTextContent(
      'The session expires in 4 minutes',
    );
    expect(status.textContent).toBe(announced);
  });

  it('the account menu trigger announces the dialog it opens', async () => {
    const user = userEvent.setup();
    await shellAt('/overview');
    const trigger = screen.getByTestId('account-menu');
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
    await user.click(trigger);
    const panel = await screen.findByRole('dialog', { name: 'Account' }, FIND);
    expect(panel.getAttribute('role')).toBe(trigger.getAttribute('aria-haspopup'));
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('shell: kiosk only on the overview, the dashboard and active (docs/15 §3.4)', () => {
  it('applies to exactly those screens', () => {
    for (const def of SCREENS) {
      expect(kioskApplies(true, def)).toBe(['overview', 'dashboard', 'active'].includes(def.id));
      expect(kioskApplies(false, def)).toBe(false);
    }
    expect(kioskApplies(true, null)).toBe(false);
  });

  it('keeps the top bar and the menu for ?kiosk=1 on a statistics screen', async () => {
    await shellAt('/apps/2/stats/services?kiosk=1');
    expect(screen.getByTestId('topbar')).toBeInTheDocument();
    expect(screen.getByTestId('side-menu')).toBeInTheDocument();
    expect(screen.getByTestId('app-shell')).not.toHaveAttribute('data-kiosk');
    expect(screen.queryByTestId('kiosk-badge')).not.toBeInTheDocument();
    // The key stays in the URL and is carried back to the kiosk screens.
    expect(screen.getByTestId('side-menu-dashboard')).toHaveAttribute(
      'href',
      '/apps/2/dashboard?kiosk=1',
    );
  });

  it('keeps the shell for ?kiosk=1 on an administration screen', async () => {
    await shellAt('/admin/users?kiosk=1', ['admin']);
    expect(screen.getByTestId('topbar')).toBeInTheDocument();
    expect(screen.getByTestId('side-menu')).toBeInTheDocument();
    expect(screen.queryByTestId('kiosk-badge')).not.toBeInTheDocument();
  });

  it('opens no kiosk subscription for ?kiosk=1 on XLog with live off', async () => {
    await shellAt('/apps/1/xlog?kiosk=1');
    // The XLog screen (WO-33) with live off: no topic, so no socket.
    await screen.findByTestId('xlog-page', {}, FIND);
    expect(screen.getByTestId('topbar')).toBeInTheDocument();
    expect(sockets.sockets).toHaveLength(0);
  });

  it('without a session, ?kiosk=1 on another screen leads to the sign-in page', async () => {
    signedOut(stub);
    const app = renderApp('/apps/2/stats/services?kiosk=1');
    await screen.findByTestId('login-form', {}, FIND);
    expect(screen.queryByTestId('session-expired')).not.toBeInTheDocument();
    expect(app.url()).toBe(`/login?next=${encodeURIComponent('/apps/2/stats/services?kiosk=1')}`);
  });
});

describe('shell: a session end drops the cached server data (C-13)', () => {
  it('after close 4401 and the sign-in of another user, the applications are read again', async () => {
    const user = userEvent.setup();
    await shellAt('/apps/1/dashboard?live=1');
    await waitFor(() => {
      expect(sockets.sockets).toHaveLength(1);
    });
    await waitFor(() => {
      expect(screen.getByTestId('app-select')).toHaveTextContent('order-api');
    });
    expect(stub.calls('GET /api/v1/apps')).toHaveLength(1);
    stub.on('GET /api/v1/setup/status', () =>
      envelope({ needs_setup: false, server_version: '1' }),
    );
    act(() => {
      sockets.last().serverOpen();
      sockets.last().serverClose(4401);
    });
    await screen.findByTestId('login-form', {}, FIND);
    // Another user signs in on the same tab and sees other applications.
    const bobApp = appBody(1, 'bob-api', 'prod');
    stub.on('POST /api/v1/auth/login', () =>
      envelope({
        user: { id: 'u-2', username: 'bob', roles: ['viewer'], permissions: [] },
        roles: ['viewer'],
        must_change_password: false,
        session_expires_at: '2099-01-01T00:00:00.000Z',
      }),
    );
    stub.on('GET /api/v1/auth/me', () => envelope(meBody({ username: 'bob' })));
    stub.on('GET /api/v1/apps', () => envelope([bobApp]));
    stub.on('GET /api/v1/apps/1', () => envelope(bobApp));
    await user.type(screen.getByTestId('login-username'), 'bob');
    await user.type(screen.getByTestId('login-password'), 'correct horse battery');
    await user.click(screen.getByTestId('login-submit'));
    await screen.findByTestId('app-shell', {}, FIND);
    await waitFor(() => {
      expect(screen.getByTestId('app-select')).toHaveTextContent('bob-api');
    });
    expect(screen.getByTestId('app-select')).not.toHaveTextContent('order-api');
    expect(stub.calls('GET /api/v1/apps')).toHaveLength(2);
  });
});
