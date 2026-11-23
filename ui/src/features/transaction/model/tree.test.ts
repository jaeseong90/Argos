// SPDX-License-Identifier: Apache-2.0
//
// The call tree of docs/15 §8.2 and DSN-15-12 (docs/15 §15 "반복 접기"): steps linked by parent_seq
// in start order, runs of equal consecutive siblings folded at run >= 3 (2 stays 2), the key
// type + nameId (sql_id for SQL) + target, nested runs, batch chunks, steps of a cut profile.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'DSN-15-12' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { sqlStepFixture, stepFixture } from '../data/testing/fixtures';
import type { TransactionStep } from '../data/transactionApi';

import {
  allStepNodes,
  buildTree,
  collapseRepeats,
  isExpanded,
  pathTo,
  profileTree,
  repeatKey,
  setExpanded,
  visibleRows,
  type GroupNode,
  type StepNode,
} from './tree';

const SQL_A = '11111111111111111111';
const SQL_B = '22222222222222222222';

/** A SQL step as the server sends it: the statement key in `sql_id`, no `name_id`. */
function sql(seq: number, start: number, sqlId = SQL_A, extra: Partial<TransactionStep> = {}) {
  return sqlStepFixture(seq, {
    start_offset_ms: start,
    elapsed_ms: 10,
    sql_id: sqlId,
    ...extra,
  });
}

function method(seq: number, start: number, elapsed: number, extra: Partial<TransactionStep> = {}) {
  return stepFixture({
    seq,
    type: 'METHOD',
    start_offset_ms: start,
    elapsed_ms: elapsed,
    name: untrusted(`com.shop.Step${String(seq)}`),
    name_id: String(1000 + seq),
    ...extra,
  });
}

describe('buildTree (docs/15 §8.2)', () => {
  it('links children by parent_seq and orders siblings by start offset, then seq', () => {
    const roots = buildTree([
      method(1, 0, 100),
      sql(3, 50, SQL_A, { parent_seq: 1 }),
      sql(2, 10, SQL_A, { parent_seq: 1 }),
      sql(4, 10, SQL_B, { parent_seq: 1 }),
      method(5, 120, 5),
    ]);
    expect(roots.map((r) => r.step.seq)).toEqual([1, 5]);
    expect(roots[0]?.children.map((c) => c.step.seq)).toEqual([2, 4, 3]);
  });

  it('keeps steps whose parent is missing, later or itself under the transaction (cut profile)', () => {
    const roots = buildTree([
      method(2, 0, 10, { parent_seq: 99 }),
      method(3, 5, 10, { parent_seq: 4 }),
      method(4, 6, 10, { parent_seq: 0 }),
      method(5, 7, 1, { parent_seq: 5 }),
    ]);
    expect(roots.map((r) => [r.step.seq, r.orphan])).toEqual([
      [2, true],
      [3, true],
      [4, false],
      [5, true],
    ]);
  });
});

describe('collapseRepeats (DSN-15-12)', () => {
  it('a run of 2 equal siblings is not folded, a run of 3 is', () => {
    const two = collapseRepeats(buildTree([sql(1, 0), sql(2, 10)]));
    expect(two.map((n) => n.kind)).toEqual(['step', 'step']);
    const three = collapseRepeats(buildTree([sql(1, 0), sql(2, 10), sql(3, 20)]));
    expect(three).toHaveLength(1);
    const group = three[0] as GroupNode;
    expect(group.kind).toBe('group');
    expect(group.count).toBe(3);
    expect(group.sumMs).toBe(30);
    expect(group.avgMs).toBe(10);
    expect(group.maxMs).toBe(10);
    expect(group.first.seq).toBe(1);
    expect(group.last.seq).toBe(3);
    expect(group.startMs).toBe(0);
    expect(group.elapsedMs).toBe(30);
  });

  it('keys SQL steps by sql_id and other steps by name_id and target', () => {
    expect(repeatKey(sql(1, 0, SQL_A))).not.toBe(repeatKey(sql(2, 0, SQL_B)));
    // Same SQL text id on another database: a different key.
    expect(repeatKey(sql(1, 0, SQL_A, { target: untrusted('db1') }))).not.toBe(
      repeatKey(sql(1, 0, SQL_A, { target: untrusted('db2') })),
    );
    const folded = collapseRepeats(
      buildTree([sql(1, 0), sql(2, 10), sql(3, 20, SQL_B), sql(4, 30), sql(5, 40), sql(6, 50)]),
    );
    // A, A | B | A, A, A: only the last run reaches three.
    expect(folded.map((n) => (n.kind === 'group' ? `g${String(n.count)}` : 's'))).toEqual([
      's',
      's',
      's',
      'g3',
    ]);
  });

  it('a SQL step is keyed by sql_id, never by name_id', () => {
    const shape = (steps: TransactionStep[]) =>
      collapseRepeats(buildTree(steps)).map((n) =>
        n.kind === 'group' ? `g${String(n.count)}` : 's',
      );
    // (a) The same name_id with three different statements: no run.
    expect(
      shape([
        sql(1, 0, SQL_A, { name_id: '42' }),
        sql(2, 10, SQL_B, { name_id: '42' }),
        sql(3, 20, '33333333333333333333', { name_id: '42' }),
      ]),
    ).toEqual(['s', 's', 's']);
    // (b) One statement with different or absent name_id values: a run of three.
    expect(
      shape([sql(1, 0, SQL_A, { name_id: '1' }), sql(2, 10, SQL_A, { name_id: '2' }), sql(3, 20)]),
    ).toEqual(['g3']);
  });

  it('counts agent-folded repeats as executions and failed members as errors', () => {
    const [group] = collapseRepeats(
      buildTree([
        sql(1, 0, SQL_A, { repeat: 4, elapsed_ms: 40 }),
        sql(2, 40),
        sql(3, 50, SQL_A, { error: true }),
      ]),
    ) as GroupNode[];
    expect(group?.count).toBe(3);
    expect(group?.executions).toBe(6);
    expect(group?.sumMs).toBe(60);
    expect(group?.avgMs).toBe(10);
    expect(group?.maxMs).toBe(40);
    expect(group?.errors).toBe(1);
  });

  it('folds nested runs at every level, inside group members too', () => {
    // Three chunks (METHOD, same name) each with three equal SQL statements.
    const steps: TransactionStep[] = [];
    let seq = 1;
    for (let chunk = 0; chunk < 3; chunk += 1) {
      const parent = seq;
      steps.push(method(parent, chunk * 100, 90, { name_id: '777', name: untrusted('Chunk.run') }));
      seq += 1;
      for (let i = 0; i < 3; i += 1) {
        steps.push(sql(seq, chunk * 100 + i * 20, SQL_A, { parent_seq: parent }));
        seq += 1;
      }
    }
    const roots = profileTree(steps);
    expect(roots).toHaveLength(1);
    const chunks = roots[0] as GroupNode;
    expect(chunks.kind).toBe('group');
    expect(chunks.count).toBe(3);
    for (const member of chunks.children) {
      expect(member.children).toHaveLength(1);
      expect(member.children[0]?.kind).toBe('group');
      expect((member.children[0] as GroupNode).count).toBe(3);
    }
    expect(allStepNodes(roots)).toHaveLength(12);
  });

  it('batch chunk repetitions fold by the same rule (run of chunk steps with one name)', () => {
    const chunks = Array.from({ length: 50 }, (_, i) =>
      method(i + 1, i * 10, 9, { name_id: '4242', name: untrusted('ItemWriter.write') }),
    );
    const roots = profileTree(chunks);
    expect(roots).toHaveLength(1);
    expect(roots[0]?.kind).toBe('group');
    expect((roots[0] as GroupNode).count).toBe(50);
    expect((roots[0] as GroupNode).sumMs).toBe(450);
  });

  it('zero-length steps fold like any other and keep a zero span', () => {
    const [group] = collapseRepeats(
      buildTree([
        sql(1, 5, SQL_A, { elapsed_ms: 0 }),
        sql(2, 5, SQL_A, { elapsed_ms: 0 }),
        sql(3, 5, SQL_A, { elapsed_ms: 0 }),
      ]),
    ) as GroupNode[];
    expect(group?.sumMs).toBe(0);
    expect(group?.elapsedMs).toBe(0);
  });
});

describe('expansion and visible rows', () => {
  const roots = profileTree([
    method(1, 0, 100),
    sql(2, 10, SQL_A, { parent_seq: 1 }),
    sql(3, 20, SQL_A, { parent_seq: 1 }),
    sql(4, 30, SQL_A, { parent_seq: 1 }),
    method(5, 60, 10, { parent_seq: 1 }),
  ]);

  it('groups start collapsed and steps with children expanded', () => {
    const rows = visibleRows(roots, new Set());
    expect(rows.map((r) => [r.node.kind, r.depth, r.expanded])).toEqual([
      ['step', 0, true],
      ['group', 1, false],
      ['step', 1, false],
    ]);
  });

  it('toggling a group shows its members; toggling back restores the default', () => {
    const group = roots[0]?.children[0] as GroupNode;
    const open = setExpanded(new Set(), group, true);
    expect(isExpanded(group, open)).toBe(true);
    expect(visibleRows(roots, open).map((r) => r.node.key)).toEqual([
      's1',
      'g2-4',
      's2',
      's3',
      's4',
      's5',
    ]);
    const closed = setExpanded(open, group, false);
    expect(closed.size).toBe(0);
    const step = roots[0] as StepNode;
    expect(visibleRows(roots, setExpanded(new Set(), step, false))).toHaveLength(1);
  });

  it('pathTo finds the nodes above a step, through groups', () => {
    expect(pathTo(roots, 3)?.map((n) => n.key)).toEqual(['s1', 'g2-4']);
    expect(pathTo(roots, 99)).toBeNull();
  });
});
