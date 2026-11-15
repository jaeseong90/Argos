// SPDX-License-Identifier: Apache-2.0
//
// Auth store and permission keys (docs/15 §11, docs/14 §7.1 role matrix): the store keeps who is
// logged in and nothing else (SEC-40), useCan mirrors the role matrix, pii.view needs the grant
// and the administrator role.
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-40' }, { type: 'req', description: 'SEC-49' }
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { highestRole, roleAtLeast, useAuthStore } from './authStore';
import { can, useCan, type Permission } from './permissions';

const SESSION = {
  user: { id: 'u-1', username: 'alice' },
  roles: ['VIEWER', 'OPERATOR'],
  permissions: ['pii.view'],
  must_change_password: false,
  session_expires_at: '2026-11-14T10:00:00.000Z',
};

beforeEach(() => {
  useAuthStore.setState({
    status: 'unknown',
    user: null,
    role: null,
    permissions: [],
    sessionExpiresAt: null,
    mustChangePassword: false,
  });
});

describe('T-SEC-40 auth store (docs/15 §11)', () => {
  it('T-SEC-40 keeps user, role, permissions and the session expiry only', () => {
    useAuthStore.getState().setSession(SESSION);
    const state = useAuthStore.getState();
    expect(state.status).toBe('authenticated');
    expect(state.user).toEqual({ id: 'u-1', username: 'alice' });
    expect(state.role).toBe('operator');
    expect(state.permissions).toEqual(['pii.view']);
    expect(state.sessionExpiresAt).toBe(Date.parse(SESSION.session_expires_at));
    const dataKeys = Object.entries(state)
      .filter(([, value]) => typeof value !== 'function')
      .map(([key]) => key)
      .sort();
    expect(dataKeys).toEqual(
      ['mustChangePassword', 'permissions', 'role', 'sessionExpiresAt', 'status', 'user'].sort(),
    );
    expect(JSON.stringify(state)).not.toMatch(/token|argos_at|argos_rt/i);
  });

  it('T-SEC-40 expire() ends an existing session; an anonymous visitor stays anonymous', () => {
    useAuthStore.getState().setSession(SESSION);
    useAuthStore.getState().expire();
    expect(useAuthStore.getState()).toMatchObject({ status: 'expired', user: null, role: null });
    useAuthStore.getState().setAnonymous();
    useAuthStore.getState().expire();
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('T-SEC-40 a refresh updates the expiry; an unreadable value is ignored', () => {
    useAuthStore.getState().setSession(SESSION);
    useAuthStore.getState().setSessionExpiresAt('2026-11-14T11:00:00.000Z');
    expect(useAuthStore.getState().sessionExpiresAt).toBe(Date.parse('2026-11-14T11:00:00.000Z'));
    useAuthStore.getState().setSessionExpiresAt('soon');
    useAuthStore.getState().setSessionExpiresAt(null);
    expect(useAuthStore.getState().sessionExpiresAt).toBe(Date.parse('2026-11-14T11:00:00.000Z'));
  });

  it('T-SEC-40 takes the highest role of any case and ignores unknown roles', () => {
    expect(highestRole(['viewer'])).toBe('viewer');
    expect(highestRole(['ADMIN', 'viewer'])).toBe('admin');
    expect(highestRole(['root', 'guest'])).toBeNull();
    expect(roleAtLeast('operator', 'viewer')).toBe(true);
    expect(roleAtLeast('viewer', 'operator')).toBe(false);
    expect(roleAtLeast(null, 'viewer')).toBe(false);
  });
});

describe('T-SEC-49 useCan (docs/14 §7.1)', () => {
  const MATRIX: readonly (readonly [Permission, boolean, boolean, boolean])[] = [
    // permission, viewer, operator, admin
    ['view', true, true, true],
    ['csv.export', true, true, true],
    ['commands.thread-dump', false, true, true],
    ['alerts.rules.edit', false, true, true],
    ['admin.view', false, true, true],
    ['commands.env-dump', false, false, true],
    ['channels.edit-url', false, false, true],
    ['tokens.manage', false, false, true],
    ['config.push', false, false, true],
    ['kill-switch', false, false, true],
    ['users.manage', false, false, true],
    ['audit.read', false, false, true],
    ['privacy.delete', false, false, true],
  ];

  it.each(MATRIX)(
    'T-SEC-49 %s: viewer %s, operator %s, admin %s',
    (key, viewer, operator, admin) => {
      expect(can(key, 'viewer', [])).toBe(viewer);
      expect(can(key, 'operator', [])).toBe(operator);
      expect(can(key, 'admin', [])).toBe(admin);
      expect(can(key, null, [])).toBe(false);
    },
  );

  it('T-SEC-49 pii.view needs the administrator role and the grant (docs/14 §7.1)', () => {
    expect(can('pii.view', 'admin', ['pii.view'])).toBe(true);
    expect(can('pii.view', 'admin', [])).toBe(false);
    expect(can('pii.view', 'operator', ['pii.view'])).toBe(false);
  });

  it('T-SEC-49 the hook follows the logged-in user', () => {
    const { result } = renderHook(() => useCan('users.manage'));
    expect(result.current).toBe(false);
    act(() => {
      useAuthStore.getState().setSession({ ...SESSION, roles: ['ADMIN'] });
    });
    expect(result.current).toBe(true);
    act(() => {
      useAuthStore.getState().setAnonymous();
    });
    expect(result.current).toBe(false);
  });
});
