// SPDX-License-Identifier: Apache-2.0
//
// Configuration change and kill switch calls of `/admin/config` (docs/14 §4.14, WO-39): the
// preview body (reason, values, targets), the 202 apply and kill switch envelopes, the revert,
// the polling rule (1 s while applying, stop when final or after 30 s) and the refusal messages
// that name the key (422 remote_key_not_allowed, 400 unknown_key, immutable, invalid_value,
// reduce_masking, use_kill_switch, 409 invalid_state, 429).
// Requirements (test/README.md §5): { type: 'req', description: 'F-S29' }, { type: 'req', description: 'SEC-23' }
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import { ApiError } from '../errors';

import {
  applyConfigChange,
  changeRefetchInterval,
  CONFIG_POLL_LIMIT_MS,
  CONFIG_POLL_MS,
  configProblem,
  proposeConfigChange,
  revertConfigChange,
  setKillSwitch,
} from './config';

export const REQ = { type: 'req', description: 'F-S29' };

const ID = '0a1b2c3d-4e5f-4a6b-8c7d-000000000001';

function change(status: string) {
  return {
    change_id: ID,
    app_id: 1,
    kind: 'config',
    status,
    reason: 'r',
    requested_at: '2026-11-24T00:00:00.000Z',
    requested_by: 'u-1',
    applied_at: null,
    applied_by: null,
    revert_of: null,
    targets: { all: true, instance_ids: [] },
    preview: { diff: [], rejected_keys_by_instance: [] },
    results: [],
  };
}

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('config calls (docs/14 §4.14)', () => {
  it('sends the preview with reason, values and chosen targets, and applies with the CSRF header', async () => {
    stub.csrfCookie = 'csrf-1';
    stub.on('POST /api/v1/admin/apps/1/config/changes', () =>
      envelope(change('previewed'), {}, 201),
    );
    stub.on(`POST /api/v1/admin/config/changes/${ID}/apply`, () =>
      envelope(change('applying'), {}, 202),
    );
    await proposeConfigChange(1, {
      reason: 'raise slow threshold',
      changes: { 'trace.slow-threshold-ms': '2000', 'capture.sql-parameters': null },
      targets: { all: false, instanceIds: [4, 5] },
    });
    expect(stub.calls('POST /api/v1/admin/apps/1/config/changes')[0]?.body).toEqual({
      reason: 'raise slow threshold',
      changes: { 'trace.slow-threshold-ms': '2000', 'capture.sql-parameters': null },
      targets: { all: false, instance_ids: [4, 5] },
    });
    const applied = await applyConfigChange(ID);
    expect(applied.status).toBe('applying');
    expect(
      stub.calls(`POST /api/v1/admin/config/changes/${ID}/apply`)[0]?.headers.get('X-Argos-CSRF'),
    ).toBe('csrf-1');
  });

  it('reverts with a reason and turns the kill switch on for every instance or one', async () => {
    stub.on(`POST /api/v1/admin/config/changes/${ID}/revert`, () =>
      envelope(change('applying'), {}, 201),
    );
    stub.on('POST /api/v1/admin/apps/1/kill-switch', () => envelope(change('applying'), {}, 202));
    await revertConfigChange(ID, 'back');
    expect(stub.calls(`POST /api/v1/admin/config/changes/${ID}/revert`)[0]?.body).toEqual({
      reason: 'back',
    });
    await setKillSwitch(1, { enabled: false, reason: 'incident', instanceIds: [] });
    await setKillSwitch(1, { enabled: true, reason: 'done', instanceIds: [7] });
    const calls = stub.calls('POST /api/v1/admin/apps/1/kill-switch');
    expect(calls[0]?.body).toEqual({ enabled: false, reason: 'incident' });
    expect(calls[1]?.body).toEqual({ enabled: true, reason: 'done', instance_ids: [7] });
  });
});

describe('config: result polling (N-25, docs/15 §9.10 "10초 내 갱신")', () => {
  it('polls every second while applying and stops when final or after 30 s', () => {
    expect(changeRefetchInterval(undefined, 0, 0)).toBe(CONFIG_POLL_MS);
    expect(changeRefetchInterval({ status: 'applying' }, 0, CONFIG_POLL_LIMIT_MS - 1)).toBe(
      CONFIG_POLL_MS,
    );
    expect(changeRefetchInterval({ status: 'applying' }, 0, CONFIG_POLL_LIMIT_MS)).toBe(false);
    expect(changeRefetchInterval({ status: 'applied' }, 0, 10)).toBe(false);
    expect(changeRefetchInterval({ status: 'superseded' }, 0, 10)).toBe(false);
    expect(changeRefetchInterval({ status: 'previewed' }, 0, 10)).toBe(false);
  });
});

describe('config: refusals name the key (docs/14 §4.14)', () => {
  const http = (
    status: number,
    code: string,
    details: { field: string | null; reason: string }[],
  ) => new ApiError({ kind: 'http', status, code, message: code, details });

  it('422 remote_key_not_allowed names the key no target allows', async () => {
    stub.on('POST /api/v1/admin/apps/1/config/changes', () =>
      errorBody(422, 'remote_key_not_allowed', {
        details: [
          { field: 'targets.instance_ids.4', reason: 'not_allowed' },
          { field: 'changes.capture.query-string', reason: 'not_allowed_by_targets' },
        ],
      }),
    );
    const error: unknown = await proposeConfigChange(1, {
      reason: 'r',
      changes: { 'capture.query-string': 'full' },
      targets: { all: true },
    }).catch((e: unknown) => e);
    expect(configProblem(error)).toEqual({ kind: 'keyNotAllowed', key: 'capture.query-string' });
  });

  it('400 reasons, 409, 429 and 403', () => {
    const k = (reason: string) =>
      configProblem(http(400, 'invalid_request', [{ field: 'changes.a.b', reason }]));
    expect(k('unknown_key')).toEqual({ kind: 'unknownKey', key: 'a.b' });
    expect(k('immutable')).toEqual({ kind: 'immutableKey', key: 'a.b' });
    expect(k('invalid_value')).toEqual({ kind: 'invalidValue', key: 'a.b' });
    expect(k('reduce_masking')).toEqual({ kind: 'reduceMasking', key: 'a.b' });
    expect(k('use_kill_switch').kind).toBe('killSwitchKey');
    expect(configProblem(http(409, 'invalid_state', [])).kind).toBe('invalidState');
    expect(configProblem(http(429, 'rate_limited', [])).kind).toBe('rateLimited');
    expect(configProblem(http(403, 'forbidden', [])).kind).toBe('forbidden');
    expect(configProblem(http(500, 'internal', [])).kind).toBe('other');
    expect(configProblem(new Error('x')).kind).toBe('other');
  });
});
