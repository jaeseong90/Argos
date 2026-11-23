// SPDX-License-Identifier: Apache-2.0
//
// Thread dump model (05 §2.7-5, docs/15 §9.8, WO-37): state groups and counts, identical stacks
// collapsed by hash (×N), lock holder → waiters, deadlocks from the agent's list and from a cycle
// of the wait-for graph (text dumps), virtual threads, search over names and frames, tx matching,
// the 64-frame depth cut and the truncated flag, and a 5,000-thread dump (time as a reference).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { dumpResult, frames, thread } from '../commands/testing/fixtures';

import {
  buildDumpModel,
  fnv1a,
  lockId,
  MAX_DUMP_FRAMES,
  stackHash,
  stateGroup,
  threadOfTransaction,
  waitForCycles,
  dumpThreads,
} from './dumpModel';

const POOL = frames(
  'jdk.internal.misc.Unsafe.park(Native Method)',
  'java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)',
  'org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)',
);

describe('dump model', () => {
  it('groups threads by state with counts and collapses identical stacks (×N)', () => {
    const threads = [
      ...Array.from({ length: 8 }, (_, i) =>
        thread({
          id: 10 + i,
          name: `http-nio-8080-exec-${String(i)}`,
          state: untrusted('WAITING (parking)'),
          frames: POOL,
        }),
      ),
      thread({ id: 1, name: 'main', state: untrusted('RUNNABLE') }),
      thread({
        id: 2,
        name: 'sleeper',
        state: untrusted('TIMED_WAITING (sleeping)'),
        frames: frames('java.lang.Thread.sleep(Native Method)'),
      }),
      thread({ id: 3, name: 'new', state: untrusted('NEW'), frames: [] }),
      thread({ id: 4, name: 'nostate', state: null, frames: [] }),
    ];
    const model = buildDumpModel(dumpResult(threads));
    expect(model.total).toBe(12);
    expect(model.counts).toEqual({
      BLOCKED: 0,
      RUNNABLE: 1,
      WAITING: 8,
      TIMED_WAITING: 1,
      OTHER: 2,
    });
    expect(model.sections.map((s) => s.group)).toEqual([
      'RUNNABLE',
      'WAITING',
      'TIMED_WAITING',
      'OTHER',
    ]);
    const waiting = model.sections.find((s) => s.group === 'WAITING');
    expect(waiting?.stacks).toHaveLength(1);
    expect(waiting?.stacks[0]?.threads).toHaveLength(8);
    expect(waiting?.stacks[0]?.hash).toBe(stackHash('WAITING', POOL));
    // NEW and no state share OTHER and, both without frames, one stack entry.
    expect(model.sections.find((s) => s.group === 'OTHER')?.stacks[0]?.threads).toHaveLength(2);
  });

  it('keeps different stacks apart and orders the largest group first', () => {
    const a = frames('a.A.run(A.java:1)');
    const b = frames('b.B.run(B.java:1)');
    const model = buildDumpModel(
      dumpResult([
        thread({ id: 1, frames: a }),
        thread({ id: 2, frames: b }),
        thread({ id: 3, frames: b }),
      ]),
    );
    const runnable = model.sections[0];
    expect(runnable?.stacks.map((s) => s.threads.length)).toEqual([2, 1]);
    expect(stateGroup('RUNNABLE')).toBe('RUNNABLE');
    expect(stateGroup('blocked (on object monitor)')).toBe('BLOCKED');
    expect(stateGroup('TERMINATED')).toBe('OTHER');
    expect(fnv1a('')).toBe('811c9dc5');
  });

  it('relates lock holders and waiters, and takes the agent deadlock list', () => {
    const lockA = untrusted('<0x000000076ab0c0d8> (a java.lang.Object)');
    const lockB = untrusted('<0x000000076ab0c0e8> (a java.lang.Object)');
    const threads = [
      thread({
        id: 21,
        name: 'dl-1',
        state: untrusted('BLOCKED'),
        locks_held: [lockA],
        waiting_on: lockB,
      }),
      thread({
        id: 22,
        name: 'dl-2',
        state: untrusted('BLOCKED'),
        locks_held: [lockB],
        waiting_on: lockA,
      }),
      thread({
        id: 23,
        name: 'waiter',
        state: untrusted('BLOCKED'),
        waiting_on: untrusted('<0x000000076AB0C0D8> (a java.lang.Object)'),
      }),
    ];
    const model = buildDumpModel(dumpResult(threads, { deadlocks: [{ thread_ids: [21, 22] }] }));
    expect(model.deadlockSource).toBe('agent');
    expect(model.deadlocks).toHaveLength(1);
    expect(model.deadlocks[0]?.map((t) => t.name)).toEqual(['dl-1', 'dl-2']);
    expect(model.deadlocked.has('id:21')).toBe(true);
    expect(model.deadlocked.has('id:23')).toBe(false);
    const a = model.locks.find((l) => l.lock === '0x000000076ab0c0d8');
    expect(a?.holder?.name).toBe('dl-1');
    expect(a?.waiters.map((w) => w.name)).toEqual(['dl-2', 'waiter']);
    // The lock with more waiters comes first.
    expect(model.locks[0]?.lock).toBe('0x000000076ab0c0d8');
    expect(lockId('java.lang.Object@1b2c3d ')).toBe('java.lang.Object@1b2c3d');
  });

  it('finds a deadlock as a wait-for cycle when the dump has no deadlock list (text dumps)', () => {
    const l1 = untrusted('<0x1> (a A)');
    const l2 = untrusted('<0x2> (a B)');
    const l3 = untrusted('<0x3> (a C)');
    const threads = [
      thread({
        id: null,
        name: 'x',
        state: untrusted('BLOCKED'),
        locks_held: [l1],
        waiting_on: l2,
      }),
      thread({
        id: null,
        name: 'y',
        state: untrusted('BLOCKED'),
        locks_held: [l2],
        waiting_on: l3,
      }),
      thread({
        id: null,
        name: 'z',
        state: untrusted('BLOCKED'),
        locks_held: [l3],
        waiting_on: l1,
      }),
      // Waits on the cycle without being part of it.
      thread({ id: null, name: 'tail', state: untrusted('BLOCKED'), waiting_on: l1 }),
      // Object.wait() on its own monitor is not a self-deadlock.
      thread({
        id: null,
        name: 'self',
        state: untrusted('WAITING'),
        locks_held: [untrusted('<0x9> (a D)')],
        waiting_on: untrusted('<0x9> (a D)'),
      }),
    ];
    const model = buildDumpModel(dumpResult(threads));
    expect(model.deadlockSource).toBe('graph');
    expect(model.deadlocks).toHaveLength(1);
    expect(model.deadlocks[0]?.map((t) => t.name).sort()).toEqual(['x', 'y', 'z']);
    expect(model.deadlocked.has('#3')).toBe(false);
    const none = buildDumpModel(dumpResult([thread({ waiting_on: untrusted('<0x5> (a E)') })]));
    expect(none.deadlockSource).toBe('none');
    expect(waitForCycles(dumpThreads(dumpResult([])), new Map())).toEqual([]);
  });

  it('counts virtual threads, matches transactions and filters by name, class or method', () => {
    const threads = [
      thread({
        id: 1,
        name: 'vt-order-1',
        virtual: true,
        tx_id: '7312000000000001',
        frames: frames('com.shop.OrderService.place(OrderService.java:88)'),
      }),
      thread({ id: 2, name: 'vt-order-2', virtual: true }),
      thread({ id: 3, name: 'scheduler', frames: frames('com.shop.Cron.tick(Cron.java:5)') }),
      thread({ id: 4, name: 'bad-tx', tx_id: 'not-a-number' }),
    ];
    const model = buildDumpModel(dumpResult(threads));
    expect(model.virtualCount).toBe(2);
    expect(model.txThreads.map((t) => t.txId)).toEqual(['7312000000000001']);
    expect(threadOfTransaction(model.threads, '7312000000000001')?.name).toBe('vt-order-1');
    expect(threadOfTransaction(model.threads, '1')).toBeNull();
    const byMethod = buildDumpModel(dumpResult(threads), 'orderservice.place');
    expect(
      byMethod.sections.flatMap((s) => s.stacks.flatMap((st) => st.threads.map((t) => t.name))),
    ).toEqual(['vt-order-1']);
    const byName = buildDumpModel(dumpResult(threads), 'SCHED');
    expect(
      byName.sections.flatMap((s) => s.stacks.flatMap((st) => st.threads.map((t) => t.name))),
    ).toEqual(['scheduler']);
    // Totals stay those of the whole dump.
    expect(byName.total).toBe(4);
    expect(buildDumpModel(dumpResult(threads), 'nothing-like-this').sections).toEqual([]);
  });

  it('flags the 64-frame depth cut and the truncated result', () => {
    const deep = Array.from({ length: MAX_DUMP_FRAMES }, (_, i) =>
      untrusted(`com.deep.F.f${String(i)}(F.java:${String(i)})`),
    );
    const cut = buildDumpModel(dumpResult([thread({ frames: deep })]));
    expect(cut.depthCut).toBe(true);
    expect(cut.truncated).toBe(true);
    const whole = buildDumpModel(dumpResult([thread()]));
    expect(whole.truncated).toBe(false);
    expect(buildDumpModel(dumpResult([thread()], { truncated: true })).truncated).toBe(true);
  });

  it('builds a 5,000-thread dump (reference time, shared machine: not a judgement)', () => {
    const threads = Array.from({ length: 5000 }, (_, i) =>
      thread({
        id: i,
        name: `worker-${String(i)}`,
        state: untrusted(i % 3 === 0 ? 'RUNNABLE' : 'WAITING (parking)'),
        frames: i % 3 === 0 ? frames(`com.app.Job.run${String(i % 50)}(Job.java:1)`) : POOL,
      }),
    );
    const started = performance.now();
    const model = buildDumpModel(dumpResult(threads));
    const elapsed = performance.now() - started;
    expect(model.total).toBe(5000);
    expect(model.sections.find((s) => s.group === 'WAITING')?.stacks).toHaveLength(1);
    expect(model.sections.find((s) => s.group === 'RUNNABLE')?.stacks).toHaveLength(50);
    console.info(`[wo37-dump-model] 5000 threads built in ${elapsed.toFixed(1)} ms (reference)`);
  });
});
