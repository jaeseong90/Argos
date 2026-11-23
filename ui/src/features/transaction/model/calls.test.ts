// SPDX-License-Identifier: Apache-2.0
//
// SQL, connection-wait and external-call rows of the detail tabs (05 §2.2-5, 2.2-6) and the STACK
// comparison (05 §2.2-8, docs/15 §8.5) built from typed fixtures: external failure classes
// (TIMEOUT / CONNECT_FAIL / 5xx) are not in the E2E seed before WO-14 (ADR-0010 C-207 (5)).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'SEC-3A' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { sqlStepFixture, stepFixture } from '../data/testing/fixtures';
import type { TransactionStep } from '../data/transactionApi';

import { errorSteps, extRows, failKind, poolWaits, sqlRows } from './calls';
import { compareCaptures, isAppFrame, stackCaptures } from './stacks';

describe('SQL rows and connection waits (05 §2.2-5)', () => {
  const steps = [
    stepFixture({
      seq: 1,
      type: 'DBC',
      start_offset_ms: 0,
      elapsed_ms: 30,
      name: untrusted('HikariPool-1'),
      target: untrusted('db1'),
    }),
    sqlStepFixture(2, {
      start_offset_ms: 30,
      elapsed_ms: 40,
      exec_ms: 35,
      fetch_ms: 5,
      rows: 3,
      params_mode: 'masked_by_role',
      params: untrusted('[String(5)]'),
    }),
    stepFixture({
      seq: 3,
      type: 'COMMIT',
      start_offset_ms: 80,
      elapsed_ms: 2,
      target: untrusted('db1'),
    }),
    stepFixture({
      seq: 4,
      type: 'DBC',
      start_offset_ms: 90,
      elapsed_ms: 10,
      repeat: 2,
      name: untrusted('HikariPool-1'),
      target: untrusted('db1'),
    }),
    stepFixture({
      seq: 5,
      type: 'SUMMARY',
      start_offset_ms: 100,
      elapsed_ms: 70,
      sql_id: '3',
      sql: untrusted('SELECT 1'),
      summary: { step_type: 'SQL', count: 7, total_ms: 70, max_ms: 20, failures: 0 },
    }),
    stepFixture({ seq: 6, type: 'METHOD', start_offset_ms: 0, elapsed_ms: 200 }),
  ];

  it('lists SQL, COMMIT and SQL summaries in start order with the server values', () => {
    const rows = sqlRows(steps);
    expect(rows.map((r) => [r.seq, r.type, r.executions])).toEqual([
      [2, 'SQL', 1],
      [3, 'COMMIT', 1],
      [5, 'SUMMARY', 7],
    ]);
    expect(rows[0]).toMatchObject({
      execMs: 35,
      fetchMs: 5,
      rows: 3,
      paramsMode: 'masked_by_role',
    });
    expect(rows[1]?.paramsMode).toBeNull();
    expect(rows[2]?.elapsedMs).toBe(70);
  });

  it('groups connection waits by pool and target, apart from execution', () => {
    expect(poolWaits(steps)).toEqual([
      { pool: 'HikariPool-1', target: 'db1', count: 3, totalMs: 40, maxMs: 30, errors: 0 },
    ]);
  });
});

describe('external calls (05 §2.2-6)', () => {
  const call = (seq: number, extra: Partial<TransactionStep>) =>
    stepFixture({
      seq,
      type: 'HTTP',
      start_offset_ms: seq * 10,
      elapsed_ms: 5,
      name: untrusted('pay.internal'),
      name_id: '99',
      url: untrusted('/pay'),
      ...extra,
    });

  it('maps the failure classes TIMEOUT / CONNECT_FAIL / 5xx / other', () => {
    expect(failKind(call(1, { failure: 'timeout', status: 1, error: true }))).toBe('timeout');
    expect(failKind(call(2, { failure: 'connect_fail', status: 2, error: true }))).toBe(
      'connect_fail',
    );
    expect(failKind(call(3, { failure: 'http_5xx', status: 3, error: true }))).toBe('http_5xx');
    expect(failKind(call(4, { status: 503, error: true }))).toBe('http_5xx');
    expect(failKind(call(5, { failure: 'something_new', status: 9, error: true }))).toBe(
      'other_error',
    );
    expect(failKind(call(6, { error: true }))).toBe('other_error');
    expect(failKind(call(7, { status: 200 }))).toBeNull();
  });

  it('rows carry host, host key, path, HTTP status only for 100..599 and the failure class', () => {
    const rows = extRows([
      call(1, { failure: 'timeout', status: 1, error: true }),
      call(2, { status: 200, rows: 512 }),
      stepFixture({
        seq: 3,
        type: 'MESSAGE',
        start_offset_ms: 1,
        elapsed_ms: 1,
        name: untrusted('kafka:9092'),
      }),
    ]);
    expect(rows.map((r) => [r.seq, r.type, r.httpStatus, r.failKind])).toEqual([
      [3, 'MESSAGE', null, null],
      [1, 'HTTP', null, 'timeout'],
      [2, 'HTTP', 200, null],
    ]);
    expect(rows[1]).toMatchObject({ host: 'pay.internal', hostId: '99', path: '/pay' });
    expect(rows[2]?.bytes).toBe(512);
  });

  it('error steps in start order', () => {
    expect(
      errorSteps([
        stepFixture({ seq: 2, type: 'ERROR', start_offset_ms: 50 }),
        stepFixture({ seq: 1, type: 'ERROR', start_offset_ms: 10 }),
        stepFixture({ seq: 3, type: 'SQL', start_offset_ms: 0 }),
      ]).map((s) => s.seq),
    ).toEqual([1, 2]);
  });
});

describe('STACK comparison (docs/15 §8.5)', () => {
  const capture = (seq: number, start: number, frames: string[]) =>
    stepFixture({ seq, type: 'STACK', start_offset_ms: start, frames: frames.map(untrusted) });

  it('marks frames common to every capture and the deepest of them as stuck', () => {
    const captures = stackCaptures([
      capture(9, 3000, [
        'java.net.SocketInputStream.read',
        'com.shop.Pay.call',
        'com.shop.Order.save',
        'Thread.run',
      ]),
      capture(8, 1000, [
        'sun.misc.Unsafe.park',
        'com.shop.Pay.call',
        'com.shop.Order.save',
        'Thread.run',
      ]),
    ]);
    expect(captures.map((c) => c.seq)).toEqual([8, 9]);
    const { common, stuckAt } = compareCaptures(captures);
    expect([...common]).toEqual(['com.shop.Pay.call', 'com.shop.Order.save', 'Thread.run']);
    expect(stuckAt).toBe('com.shop.Pay.call');
  });

  it('a single capture has nothing to compare', () => {
    const { common, stuckAt } = compareCaptures(stackCaptures([capture(1, 0, ['a.B.c'])]));
    expect(common.size).toBe(0);
    expect(stuckAt).toBeNull();
  });

  it('application frames need the packages (none until WO-26, C-207 (3))', () => {
    expect(isAppFrame('com.shop.Pay.call(Pay.java)', [])).toBe(false);
    expect(isAppFrame('com.shop.Pay.call(Pay.java)', ['com.shop'])).toBe(true);
    expect(isAppFrame('com.shopping.X.y(X.java)', ['com.shop'])).toBe(false);
  });
});
