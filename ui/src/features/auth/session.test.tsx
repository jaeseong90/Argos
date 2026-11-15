// SPDX-License-Identifier: Apache-2.0
//
// Cached server data and the session (docs/15 §11, C-13): a new sign-in and an expired session
// (401 after the refresh, WebSocket 4401, reauth) drop the TanStack Query cache, so the next user
// of a tab never sees the previous session's data (masked for another role) before their own
// queries return. The HTTP boundary is stubbed (test/apiStub.ts); the full shell flow (4401, then
// another user signs in) is in src/routes/shell.test.tsx.
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-41' }
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  meBody,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import { resetShellState } from '../../../test/renderApp';
import { createQueryClient } from '../../api/queryClient';
import { queryKeys } from '../../api/queryKeys';
import { useAuthStore, type SessionInfo } from '../../state/authStore';

import { loginErrorMessage, SessionNotKeptError } from './authErrors';
import { signIn, useDropCacheOnExpiry } from './session';

let stub: ApiStub;

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

/** The cache as user A left it: their applications and one more query. */
function cacheOfUserA(): QueryClient {
  const queryClient = createQueryClient();
  queryClient.setQueryData(queryKeys.apps.list(), {
    data: [{ app_id: 1, name: 'alice-only-api', env: 'prod' }],
    meta: { request_id: 'req-a', generated_at: '2026-01-01T00:00:00.000Z' },
  });
  queryClient.setQueryData(queryKeys.alerts.firingCount(null), 3);
  return queryClient;
}

function Harness() {
  useDropCacheOnExpiry();
  return null;
}

function renderHarness(queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  );
}

describe('signIn drops the previous session cache', () => {
  it('user B never gets the applications user A had cached', async () => {
    const queryClient = cacheOfUserA();
    stub.on('POST /api/v1/auth/login', () =>
      envelope({
        user: { id: 'u-2', username: 'bob', roles: ['viewer'], permissions: [] },
        roles: ['viewer'],
        must_change_password: false,
        session_expires_at: '2099-01-01T00:00:00.000Z',
      }),
    );
    stub.on('GET /api/v1/auth/me', () => envelope(meBody({ username: 'bob' })));
    await signIn('bob', 'correct horse battery', queryClient);
    expect(queryClient.getQueryData(queryKeys.apps.list())).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useAuthStore.getState().user?.username).toBe('bob');
  });
});

describe('C-197 (3) signIn notices a session cookie the browser did not keep', () => {
  const loginBody = () =>
    envelope({
      user: { id: 'u-2', username: 'bob', roles: ['viewer'], permissions: [] },
      roles: ['viewer'],
      must_change_password: false,
      session_expires_at: '2099-01-01T00:00:00.000Z',
    });

  it('a 401 of the /auth/me right after the login is SessionNotKeptError, and nobody is signed in', async () => {
    const queryClient = cacheOfUserA();
    stub.on('POST /api/v1/auth/login', loginBody);
    stub.on('GET /api/v1/auth/me', () => errorBody(401, 'unauthenticated'));
    stub.on('POST /api/v1/auth/refresh', () => errorBody(401, 'unauthenticated'));
    const failure = await signIn('bob', 'correct horse battery', queryClient).then(
      () => null,
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(SessionNotKeptError);
    expect(loginErrorMessage(failure)).toEqual({ key: 'login.error.sessionNotKept' });
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().user).toBeNull();
    // The previous session's data is gone all the same.
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('another failure of that /auth/me keeps the session of the login response', async () => {
    stub.on('POST /api/v1/auth/login', loginBody);
    stub.on('GET /api/v1/auth/me', () => errorBody(500, 'internal', { requestId: 'req-me' }));
    await signIn('bob', 'correct horse battery', createQueryClient());
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(useAuthStore.getState().user?.username).toBe('bob');
  });
});

describe('an expired session drops the cache', () => {
  it('empties the cache when the session expires', () => {
    const queryClient = cacheOfUserA();
    useAuthStore.getState().setSession(meBody() as unknown as SessionInfo);
    renderHarness(queryClient);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);
    act(() => {
      useAuthStore.getState().expire();
    });
    expect(useAuthStore.getState().status).toBe('expired');
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('cancels the queries in flight, so they cannot answer into the next session', async () => {
    const queryClient = cacheOfUserA();
    useAuthStore.getState().setSession(meBody() as unknown as SessionInfo);
    renderHarness(queryClient);
    let aborted = false;
    const pending = queryClient.query({
      queryKey: ['in-flight'],
      queryFn: ({ signal }) =>
        new Promise<never>((_resolve, reject) => {
          signal.addEventListener('abort', () => {
            aborted = true;
            reject(new Error('aborted'));
          });
        }),
    });
    expect(queryClient.isFetching()).toBe(1);
    act(() => {
      useAuthStore.getState().expire();
    });
    await expect(pending).rejects.toBeDefined();
    expect(aborted).toBe(true);
    expect(queryClient.isFetching()).toBe(0);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    // A late 401 of another request cannot expire the session again or loop.
    act(() => {
      useAuthStore.getState().expire();
    });
    expect(useAuthStore.getState().status).toBe('expired');
  });

  it('keeps the cache on other changes of the auth store', () => {
    const queryClient = cacheOfUserA();
    useAuthStore.getState().setSession(meBody() as unknown as SessionInfo);
    renderHarness(queryClient);
    act(() => {
      useAuthStore.getState().setSessionExpiresAt('2099-06-01T00:00:00.000Z');
      useAuthStore.getState().setMustChangePassword(false);
    });
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);
  });

  it('stops listening when the shell unmounts', () => {
    const queryClient = cacheOfUserA();
    useAuthStore.getState().setSession(meBody() as unknown as SessionInfo);
    const view = renderHarness(queryClient);
    view.unmount();
    act(() => {
      useAuthStore.getState().expire();
    });
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);
  });
});
