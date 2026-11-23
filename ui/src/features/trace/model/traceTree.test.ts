// SPDX-License-Identifier: Apache-2.0
//
// The trace model of docs/15 §9.5 / §15 (tree building, missing and external nodes, network/wait
// maths with the D-05 violation, critical path from the server or computed, ordering, the time
// axis) on typed fixtures of the docs/14 §4.7 contract. T-ACC-05 display: the caller step and the
// callee are both kept and the network/wait part is their difference.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U03' }, { type: 'req', description: 'D-05' }, { type: 'req', description: 'D-07' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { CALLEE_TX_ID, ROOT_TX_ID, traceFixture, traceNodeFixture } from '../data/testing/fixtures';

import {
  buildTraceTree,
  callGap,
  callInterval,
  expandableKeys,
  flattenTraceTree,
  rowInterval,
  waitIntervals,
  type TraceItem,
  type TraceTxItem,
} from './traceTree';

const T0 = Date.parse('2026-11-14T00:00:01.000Z');

function at(offsetMs: number): string {
  return new Date(T0 + offsetMs).toISOString();
}

function tx(item: TraceItem | undefined): TraceTxItem {
  if (item?.kind !== 'tx') {
    throw new Error(`expected a transaction item, got ${String(item?.kind)}`);
  }
  return item;
}

/** Three hops over three apps: root (A) → B → C, plus a second callee of A. */
function threeHop() {
  return traceFixture({
    nodes: [
      traceNodeFixture({ tx_id: '100', ts: at(0), elapsed_ms: 300 }),
      traceNodeFixture({
        tx_id: '200',
        app_id: 4,
        app: untrusted('stock-api'),
        ts: at(20),
        elapsed_ms: 200,
        parent_tx_id: '100',
        caller_step: { elapsed_ms: 240, network_wait_ms: 40 },
      }),
      traceNodeFixture({
        tx_id: '300',
        app_id: 5,
        app: untrusted('price-api'),
        ts: at(50),
        elapsed_ms: 100,
        parent_tx_id: '200',
        caller_step: { elapsed_ms: 110, network_wait_ms: 10 },
      }),
      traceNodeFixture({
        tx_id: '150',
        app_id: 4,
        ts: at(270),
        elapsed_ms: 20,
        parent_tx_id: '100',
        caller_step: { elapsed_ms: 26, network_wait_ms: 6 },
      }),
    ],
    root_tx_id: '100',
    critical_path: ['100', '200', '300'],
  });
}

describe('callGap: caller step against the callee (D-05, T-ACC-05 display)', () => {
  it('T-ACC-05 keeps both durations and uses the server network_wait_ms', () => {
    const node = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      caller_step: { elapsed_ms: 60, network_wait_ms: 15 },
    });
    expect(callGap(node)).toEqual({
      callerStepMs: 60,
      calleeMs: 45,
      differenceMs: 15,
      waitMs: 15,
      waitSource: 'server',
      clockOffset: false,
    });
  });

  it('computes the difference when the server value is not usable', () => {
    const step = { elapsed_ms: 60, network_wait_ms: Number.NaN };
    const gap = callGap(traceNodeFixture({ tx_id: '2', elapsed_ms: 45, caller_step: step }));
    expect(gap?.waitMs).toBe(15);
    expect(gap?.waitSource).toBe('computed');
  });

  it('a caller step shorter than the callee violates D-05: raw values, no wait, no negative bar', () => {
    const node = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      // The server clamps the wait at 0 (TraceService); the raw values disagree.
      caller_step: { elapsed_ms: 30, network_wait_ms: 0 },
    });
    const gap = callGap(node);
    expect(gap).toEqual({
      callerStepMs: 30,
      calleeMs: 45,
      differenceMs: -15,
      waitMs: null,
      waitSource: null,
      clockOffset: true,
    });
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: ROOT_TX_ID, ts: at(0), elapsed_ms: 100 }),
          { ...node, tx_id: CALLEE_TX_ID, ts: at(20), parent_tx_id: ROOT_TX_ID },
        ],
      }),
    );
    const callee = tx(tx(tree.roots[0]).children[0]);
    // The caller step starts with the callee and keeps its raw length; no wait segment exists.
    expect(callInterval(callee)).toEqual({ startMs: T0 + 20, elapsedMs: 30 });
    expect(waitIntervals(callee)).toEqual([]);
    expect(tree.counts.clockOffsets).toBe(1);
  });

  it('equal durations: zero wait, no grey segment', () => {
    const node = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      caller_step: { elapsed_ms: 45, network_wait_ms: 0 },
    });
    expect(callGap(node)?.waitMs).toBe(0);
    expect(callGap(node)?.clockOffset).toBe(false);
  });

  it('boundary: a caller step 1 ms shorter than the callee already violates D-05', () => {
    // The server clamps network_wait_ms at 0; the raw values are what decide.
    const node = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      caller_step: { elapsed_ms: 44, network_wait_ms: 0 },
    });
    expect(callGap(node)).toEqual({
      callerStepMs: 44,
      calleeMs: 45,
      differenceMs: -1,
      waitMs: null,
      waitSource: null,
      clockOffset: true,
    });
  });

  it('boundary: a caller step 1 ms longer than the callee holds D-05 with a 1 ms wait', () => {
    const server = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      caller_step: { elapsed_ms: 46, network_wait_ms: 1 },
    });
    expect(callGap(server)).toEqual({
      callerStepMs: 46,
      calleeMs: 45,
      differenceMs: 1,
      waitMs: 1,
      waitSource: 'server',
      clockOffset: false,
    });
    const computed = traceNodeFixture({
      tx_id: '2',
      elapsed_ms: 45,
      caller_step: { elapsed_ms: 46, network_wait_ms: Number.NaN },
    });
    expect(callGap(computed)).toEqual({
      callerStepMs: 46,
      calleeMs: 45,
      differenceMs: 1,
      waitMs: 1,
      waitSource: 'computed',
      clockOffset: false,
    });
  });

  it('a root has no caller step', () => {
    expect(callGap(traceNodeFixture({ tx_id: '1' }))).toBeNull();
  });

  it('places the caller step around its callee with the wait split before and after', () => {
    const tree = buildTraceTree(traceFixture());
    const callee = tx(tx(tree.roots[0]).children[0]);
    const start = Date.parse('2026-11-14T00:00:01.020Z');
    expect(callInterval(callee)).toEqual({ startMs: start - 7.5, elapsedMs: 60 });
    expect(waitIntervals(callee)).toEqual([
      { startMs: start - 7.5, elapsedMs: 7.5 },
      { startMs: start + 45, elapsedMs: 7.5 },
    ]);
    const waitTotal = waitIntervals(callee).reduce((sum, w) => sum + w.elapsedMs, 0);
    expect(waitTotal + callee.node.elapsed_ms).toBe(callee.gap?.callerStepMs);
  });
});

describe('buildTraceTree: tree from parent_tx_id (docs/15 §9.5)', () => {
  it('a two-app trace: root with its callee; both transactions and the caller step kept', () => {
    const tree = buildTraceTree(traceFixture());
    expect(tree.roots).toHaveLength(1);
    const root = tx(tree.roots[0]);
    expect(root.node.tx_id).toBe(ROOT_TX_ID);
    expect(root.isRoot).toBe(true);
    expect(tree.root?.node.tx_id).toBe(ROOT_TX_ID);
    const callee = tx(root.children[0]);
    expect(callee.node.tx_id).toBe(CALLEE_TX_ID);
    expect(callee.caller?.tx_id).toBe(ROOT_TX_ID);
    expect(callee.gap?.callerStepMs).toBe(60);
    expect(callee.gap?.calleeMs).toBe(45);
    expect(callee.profileState).toBe('sampled_out');
    expect(tree.counts).toEqual({
      transactions: 2,
      apps: 2,
      external: 0,
      notYetCollected: 0,
      notInstrumented: 0,
      profileNotCaptured: 1,
      clockOffsets: 0,
    });
  });

  it('three hops: every level under its caller, children by start time', () => {
    const tree = buildTraceTree(threeHop());
    const root = tx(tree.roots[0]);
    expect(root.children.map((c) => tx(c).node.tx_id)).toEqual(['200', '150']);
    const b = tx(root.children[0]);
    expect(b.children.map((c) => tx(c).node.tx_id)).toEqual(['300']);
    expect(tx(b.children[0]).caller?.tx_id).toBe('200');
    expect(tree.counts.apps).toBe(3);
  });

  it('orders siblings by start, then by tx id as a number (not as text)', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0), elapsed_ms: 100 }),
          traceNodeFixture({ tx_id: '10', ts: at(10), elapsed_ms: 5, parent_tx_id: '1' }),
          traceNodeFixture({ tx_id: '9', ts: at(10), elapsed_ms: 5, parent_tx_id: '1' }),
          traceNodeFixture({ tx_id: '3', ts: at(5), elapsed_ms: 5, parent_tx_id: '1' }),
        ],
        root_tx_id: '1',
        critical_path: ['1'],
      }),
    );
    expect(tx(tree.roots[0]).children.map((c) => tx(c).node.tx_id)).toEqual(['3', '9', '10']);
  });

  it('the root comes first on the top level even when another top-level node starts earlier', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '5', ts: at(-50), elapsed_ms: 10 }),
          traceNodeFixture({ tx_id: '1', ts: at(0), elapsed_ms: 100 }),
        ],
        root_tx_id: '1',
        critical_path: ['1'],
      }),
    );
    expect(tree.roots.map((r) => tx(r).node.tx_id)).toEqual(['1', '5']);
    expect(tree.originMs).toBe(T0 - 50);
  });

  it('a parent that is not in the response leaves the node on the top level, flagged', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0) }),
          traceNodeFixture({ tx_id: '2', ts: at(5), parent_tx_id: '999' }),
        ],
        root_tx_id: '1',
        critical_path: ['1'],
      }),
    );
    const orphan = tx(tree.roots[1]);
    expect(orphan.node.tx_id).toBe('2');
    expect(orphan.callerAbsent).toBe(true);
    expect(orphan.gap).toBeNull();
  });

  it('a parent chain that loops is cut instead of recursing forever', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0), parent_tx_id: '2' }),
          traceNodeFixture({ tx_id: '2', ts: at(5), parent_tx_id: '1' }),
        ],
        root_tx_id: null,
        critical_path: [],
      }),
    );
    const rows = flattenTraceTree(tree, new Set());
    expect(rows.filter((r) => r.kind === 'tx').map((r) => r.item.key)).toEqual(['tx:1', 'tx:2']);
  });

  it('a duplicated node is kept once', () => {
    const node = traceNodeFixture({ tx_id: ROOT_TX_ID });
    const tree = buildTraceTree(traceFixture({ nodes: [node, node], critical_path: [] }));
    expect(tree.counts.transactions).toBe(1);
  });
});

describe('external and missing nodes (05 §2.8-3)', () => {
  it('external nodes hang under their caller after its transaction callees', () => {
    const tree = buildTraceTree(
      traceFixture({
        external_nodes: [
          {
            caller_tx_id: ROOT_TX_ID,
            host: untrusted('https://pay.example:443'),
            path_pattern: untrusted('/v1/pay'),
            elapsed_ms: 80,
            status: null,
            error: true,
          },
        ],
      }),
    );
    const root = tx(tree.roots[0]);
    expect(root.children.map((c) => c.kind)).toEqual(['tx', 'external']);
    const external = root.children[1];
    expect(external?.kind === 'external' && external.external.host).toBe('https://pay.example:443');
    expect(tree.counts.external).toBe(1);
    // Its start is not in the response: drawn from the caller's start, flagged as estimated.
    const rows = flattenTraceTree(tree, new Set());
    const row = rows.find((r) => r.kind === 'external');
    expect(row === undefined ? null : rowInterval(row)).toEqual({
      startMs: T0,
      elapsedMs: 80,
      estimated: true,
    });
  });

  it('an external node whose caller is not in the response stays on the top level', () => {
    const tree = buildTraceTree(
      traceFixture({
        external_nodes: [
          {
            caller_tx_id: '42',
            host: null,
            path_pattern: null,
            elapsed_ms: 5,
            status: 504,
            error: true,
          },
        ],
      }),
    );
    expect(tree.roots.map((r) => r.kind)).toEqual(['tx', 'external']);
  });

  it('the server form: a transaction whose caller is missing gets a caller placeholder above it', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: ROOT_TX_ID, ts: at(0) }),
          traceNodeFixture({ tx_id: '77', ts: at(500), elapsed_ms: 10, parent_tx_id: null }),
        ],
        missing: [{ parent_tx_id: null, tx_id: '77', reason: 'not_yet_collected' }],
      }),
    );
    expect(tree.roots.map((r) => r.kind)).toEqual(['tx', 'missing']);
    const placeholder = tree.roots[1];
    expect(placeholder?.kind).toBe('missing');
    if (placeholder?.kind !== 'missing') return;
    expect(placeholder.role).toBe('caller');
    expect(placeholder.missing.reason).toBe('not_yet_collected');
    expect(tx(placeholder.children[0]).node.tx_id).toBe('77');
    expect(tree.counts.notYetCollected).toBe(1);
    const rows = flattenTraceTree(tree, new Set());
    expect(rows.map((r) => [r.kind, r.level])).toEqual([
      ['tx', 1],
      ['missing', 1],
      ['tx', 2],
    ]);
  });

  it('a missing transaction that is not in the response hangs under parent_tx_id', () => {
    const tree = buildTraceTree(
      traceFixture({
        missing: [{ parent_tx_id: ROOT_TX_ID, tx_id: '555', reason: 'not_instrumented' }],
      }),
    );
    const root = tx(tree.roots[0]);
    expect(root.children.map((c) => c.kind)).toEqual(['tx', 'missing']);
    const placeholder = root.children[1];
    expect(placeholder?.kind === 'missing' && placeholder.role).toBe('callee');
    expect(tree.counts.notInstrumented).toBe(1);
  });

  it('a missing hop between two present transactions: parent → placeholder → transaction', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0), elapsed_ms: 300 }),
          traceNodeFixture({ tx_id: '3', ts: at(40), elapsed_ms: 50, parent_tx_id: null }),
        ],
        root_tx_id: '1',
        critical_path: ['1'],
        missing: [{ parent_tx_id: '1', tx_id: '3', reason: 'not_yet_collected' }],
      }),
    );
    expect(tree.roots).toHaveLength(1);
    const placeholder = tx(tree.roots[0]).children[0];
    expect(placeholder?.kind).toBe('missing');
    expect(tx(placeholder?.children[0]).node.tx_id).toBe('3');
  });

  it('profile states: sampled out and lost count as not captured (D-07)', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0), profile_state: 'lost' }),
          traceNodeFixture({
            tx_id: '2',
            ts: at(5),
            parent_tx_id: '1',
            profile_state: 'sampled_out',
            caller_step: { elapsed_ms: 50, network_wait_ms: 5 },
            elapsed_ms: 45,
          }),
          traceNodeFixture({ tx_id: '3', ts: at(6), parent_tx_id: '1', profile_state: 'weird' }),
        ],
        root_tx_id: '1',
        critical_path: ['1'],
      }),
    );
    const root = tx(tree.roots[0]);
    expect(root.profileState).toBe('lost');
    expect(root.children.map((c) => tx(c).profileState)).toEqual(['sampled_out', 'unknown']);
    expect(tree.counts.profileNotCaptured).toBe(2);
  });
});

describe('critical path (docs/15 §9.5)', () => {
  it('uses the server critical_path', () => {
    const tree = buildTraceTree(threeHop());
    expect(tree.criticalSource).toBe('server');
    expect([...tree.critical]).toEqual(['100', '200', '300']);
    const rows = flattenTraceTree(tree, new Set());
    const critical = rows.filter((r) => (r.kind === 'call' ? r.critical : false)).map((r) => r.key);
    expect(critical).toEqual(['call:200', 'call:300']);
    expect(rows.find((r) => r.key === 'call:150')).toMatchObject({ critical: false });
  });

  it('computes the longest root-to-leaf path only when the server sent none', () => {
    const tree = buildTraceTree({ ...threeHop(), critical_path: [] });
    expect(tree.criticalSource).toBe('client');
    // 200 ends at 220, 150 at 290: the path follows the child that ends last.
    expect([...tree.critical]).toEqual(['100', '150']);
  });

  it('ignores server ids that are not in the response; none when nothing is left', () => {
    const tree = buildTraceTree(
      traceFixture({ nodes: [], critical_path: ['1'], root_tx_id: null }),
    );
    expect(tree.criticalSource).toBe('none');
    expect(tree.critical.size).toBe(0);
    expect(tree.root).toBeNull();
    expect(tree.totalMs).toBe(1);
  });
});

describe('flattenTraceTree and the time axis', () => {
  it('a call row sits right above its callee at the same level; collapsed keys hide children', () => {
    const tree = buildTraceTree(threeHop());
    const rows = flattenTraceTree(tree, new Set());
    expect(rows.map((r) => `${r.kind}:${String(r.level)}:${r.key}`)).toEqual([
      'tx:1:tx:100',
      'call:2:call:200',
      'tx:2:tx:200',
      'call:3:call:300',
      'tx:3:tx:300',
      'call:2:call:150',
      'tx:2:tx:150',
    ]);
    expect(rows[0]).toMatchObject({ kind: 'tx', expanded: true, parentKey: null });
    expect(rows[2]).toMatchObject({ parentKey: 'tx:100' });
    const collapsed = flattenTraceTree(tree, new Set(['tx:200']));
    expect(collapsed.map((r) => r.key)).toEqual([
      'tx:100',
      'call:200',
      'tx:200',
      'call:150',
      'tx:150',
    ]);
    expect(collapsed[2]).toMatchObject({ expanded: false });
    expect(expandableKeys(tree)).toEqual(['tx:100', 'tx:200']);
  });

  it('the axis starts at the earliest start and covers the latest end', () => {
    const tree = buildTraceTree(threeHop());
    expect(tree.originMs).toBe(T0);
    expect(tree.totalMs).toBe(300);
  });

  it('a caller step that starts before the root (clock offset between hosts) moves the origin', () => {
    const tree = buildTraceTree(
      traceFixture({
        nodes: [
          traceNodeFixture({ tx_id: '1', ts: at(0), elapsed_ms: 100 }),
          traceNodeFixture({
            tx_id: '2',
            ts: at(0),
            elapsed_ms: 40,
            parent_tx_id: '1',
            caller_step: { elapsed_ms: 60, network_wait_ms: 20 },
          }),
        ],
        root_tx_id: '1',
        critical_path: ['1', '2'],
      }),
    );
    expect(tree.originMs).toBe(T0 - 10);
    expect(tree.totalMs).toBe(110);
  });
});
