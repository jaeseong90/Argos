// SPDX-License-Identifier: Apache-2.0
//
// Renders the whole UI (App + data router of src/routes/routeTree.tsx) in jsdom for route, shell
// and auth tests. The HTTP boundary is the API stub (test/apiStub.ts) and the WebSocket is a fake
// socket (test/fakeWebSocket.ts): everything above them (transport, generated client, router,
// loaders, stores) is the real code. Test-only module.
import { render, type RenderResult } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';

import { createQueryClient } from '../src/api/queryClient';
import { App } from '../src/app/App';
import { createRoutes } from '../src/app/router';
import { installDomStubs } from '../src/components/testing/dom';
import { installSessionHandlers } from '../src/features/auth/session';
import { createI18n, type SupportedLanguage } from '../src/i18n';
import { INITIAL_CONNECTION_STATE, useConnectionStore } from '../src/realtime/connectionStore';
import { setWsManager } from '../src/realtime/hooks';
import { WsManager } from '../src/realtime/WsManager';
import { useAuthStore } from '../src/state/authStore';
import { useUiStore } from '../src/state/uiStore';

import { envelope, errorBody, meBody, type ApiStub } from './apiStub';
import { fakeSockets, type FakeSockets } from './fakeWebSocket';

export const TEST_VERSION = '1.2.3-test';

/**
 * Imports the lazy route modules once (Ant Design, shell, auth pages), so that the first test of a
 * file does not spend its find timeout on module transformation. Call from beforeAll.
 */
export async function preloadRoutes(): Promise<void> {
  await Promise.all([
    import('../src/app/AntdRoot'),
    import('../src/app/RouteStatus'),
    import('../src/routes/AuthGate'),
    import('../src/routes/Shell'),
    import('../src/routes/ScreenRoute'),
    import('../src/routes/TransactionAlias'),
    import('../src/features/auth/LoginPage'),
    import('../src/features/auth/SetupPage'),
  ]);
}

/** Timeout of preloadRoutes in beforeAll (cold transformation of Ant Design). */
export const PRELOAD_TIMEOUT_MS = 60_000;

/** Resets every store of the shell to its initial state and installs a fake WebSocket. */
export function resetShellState(): FakeSockets {
  installDomStubs();
  try {
    localStorage.clear();
  } catch {
    // jsdom always has storage; ignore elsewhere.
  }
  useAuthStore.setState({
    status: 'unknown',
    user: null,
    role: null,
    permissions: [],
    sessionExpiresAt: null,
    mustChangePassword: false,
  });
  useUiStore.setState({
    restoreNotice: null,
    restorePath: null,
    urlTooLong: false,
    resumeAvailable: false,
    sidebarCollapsed: false,
    shortcutHelpOpen: false,
  });
  useConnectionStore.setState(INITIAL_CONNECTION_STATE);
  const sockets = fakeSockets();
  setWsManager(new WsManager({ createSocket: sockets.factory, visibility: null }));
  return sockets;
}

export interface RenderedApp extends RenderResult {
  readonly router: ReturnType<typeof createMemoryRouter>;
  /** pathname + search of the router's current location. */
  readonly url: () => string;
}

/** Renders the UI at `path` with the API stub already installed. */
export function renderApp(
  path: string,
  options: { language?: SupportedLanguage; version?: string } = {},
): RenderedApp {
  installSessionHandlers();
  const i18n = createI18n(options.language ?? 'en');
  const queryClient = createQueryClient();
  const router = createMemoryRouter(createRoutes(options.version ?? TEST_VERSION), {
    initialEntries: [path],
  });
  const view = render(<App i18n={i18n} queryClient={queryClient} router={router} />);
  return {
    ...view,
    router,
    url: () => `${router.state.location.pathname}${router.state.location.search}`,
  };
}

/** Example applications (docs/14 §4.3 App). */
export function appBody(
  appId: number,
  name: string,
  env: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    app_id: appId,
    name,
    env,
    group: null,
    apdex_t_ms: 500,
    created_at: '2026-11-01T00:00:00.000Z',
    instances_total: 2,
    instances_up: 2,
    last_seen_at: '2026-11-14T00:00:00.000Z',
    recommended_rules_applied: true,
    registered_from_app_id: null,
    registration_policy: 'auto',
    slow_threshold_ms: 1000,
    ...overrides,
  };
}

export const APPS = [
  appBody(1, 'order-api', 'prod'),
  appBody(2, 'order-api', 'stage'),
  appBody(3, 'billing', 'prod'),
];

/** A signed-in session with the shell's read calls answered (apps, alerts). */
export function signedIn(stub: ApiStub, me: Parameters<typeof meBody>[0] = {}): void {
  stub.on('GET /api/v1/auth/me', () => envelope(meBody(me)));
  stub.on('GET /api/v1/apps', () => envelope(APPS));
  stub.on('GET /api/v1/overview/alerts', () => envelope([]));
  for (const app of APPS) {
    stub.on(`GET /api/v1/apps/${String(app.app_id)}`, () => envelope(app));
  }
}

/** No session: /auth/me and the refresh answer 401. */
export function signedOut(stub: ApiStub): void {
  stub.on('GET /api/v1/auth/me', () => errorBody(401, 'unauthenticated'));
  stub.on('POST /api/v1/auth/refresh', () => errorBody(401, 'unauthenticated'));
  stub.on('GET /api/v1/setup/status', () =>
    envelope({ needs_setup: false, server_version: TEST_VERSION }),
  );
}
