// SPDX-License-Identifier: Apache-2.0
//
// Self time of docs/15 §8.3 (docs/15 §15 "self time"): the union of children clipped to the node,
// overlapping asynchronous children merged, zero-length steps covering nothing, the transaction's
// own self time over the roots of the call tree (orphaned steps included) equal to the sum of the
// timeline's grey segments, the server's `self_time_ms` kept as the source only when it equals that
// value (it is a plain sum of the parent_seq 0 steps), and the 20 % highlight boundary of 05
// §2.2-3. STACK, ERROR and SUMMARY steps are not work intervals (as on the server).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'D-05' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import {
  profileFixture,
  sqlStepFixture,
  stepFixture,
  transactionDetailFixture,
} from '../data/testing/fixtures';
import type { TransactionStep } from '../data/transactionApi';

import {
  clientRootSelfTime,
  isPointEventType,
  mergeIntervals,
  nodeSelfTime,
  rootSelfGaps,
  selfGaps,
  selfTimeOf,
  uncoveredIntervals,
  uninstrumentedTime,
  unionLength,
  UNINSTRUMENTED_HIGHLIGHT_RATIO,
} from './selfTime';
import { profileTree } from './tree';

function step(seq: number, start: number, elapsed: number, extra: Partial<TransactionStep> = {}) {
  return stepFixture({
    seq,
    type: 'METHOD',
    start_offset_ms: start,
    elapsed_ms: elapsed,
    name_id: String(seq),
    ...extra,
  });
}

/** The server's self_time_ms rule (TransactionDetailService): elapsed − Σ parent_seq 0 work steps. */
function serverRule(elapsed: number, steps: readonly TransactionStep[]): number {
  const sum = steps
    .filter((s) => s.parent_seq === 0 && !['STACK', 'ERROR', 'SUMMARY'].includes(s.type))
    .reduce((total, s) => total + s.elapsed_ms, 0);
  return Math.max(0, elapsed - sum);
}

describe('interval union (docs/15 §8.3)', () => {
  it('merges overlapping and touching intervals and drops zero-length ones', () => {
    expect(
      mergeIntervals([
        { start: 10, end: 20 },
        { start: 15, end: 30 },
        { start: 30, end: 35 },
        { start: 50, end: 50 },
        { start: 40, end: 45 },
      ]),
    ).toEqual([
      { start: 10, end: 35 },
      { start: 40, end: 45 },
    ]);
  });

  it('clips intervals to the parent', () => {
    expect(
      unionLength(
        [
          { start: -10, end: 10 },
          { start: 90, end: 150 },
        ],
        { start: 0, end: 100 },
      ),
    ).toBe(20);
  });

  it('overlapping async children count once (union, not sum)', () => {
    // Parent 0..100; two async children 10..60 and 30..80 overlap on 30..60.
    expect(
      selfTimeOf({ start: 0, end: 100 }, [
        { start: 10, end: 60 },
        { start: 30, end: 80 },
      ]),
    ).toBe(30);
  });

  it('zero-length children cover nothing', () => {
    expect(
      selfTimeOf({ start: 0, end: 50 }, [
        { start: 10, end: 10 },
        { start: 20, end: 20 },
      ]),
    ).toBe(50);
  });

  it('self gaps are the uncovered parts in order; a node without children has none', () => {
    expect(
      selfGaps({ start: 0, end: 100 }, [
        { start: 20, end: 40 },
        { start: 60, end: 70 },
      ]),
    ).toEqual([
      { start: 0, end: 20 },
      { start: 40, end: 60 },
      { start: 70, end: 100 },
    ]);
    expect(selfGaps({ start: 0, end: 100 }, [])).toEqual([]);
  });

  it('uncovered parts add up to the self time; without children the whole interval is uncovered', () => {
    const parent = { start: 0, end: 100 };
    const children = [
      { start: 10, end: 60 },
      { start: 30, end: 80 },
      { start: 95, end: 130 },
    ];
    const parts = uncoveredIntervals(parent, children);
    expect(parts).toEqual([
      { start: 0, end: 10 },
      { start: 80, end: 95 },
    ]);
    expect(parts.reduce((s, p) => s + p.end - p.start, 0)).toBe(selfTimeOf(parent, children));
    expect(uncoveredIntervals(parent, [])).toEqual([parent]);
  });

  it('STACK and ERROR are point events; SUMMARY and work steps are not', () => {
    expect(isPointEventType('STACK')).toBe(true);
    expect(isPointEventType('error')).toBe(true);
    expect(isPointEventType('SUMMARY')).toBe(false);
    expect(isPointEventType('SQL')).toBe(false);
  });
});

describe('node and root self time', () => {
  it('a step with children: elapsed minus the union of its children', () => {
    const roots = profileTree([
      step(1, 0, 100),
      step(2, 10, 30, { parent_seq: 1, type: 'ASYNC' }),
      step(3, 20, 40, { parent_seq: 1, type: 'ASYNC' }),
      step(4, 200, 0, { parent_seq: 1 }),
    ]);
    const node = roots[0];
    expect(node).toBeDefined();
    if (node !== undefined) {
      // union 10..60 = 50; the 0 ms step at 200 is outside the parent and covers nothing.
      expect(nodeSelfTime(node)).toBe(50);
    }
  });

  it('without overlap, steps past the end or orphans the client root value equals the server rule', () => {
    // On such profiles the union of the top-level steps is their sum, so the timeline value and the
    // server's self_time_ms (elapsed − Σ parent_seq 0 work steps) are the same number.
    const fixtures = [
      // The default profile (DBC 3..10, SQL 10..50) with the server's value for it: 100 − 47.
      transactionDetailFixture(),
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({
          steps: [
            stepFixture({ seq: 1, type: 'DBC', start_offset_ms: 3, elapsed_ms: 120 }),
            sqlStepFixture(2, { start_offset_ms: 130, elapsed_ms: 400 }),
            stepFixture({
              seq: 3,
              type: 'HTTP',
              start_offset_ms: 600,
              elapsed_ms: 250,
              name: untrusted('pay'),
            }),
            stepFixture({
              seq: 4,
              type: 'STACK',
              start_offset_ms: 700,
              frames: [untrusted('a.B.c(B.java)')],
            }),
            stepFixture({ seq: 5, type: 'ERROR', start_offset_ms: 900, name: untrusted('x.Y') }),
            sqlStepFixture(6, { parent_seq: 3, start_offset_ms: 610, elapsed_ms: 50 }),
          ],
          // Server: elapsed − Σ root steps except STACK, ERROR and SUMMARY = 1000 − 770.
          self_time_ms: 230,
          self_time_ratio: 0.23,
        }),
      }),
    ];
    for (const detail of fixtures) {
      expect(detail.profile.self_time_ms).toBe(serverRule(detail.elapsed_ms, detail.profile.steps));
      expect(clientRootSelfTime(detail.elapsed_ms, detail.profile.steps)).toBe(
        detail.profile.self_time_ms,
      );
    }
  });

  it('no steps: no self time (nothing was measured)', () => {
    expect(clientRootSelfTime(100, [])).toBeNull();
    expect(
      uninstrumentedTime(
        transactionDetailFixture({
          profile: profileFixture({
            state: 'sampled_out',
            steps: [],
            self_time_ms: null,
            self_time_ratio: null,
          }),
        }),
      ),
    ).toBeNull();
  });

  it('the root self time is the sum of the root gaps of the displayed tree', () => {
    const steps = [
      step(1, 0, 600, { type: 'SQL', sql_id: '1' }),
      step(2, 100, 600, { type: 'ASYNC' }),
      step(3, 900, 300),
      step(9, 750, 50, { parent_seq: 5 }),
    ];
    const gaps = rootSelfGaps(1000, profileTree(steps));
    // Covered: 0..700 (overlap merged), 750..800 (orphan), 900..1000 (clipped at the end).
    expect(gaps).toEqual([
      { start: 700, end: 750 },
      { start: 800, end: 900 },
    ]);
    expect(clientRootSelfTime(1000, steps)).toBe(150);
  });
});

describe('uninstrumented time (05 §2.2-3, 20 % boundary)', () => {
  it('uses the server value as the source when it equals the timeline value', () => {
    const value = uninstrumentedTime(transactionDetailFixture());
    expect(value).toEqual({
      ms: 53,
      ratio: 0.53,
      source: 'server',
      highlight: true,
      serverMs: null,
    });
  });

  it('highlights from exactly 20 % and not below', () => {
    expect(UNINSTRUMENTED_HIGHLIGHT_RATIO).toBe(0.2);
    const at = uninstrumentedTime(
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({
          steps: [step(1, 0, 800)],
          self_time_ms: 200,
          self_time_ratio: 0.2,
        }),
      }),
    );
    expect(at).toMatchObject({ ms: 200, source: 'server', highlight: true });
    const below = uninstrumentedTime(
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({
          steps: [step(1, 0, 801)],
          self_time_ms: 199,
          self_time_ratio: 0.199,
        }),
      }),
    );
    expect(below).toMatchObject({ ms: 199, source: 'server', highlight: false });
  });

  it('computes the value from the steps when the server sent none', () => {
    const detail = transactionDetailFixture({
      elapsed_ms: 100,
      profile: profileFixture({
        steps: [step(1, 0, 40), step(2, 30, 30, { type: 'ASYNC' })],
        self_time_ms: null,
        self_time_ratio: null,
      }),
    });
    // union 0..60 → 40 ms uncovered.
    expect(uninstrumentedTime(detail)).toEqual({
      ms: 40,
      ratio: 0.4,
      source: 'client',
      highlight: true,
      serverMs: null,
    });
  });

  it('step_truncated: steps under a missing parent still count at the top level', () => {
    const steps = [step(1, 0, 50), step(7, 60, 10, { parent_seq: 5 })];
    const detail = transactionDetailFixture({
      elapsed_ms: 100,
      profile: profileFixture({
        step_truncated: true,
        steps,
        // The server counts only parent_seq 0: 100 − 50.
        self_time_ms: serverRule(100, steps),
        self_time_ratio: 0.5,
      }),
    });
    expect(uninstrumentedTime(detail)).toEqual({
      ms: 40,
      ratio: 0.4,
      source: 'client',
      highlight: true,
      serverMs: 50,
    });
  });

  it.each([
    {
      name: 'overlapping asynchronous root steps',
      steps: [step(1, 0, 600, { type: 'SQL', sql_id: '1' }), step(2, 100, 600, { type: 'ASYNC' })],
      // union 0..700; the server's plain sum (1,200) leaves nothing.
      ms: 300,
      highlight: true,
    },
    {
      name: 'a root step ending after the transaction',
      steps: [step(1, 0, 700), step(2, 900, 300, { type: 'HTTP' })],
      // 0..700 and 900..1000 (clipped): 200 = exactly 20 %; the server's sum (1,000) leaves 0.
      ms: 200,
      highlight: true,
    },
    {
      name: 'a step under a missing parent',
      steps: [step(1, 0, 500), step(7, 600, 300, { parent_seq: 5 })],
      // 0..500 and the orphan 600..900; the server counts only parent_seq 0 (500 uncovered).
      ms: 200,
      highlight: true,
    },
  ])(
    '$name: the timeline value is shown and the server value named',
    ({ steps, ms, highlight }) => {
      const server = serverRule(1000, steps);
      const value = uninstrumentedTime(
        transactionDetailFixture({
          elapsed_ms: 1000,
          profile: profileFixture({ steps, self_time_ms: server, self_time_ratio: server / 1000 }),
        }),
      );
      expect(server).not.toBe(ms);
      expect(value).toEqual({
        ms,
        ratio: ms / 1000,
        source: 'client',
        highlight,
        serverMs: server,
      });
    },
  );
});
