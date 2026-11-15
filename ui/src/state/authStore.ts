// SPDX-License-Identifier: Apache-2.0
//
// Who is logged in (docs/15 §11). Holds the user, the role, the permission keys and the session
// expiry from /auth/me or the login response, and nothing else: no token ever reaches JavaScript
// (the session cookies are HttpOnly, SEC-40). Transient state, never persisted.
import { create } from 'zustand';

export type Role = 'viewer' | 'operator' | 'admin';
/**
 * unknown: not asked yet; authenticated; anonymous: no session (never logged in or after a reload
 * without cookies); expired: the session ended by itself (401 after refresh, 4401, reauth);
 * signedOut: the user logged out on this page (the shell goes to /login without `next`).
 */
export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous' | 'expired' | 'signedOut';

const ROLE_RANK: Record<Role, number> = { viewer: 1, operator: 2, admin: 3 };

/** The highest role of a role list (`ADMIN` ⊃ `OPERATOR` ⊃ `VIEWER`, 16 §7.10), any case. */
export function highestRole(roles: readonly string[]): Role | null {
  let best: Role | null = null;
  for (const raw of roles) {
    const role = raw.toLowerCase();
    if (role === 'viewer' || role === 'operator' || role === 'admin') {
      if (best === null || ROLE_RANK[role] > ROLE_RANK[best]) {
        best = role;
      }
    }
  }
  return best;
}

export function roleAtLeast(role: Role | null, minimum: Role): boolean {
  return role !== null && ROLE_RANK[role] >= ROLE_RANK[minimum];
}

export interface AuthUser {
  readonly id: string;
  /** Rendered as text only (docs/16 §9.1 lists user names as untrusted input). */
  readonly username: string;
}

/** The fields of /auth/me and the login response that the store keeps. */
export interface SessionInfo {
  readonly user: { readonly id: string; readonly username: string };
  readonly roles: readonly string[];
  readonly permissions?: readonly string[];
  readonly must_change_password: boolean;
  readonly session_expires_at: string;
}

export interface AuthState {
  readonly status: AuthStatus;
  readonly user: AuthUser | null;
  readonly role: Role | null;
  readonly permissions: readonly string[];
  /** Session expiry, epoch ms (kiosk warning 10 minutes before, docs/15 §3.4). */
  readonly sessionExpiresAt: number | null;
  /** The server refuses everything but the password change until it is changed (16 §7.9). */
  readonly mustChangePassword: boolean;
  readonly setSession: (info: SessionInfo) => void;
  readonly setSessionExpiresAt: (iso: string | null) => void;
  readonly setMustChangePassword: (value: boolean) => void;
  /** The session expired or was revoked (401 after refresh, WebSocket 4401/reauth). */
  readonly expire: () => void;
  /** Never logged in, or no session any more after a reload. */
  readonly setAnonymous: () => void;
  /** The user logged out on this page. */
  readonly setSignedOut: () => void;
}

function epochOf(iso: string | null): number | null {
  if (iso === null) {
    return null;
  }
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : ms;
}

const SIGNED_OUT = {
  user: null,
  role: null,
  permissions: [],
  sessionExpiresAt: null,
  mustChangePassword: false,
} as const;

export const useAuthStore = create<AuthState>()((set, get) => ({
  status: 'unknown',
  ...SIGNED_OUT,
  setSession: (info) => {
    set({
      status: 'authenticated',
      user: { id: info.user.id, username: info.user.username },
      role: highestRole(info.roles),
      permissions: [...(info.permissions ?? [])],
      sessionExpiresAt: epochOf(info.session_expires_at),
      mustChangePassword: info.must_change_password,
    });
  },
  setSessionExpiresAt: (iso) => {
    const ms = epochOf(iso);
    if (ms !== null) {
      set({ sessionExpiresAt: ms });
    }
  },
  setMustChangePassword: (value) => {
    set({ mustChangePassword: value });
  },
  expire: () => {
    // Only a session that existed can expire; an anonymous visitor stays anonymous.
    if (get().status === 'authenticated' || get().status === 'unknown') {
      set({ status: 'expired', ...SIGNED_OUT });
    }
  },
  setAnonymous: () => {
    set({ status: 'anonymous', ...SIGNED_OUT });
  },
  setSignedOut: () => {
    set({ status: 'signedOut', ...SIGNED_OUT });
  },
}));
