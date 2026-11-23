// SPDX-License-Identifier: Apache-2.0
//
// Alert API wrapper (docs/14 §4.13, WO-38): the event query with every server filter and the
// cursor, ACK and mute bodies, the channel calls, and the readers of the 422 destination refusal,
// 400 field details, 409 conflicts, the transition reason and the rule unit.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';

import {
  ackAlert,
  alertEventsQuery,
  createAlertMute,
  createChannel,
  deleteAlertMute,
  deleteChannel,
  destinationBlockedReason,
  eventReason,
  getAlertStats,
  invalidField,
  isConflict,
  listActiveAlerts,
  listAlertEvents,
  rotateSigningKey,
  ruleUnit,
  testChannel,
  updateChannel,
  type AlertEventsRequest,
} from './alerts';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U10' };

const FROM = Date.parse('2026-11-23T00:00:00.000Z');
const TO = Date.parse('2026-11-23T01:00:00.000Z');

function request(overrides: Partial<AlertEventsRequest> = {}): AlertEventsRequest {
  return {
    from: FROM,
    to: TO,
    appId: null,
    env: null,
    severity: 'all',
    state: 'all',
    ruleId: null,
    failedOnly: false,
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

describe('alert events query (docs/14 §4.13 GET /alerts/events)', () => {
  it('sends only the range for the defaults (every application, server page size)', () => {
    expect(alertEventsQuery(request())).toEqual({
      from: '2026-11-23T00:00:00.000Z',
      to: '2026-11-23T01:00:00.000Z',
    });
  });

  it('maps every filter to its server parameter and the URL state to the API state', () => {
    expect(
      alertEventsQuery(
        request({
          appId: 3,
          env: 'prod',
          severity: 'critical',
          state: 'nodata',
          ruleId: '0b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9aa',
          failedOnly: true,
          limit: 250,
        }),
        'cursor-1',
      ),
    ).toEqual({
      from: '2026-11-23T00:00:00.000Z',
      to: '2026-11-23T01:00:00.000Z',
      app: 3,
      env: 'prod',
      severity: 'critical',
      state: 'NO_DATA',
      rule: '0b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9aa',
      delivery: 'failed',
      cursor: 'cursor-1',
      limit: 250,
    });
    expect(alertEventsQuery(request({ state: 'acked' })).state).toBe('ACKED');
    expect(alertEventsQuery(request({ state: 'pending' })).state).toBe('PENDING');
  });

  it('clamps the page size to the server maximum of 500', () => {
    expect(alertEventsQuery(request({ limit: 5000 })).limit).toBe(500);
    expect(alertEventsQuery(request({ limit: 0 })).limit).toBe(1);
  });

  it('calls the generated client with the query and returns the envelope', async () => {
    stub.on('GET /api/v1/alerts/events', () => envelope([], { next_cursor: 'c2' }));
    const result = await listAlertEvents(request({ appId: 1 }), 'c1');
    expect(result.meta.next_cursor).toBe('c2');
    const call = stub.calls('GET /api/v1/alerts/events')[0];
    expect(call?.query.get('app')).toBe('1');
    expect(call?.query.get('cursor')).toBe('c1');
  });
});

describe('alert actions (ACK, mutes, statistics, active alerts)', () => {
  it('sends the trimmed ACK note, and no note when it is blank', async () => {
    stub.on('POST /api/v1/alerts/events/e-1/ack', () =>
      envelope({
        event_id: 'e-1',
        state: 'ACKED',
        acked_by: 'alice',
        acked_at: '2026-11-23T00:10:00.000Z',
        note: 'on it',
      }),
    );
    await ackAlert('e-1', '  on it  ');
    await ackAlert('e-1', '   ');
    const bodies = stub.calls('POST /api/v1/alerts/events/e-1/ack').map((call) => call.body);
    expect(bodies).toEqual([{ note: 'on it' }, {}]);
  });

  it('a 409 of an alert that is no longer firing is a conflict', async () => {
    stub.on('POST /api/v1/alerts/events/e-2/ack', () => errorBody(409, 'invalid_state'));
    const error = await ackAlert('e-2', '').catch((caught: unknown) => caught);
    expect(isConflict(error)).toBe(true);
  });

  it('creates and deletes a mute with the request as given', async () => {
    stub.on('POST /api/v1/alerts/mutes', () =>
      envelope(
        {
          mute_id: 'm-1',
          from: '2026-11-23T00:00:00.000Z',
          to: '2026-11-23T04:00:00.000Z',
          reason: null,
          target: { app: 1, env: null, instance: null, rule: null },
          created_at: null,
          created_by: null,
        },
        {},
        201,
      ),
    );
    stub.on('DELETE /api/v1/alerts/mutes/m-1', () => noContent());
    await createAlertMute({
      from: '2026-11-23T00:00:00.000Z',
      to: '2026-11-23T04:00:00.000Z',
      target: { app: 1 },
    });
    await deleteAlertMute('m-1');
    expect(stub.calls('POST /api/v1/alerts/mutes')[0]?.body).toEqual({
      from: '2026-11-23T00:00:00.000Z',
      to: '2026-11-23T04:00:00.000Z',
      target: { app: 1 },
    });
    expect(stub.calls('DELETE /api/v1/alerts/mutes/m-1')).toHaveLength(1);
  });

  it('asks the statistics for 30 days of one application, and 7 days without parameters', async () => {
    stub.on('GET /api/v1/alerts/stats', () => envelope([]));
    await getAlertStats({ days: 30, appId: 4 });
    await getAlertStats({ days: 7, appId: null });
    const queries = stub
      .calls('GET /api/v1/alerts/stats')
      .map((call) => Object.fromEntries(call.query.entries()));
    expect(queries).toEqual([{ days: '30', app: '4' }, {}]);
  });

  it('asks the active alerts of an environment', async () => {
    stub.on('GET /api/v1/overview/alerts', () => envelope([]));
    await listActiveAlerts('prod');
    await listActiveAlerts(null);
    const queries = stub
      .calls('GET /api/v1/overview/alerts')
      .map((call) => Object.fromEntries(call.query.entries()));
    expect(queries).toEqual([{ env: 'prod' }, {}]);
  });
});

describe('channel calls (docs/14 §4.13 channels, docs/16 §8)', () => {
  const channel = {
    channel_id: 'c-1',
    type: 'webhook',
    name: 'ops',
    target_masked: 'https://hooks.example:443/****',
    allow_http: false,
    enabled: true,
    signing_enabled: true,
    status: 'ok',
    consecutive_failures: 0,
    last_success_at: null,
    last_failure_at: null,
    created_at: '2026-11-23T00:00:00.000Z',
    updated_at: '2026-11-23T00:00:00.000Z',
  };

  it('creates, updates, tests, rotates and deletes through the generated client', async () => {
    stub.on('POST /api/v1/alerts/channels', () =>
      envelope({ channel, signing_key: 'one-time-secret' }, {}, 201),
    );
    stub.on('PUT /api/v1/alerts/channels/c-1', () => envelope(channel));
    stub.on('POST /api/v1/alerts/channels/c-1/test', () =>
      envelope({ delivered: true, status_code: 204, duration_ms: 31, error: null }),
    );
    stub.on('POST /api/v1/alerts/channels/c-1/signing-key/rotate', () =>
      envelope({ signing_key: 'rotated', previous_valid_until: '2026-11-30T00:00:00.000Z' }),
    );
    stub.on('DELETE /api/v1/alerts/channels/c-1', () => noContent());
    const created = await createChannel({
      type: 'webhook',
      name: 'ops',
      target: 'https://hooks.example/x',
    });
    expect(created.signing_key).toBe('one-time-secret');
    await updateChannel('c-1', { name: 'ops-2' });
    expect((await testChannel('c-1')).status_code).toBe(204);
    expect((await rotateSigningKey('c-1')).signing_key).toBe('rotated');
    await deleteChannel('c-1');
    expect(stub.calls('PUT /api/v1/alerts/channels/c-1')[0]?.body).toEqual({ name: 'ops-2' });
    expect(stub.calls('DELETE /api/v1/alerts/channels/c-1')).toHaveLength(1);
  });

  it('reads the reason of a 422 destination refusal and nothing from other errors', async () => {
    stub.on('POST /api/v1/alerts/channels', () =>
      errorBody(422, 'webhook_destination_blocked', {
        details: [{ field: 'target', reason: 'link_local' }],
      }),
    );
    const error = await createChannel({
      type: 'webhook',
      name: 'x',
      target: 'https://169.254.169.254/',
    }).catch((caught: unknown) => caught);
    expect(destinationBlockedReason(error)).toBe('link_local');
    expect(invalidField(error)).toBeNull();
    expect(destinationBlockedReason(new Error('x'))).toBeNull();
  });

  it('an unknown refusal reason is the generic one; a 400 detail names the field', async () => {
    stub.on('POST /api/v1/alerts/channels', () =>
      errorBody(422, 'webhook_destination_blocked', {
        details: [{ field: 'target', reason: 'new_rule' }],
      }),
    );
    const blocked = await createChannel({ type: 'slack', name: 'x', target: 'https://x/' }).catch(
      (caught: unknown) => caught,
    );
    expect(destinationBlockedReason(blocked)).toBe('blocked');
    stub.on('POST /api/v1/alerts/channels', () =>
      errorBody(400, 'invalid_request', {
        details: [{ field: 'target', reason: 'invalid_address' }],
      }),
    );
    const invalid = await createChannel({ type: 'email', name: 'x', target: 'not-mail' }).catch(
      (caught: unknown) => caught,
    );
    expect(invalidField(invalid)).toEqual({ field: 'target', reason: 'invalid_address' });
  });
});

describe('additive fields (ADR-0010 C-227 (3), (4))', () => {
  it('reads a known reason, and null for old rows and unknown values', () => {
    expect(eventReason({ reason: 'target_gone' })).toBe('target_gone');
    expect(eventReason({ reason: null })).toBeNull();
    expect(eventReason({})).toBeNull();
    expect(eventReason({ reason: 'surprise' })).toBeNull();
  });

  it('reads the rule unit as sent, null while the server does not send it', () => {
    expect(ruleUnit({ unit: 'ratio' })).toBe('ratio');
    expect(ruleUnit({})).toBeNull();
  });
});
