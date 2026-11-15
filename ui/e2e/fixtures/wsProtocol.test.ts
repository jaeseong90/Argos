// SPDX-License-Identifier: Apache-2.0
//
// The E2E WebSocket stub answers like the docs/14 §5 server (e2e/fixtures/wsProtocol.ts) and
// records every client frame that breaks §5.2.
import { describe, expect, it } from 'vitest';

import { MAX_SUBSCRIPTIONS, notice, reauthFrame, WsConnectionState, xlogPush } from './wsProtocol';

const NOW = new Date('2030-01-01T00:00:00.000Z');

describe('E2E WebSocket stub protocol (docs/14 §5)', () => {
  it('acks a subscription of a known topic and keeps it until unsubscribe', () => {
    const state = new WsConnectionState();
    const out = state.receive(
      JSON.stringify({
        op: 'subscribe',
        id: 's1',
        topic: 'xlog',
        params: { app: 3, kind: 'HTTP' },
      }),
      NOW,
    );
    expect(out.map((f) => JSON.parse(f) as unknown)).toEqual([
      { op: 'ack', id: 's1', topic: 'xlog', since: '2030-01-01T00:00:00.000Z' },
    ]);
    expect(state.of('xlog')).toEqual([
      { id: 's1', topic: 'xlog', params: { app: 3, kind: 'HTTP' } },
    ]);
    expect(state.receive(JSON.stringify({ op: 'unsubscribe', id: 's1' }), NOW)).toEqual([]);
    expect(state.of()).toEqual([]);
    expect(state.violations).toEqual([]);
  });

  it('answers ping with pong carrying the client time', () => {
    const state = new WsConnectionState();
    const [pong] = state.receive(JSON.stringify({ op: 'ping', t: 1729645323456 }), NOW);
    expect(JSON.parse(pong ?? '')).toEqual({
      op: 'pong',
      t: 1729645323456,
      server_t: '2030-01-01T00:00:00.000Z',
    });
  });

  it('rejects an unknown topic and the 21st subscription with error frames', () => {
    const state = new WsConnectionState();
    expect(
      JSON.parse(
        state.receive(JSON.stringify({ op: 'subscribe', id: 'x', topic: 'nope', params: {} }))[0] ??
          '',
      ),
    ).toMatchObject({
      op: 'error',
      id: 'x',
      code: 'topic_unknown',
    });
    for (let i = 0; i < MAX_SUBSCRIPTIONS; i += 1) {
      state.receive(
        JSON.stringify({
          op: 'subscribe',
          id: `s${String(i)}`,
          topic: 'counter',
          params: { app: i },
        }),
      );
    }
    expect(
      JSON.parse(
        state.receive(
          JSON.stringify({ op: 'subscribe', id: 's99', topic: 'counter', params: {} }),
        )[0] ?? '',
      ),
    ).toMatchObject({
      code: 'limit_exceeded',
    });
    expect(state.of('counter')).toHaveLength(MAX_SUBSCRIPTIONS);
  });

  it('records frames that break docs/14 §5.2 and answers nothing to them', () => {
    const state = new WsConnectionState();
    expect(state.receive('not json')).toEqual([]);
    expect(state.receive(JSON.stringify({ op: 'subscribe', id: 's1', topic: 'xlog' }))).toEqual([]);
    expect(state.receive(JSON.stringify({ op: 'ping', t: 1, extra: true }))).toEqual([]);
    expect(
      state.receive(
        JSON.stringify({
          op: 'subscribe',
          id: 's',
          topic: 'xlog',
          params: { q: 'x'.repeat(5000) },
        }),
      ),
    ).toEqual([]);
    expect(state.violations).toHaveLength(4);
  });

  it('withholds every answer while paused', () => {
    const state = new WsConnectionState();
    state.paused = true;
    expect(state.receive(JSON.stringify({ op: 'ping', t: 5 }))).toEqual([]);
    expect(state.frames).toHaveLength(1);
  });

  it('builds columnar xlog pushes, notices and the reauth frame', () => {
    const subscription = { id: 's1', topic: 'xlog' as const, params: {} };
    const frame = JSON.parse(
      xlogPush(
        subscription,
        [
          {
            tx_id: '18446744073709551615',
            ts: '2030-01-01T00:00:00Z',
            elapsed_ms: 12,
            status: 'ok',
            instance_id: 7,
            service_id: '17',
            kind: 'HTTP',
          },
        ],
        { '17': 'GET /api/orders/{id}' },
        NOW,
      ),
    ) as { data: { tx_id: string[] }; dict: unknown; op: string };
    expect(frame.op).toBe('push');
    // 64-bit ids stay decimal strings (ADR-0010 C-175).
    expect(frame.data.tx_id).toEqual(['18446744073709551615']);
    expect(frame.dict).toEqual({ service: { '17': 'GET /api/orders/{id}' } });
    expect(JSON.parse(notice('xlog', 1250))).toEqual({
      op: 'notice',
      code: 'backpressure',
      topic: 'xlog',
      dropped: 1250,
    });
    expect(JSON.parse(reauthFrame())).toEqual({ op: 'reauth' });
  });
});
