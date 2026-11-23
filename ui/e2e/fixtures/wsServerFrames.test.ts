// SPDX-License-Identifier: Apache-2.0
//
// T-API-007, UI side: the frames the WO-22 server writes (its serializer's fixed vectors,
// server/src/test/resources/io/argos/server/realtime/ws-frame-vectors.json, kept equal to the
// generated frames by the server's WsFrameVectorsTest) pass the UI's own parsers: every server
// frame src/realtime/frames.ts parseServerFrame, every xlog push payload
// src/features/xlog/live/pushData.ts parseXLogPush. The client frames of docs/14 §5.2 (and the
// vectors' client examples) pass the E2E fixture's ClientFrame schema and the server's parameter
// rules as the stub applies them. The stub's own frames parse as well, so stub and server speak
// the same contract (ADR-0010 C-167 (1)).
//
// Vector file: {"server": [{"name": ..., "frame": <JSON text or object>}, ...],
//               "client": [{"name": ..., "frame": ...}, ...]?}  (a bare array = the server list).
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { eventReason } from '../../src/api/alerts';
import { parseXLogPush } from '../../src/features/xlog/live/pushData';
import { parseServerFrame } from '../../src/realtime/frames';
import { REPO_ROOT } from '../stack/paths';

import {
  ClientFrame,
  errorFrame,
  invalidParams,
  notice,
  push,
  reauthFrame,
  TOPICS,
  WsConnectionState,
  xlogPush,
  type Topic,
} from './wsProtocol';

const VECTOR_FILE = join(
  REPO_ROOT,
  'server',
  'src',
  'test',
  'resources',
  'io',
  'argos',
  'server',
  'realtime',
  'ws-frame-vectors.json',
);

interface Vector {
  readonly name: string;
  readonly text: string;
}

function toVector(entry: unknown, index: number, side: string): Vector {
  if (typeof entry === 'string') {
    return { name: `${side}[${String(index)}]`, text: entry };
  }
  if (typeof entry === 'object' && entry !== null && 'frame' in entry) {
    const { frame, name } = entry as { frame: unknown; name?: unknown };
    return {
      name: typeof name === 'string' ? name : `${side}[${String(index)}]`,
      text: typeof frame === 'string' ? frame : JSON.stringify(frame),
    };
  }
  throw new Error(`${side}[${String(index)}] of ${VECTOR_FILE} is neither a string nor {frame}`);
}

/** The server and client vectors of the file. */
function readVectors(file: string = VECTOR_FILE): { server: Vector[]; client: Vector[] } {
  const json = JSON.parse(readFileSync(file, 'utf8')) as unknown;
  const server = Array.isArray(json) ? json : (json as { server?: unknown }).server;
  const client = Array.isArray(json) ? [] : ((json as { client?: unknown }).client ?? []);
  if (!Array.isArray(server) || !Array.isArray(client)) {
    throw new Error(`${file}: expected {"server": [...], "client": [...]} or an array`);
  }
  return {
    server: server.map((e, i) => toVector(e, i, 'server')),
    client: client.map((e, i) => toVector(e, i, 'client')),
  };
}

/** The client frames of docs/14 §5.2, verbatim (the admin change id filled in). */
const DOC_CLIENT_FRAMES: readonly string[] = [
  '{"op": "subscribe", "id": "s1", "topic": "xlog", "params": {"app": 3, "env": "prod", "kind": "HTTP", "service": [], "instance": []}}',
  '{"op": "subscribe", "id": "s2", "topic": "counter", "params": {"app": 3}}',
  '{"op": "subscribe", "id": "s3", "topic": "active", "params": {"app": 3}}',
  '{"op": "subscribe", "id": "s4", "topic": "alert", "params": {"env": "prod"}}',
  '{"op": "subscribe", "id": "s5", "topic": "overview", "params": {"env": "prod"}}',
  '{"op": "subscribe", "id": "s6", "topic": "admin", "params": {"change_id": "c-1"}}',
  '{"op": "unsubscribe", "id": "s1"}',
  '{"op": "ping", "t": 1729645323456}',
];

/** What T-API-007 needs the vectors to cover (WO-22 plan: ack, push of 4 topics, 4 error codes, ...). */
const REQUIRED_PUSH_TOPICS: readonly Topic[] = ['xlog', 'counter', 'active', 'overview', 'alert'];
const REQUIRED_ERROR_CODES = ['forbidden', 'limit_exceeded', 'invalid_params', 'topic_unknown'];
const INSTANCE_STATES = ['UP', 'STALE', 'DOWN', 'WARN'];

describe('the server frames of WO-22 pass the UI parsers (docs/14 §5.2)', () => {
  const present = existsSync(VECTOR_FILE);

  it('T-API-007 has the vector file of the server (WsFrameVectorsTest)', () => {
    expect(present, `${VECTOR_FILE} (written by the server's WsFrameVectorsTest)`).toBe(true);
  });

  it.runIf(present)('T-API-007 parses every server frame with parseServerFrame', () => {
    const { server } = readVectors();
    expect(server.length).toBeGreaterThan(0);
    const rejected = server
      .filter((v) => parseServerFrame(v.text) === null)
      .map((v) => `${v.name}: ${v.text.slice(0, 200)}`);
    expect(rejected, 'server frames the UI parser rejects').toEqual([]);
  });

  it.runIf(present)('T-API-007 covers every frame kind, push topic and error code', () => {
    const frames = readVectors().server.map((v) => parseServerFrame(v.text));
    const ops = new Set(frames.map((f) => f?.op));
    for (const op of ['ack', 'push', 'error', 'pong', 'notice', 'reauth']) {
      expect(ops, `a ${op} frame among the vectors`).toContain(op);
    }
    const pushTopics = new Set(frames.flatMap((f) => (f?.op === 'push' ? [f.topic] : [])));
    for (const topic of REQUIRED_PUSH_TOPICS) {
      expect(pushTopics, `a push of topic ${topic}`).toContain(topic);
    }
    const codes = new Set(frames.flatMap((f) => (f?.op === 'error' ? [f.code] : [])));
    for (const code of REQUIRED_ERROR_CODES) {
      expect(codes, `an error with code ${code}`).toContain(code);
    }
    const notices = frames.flatMap((f) => (f?.op === 'notice' ? [f] : []));
    expect(notices.some((n) => n.code === 'backpressure' && n.topic === 'xlog')).toBe(true);
  });

  it.runIf(present)(
    'T-API-007 parses every xlog push payload with parseXLogPush (C-175 strings)',
    () => {
      const pushes = readVectors()
        .server.map((v) => parseServerFrame(v.text))
        .flatMap((f) => (f?.op === 'push' && f.topic === 'xlog' ? [f] : []));
      expect(pushes.length).toBeGreaterThan(0);
      for (const frame of pushes) {
        const columns = parseXLogPush(frame.data);
        expect(columns, `xlog push ${JSON.stringify(frame.data).slice(0, 200)}`).not.toBeNull();
        // 64-bit keys stay decimal strings (ADR-0010 C-175 (1)); the raw JSON proves it.
        const raw = frame.data as { tx_id: unknown[]; service_id: unknown[] };
        expect(raw.tx_id.every((id) => typeof id === 'string')).toBe(true);
        expect(raw.service_id.every((id) => typeof id === 'string')).toBe(true);
        for (const names of Object.values(frame.dict ?? {})) {
          expect(Object.keys(names).every((key) => /^[0-9]{1,20}$/.test(key))).toBe(true);
        }
      }
    },
  );

  it.runIf(present)(
    'T-API-007 reports instance states of the state machine in counter pushes',
    () => {
      const counters = readVectors()
        .server.map((v) => parseServerFrame(v.text))
        .flatMap((f) => (f?.op === 'push' && f.topic === 'counter' ? [f] : []));
      expect(counters.length).toBeGreaterThan(0);
      for (const frame of counters) {
        const perInstance = (frame.data as { per_instance?: unknown }).per_instance;
        expect(Array.isArray(perInstance), 'counter data.per_instance is an array').toBe(true);
        for (const row of perInstance as { instance_id?: unknown; state?: unknown }[]) {
          expect(typeof row.instance_id).toBe('number');
          expect(INSTANCE_STATES).toContain(row.state);
        }
      }
    },
  );

  it.runIf(present)(
    'T-API-007 every alert push carries a reason of the history closed list (C-228 (2))',
    () => {
      const alerts = readVectors()
        .server.map((v) => parseServerFrame(v.text))
        .flatMap((f) => (f?.op === 'push' && f.topic === 'alert' ? [f] : []));
      expect(alerts.length).toBeGreaterThan(0);
      for (const frame of alerts) {
        const event = (frame.data as { event?: { reason?: unknown } }).event;
        expect(event, 'alert data.event').toBeDefined();
        expect(eventReason(event ?? {}), JSON.stringify(event)).not.toBeNull();
      }
    },
  );

  it.runIf(present)(
    'T-API-007 accepts the vectors client frames like the docs/14 §5.2 examples',
    () => {
      const client = readVectors().client.map((v) => v.text);
      for (const text of [...DOC_CLIENT_FRAMES, ...client]) {
        const parsed = ClientFrame.safeParse(JSON.parse(text));
        expect(parsed.success, text).toBe(true);
        if (parsed.success && parsed.data.op === 'subscribe') {
          expect(TOPICS as readonly string[]).toContain(parsed.data.topic);
          expect(invalidParams(parsed.data.topic as Topic, parsed.data.params), text).toBeNull();
        }
      }
    },
  );
});

describe('the E2E stub writes the frames the UI parses (same contract as the server)', () => {
  it('accepts the docs/14 §5.2 client examples and answers them with valid server frames', () => {
    const state = new WsConnectionState();
    for (const text of DOC_CLIENT_FRAMES) {
      for (const answer of state.receive(text)) {
        expect(parseServerFrame(answer), answer).not.toBeNull();
      }
    }
    expect(state.violations).toEqual([]);
    // The six subscriptions minus the unsubscribed s1.
    expect(state.of().map((s) => s.id)).toEqual(['s2', 's3', 's4', 's5', 's6']);
  });

  it('builds push, notice, error and reauth frames the UI parses', () => {
    const xlog = { id: 's1', topic: 'xlog' as const, params: { app: 3 } };
    const frames = [
      xlogPush(
        xlog,
        [
          {
            tx_id: '18446744073709551615',
            ts: 1,
            elapsed_ms: 2,
            status: 2,
            instance_id: 7,
            service_id: '17',
            kind: 1,
          },
        ],
        { '17': 'GET /api/orders/{id}' },
      ),
      push({ id: 's2', topic: 'counter', params: { app: 3 } }, { tps: 1, per_instance: [] }),
      notice('xlog', 3),
      errorFrame('s9', 'forbidden', 'admin only'),
      reauthFrame(),
    ];
    for (const frame of frames) {
      expect(parseServerFrame(frame), frame).not.toBeNull();
    }
    const parsed = parseServerFrame(frames[0]);
    expect(parsed?.op === 'push' ? parseXLogPush(parsed.data) : null).not.toBeNull();
  });
});
