// SPDX-License-Identifier: Apache-2.0
//
// The async seed calls of the stack traffic (WO-15, e2e/stack/seed/traffic.ts): the sample app's
// answer is read strictly (asyncSql a non-negative integer, else a problem naming the contract),
// a transaction maps to the call whose interval holds its start, the manifest records the calls
// as ISO-8601 with milliseconds (schema of e2e/stack/manifest.ts), and the agent keeps every
// profile of the hop and async services only.
import { describe, expect, it } from 'vitest';

import { ManifestAsyncCall } from '../manifest';

import {
  ASYNC_PATH,
  ASYNC_SERVICE,
  ASYNC_GAP_MS,
  asyncCallOf,
  HOP_SERVICE,
  manifestAsyncCalls,
  parseAsyncAnswer,
  profileKeptProperties,
  type AsyncCall,
} from './traffic';

describe('parseAsyncAnswer', () => {
  it('reads asyncSql and the virtual-thread-per-task thread name of the contract', () => {
    expect(
      parseAsyncAnswer(
        JSON.stringify({ asyncThread: 'task-1', asyncSql: 5, virtualPerTaskThread: '' }),
      ),
    ).toEqual({ asyncSql: 5, virtualPerTaskThread: '', problem: null });
    expect(parseAsyncAnswer('{"asyncSql":0}')).toEqual({
      asyncSql: 0,
      virtualPerTaskThread: null,
      problem: null,
    });
  });

  it('names the problem when asyncSql is missing, not an integer or negative', () => {
    for (const text of [
      '{"asyncThread":"task-1"}',
      '{"asyncSql":"5"}',
      '{"asyncSql":2.5}',
      '{"asyncSql":-1}',
      '{"asyncSql":null}',
    ]) {
      const answer = parseAsyncAnswer(text);
      expect(answer.asyncSql, text).toBeNull();
      expect(answer.problem, text).toContain('asyncSql');
    }
    expect(parseAsyncAnswer('<html>').problem).toBe('the answer is not JSON');
    expect(parseAsyncAnswer('[1]').problem).toBe('the answer is not an object');
    expect(parseAsyncAnswer('null').problem).toBe('the answer is not an object');
  });
});

describe('asyncCallOf', () => {
  const base = Date.parse('2030-01-01T00:00:00Z');
  const calls: AsyncCall[] = [0, 1, 2].map((i) => ({
    sentAt: base + i * 10_000,
    doneAt: base + i * 10_000 + 400,
    status: 200,
    asyncSql: 5,
    virtualPerTaskThread: null,
    problem: null,
  }));

  it('takes the call whose interval holds the start, at both ends', () => {
    expect(asyncCallOf(base + 10_000, calls)).toBe(calls[1]);
    expect(asyncCallOf(base + 10_400, calls)).toBe(calls[1]);
    expect(asyncCallOf(new Date(base + 20_123).toISOString(), calls)).toBe(calls[2]);
  });

  it('takes the nearest call within the slack and nothing beyond it', () => {
    expect(asyncCallOf(base - 300, calls)).toBe(calls[0]);
    expect(asyncCallOf(base + 10_400 + 999, calls)).toBe(calls[1]);
    expect(asyncCallOf(base + 5000, calls)).toBeUndefined();
    expect(asyncCallOf(base + 10_400 + 1001, calls)).toBeUndefined();
    expect(asyncCallOf(base - 300, calls, 100)).toBeUndefined();
    expect(asyncCallOf('not a time', calls)).toBeUndefined();
  });

  it('keeps a start late by the clock difference on its own call when the seed spaces the calls', () => {
    // Calls as the seed sends them: about 40 ms each, ASYNC_GAP_MS apart.
    const spaced: AsyncCall[] = [0, 1, 2].map((i) => ({
      sentAt: base + i * (ASYNC_GAP_MS + 40),
      doneAt: base + i * (ASYNC_GAP_MS + 40) + 40,
      status: 200,
      asyncSql: 5,
      virtualPerTaskThread: null,
      problem: null,
    }));
    // The agent's clock 60 ms ahead of the host: every start lies after its own call's end.
    spaced.forEach((call) => {
      expect(asyncCallOf(call.sentAt + 60, spaced)).toBe(call);
      expect(asyncCallOf(call.sentAt - 60, spaced)).toBe(call);
    });
    expect(ASYNC_GAP_MS / 2).toBeGreaterThan(1000);
  });
});

describe('manifestAsyncCalls', () => {
  it('records every call in the manifest schema with millisecond times', () => {
    const sentAt = Date.parse('2030-01-01T00:00:00.120Z');
    const recorded = manifestAsyncCalls([
      {
        sentAt,
        doneAt: sentAt + 345,
        status: 200,
        asyncSql: 5,
        virtualPerTaskThread: '',
        problem: null,
      },
      {
        sentAt: sentAt + 1000,
        doneAt: sentAt + 2000,
        status: null,
        asyncSql: null,
        virtualPerTaskThread: null,
        problem: 'connect ECONNREFUSED',
      },
    ]);
    expect(recorded.map((r) => ManifestAsyncCall.parse(r))).toEqual(recorded);
    expect(recorded[0]).toEqual({
      sent_at: '2030-01-01T00:00:00.120Z',
      done_at: '2030-01-01T00:00:00.465Z',
      status: 200,
      async_sql: 5,
      virtual_per_task_thread: '',
      problem: null,
    });
    expect(recorded[1]?.problem).toBe('connect ECONNREFUSED');
  });
});

describe('agent sampling of the seed', () => {
  it('keeps every profile of the hop and async services only', () => {
    expect(profileKeptProperties()).toEqual([
      `-Dargos.sampling.profile.slow-threshold-ms.services.${HOP_SERVICE}=0`,
      '-Dargos.sampling.profile.slow-threshold-ms.services.GET /api/async/executor=0',
    ]);
    expect(ASYNC_SERVICE).toBe('GET /api/async/executor');
    expect(ASYNC_PATH).toBe('/api/async/executor?work=sql');
  });
});
