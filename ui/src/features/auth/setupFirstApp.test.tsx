// SPDX-License-Identifier: Apache-2.0
//
// The setup wizard's optional first application (docs/15 §9.11, ADR-0010 C-168 (2), C-153): after
// the administrator is signed in, a name and an environment register the application (POST /apps)
// and issue its agent token (POST /admin/apps/{appId}/tokens). The token is shown once with a copy
// button and the -javaagent options, and is never kept in browser storage, the URL or the query
// cache (docs/16 §2.1, SEC-02); leaving the step drops it. The step is skippable, is not offered
// when the browser did not keep the session, and reports a taken name and a failed issuance (the
// retry issues the token without registering the application twice). The HTTP boundary is stubbed
// (test/apiStub.ts); the real server flow runs in e2e/specs/auth.spec.ts (T-E2E-057).
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-02' }, { type: 'req', description: 'SEC-47' }, { type: 'req', description: 'F-U14' }
import { screen, waitFor } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  meBody,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  appBody,
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
} from '../../../test/renderApp';
import enAuth from '../../locales/en/auth.json';
import koAuth from '../../locales/ko/auth.json';
import { javaagentOptions } from '../admin/install/javaagent';

const FIND = { timeout: 5000 };
/** A token of the server's shape (argos_ + 43 base64url characters); a test value. */
const TOKEN = `argos_${'Q7'.repeat(21)}x`;
const TOKEN_ROUTE = 'POST /api/v1/admin/apps/7/tokens';
let stub: ApiStub;

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  stub.on('GET /api/v1/setup/status', () => envelope({ needs_setup: true, server_version: '1' }));
  stub.on('POST /api/v1/setup/admin', () =>
    envelope(
      { user: { id: 'u-1', username: 'root-admin', roles: ['admin'], permissions: [] } },
      {},
      201,
    ),
  );
  stub.on('GET /api/v1/auth/me', () => envelope(meBody({ roles: ['admin'] })));
  stub.on('GET /api/v1/apps', () => envelope([]));
  stub.on('GET /api/v1/overview/alerts', () => envelope([]));
});

afterEach(() => {
  uninstallApiStub();
});

function issuedBody(
  expiresAt: string | null = '2027-11-23T00:00:00.000Z',
  collectorAddress: string | null = null,
) {
  return envelope(
    {
      token_id: '0b6f0c9e-5d1c-4a43-9a51-6f1b8f1d2c3e', // gitleaks:allow (test fixture, not a real token)
      token: TOKEN,
      prefix: TOKEN.slice(0, 14),
      app_id: 7,
      expires_at: expiresAt,
      rotated_token_id: null,
      grace_until: null,
      collector_address: collectorAddress,
    },
    {},
    201,
  );
}

/** Walks through token, administrator and IP mode to the first-application step. */
async function toFirstApp(): Promise<{ user: UserEvent; app: ReturnType<typeof renderApp> }> {
  const user = userEvent.setup();
  const app = renderApp('/setup');
  await user.type(await screen.findByTestId('setup-token-input', {}, FIND), 'setup-token-value');
  await user.click(screen.getByTestId('setup-next'));
  await user.type(await screen.findByTestId('setup-admin-username', {}, FIND), 'root-admin');
  await user.type(screen.getByTestId('setup-admin-password'), 'twelve-chars');
  await user.type(screen.getByTestId('setup-admin-confirm'), 'twelve-chars');
  await user.click(screen.getByTestId('setup-next'));
  await user.click(await screen.findByTestId('setup-create', {}, FIND));
  await screen.findByTestId('setup-first-app', {}, FIND);
  return { user, app };
}

async function register(user: UserEvent, name: string, env?: string): Promise<void> {
  await user.type(screen.getByTestId('setup-first-app-name'), name);
  if (env !== undefined) {
    await user.clear(screen.getByTestId('setup-first-app-env'));
    await user.type(screen.getByTestId('setup-first-app-env'), env);
  }
  await user.click(screen.getByTestId('setup-first-app-create'));
}

/** Every string the browser keeps for this origin, plus the URL. */
function kept(app: ReturnType<typeof renderApp>): string {
  const values: string[] = [app.url(), window.location.href, document.cookie];
  for (const storage of [localStorage, sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i) ?? '';
      values.push(key, storage.getItem(key) ?? '');
    }
  }
  return values.join('\n');
}

describe('C-168 (2) setup wizard first application (docs/15 §9.11)', () => {
  it('C-168 (2) registers the application, shows the token once with copy and the -javaagent options', async () => {
    stub.on('POST /api/v1/apps', () => envelope(appBody(7, 'order-api', 'stage'), {}, 201));
    stub.on(TOKEN_ROUTE, () => issuedBody());
    const { user, app } = await toFirstApp();
    const step = screen.getByTestId('setup-wizard-first-app');
    expect(step.querySelector('[aria-current="step"]')).toHaveTextContent(
      enAuth.setup.stepFirstApp,
    );
    expect(screen.getByTestId('setup-first-app-env')).toHaveValue('prod');

    await register(user, '  order-api  ', 'stage');
    const value = await screen.findByTestId('setup-first-app-token-value', {}, FIND);
    expect(value).toHaveValue(TOKEN);
    expect(value).toHaveAttribute('readonly');
    expect(screen.getByTestId('setup-first-app-token-once')).toHaveTextContent(
      enAuth.setup.firstApp.tokenOnce,
    );
    // The name is trimmed like the server does; the token request carries no body.
    expect(stub.calls('POST /api/v1/apps').map((r) => r.body)).toEqual([
      { name: 'order-api', env: 'stage' },
    ]);
    expect(stub.calls(TOKEN_ROUTE)).toHaveLength(1);
    expect(stub.calls(TOKEN_ROUTE)[0]?.headers.get('X-Argos-CSRF')).toBe('csrf-token-1');

    // The options name the token file, never the token.
    const snippet = screen.getByTestId('setup-first-app-snippet');
    expect(snippet).toHaveTextContent('-javaagent:/opt/argos-agent/argos-agent.jar');
    expect(snippet).toHaveTextContent('-Dargos.server.token-file=/opt/argos-agent/token');
    expect(snippet).toHaveTextContent('-Dargos.app.name=order-api');
    expect(snippet).toHaveTextContent('-Dargos.app.env=stage');
    // No argos.collector.public-address on the server: the browser's host and port 9100.
    expect(snippet).toHaveTextContent('-Dargos.server.address=localhost:9100');
    expect(snippet.textContent).not.toContain(TOKEN);

    await user.click(screen.getByTestId('setup-first-app-copy'));
    await waitFor(() => {
      expect(screen.getByTestId('setup-first-app-copy-status')).toHaveTextContent(
        enAuth.setup.firstApp.copied,
      );
    });
    expect(await navigator.clipboard.readText()).toBe(TOKEN);

    // Never stored by the page, never in the URL.
    expect(kept(app)).not.toContain(TOKEN);

    await user.click(screen.getByTestId('setup-first-app-continue'));
    const finish = await screen.findByTestId('setup-finish', {}, FIND);
    expect(screen.getByTestId('setup-finish-token-issued')).toHaveTextContent(
      enAuth.setup.finish.agentTokenIssued,
    );
    expect(screen.queryByTestId('setup-finish-token-hint')).toBeNull();
    expect(finish).toHaveTextContent(enAuth.setup.finish.agentAttach);
    // Gone once the step is left: nothing on the page and nothing kept holds it.
    expect(document.body.textContent).not.toContain(TOKEN);
    expect(
      Array.from(document.querySelectorAll('input')).filter((input) => input.value === TOKEN),
    ).toEqual([]);
    expect(kept(app)).not.toContain(TOKEN);
  });

  it('C-168 (2) a token without expiry says so', async () => {
    stub.on('POST /api/v1/apps', () => envelope(appBody(7, 'order-api', 'prod'), {}, 201));
    stub.on(TOKEN_ROUTE, () => issuedBody(null));
    const { user } = await toFirstApp();
    await register(user, 'order-api');
    expect(await screen.findByTestId('setup-first-app-token-expiry', {}, FIND)).toHaveTextContent(
      enAuth.setup.firstApp.tokenNoExpiry,
    );
  });

  it('C-231 (9) the snippet uses the collector address of the token response (argos.collector.public-address)', async () => {
    stub.on('POST /api/v1/apps', () => envelope(appBody(7, 'order-api', 'prod'), {}, 201));
    stub.on(TOKEN_ROUTE, () => issuedBody(undefined, 'collector.example.com:443'));
    const { user } = await toFirstApp();
    await register(user, 'order-api');
    const snippet = await screen.findByTestId('setup-first-app-snippet', {}, FIND);
    expect(snippet).toHaveTextContent('-Dargos.server.address=collector.example.com:443');
    expect(snippet.textContent).not.toContain('localhost:9100');
    expect(javaagentOptions('a', 'b', 'h', '  [::1]:9443 ')).toContain(
      '-Dargos.server.address=[::1]:9443',
    );
    expect(javaagentOptions('a', 'b', 'h', '')).toContain('-Dargos.server.address=h:9100');
  });

  it('C-168 (2) is skippable: the finish keeps the token create command hint (C-173)', async () => {
    const { user } = await toFirstApp();
    await user.click(screen.getByTestId('setup-first-app-skip'));
    expect(await screen.findByTestId('setup-finish-token-hint', {}, FIND)).toHaveTextContent(
      enAuth.setup.finish.agentToken,
    );
    expect(stub.calls('POST /api/v1/apps')).toHaveLength(0);
  });

  it('C-168 (2) checks the fields and reports a taken name and environment (409)', async () => {
    stub.on('POST /api/v1/apps', () =>
      errorBody(409, 'already_exists', { details: [{ field: 'name', reason: 'already_exists' }] }),
    );
    const { user } = await toFirstApp();
    await user.click(screen.getByTestId('setup-first-app-create'));
    expect(screen.getByTestId('setup-first-app-error')).toHaveTextContent(
      enAuth.setup.firstApp.error.nameRequired,
    );
    await user.type(screen.getByTestId('setup-first-app-name'), 'order-api');
    await user.clear(screen.getByTestId('setup-first-app-env'));
    await user.click(screen.getByTestId('setup-first-app-create'));
    expect(screen.getByTestId('setup-first-app-error')).toHaveTextContent(
      enAuth.setup.firstApp.error.envRequired,
    );
    expect(stub.calls('POST /api/v1/apps')).toHaveLength(0);
    await user.type(screen.getByTestId('setup-first-app-env'), 'prod');
    await user.click(screen.getByTestId('setup-first-app-create'));
    expect(await screen.findByTestId('setup-first-app-error', {}, FIND)).toHaveTextContent(
      enAuth.setup.firstApp.error.exists,
    );
    expect(stub.calls(TOKEN_ROUTE)).toHaveLength(0);
  });

  it('C-168 (2) a failed issuance is retried without registering the application again', async () => {
    stub.on('POST /api/v1/apps', () => envelope(appBody(7, 'order-api', 'prod'), {}, 201));
    stub.on(TOKEN_ROUTE, () => errorBody(503, 'unavailable', { requestId: 'req-503' }));
    const { user } = await toFirstApp();
    await register(user, 'order-api');
    expect(await screen.findByTestId('setup-first-app-error', {}, FIND)).toHaveTextContent(
      'req-503',
    );
    expect(screen.getByTestId('setup-first-app-name')).toBeDisabled();
    expect(screen.getByTestId('setup-first-app-create')).toHaveTextContent(
      enAuth.setup.firstApp.retryToken,
    );
    stub.on(TOKEN_ROUTE, () => issuedBody());
    await user.click(screen.getByTestId('setup-first-app-create'));
    expect(await screen.findByTestId('setup-first-app-token-value', {}, FIND)).toHaveValue(TOKEN);
    expect(stub.calls('POST /api/v1/apps')).toHaveLength(1);
    expect(stub.calls(TOKEN_ROUTE)).toHaveLength(2);
  });

  it('C-201 (2) is not offered when the browser did not keep the session', async () => {
    const user = userEvent.setup();
    stub.on('GET /api/v1/auth/me', () => errorBody(401, 'unauthenticated'));
    stub.on('POST /api/v1/auth/refresh', () => errorBody(401, 'unauthenticated'));
    renderApp('/setup');
    await user.type(await screen.findByTestId('setup-token-input', {}, FIND), 'setup-token-value');
    await user.click(screen.getByTestId('setup-next'));
    await user.type(await screen.findByTestId('setup-admin-username', {}, FIND), 'root-admin');
    await user.type(screen.getByTestId('setup-admin-password'), 'twelve-chars');
    await user.type(screen.getByTestId('setup-admin-confirm'), 'twelve-chars');
    await user.click(screen.getByTestId('setup-next'));
    await user.click(await screen.findByTestId('setup-create', {}, FIND));
    await screen.findByTestId('setup-finish', {}, FIND);
    expect(screen.queryByTestId('setup-first-app')).toBeNull();
    expect(screen.getByTestId('setup-finish-token-hint')).toBeInTheDocument();
  });

  it('C-168 (2) quotes option values that need it and brackets IPv6 hosts', () => {
    expect(javaagentOptions('order-api', 'prod', 'argos.example')).toBe(
      [
        '-javaagent:/opt/argos-agent/argos-agent.jar',
        '-Dargos.server.address=argos.example:9100',
        '-Dargos.server.token-file=/opt/argos-agent/token',
        '-Dargos.app.name=order-api',
        '-Dargos.app.env=prod',
      ].join('\n'),
    );
    const options = javaagentOptions('order "api" $x', 'prod', 'h');
    expect(options).toContain('-Dargos.app.name="order \\"api\\" \\$x"');
    expect(javaagentOptions('a', 'b')).toContain('-Dargos.server.address=localhost:9100');
  });

  it('C-168 (2) the Korean catalog has every text of the step', () => {
    expect(Object.keys(koAuth.setup.firstApp).sort()).toEqual(
      Object.keys(enAuth.setup.firstApp).sort(),
    );
    expect(Object.keys(koAuth.setup.firstApp.error).sort()).toEqual(
      Object.keys(enAuth.setup.firstApp.error).sort(),
    );
    expect(koAuth.setup.finish.agentTokenIssued).toContain('token create');
  });
});
