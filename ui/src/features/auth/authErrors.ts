// SPDX-License-Identifier: Apache-2.0
//
// Messages of the authentication screens for API errors (docs/15 §9.11, docs/16 §7.9, WO-24
// error codes). Login failures stay generic (no hint whether the user name exists); a lock says
// how long in general terms unless the response carries Retry-After (the 423 body has no
// remaining time).
import { isApiError } from '../../api/errors';

/** An i18n key of namespace auth with its interpolation values. */
export interface AuthMessage {
  readonly key:
    | 'login.error.invalid'
    | 'login.error.locked'
    | 'login.error.lockedFor'
    | 'login.error.rateLimited'
    | 'login.error.rateLimitedNoWait'
    | 'login.error.network'
    | 'login.error.server'
    | 'login.error.serverNoId'
    | 'login.error.required'
    | 'login.error.sessionNotKept'
    | 'password.error.mismatch'
    | 'password.error.tooShort'
    | 'password.error.tooLong'
    | 'password.error.denylisted'
    | 'password.error.containsUsername'
    | 'password.error.containsProductName'
    | 'password.error.sameAsCurrent'
    | 'password.error.currentIncorrect'
    | 'password.error.locked'
    | 'password.error.required'
    | 'password.error.failed'
    | 'password.error.failedNoId'
    | 'setup.error.token'
    | 'setup.error.closed'
    | 'setup.error.usernameTaken'
    | 'setup.error.rateLimited'
    | 'setup.error.rateLimitedNoWait'
    | 'setup.error.failed'
    | 'setup.error.failedNoId'
    | 'setup.token.required'
    | 'setup.admin.usernameInvalid';
  readonly values?: Readonly<Record<string, string | number>>;
}

/** Password length rules of 16 §7.9 (code points, no trimming). */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 128;

/** User names: 1 to 64 letters, digits and . _ @ + - (ADR-0010 C-132 (2)). */
export const USERNAME_PATTERN = /^[\p{L}\p{N}._@+-]{1,64}$/u;

function codePoints(text: string): number {
  return Array.from(text).length;
}

/** Client-side check of a new password before the server's policy (length, confirmation). */
export function checkNewPassword(next: string, confirm: string): AuthMessage | null {
  if (next === '' || confirm === '') {
    return { key: 'password.error.required' };
  }
  if (codePoints(next) < PASSWORD_MIN) {
    return { key: 'password.error.tooShort' };
  }
  if (codePoints(next) > PASSWORD_MAX) {
    return { key: 'password.error.tooLong' };
  }
  if (next !== confirm) {
    return { key: 'password.error.mismatch' };
  }
  return null;
}

/**
 * The login answered 200, but the /auth/me right after it answered 401: the browser did not keep
 * the session cookies (ADR-0010 C-197 (3)). They are Secure (16 §7.1), so this is what a browser
 * does over plain HTTP to a host other than localhost; blocked cookies end the same way. Carries no
 * request data.
 */
export class SessionNotKeptError extends Error {
  constructor() {
    super('The browser did not keep the session cookie after the login');
    this.name = 'SessionNotKeptError';
  }
}

/** Login failure message (docs/15 §9.11: generic text, lock, rate limit). */
export function loginErrorMessage(error: unknown): AuthMessage {
  if (error instanceof SessionNotKeptError) {
    // Not the generic failure: the credentials were right, the address is the problem.
    return { key: 'login.error.sessionNotKept' };
  }
  if (!isApiError(error) || error.kind === 'network') {
    return { key: 'login.error.network' };
  }
  switch (error.status) {
    case 400:
    case 401:
      return error.status === 400 && error.hasDetail('required')
        ? { key: 'login.error.required' }
        : { key: 'login.error.invalid' };
    case 423:
      // The server's 423 carries no remaining time today; a Retry-After, if ever sent, is shown.
      return error.retryAfterSeconds !== null && error.retryAfterSeconds > 0
        ? {
            key: 'login.error.lockedFor',
            values: { count: Math.ceil(error.retryAfterSeconds / 60) },
          }
        : { key: 'login.error.locked' };
    case 429:
      return error.retryAfterSeconds !== null
        ? { key: 'login.error.rateLimited', values: { seconds: error.retryAfterSeconds } }
        : { key: 'login.error.rateLimitedNoWait' };
    default:
      return error.requestId !== null
        ? { key: 'login.error.server', values: { requestId: error.requestId } }
        : { key: 'login.error.serverNoId' };
  }
}

const POLICY_REASONS: Readonly<Record<string, AuthMessage['key']>> = {
  too_short: 'password.error.tooShort',
  too_long: 'password.error.tooLong',
  denylisted: 'password.error.denylisted',
  contains_username: 'password.error.containsUsername',
  contains_product_name: 'password.error.containsProductName',
  same_as_current: 'password.error.sameAsCurrent',
  incorrect: 'password.error.currentIncorrect',
};

/** The first password-policy reason of a 422 password_policy error, if any. */
export function policyMessage(error: unknown): AuthMessage | null {
  if (!isApiError(error) || error.code !== 'password_policy') {
    return null;
  }
  for (const detail of error.details) {
    const key = POLICY_REASONS[detail.reason];
    if (key !== undefined) {
      return { key };
    }
  }
  return null;
}

/** Password change failure (docs/16 §7.9, ADR-0010 C-132 (5)). */
export function passwordErrorMessage(error: unknown): AuthMessage {
  const policy = policyMessage(error);
  if (policy !== null) {
    return policy;
  }
  if (isApiError(error) && error.status === 423) {
    return { key: 'password.error.locked' };
  }
  if (isApiError(error) && error.requestId !== null) {
    return { key: 'password.error.failed', values: { requestId: error.requestId } };
  }
  return { key: 'password.error.failedNoId' };
}

/** Which wizard step a setup failure belongs to, with its message. */
export interface SetupFailure {
  readonly step: 'token' | 'admin' | 'ipMode' | 'closed';
  readonly message: AuthMessage;
}

/** Setup failure (docs/14 §4.1, C-106 (1): token problems are 403, an existing admin 404). */
export function setupErrorMessage(error: unknown): SetupFailure {
  if (isApiError(error)) {
    if (error.status === 403) {
      return { step: 'token', message: { key: 'setup.error.token' } };
    }
    if (error.status === 404) {
      return { step: 'closed', message: { key: 'setup.error.closed' } };
    }
    if (error.status === 409 || error.hasDetail('already_exists', 'username')) {
      return { step: 'admin', message: { key: 'setup.error.usernameTaken' } };
    }
    const policy = policyMessage(error);
    if (policy !== null) {
      return { step: 'admin', message: policy };
    }
    if (error.status === 400 && error.details.some((d) => d.field === 'username')) {
      return { step: 'admin', message: { key: 'setup.admin.usernameInvalid' } };
    }
    if (error.status === 429) {
      return {
        step: 'ipMode',
        message:
          error.retryAfterSeconds !== null
            ? { key: 'setup.error.rateLimited', values: { seconds: error.retryAfterSeconds } }
            : { key: 'setup.error.rateLimitedNoWait' },
      };
    }
    if (error.requestId !== null) {
      return {
        step: 'ipMode',
        message: { key: 'setup.error.failed', values: { requestId: error.requestId } },
      };
    }
  }
  return { step: 'ipMode', message: { key: 'setup.error.failedNoId' } };
}
