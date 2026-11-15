// SPDX-License-Identifier: Apache-2.0
//
// Session lifecycle in the browser (docs/15 §11, docs/16 §7): bootstrap (CSRF cookie + /auth/me),
// login, logout, expiry. The session itself is the HttpOnly cookies; the auth store only mirrors
// who is logged in (SEC-40).
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router';

import {
  fetchCsrfToken,
  fetchMe,
  login,
  logout,
  type LoginResponse,
  type MeResponse,
} from '../../api/auth';
import { api } from '../../api/client';
import { isApiError } from '../../api/errors';
import { getWsManager } from '../../realtime/hooks';
import { useAuthStore } from '../../state/authStore';
import { safeNextPath, URL_WRITE_LIMIT } from '../../state/urlState';

import { SessionNotKeptError } from './authErrors';

/**
 * Connects the transport and the WebSocket manager to the auth store: a failed refresh, a 4401
 * close or a reauth frame expire the session; a refresh updates the expiry. Returns the undo.
 */
export function installSessionHandlers(): () => void {
  const transport = api().transport;
  const expire = (): void => {
    useAuthStore.getState().expire();
  };
  transport.setListeners({
    onSessionExpired: expire,
    onSessionRefreshed: (expiresAt) => {
      useAuthStore.getState().setSessionExpiresAt(expiresAt);
    },
  });
  const ws = getWsManager();
  ws.setSessionExpiredHandler(expire);
  return () => {
    transport.setListeners({});
    ws.setSessionExpiredHandler(undefined);
  };
}

/**
 * Starts a page: obtains the CSRF cookie, then asks /auth/me. A 401 (after the transport's one
 * refresh attempt) means nobody is logged in. Other failures are thrown (the shell shows them).
 */
export async function bootstrapSession(): Promise<'authenticated' | 'anonymous'> {
  try {
    await fetchCsrfToken();
  } catch {
    // The CSRF cookie is fetched again before the first state change; /auth/me decides here.
  }
  try {
    const me = await fetchMe();
    useAuthStore.getState().setSession(me);
    return 'authenticated';
  } catch (error) {
    if (isApiError(error) && error.status === 401) {
      useAuthStore.getState().setAnonymous();
      return 'anonymous';
    }
    throw error;
  }
}

/**
 * The /auth/me right after a call that set new session cookies (login, or the setup wizard's
 * administrator creation). A 401 there means the browser did not keep those cookies (ADR-0010
 * C-197 (3), C-201 (2)): nobody is signed in on this page and {@link SessionNotKeptError} is
 * thrown, so that the page explains it instead of claiming a session (or opening a shell that
 * falls back to the login page with "session ended"). Any other failure resolves to null: the
 * caller keeps what it already knows, and the next /auth/me runs in the shell.
 */
export async function confirmNewSession(): Promise<MeResponse | null> {
  try {
    return await fetchMe();
  } catch (error) {
    if (isApiError(error) && error.status === 401) {
      useAuthStore.getState().setAnonymous();
      throw new SessionNotKeptError();
    }
    return null;
  }
}

/**
 * Logs in and loads the full session (/auth/me adds the permissions and the kiosk flag). After a
 * new login the WebSocket may connect again. The query cache is dropped before the new session is
 * set: it may still hold the data of another user of this tab (a session that ended by expiry),
 * masked for another role (C-13), which must not show before the new user's queries return.
 * Throws {@link SessionNotKeptError} when the browser did not keep the session cookies
 * ({@link confirmNewSession}).
 */
export async function signIn(
  username: string,
  password: string,
  queryClient: QueryClient,
): Promise<LoginResponse> {
  const result = await login(username, password);
  queryClient.clear();
  const me = await confirmNewSession();
  // Without /auth/me the login response identifies the user; permissions load with the next one.
  useAuthStore.getState().setSession(me ?? { ...result, permissions: [] });
  getWsManager().resetSession();
  return result;
}

/**
 * Drops the cached server data when the session expires (401 after the refresh, WebSocket 4401 or
 * reauth): the next person to sign in on this tab must not see the previous session's data.
 * Clearing also cancels the queries in flight (silently), so they cannot run into another 401;
 * a late 401 is a no-op anyway because the auth store only expires a live session. The auth store
 * stays independent of TanStack Query: the shell (AuthGate) mounts this hook.
 */
export function useDropCacheOnExpiry(): void {
  const queryClient = useQueryClient();
  useEffect(
    () =>
      useAuthStore.subscribe((state, previous) => {
        if (state.status === 'expired' && previous.status !== 'expired') {
          queryClient.clear();
        }
      }),
    [queryClient],
  );
}

/** `/login?next=<path+query>` for the current location (docs/15 §4.3 deep link 1). */
export function loginPath(location: { pathname: string; search: string; hash?: string }): string {
  const target = `${location.pathname}${location.search}${location.hash ?? ''}`;
  const next = safeNextPath(target);
  if (next === null) {
    return '/login';
  }
  const url = `/login?next=${encodeURIComponent(next)}`;
  return url.length <= URL_WRITE_LIMIT ? url : '/login';
}

/**
 * Logout (docs/14 §4.1): closes the WebSocket first (the client never keeps a socket of an ended
 * session, T-SEC-44 client part), ends the server session, marks the store signed out (the shell
 * then leaves for /login without `next`, and a late 401 of a screen cannot turn it into "session
 * expired"), goes to the login page and drops cached server data. The local state is cleared even
 * if the server call fails.
 */
export function useLogout(): () => Promise<void> {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useCallback(async () => {
    getWsManager().shutdown();
    try {
      await logout();
    } catch {
      // Already logged out or unreachable: the local session state goes anyway.
    }
    useAuthStore.getState().setSignedOut();
    await navigate('/login', { replace: true });
    queryClient.clear();
  }, [navigate, queryClient]);
}
