// SPDX-License-Identifier: Apache-2.0
//
// "My sessions" of /account (docs/15 §9.11, docs/16 §7.10, ADR-0010 C-136 (3), C-168 (2)): the
// caller's live sessions from GET /auth/sessions with the current one marked and no way to end it
// here, "End session" for the others (DELETE /auth/sessions/{id} with the CSRF header, then the
// refreshed list), a session that ended meanwhile (404), a refused request with its request id,
// the loading, empty and error states, and the client-supplied user agent rendered as text only
// (SEC-60). The HTTP boundary is stubbed (test/apiStub.ts); the real server flow runs in
// e2e/specs/auth.spec.ts (T-E2E-058).
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-42' }, { type: 'req', description: 'SEC-60' }, { type: 'req', description: 'F-U14' }
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
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
} from '../../../test/renderApp';
import enAuth from '../../locales/en/auth.json';

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/auth/sessions';
const CURRENT = '6f0e7d1a-3b2c-4d5e-8f90-a1b2c3d4e5f6';
const OTHER = '9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d';
const XSS = '<img src=x onerror="window.__xss=1">Mozilla/5.0';
let stub: ApiStub;

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  signedIn(stub);
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

function session(id: string, overrides: Record<string, unknown> = {}) {
  return {
    session_id: id,
    created_at: '2026-11-23T08:00:00.000Z',
    last_seen_at: '2026-11-23T09:30:00.000Z',
    expires_at: '2026-11-30T08:00:00.000Z',
    ip: '203.0.113.0',
    user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/142.0',
    current: false,
    kiosk: false,
    ...overrides,
  };
}

async function openAccount() {
  const app = renderApp('/account');
  const section = await screen.findByTestId('account-sessions', {}, FIND);
  return { app, section };
}

describe('C-136 (3) /account my sessions (docs/16 §7.10)', () => {
  it('C-136 (3) lists the sessions, marks the current one and ends another with X-Argos-CSRF', async () => {
    const user = userEvent.setup();
    let sessions = [
      session(CURRENT, { current: true }),
      session(OTHER, { ip: null, user_agent: XSS, kiosk: true }),
    ];
    stub.on(LIST, () => envelope(sessions));
    stub.on(`DELETE /api/v1/auth/sessions/${OTHER}`, () => {
      sessions = sessions.filter((s) => s.session_id !== OTHER);
      return noContent();
    });
    const { app } = await openAccount();
    const items = await screen.findAllByTestId('account-session', {}, FIND);
    expect(items.map((i) => i.getAttribute('data-session-id'))).toEqual([CURRENT, OTHER]);
    const [current, other] = items as [HTMLElement, HTMLElement];

    // The current session: marked, no "End session" (sign-out ends it).
    expect(current).toHaveAttribute('data-current', 'true');
    expect(within(current).getByTestId('account-session-current')).toHaveTextContent(
      enAuth.account.sessions.current,
    );
    expect(within(current).queryByTestId('account-session-revoke')).toBeNull();
    expect(current).toHaveTextContent(enAuth.account.sessions.currentHint);
    expect(within(current).getByTestId('account-session-ip')).toHaveTextContent('203.0.113.0');
    expect(current.querySelectorAll('time')).toHaveLength(3);

    // The user agent is text, never markup; a missing IP is the dash.
    expect(within(other).getByTestId('account-session-agent')).toHaveTextContent(XSS);
    expect(app.container.querySelector('img')).toBeNull();
    expect(Reflect.get(window, '__xss')).toBeUndefined();
    expect(within(other).getByTestId('account-session-ip')).toHaveTextContent('—');
    expect(other).toHaveTextContent(enAuth.account.sessions.kiosk);

    await user.click(within(other).getByTestId('account-session-revoke'));
    expect(await screen.findByTestId('account-sessions-revoked', {}, FIND)).toHaveTextContent(
      enAuth.account.sessions.revoked,
    );
    const deletes = stub.calls(`DELETE /api/v1/auth/sessions/${OTHER}`);
    expect(deletes).toHaveLength(1);
    expect(deletes[0]?.headers.get('X-Argos-CSRF')).toBe('csrf-token-1');
    await waitFor(() => {
      expect(screen.getAllByTestId('account-session')).toHaveLength(1);
    }, FIND);
    expect(stub.calls(LIST).length).toBeGreaterThanOrEqual(2);
  });

  it('C-136 (3) a session that ended meanwhile (404) only refreshes the list', async () => {
    const user = userEvent.setup();
    let calls = 0;
    stub.on(LIST, () => {
      calls += 1;
      return envelope(
        calls === 1 ? [session(CURRENT, { current: true }), session(OTHER)] : [session(CURRENT)],
      );
    });
    stub.on(`DELETE /api/v1/auth/sessions/${OTHER}`, () => errorBody(404, 'not_found'));
    await openAccount();
    const items = await screen.findAllByTestId('account-session', {}, FIND);
    await user.click(within(items[1] as HTMLElement).getByTestId('account-session-revoke'));
    await waitFor(() => {
      expect(screen.getAllByTestId('account-session')).toHaveLength(1);
    }, FIND);
    expect(screen.queryByTestId('account-sessions-revoke-error')).toBeNull();
  });

  it('C-136 (3) a refused request names its request id and keeps the session listed', async () => {
    const user = userEvent.setup();
    stub.on(LIST, () => envelope([session(CURRENT, { current: true }), session(OTHER)]));
    stub.on(`DELETE /api/v1/auth/sessions/${OTHER}`, () =>
      errorBody(500, 'internal', { requestId: 'req-500' }),
    );
    await openAccount();
    const items = await screen.findAllByTestId('account-session', {}, FIND);
    await user.click(within(items[1] as HTMLElement).getByTestId('account-session-revoke'));
    expect(await screen.findByTestId('account-sessions-revoke-error', {}, FIND)).toHaveTextContent(
      'req-500',
    );
    expect(screen.getAllByTestId('account-session')).toHaveLength(2);
  });

  it('C-136 (3) shows the loading, empty and error states', async () => {
    const user = userEvent.setup();
    let answer: () => Response = () => errorBody(503, 'unavailable', { requestId: 'req-503' });
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    stub.on(LIST, async () => {
      await held;
      return answer();
    });
    await openAccount();
    expect(screen.getByTestId('account-sessions-loading')).toBeInTheDocument();
    release();
    const error = await screen.findByTestId('account-sessions-error', {}, FIND);
    expect(error).toHaveTextContent('req-503');
    answer = () => envelope([]);
    await user.click(within(error).getByRole('button'));
    expect(await screen.findByTestId('account-sessions-empty', {}, FIND)).toHaveTextContent(
      enAuth.account.sessions.empty,
    );
  });
});
