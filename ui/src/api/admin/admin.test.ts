// SPDX-License-Identifier: Apache-2.0
//
// Administration API modules of WO-39 lane A (docs/14 §4.3, §4.14; 05 §2.11): the requests the
// generated client sends (paths, query, bodies with CSRF), the collection ranking order with
// missing values last (D-23), the configuration-majority drift rule, the token "expires soon"
// boundary (the warn threshold of the default template cert_token_expiry, 30 days) and the
// user lockout state.
// Requirements (test/README.md §5): { type: 'req', description: 'F-S28' }, { type: 'req', description: 'SEC-02' }
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  installApiStub,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  appBody,
  collectionBody,
  collectionHealthBody,
  instanceBody,
  issuedBody,
  tokenBody,
  userBody,
} from '../../features/admin/testing/fixtures';

import { applyRecommendedRules, deleteApp, tokensActive } from './apps';
import {
  hasConfigDrift,
  instanceCollection,
  listAdminInstances,
  majorityConfigHash,
  rankCollection,
} from './instances';
import { approveRegistration, rejectRegistration } from './pending';
import {
  expiresSoon,
  issueToken,
  revokeToken,
  rotateToken,
  TOKEN_EXPIRY_WARN_DAYS,
} from './tokens';
import { createUser, isLocked, patchUser, roleOf } from './users';

const DAY = 86_400_000;
const NOW = Date.parse('2026-11-24T00:00:00.000Z');

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('applications (docs/14 §4.3)', () => {
  it('reads tokens_active, null from a server that does not send it', () => {
    const older: Partial<ReturnType<typeof appBody>> = { ...appBody(1) };
    delete older.tokens_active;
    expect(tokensActive(older as never)).toBeNull();
    expect(tokensActive(appBody(1, { tokens_active: 2 }))).toBe(2);
  });

  it('deletes with the name as confirmation and applies the recommended rules by app id', async () => {
    stub.on('DELETE /api/v1/apps/7', () =>
      envelope({ app_id: 7, rules_deleted: 1, sessions_closed: 2, tokens_revoked: 3 }, {}, 202),
    );
    stub.on('POST /api/v1/alerts/templates/apply', () => envelope({ created: [], skipped: [] }));
    const deleted = await deleteApp(7, 'order api');
    expect(deleted.tokens_revoked).toBe(3);
    expect(stub.calls('DELETE /api/v1/apps/7')[0]?.query.get('confirm')).toBe('order api');
    await applyRecommendedRules(7);
    const apply = stub.calls('POST /api/v1/alerts/templates/apply')[0];
    expect(apply?.body).toEqual({ app: 7 });
    expect(apply?.headers.get('X-Argos-CSRF')).toBe('csrf-token-1');
  });
});

describe('instances and collection health (05 §2.11-2, §2.11-8)', () => {
  it('asks for the archived list with archived=true only', async () => {
    stub.on('GET /api/v1/apps/3/instances', () => envelope([instanceBody(1)]));
    await listAdminInstances(3, false);
    await listAdminInstances(3, true);
    const calls = stub.calls('GET /api/v1/apps/3/instances');
    expect(calls[0]?.query.has('archived')).toBe(false);
    expect(calls[1]?.query.get('archived')).toBe('true');
  });

  it('takes per_instance of the collection health of the range', async () => {
    stub.on('GET /api/v1/apps/3/collection-health', () =>
      envelope(collectionHealthBody([collectionBody(5, { drops: 9 })])),
    );
    const result = await instanceCollection(
      3,
      '2026-11-24T00:00:00.000Z',
      '2026-11-24T01:00:00.000Z',
    );
    expect(result.data).toEqual([collectionBody(5, { drops: 9 })]);
    const query = stub.calls('GET /api/v1/apps/3/collection-health')[0]?.query;
    expect(query?.get('from')).toBe('2026-11-24T00:00:00.000Z');
    expect(query?.get('to')).toBe('2026-11-24T01:00:00.000Z');
  });

  it('ranks the largest value first, the clock offset by its magnitude, missing values last', () => {
    const rows = [
      collectionBody(1, { drops: 3, skew_ms: -900 }),
      collectionBody(2, { drops: null, skew_ms: null }),
      collectionBody(3, { drops: 10, skew_ms: 200 }),
      collectionBody(4, { drops: 0, skew_ms: 1500 }),
    ];
    expect(rankCollection(rows, 'drops').map((r) => r.instance_id)).toEqual([3, 1, 4, 2]);
    expect(rankCollection(rows, 'skew_ms').map((r) => r.instance_id)).toEqual([4, 1, 3, 2]);
  });

  it('marks the configuration hash that differs from the application majority', () => {
    const list = [
      instanceBody(1, { config_hash: 'a' as never }),
      instanceBody(2, { config_hash: 'a' as never }),
      instanceBody(3, { config_hash: 'b' as never }),
      instanceBody(4, { config_hash: null }),
    ];
    const majority = majorityConfigHash(list);
    expect(majority).toBe('a');
    expect(list.map((i) => hasConfigDrift(i, majority))).toEqual([false, false, true, false]);
    // The server's own verdict wins when it gives one.
    expect(
      hasConfigDrift(instanceBody(5, { config_hash: 'a' as never, config_drift: true }), 'a'),
    ).toBe(true);
    expect(majorityConfigHash([])).toBeNull();
  });
});

describe('agent tokens (docs/16 §2.1, SEC-02)', () => {
  it('issues with the expiry, rotates with 7 grace days, revokes with the reason', async () => {
    stub.on('POST /api/v1/admin/apps/2/tokens', () => envelope(issuedBody(), {}, 201));
    stub.on('POST /api/v1/admin/tokens/t-1/rotate', () => envelope(issuedBody(), {}, 201));
    stub.on('POST /api/v1/admin/tokens/t-1/revoke', () => noContent());
    await issueToken(2, 90);
    await rotateToken('t-1');
    await revokeToken('t-1', 'leaked in a ticket');
    expect(stub.calls('POST /api/v1/admin/apps/2/tokens')[0]?.body).toEqual({
      expires_in_days: 90,
    });
    expect(stub.calls('POST /api/v1/admin/tokens/t-1/rotate')[0]?.body).toEqual({ grace_days: 7 });
    expect(stub.calls('POST /api/v1/admin/tokens/t-1/revoke')[0]?.body).toEqual({
      reason: 'leaked in a ticket',
    });
  });

  it('badges live tokens that expire within 30 days, the boundary included', () => {
    expect(TOKEN_EXPIRY_WARN_DAYS).toBe(30);
    const at = (ms: number) => new Date(ms).toISOString();
    expect(expiresSoon(tokenBody('a', { expires_at: at(NOW + 30 * DAY) }), NOW)).toBe(true);
    expect(expiresSoon(tokenBody('b', { expires_at: at(NOW + 30 * DAY + 1) }), NOW)).toBe(false);
    expect(expiresSoon(tokenBody('c', { expires_at: at(NOW + DAY), state: 'rotating' }), NOW)).toBe(
      true,
    );
    expect(expiresSoon(tokenBody('d', { expires_at: at(NOW - 1) }), NOW)).toBe(false);
    expect(expiresSoon(tokenBody('e', { expires_at: null }), NOW)).toBe(false);
    expect(expiresSoon(tokenBody('f', { expires_at: at(NOW + DAY), state: 'revoked' }), NOW)).toBe(
      false,
    );
  });
});

describe('registration requests (F-S30)', () => {
  it('approves with group and Apdex T and rejects by id', async () => {
    stub.on('POST /api/v1/admin/pending-registrations/4/approve', () =>
      envelope(
        { app: appBody(9), token: { token: 'x', token_id: 'y', prefix: 'p', expires_at: null } },
        {},
        201,
      ),
    );
    stub.on('POST /api/v1/admin/pending-registrations/4/reject', () => noContent());
    await approveRegistration(4, { group: 'shop', apdex_t_ms: 300 });
    await rejectRegistration(4);
    expect(stub.calls('POST /api/v1/admin/pending-registrations/4/approve')[0]?.body).toEqual({
      group: 'shop',
      apdex_t_ms: 300,
    });
    expect(stub.calls('POST /api/v1/admin/pending-registrations/4/reject')).toHaveLength(1);
  });
});

describe('users (docs/14 §4.14)', () => {
  it('creates with one role and no password (the server generates one), patches the role', async () => {
    stub.on('POST /api/v1/admin/users', () =>
      envelope({ user: userBody('u1'), initial_password: 'p' }, {}, 201),
    );
    stub.on('PATCH /api/v1/admin/users/u1', () =>
      envelope(userBody('u1', { roles: ['operator'] })),
    );
    await createUser({ username: 'kim', role: 'operator', mustChangePassword: true });
    expect(stub.calls('POST /api/v1/admin/users')[0]?.body).toEqual({
      username: 'kim',
      roles: ['operator'],
      must_change_password: true,
    });
    const patched = await patchUser('u1', { roles: ['operator'] });
    expect(roleOf(patched)).toBe('operator');
  });

  it('is locked only while locked_until is in the future', () => {
    expect(isLocked(userBody('a'), NOW)).toBe(false);
    expect(
      isLocked(userBody('b', { locked_until: new Date(NOW + 60_000).toISOString() }), NOW),
    ).toBe(true);
    expect(isLocked(userBody('c', { locked_until: new Date(NOW - 1).toISOString() }), NOW)).toBe(
      false,
    );
  });
});
