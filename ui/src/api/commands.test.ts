// SPDX-License-Identifier: Apache-2.0
//
// Remote command calls (docs/14 §4.10, WO-37): request body and 202 envelope, state, result with
// format=json|text, the instance list with its limit (≤ 100), the error kinds of the screens
// (409 instance_offline, 409 invalid_state = another command running, 429 rate_limited with
// Retry-After, 409 secret_unavailable, 403, 404, network), end reasons (ttl, timeout,
// session_ended, rejected, cooldown, agent_failed …), the 1 s polling that stops at the end or the
// deadline (ADR-0010 C-231 (3)), result visibility (SEC-50) and the requester name.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'SEC-50' }
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';

import {
  canReadResult,
  COMMAND_POLL_MARGIN_MS,
  COMMAND_POLL_MS,
  COMMAND_RESULT_TIMEOUT_MS,
  commandErrorKind,
  commandRefetchInterval,
  endReason,
  getCommand,
  getCommandResult,
  listInstanceCommands,
  pollDeadline,
  requestCommand,
  requesterName,
  type CommandView,
} from './commands';
import { ApiError } from './errors';
import { untrusted } from './untrusted';

const ID = '0a1b2c3d-4e5f-4a6b-8c7d-000000000001';

function view(overrides: Partial<CommandView> = {}): CommandView {
  return {
    command_id: ID,
    instance_id: 7,
    type: 'thread_dump',
    args: {},
    status: 'queued',
    error: null,
    requested_by: 'u-1',
    requested_by_name: null,
    requested_at: '2026-11-24T10:00:00.000Z',
    sent_at: null,
    finished_at: null,
    expires_at: '2026-11-24T10:01:00.000Z',
    duration_ms: null,
    result_size: null,
    truncated: null,
    ...overrides,
  };
}

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('command calls', () => {
  it('requests a command with its arguments and reads the 202 envelope', async () => {
    stub.on('POST /api/v1/instances/7/commands', () =>
      envelope({ command_id: ID, status: 'queued', ttl_s: 60 }, {}, 202),
    );
    const queued = await requestCommand(7, 'thread_stack', { tx_id: '101' });
    expect(queued).toEqual({ command_id: ID, status: 'queued', ttl_s: 60 });
    const call = stub.calls('POST /api/v1/instances/7/commands')[0];
    expect(call?.body).toEqual({ type: 'thread_stack', args: { tx_id: '101' } });
    // A state-changing call carries the CSRF header (transport).
    expect(call?.headers.get('X-Argos-CSRF')).toBe('csrf-token-1');
    await requestCommand(7, 'thread_dump');
    expect(stub.calls('POST /api/v1/instances/7/commands')[1]?.body).toEqual({
      type: 'thread_dump',
    });
  });

  it('reads the state, the result in both formats and the list with a clamped limit', async () => {
    stub.on(`GET /api/v1/commands/${ID}`, () => envelope(view({ status: 'sent' })));
    stub.on(`GET /api/v1/commands/${ID}/result`, (r) =>
      envelope({
        command_id: ID,
        instance_id: 7,
        type: 'thread_dump',
        format: r.query.get('format'),
        content_type: 'text/plain',
        size_bytes: 3,
        truncated: false,
        text: 'abc',
        threads: null,
        deadlocks: null,
      }),
    );
    stub.on('GET /api/v1/instances/7/commands', () => envelope([view()]));
    expect((await getCommand(ID)).status).toBe('sent');
    expect((await getCommandResult(ID, 'text')).format).toBe('text');
    expect((await getCommandResult(ID, 'json')).format).toBe('json');
    expect(await listInstanceCommands(7, 500)).toHaveLength(1);
    await listInstanceCommands(7, 0);
    const limits = stub.calls('GET /api/v1/instances/7/commands').map((c) => c.query.get('limit'));
    expect(limits).toEqual(['100', '1']);
  });

  it('maps the server errors to what the screens say', async () => {
    const cases: [Response, string][] = [
      [errorBody(409, 'instance_offline'), 'instance_offline'],
      [errorBody(409, 'invalid_state'), 'busy'],
      [errorBody(429, 'rate_limited', { headers: { 'Retry-After': '17' } }), 'rate_limited'],
      [errorBody(409, 'secret_unavailable'), 'secret_unavailable'],
      [errorBody(403, 'forbidden'), 'forbidden'],
      [errorBody(403, 'not_owner'), 'not_owner'],
      [errorBody(404, 'not_found'), 'not_found'],
    ];
    for (const [response, kind] of cases) {
      stub.on('POST /api/v1/instances/7/commands', () => response.clone());
      const error = await requestCommand(7, 'thread_dump').catch((e: unknown) => e);
      expect(commandErrorKind(error), kind).toBe(kind);
      if (kind === 'rate_limited') {
        expect((error as ApiError).retryAfterSeconds).toBe(17);
      }
    }
    expect(
      commandErrorKind(new ApiError({ kind: 'network', status: 0, code: 'network', message: '' })),
    ).toBe('network');
    expect(commandErrorKind(new Error('x'))).toBe('other');
  });
});

describe('command state rules', () => {
  it('knows the end reasons and calls a new one unknown', () => {
    for (const reason of [
      'ttl',
      'timeout',
      'session_ended',
      'rejected',
      'cooldown',
      'agent_failed',
    ]) {
      expect(endReason(reason)).toBe(reason);
    }
    expect(endReason(null)).toBeNull();
    expect(endReason('brand_new_reason')).toBe('unknown');
  });

  it('polls every second until done/failed/expired or the deadline (TTL + result timeout + margin)', () => {
    const now = Date.parse('2026-11-24T10:00:30.000Z');
    expect(commandRefetchInterval(undefined, now)).toBe(COMMAND_POLL_MS);
    expect(commandRefetchInterval(view({ status: 'queued' }), now)).toBe(1000);
    expect(commandRefetchInterval(view({ status: 'sent' }), now)).toBe(1000);
    for (const status of ['done', 'failed', 'expired']) {
      expect(commandRefetchInterval(view({ status }), now)).toBe(false);
    }
    const deadline = pollDeadline(view());
    expect(deadline).toBe(
      Date.parse('2026-11-24T10:01:00.000Z') + COMMAND_RESULT_TIMEOUT_MS + COMMAND_POLL_MARGIN_MS,
    );
    expect(commandRefetchInterval(view({ status: 'sent' }), deadline + 1)).toBe(false);
  });

  it('lets the requester and administrators read a result, administrators only for admin commands (SEC-50)', () => {
    expect(canReadResult({ requested_by: 'u-1', type: 'thread_dump' }, 'u-1', false)).toBe(true);
    expect(canReadResult({ requested_by: 'u-2', type: 'thread_dump' }, 'u-1', false)).toBe(false);
    expect(canReadResult({ requested_by: 'u-2', type: 'thread_dump' }, 'u-1', true)).toBe(true);
    expect(canReadResult({ requested_by: 'u-1', type: 'env_dump' }, 'u-1', false)).toBe(false);
    expect(canReadResult({ requested_by: 'u-1', type: 'reload_config' }, 'u-1', true)).toBe(true);
    expect(canReadResult({ requested_by: 'u-1', type: 'thread_dump' }, null, false)).toBe(false);
  });

  it('reads requested_by_name when the server sends it', () => {
    expect(requesterName(view())).toBeNull();
    expect(requesterName(view({ requested_by_name: untrusted('alice') }))).toBe('alice');
    expect(requesterName(view({ requested_by_name: untrusted('') }))).toBeNull();
    // A server without the field (older contract) gives no name.
    const older: Partial<CommandView> = view();
    delete older.requested_by_name;
    expect(requesterName(older as CommandView)).toBeNull();
  });
});
