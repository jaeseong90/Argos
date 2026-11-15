// SPDX-License-Identifier: Apache-2.0
//
// Authentication screens (docs/15 §9.11, docs/16 §7.8-§7.9, ADR-0010 C-168 (2)): /login with
// generic failures, lock, rate limit, forced password change and `next` (deep link 1); /setup
// (setup token, administrator with the password policy, IP collection mode with masked
// preselected, finish with the agent hint); /account (password change, language). The HTTP
// boundary is stubbed (test/apiStub.ts); the real server flows run in e2e/specs/auth.spec.ts.
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-41' }, { type: 'req', description: 'SEC-43' }, { type: 'req', description: 'SEC-47' }, { type: 'req', description: 'F-U14' }
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  meBody,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
  signedOut,
} from '../../../test/renderApp';
import enAuth from '../../locales/en/auth.json';
import koAuth from '../../locales/ko/auth.json';
import { useAuthStore } from '../../state/authStore';

const FIND = { timeout: 5000 };
let stub: ApiStub;

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

function loginResponse(overrides: { must_change_password?: boolean } = {}) {
  return envelope({
    user: { id: 'u-1', username: 'alice', roles: ['viewer'], permissions: [] },
    roles: ['viewer'],
    must_change_password: overrides.must_change_password ?? false,
    session_expires_at: '2099-01-01T00:00:00.000Z',
  });
}

async function submitLogin(username = 'alice', password = 'correct horse battery') {
  const user = userEvent.setup();
  await user.type(await screen.findByTestId('login-username', {}, FIND), username);
  await user.type(screen.getByTestId('login-password'), password);
  await user.click(screen.getByTestId('login-submit'));
  return user;
}

describe('T-SEC-41 /login (docs/15 §9.11)', () => {
  it('T-SEC-41 shows the same generic text for a wrong password and an unknown user', async () => {
    signedOut(stub);
    stub.on('POST /api/v1/auth/login', () => errorBody(401, 'unauthenticated'));
    renderApp('/login');
    await submitLogin('nobody', 'wrong password!');
    expect(await screen.findByTestId('login-error', {}, FIND)).toHaveTextContent(
      enAuth.login.error.invalid,
    );
    expect(screen.getByTestId('login-password')).toHaveValue('');
    expect(screen.getByTestId('login-username')).toHaveValue('nobody');
  });

  it.each([
    [errorBody(423, 'account_locked'), enAuth.login.error.locked],
    [
      errorBody(423, 'account_locked', { headers: { 'Retry-After': '600' } }),
      'Try again in 10 minutes',
    ],
    [
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' } }),
      'Wait 30 seconds and try again.',
    ],
    [errorBody(429, 'rate_limited'), enAuth.login.error.rateLimitedNoWait],
    [errorBody(500, 'internal', { requestId: 'req-500' }), 'request ID req-500'],
  ])(
    'T-SEC-41 explains a refused sign-in (lock, rate limit, server error) %#',
    async (response, text) => {
      signedOut(stub);
      stub.on('POST /api/v1/auth/login', () => response.clone());
      renderApp('/login');
      await submitLogin();
      expect(await screen.findByTestId('login-error', {}, FIND)).toHaveTextContent(text);
    },
  );

  it('C-197 (3) a login whose session cookie the browser did not keep says so (HTTPS or localhost)', async () => {
    // The login answers 200, but the browser kept none of its Secure cookies (plain HTTP to a
    // host other than localhost): the next /auth/me and the refresh answer 401.
    signedOut(stub);
    stub.on('POST /api/v1/auth/login', () => loginResponse());
    const app = renderApp('/login?next=%2Fapps%2F1%2Fxlog');
    await submitLogin();
    const error = await screen.findByTestId('login-error', {}, FIND);
    expect(error).toHaveTextContent(enAuth.login.error.sessionNotKept);
    expect(error.textContent).toContain('HTTPS');
    expect(error.textContent).toContain('localhost');
    expect(error.textContent).toContain('installation guide');
    // Never the generic failure or the "session ended" notice.
    expect(error).not.toHaveTextContent(enAuth.login.error.invalid);
    expect(error).not.toHaveTextContent(enAuth.login.error.serverNoId);
    expect(screen.queryByTestId('login-session-ended')).toBeNull();
    // The check is the /auth/me right after the login; the page stays on /login, signed out.
    const order = stub.requests.map((r) => `${r.method} ${r.path}`);
    expect(order.slice(order.indexOf('POST /api/v1/auth/login') + 1)).toContain(
      'GET /api/v1/auth/me',
    );
    expect(screen.queryByTestId('app-shell')).toBeNull();
    expect(app.url()).toBe('/login?next=%2Fapps%2F1%2Fxlog');
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().user).toBeNull();
    expect(screen.getByTestId('login-password')).toHaveValue('');
  });

  it('C-197 (3) the Korean text says the same', () => {
    expect(koAuth.login.error.sessionNotKept).toContain('HTTPS');
    expect(koAuth.login.error.sessionNotKept).toContain('localhost');
    expect(koAuth.login.error.sessionNotKept).toContain('설치 가이드');
  });

  it('T-SEC-43 obtains the CSRF cookie before the login and again after it', async () => {
    signedOut(stub);
    stub.on('POST /api/v1/auth/login', () => loginResponse());
    stub.on('GET /api/v1/auth/me', () => envelope(meBody()));
    signedIn(stub);
    const app = renderApp('/login?next=%2Fapps%2F1%2Fxlog%3Ffrom%3Dnow-1h');
    await submitLogin();
    await screen.findByTestId('app-shell', {}, FIND);
    expect(app.url()).toBe('/apps/1/xlog?from=now-1h');
    const order = stub.requests.map((r) => `${r.method} ${r.path}`);
    const login = order.indexOf('POST /api/v1/auth/login');
    expect(order.slice(0, login)).toContain('GET /api/v1/auth/csrf');
    expect(order.slice(login + 1)).toContain('GET /api/v1/auth/csrf');
    // The credentials never end up in the auth store or in browser storage.
    expect(JSON.stringify(useAuthStore.getState())).not.toContain('correct horse');
    const stored = Array.from({ length: localStorage.length }, (_, i) => {
      const key = localStorage.key(i) ?? '';
      return `${key}=${localStorage.getItem(key) ?? ''}`;
    });
    expect(stored.join(' ')).not.toContain('correct horse');
  });

  it('T-SEC-41 ignores a next that is not a same-origin path', async () => {
    signedOut(stub);
    stub.on('POST /api/v1/auth/login', () => loginResponse());
    signedIn(stub);
    const app = renderApp(`/login?next=${encodeURIComponent('//evil.example/x')}`);
    await submitLogin();
    await screen.findByTestId('app-shell', {}, FIND);
    expect(app.url()).toBe('/overview');
  });

  it('T-SEC-41 deep link 1: a link opened without a session returns there after sign-in', async () => {
    signedOut(stub);
    const deepLink =
      '/apps/1/dashboard?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:15:00.000Z&kind=BATCH';
    const app = renderApp(deepLink.replace('.000Z', 'Z'));
    await screen.findByTestId('login-form', {}, FIND);
    expect(app.url()).toBe(`/login?next=${encodeURIComponent(deepLink)}`);
    stub.on('POST /api/v1/auth/login', () => loginResponse());
    signedIn(stub);
    await submitLogin();
    await screen.findByTestId('app-shell', {}, FIND);
    expect(app.url()).toBe(deepLink);
    expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'false');
  });

  it('T-SEC-41 must_change_password leads to the forced change before the shell opens', async () => {
    signedOut(stub);
    stub.on('POST /api/v1/auth/login', () => loginResponse({ must_change_password: true }));
    stub.on('GET /api/v1/auth/me', () => envelope(meBody({ must_change_password: true })));
    let changed: unknown = null;
    stub.on('PUT /api/v1/auth/password', (request) => {
      changed = { body: request.body, csrf: request.headers.get('X-Argos-CSRF') };
      return noContent();
    });
    const app = renderApp('/login?next=%2Faccount');
    const user = await submitLogin();
    const form = await screen.findByTestId('password-change', {}, FIND);
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument();
    await user.type(within(form).getByTestId('password-change-current'), 'correct horse battery');
    await user.type(within(form).getByTestId('password-change-new'), 'a much longer passphrase');
    await user.type(
      within(form).getByTestId('password-change-confirm'),
      'a much longer passphrase',
    );
    signedIn(stub);
    await user.click(within(form).getByTestId('password-change-submit'));
    await screen.findByTestId('account-page', {}, FIND);
    expect(app.url()).toBe('/account');
    expect(changed).toEqual({
      body: { current: 'correct horse battery', new: 'a much longer passphrase' },
      csrf: 'csrf-token-1',
    });
  });

  it('T-SEC-41 the shell shows the forced change while /auth/me says must_change_password', async () => {
    signedIn(stub, { must_change_password: true });
    renderApp('/overview');
    expect(await screen.findByTestId('password-change', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument();
  });
});

describe('T-SEC-47 /setup (docs/15 §9.11, docs/16 §7.8)', () => {
  it('T-SEC-47 answers like a missing page once an administrator exists', async () => {
    stub.on('GET /api/v1/setup/status', () =>
      envelope({ needs_setup: false, server_version: '1' }),
    );
    renderApp('/setup');
    expect(await screen.findByTestId('setup-not-found', {}, FIND)).toBeInTheDocument();
    expect(screen.queryByTestId('setup-wizard')).not.toBeInTheDocument();
  });

  it('T-SEC-47 walks through token, administrator and IP mode (masked preselected) to the agent hint', async () => {
    const user = userEvent.setup();
    stub.on('GET /api/v1/setup/status', () => envelope({ needs_setup: true, server_version: '1' }));
    let created: { body: unknown; token: string | null } | null = null;
    stub.on('POST /api/v1/setup/admin', (request) => {
      created = { body: request.body, token: request.headers.get('X-Argos-Setup-Token') };
      return envelope(
        { user: { id: 'u-1', username: 'root-admin', roles: ['admin'], permissions: [] } },
        {},
        201,
      );
    });
    stub.on('GET /api/v1/auth/me', () => envelope(meBody({ roles: ['admin'] })));
    renderApp('/setup');
    await screen.findByTestId('setup-wizard', {}, FIND);

    // Step 1: the token is required.
    await user.click(screen.getByTestId('setup-next'));
    expect(screen.getByTestId('setup-error')).toHaveTextContent(enAuth.setup.token.required);
    await user.type(screen.getByTestId('setup-token-input'), ' setup-token-value ');
    await user.click(screen.getByTestId('setup-next'));

    // Step 2: administrator with the password policy hint (12..128 characters).
    const admin = await screen.findByTestId('setup-admin', {}, FIND);
    expect(admin).toHaveTextContent(enAuth.password.policy);
    await user.type(screen.getByTestId('setup-admin-username'), 'root-admin');
    await user.type(screen.getByTestId('setup-admin-password'), 'short-11ch!');
    await user.type(screen.getByTestId('setup-admin-confirm'), 'short-11ch!');
    await user.click(screen.getByTestId('setup-next'));
    expect(screen.getByTestId('setup-error')).toHaveTextContent(enAuth.password.error.tooShort);
    await user.clear(screen.getByTestId('setup-admin-password'));
    await user.clear(screen.getByTestId('setup-admin-confirm'));
    await user.type(screen.getByTestId('setup-admin-password'), 'twelve-chars');
    await user.type(screen.getByTestId('setup-admin-confirm'), 'twelve-chars');
    await user.click(screen.getByTestId('setup-next'));

    // Step 3: IP collection mode, masked preselected, every mode explained.
    const modes = await screen.findByTestId('setup-ip-mode', {}, FIND);
    expect(screen.getByTestId('setup-ip-mode-masked')).toBeChecked();
    expect(modes).toHaveTextContent(enAuth.setup.ipMode.fullHelp);
    expect(modes).toHaveTextContent(enAuth.setup.ipMode.maskedHelp);
    expect(modes).toHaveTextContent(enAuth.setup.ipMode.offHelp);
    await user.click(screen.getByTestId('setup-create'));

    // Step 4: finished, with the agent token hint (C-173) and no token in the body.
    const finish = await screen.findByTestId('setup-finish', {}, FIND);
    expect(finish).toHaveTextContent(enAuth.setup.finish.agentToken);
    expect(created).toEqual({
      body: { username: 'root-admin', password: 'twelve-chars', ip_mode: 'masked' },
      token: 'setup-token-value',
    });
    expect(useAuthStore.getState().role).toBe('admin');
  });

  it('C-201 (2) the wizard says the browser did not keep the session cookie instead of "you are signed in"', async () => {
    const user = userEvent.setup();
    stub.on('GET /api/v1/setup/status', () => envelope({ needs_setup: true, server_version: '1' }));
    stub.on('POST /api/v1/setup/admin', () =>
      envelope(
        { user: { id: 'u-1', username: 'root-admin', roles: ['admin'], permissions: [] } },
        {},
        201,
      ),
    );
    // The administrator is created, but the browser kept none of the Secure cookies.
    stub.on('GET /api/v1/auth/me', () => errorBody(401, 'unauthenticated'));
    stub.on('POST /api/v1/auth/refresh', () => errorBody(401, 'unauthenticated'));
    const app = renderApp('/setup');
    await user.type(await screen.findByTestId('setup-token-input', {}, FIND), 'setup-token-value');
    await user.click(screen.getByTestId('setup-next'));
    await user.type(await screen.findByTestId('setup-admin-username', {}, FIND), 'root-admin');
    await user.type(screen.getByTestId('setup-admin-password'), 'twelve-chars');
    await user.type(screen.getByTestId('setup-admin-confirm'), 'twelve-chars');
    await user.click(screen.getByTestId('setup-next'));
    await user.click(await screen.findByTestId('setup-create', {}, FIND));

    const finish = await screen.findByTestId('setup-finish', {}, FIND);
    expect(screen.getByTestId('setup-session-not-kept')).toHaveTextContent(
      enAuth.setup.finish.sessionNotKept,
    );
    expect(finish).not.toHaveTextContent(enAuth.setup.finish.description);
    expect(finish.textContent).toContain('HTTPS');
    expect(finish.textContent).toContain('localhost');
    expect(finish.textContent).toContain('installation guide');
    // The agent hints stay; nobody is signed in, and the way on is the sign-in page.
    expect(finish).toHaveTextContent(enAuth.setup.finish.agentToken);
    expect(screen.queryByTestId('setup-open-overview')).toBeNull();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().user).toBeNull();
    await user.click(screen.getByTestId('setup-go-sign-in'));
    await screen.findByTestId('login-form', {}, FIND);
    expect(app.url()).toBe('/login');
    expect(koAuth.setup.finish.sessionNotKept).toContain('localhost');
    expect(koAuth.setup.finish.sessionNotKept).toContain('설치 가이드');
  });

  it('T-SEC-47 a refused setup token returns to the token step with a message', async () => {
    const user = userEvent.setup();
    stub.on('GET /api/v1/setup/status', () => envelope({ needs_setup: true, server_version: '1' }));
    stub.on('POST /api/v1/setup/admin', () => errorBody(403, 'forbidden'));
    renderApp('/setup');
    await user.type(await screen.findByTestId('setup-token-input', {}, FIND), 'wrong');
    await user.click(screen.getByTestId('setup-next'));
    await user.type(await screen.findByTestId('setup-admin-username', {}, FIND), 'root-admin');
    await user.type(screen.getByTestId('setup-admin-password'), 'twelve-chars');
    await user.type(screen.getByTestId('setup-admin-confirm'), 'twelve-chars');
    await user.click(screen.getByTestId('setup-next'));
    await user.click(await screen.findByTestId('setup-create', {}, FIND));
    expect(await screen.findByTestId('setup-token', {}, FIND)).toBeInTheDocument();
    expect(screen.getByTestId('setup-error')).toHaveTextContent(enAuth.setup.error.token);
  });
});

describe('T-SEC-43 /account (docs/15 §9.11)', () => {
  it('T-UI-005 renders the user name as text and changes the password with X-Argos-CSRF', async () => {
    const user = userEvent.setup();
    const payload = '<img src=x onerror="window.__xss=1">';
    signedIn(stub, { username: payload, roles: ['operator'] });
    let csrf: string | null = null;
    stub.on('PUT /api/v1/auth/password', (request) => {
      csrf = request.headers.get('X-Argos-CSRF');
      return noContent();
    });
    const { container } = renderApp('/account');
    const page = await screen.findByTestId('account-page', {}, FIND);
    expect(within(page).getByTestId('account-username')).toHaveTextContent(payload);
    expect(container.querySelector('img')).toBeNull();
    expect(Reflect.get(window, '__xss')).toBeUndefined();
    expect(within(page).getByTestId('account-role')).toHaveTextContent('Operator');

    const form = within(page).getByTestId('password-change');
    await user.type(within(form).getByTestId('password-change-current'), 'old password 1');
    await user.type(within(form).getByTestId('password-change-new'), 'new password 12');
    await user.type(within(form).getByTestId('password-change-confirm'), 'new password 13');
    await user.click(within(form).getByTestId('password-change-submit'));
    expect(within(form).getByTestId('password-change-error')).toHaveTextContent(
      enAuth.password.error.mismatch,
    );
    await user.clear(within(form).getByTestId('password-change-confirm'));
    await user.type(within(form).getByTestId('password-change-confirm'), 'new password 12');
    await user.click(within(form).getByTestId('password-change-submit'));
    expect(await within(form).findByTestId('password-change-success', {}, FIND)).toHaveTextContent(
      enAuth.password.changed,
    );
    expect(csrf).toBe('csrf-token-1');
  });

  it('T-SEC-43 shows the server policy reason of a refused password', async () => {
    const user = userEvent.setup();
    signedIn(stub);
    stub.on('PUT /api/v1/auth/password', () =>
      errorBody(422, 'password_policy', { details: [{ field: 'new', reason: 'denylisted' }] }),
    );
    renderApp('/account');
    const form = await screen.findByTestId('password-change', {}, FIND);
    await user.type(within(form).getByTestId('password-change-current'), 'old password 1');
    await user.type(within(form).getByTestId('password-change-new'), 'password12345');
    await user.type(within(form).getByTestId('password-change-confirm'), 'password12345');
    await user.click(within(form).getByTestId('password-change-submit'));
    expect(await within(form).findByTestId('password-change-error', {}, FIND)).toHaveTextContent(
      enAuth.password.error.denylisted,
    );
  });

  it('T-UI-006 switches the language and remembers it in argos.ui.lang', async () => {
    const user = userEvent.setup();
    signedIn(stub);
    renderApp('/account');
    await user.click(await screen.findByTestId('account-language-ko', {}, FIND));
    await waitFor(() => {
      expect(localStorage.getItem('argos.ui.lang')).toBe('ko');
    });
    expect(await screen.findByText('내 계정', { selector: 'h1' }, FIND)).toBeInTheDocument();
  });
});
