// SPDX-License-Identifier: Apache-2.0
//
// Authentication and setup calls (docs/14 §4.1, WO-24): login, logout, me, password change,
// setup status and the first administrator. Cookies carry the session; nothing here returns or
// stores a token (SEC-40).
import { api, expectNoContent, unwrap, type EnvelopeOf } from './client';
import type { components } from './generated/schema';

export type MeResponse = components['schemas']['MeResponse'];
export type LoginResponse = components['schemas']['LoginResponse'];
export type SetupStatus = components['schemas']['SetupStatusResponse'];
export type UserView = components['schemas']['UserView'];

/**
 * IP collection modes of the setup wizard (docs/14 §4.1, SEC-31). The contract types ip_mode as a
 * plain string; the server accepts exactly these values (SetupService.IP_MODES).
 */
export const IP_MODES = ['full', 'masked', 'off'] as const;
export type IpMode = (typeof IP_MODES)[number];

/** Obtains the CSRF cookie (docs/14 §2.2): at start and after login. */
export async function fetchCsrfToken(): Promise<void> {
  await api().transport.refreshCsrf();
}

/** The logged-in user, roles, permissions and session expiry (docs/14 §4.1). */
export async function fetchMe(): Promise<MeResponse> {
  return (await unwrap<EnvelopeOf<'/api/v1/auth/me', 'get'>>(api().client.GET('/api/v1/auth/me')))
    .data;
}

/** Logs in; the server sets new session and CSRF cookies (docs/16 §7.6). */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const result = await unwrap<EnvelopeOf<'/api/v1/auth/login', 'post'>>(
    api().client.POST('/api/v1/auth/login', { body: { username, password } }),
  );
  // The login rotates the CSRF cookie; read it again before the next state change.
  await fetchCsrfToken();
  return result.data;
}

/** Ends the session (204; the server deletes the cookies and closes WebSockets). */
export async function logout(): Promise<void> {
  await expectNoContent(api().client.POST('/api/v1/auth/logout'));
}

/** Changes the caller's password (204 with three new cookies, 16 §7.4, WO-24). */
export async function changePassword(current: string, next: string): Promise<void> {
  await expectNoContent(
    api().client.PUT('/api/v1/auth/password', { body: { current, new: next } }),
  );
  await fetchCsrfToken();
}

/** Whether the first administrator still has to be created (docs/14 §4.1). */
export async function fetchSetupStatus(): Promise<SetupStatus> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/setup/status', 'get'>>(
      api().client.GET('/api/v1/setup/status'),
    )
  ).data;
}

export interface CreateAdminInput {
  readonly setupToken: string;
  readonly username: string;
  readonly password: string;
  readonly ipMode: IpMode;
}

/**
 * Creates the first administrator with the one-time setup token (docs/16 §7.8, SEC-47) and logs
 * in. The token goes in the X-Argos-Setup-Token header, not in the body.
 */
export async function createAdmin(input: CreateAdminInput): Promise<UserView> {
  const result = await unwrap<EnvelopeOf<'/api/v1/setup/admin', 'post'>>(
    api().client.POST('/api/v1/setup/admin', {
      params: { header: { 'X-Argos-Setup-Token': input.setupToken } },
      body: { username: input.username, password: input.password, ip_mode: input.ipMode },
    }),
  );
  await fetchCsrfToken();
  return result.data.user;
}
