// SPDX-License-Identifier: Apache-2.0
//
// The hop trace resolution of the stack seed (WO-14, e2e/stack/seed/resolve.ts and traffic.ts):
// a trace is accepted only as one complete chain of the expected length, the failure class of
// the last call follows the transaction detail's rule, and transactions map to their hop call.
import { describe, expect, it } from 'vitest';

import { hopCallOf, hopChain, stepFailure, type HopTraceResponse } from './resolve';
import { HOP_SCENARIOS, hopOrderId, hopPath, type HopCall } from './traffic';

type Node = HopTraceResponse['nodes'][number];

function node(txId: string, parent: string | null, elapsed = 10): Node {
  return {
    tx_id: txId,
    app_id: 7,
    ts: '2030-01-01T00:00:00Z',
    elapsed_ms: elapsed,
    parent_tx_id: parent,
    caller_step: parent === null ? null : { elapsed_ms: elapsed + 1 },
  };
}

function trace(nodes: Node[], over: Partial<HopTraceResponse> = {}): HopTraceResponse {
  return {
    root_tx_id: nodes.find((n) => n.parent_tx_id === null)?.tx_id ?? null,
    nodes,
    external_nodes: [{ caller_tx_id: '3' }],
    missing: [],
    ...over,
  };
}

describe('hopChain', () => {
  it('accepts a three-transaction chain with an external node under the last one', () => {
    expect(hopChain(trace([node('2', '1'), node('1', null), node('3', '2')]), 3)).toEqual({
      root: '1',
      leaf: '3',
    });
  });

  it('rejects a wrong count, missing callers, a second root, a fork and no caller step', () => {
    const three = [node('1', null), node('2', '1'), node('3', '2')];
    expect(hopChain(trace(three), 2)).toHaveProperty('problem');
    expect(
      hopChain(trace(three, { missing: [{ tx_id: '3', reason: 'not_instrumented' }] }), 3),
    ).toHaveProperty('problem');
    expect(hopChain(trace([node('1', null), node('2', '1'), node('3', null)]), 3)).toHaveProperty(
      'problem',
    );
    expect(hopChain(trace([node('1', null), node('2', '1'), node('3', '1')]), 3)).toHaveProperty(
      'problem',
    );
    expect(
      hopChain(
        trace([node('1', null), node('2', '1'), { ...node('3', '2'), caller_step: null }]),
        3,
      ),
    ).toHaveProperty('problem');
    expect(hopChain(trace([node('1', null), node('2', '1'), node('3', '9')]), 3)).toHaveProperty(
      'problem',
    );
  });

  it('rejects a chain whose last transaction has no external node', () => {
    expect(
      hopChain(trace([node('1', null), node('2', '1'), node('3', '2')], { external_nodes: [] }), 3),
    ).toHaveProperty('problem');
  });
});

describe('stepFailure', () => {
  it('follows the transaction detail: failure numbers by name, a 5xx status, other errors', () => {
    expect(stepFailure({ failure: 'timeout', error: true })).toBe('timeout');
    expect(stepFailure({ failure: 'connect_fail', error: true })).toBe('connect_fail');
    expect(stepFailure({ failure: 'something', error: true })).toBe('other_error');
    expect(stepFailure({ status: 500, error: true })).toBe('http_5xx');
    expect(stepFailure({ status: 200, error: false })).toBeNull();
    expect(stepFailure({ status: 404, error: true })).toBe('other_error');
  });
});

describe('hop calls', () => {
  const calls: HopCall[] = HOP_SCENARIOS.map((s, i) => ({
    scenario: s.scenario,
    orderId: hopOrderId(i, 0),
    depth: s.depth,
    sentAt: Date.parse('2030-01-01T00:00:00Z') + i * 10_000,
    doneAt: Date.parse('2030-01-01T00:00:05Z') + i * 10_000,
    status: 200,
    problem: null,
  }));

  it('builds one unique order id and path per call with the scenario parameters', () => {
    expect(new Set(calls.map((c) => c.orderId)).size).toBe(calls.length);
    expect(hopPath(HOP_SCENARIOS[0], 900_000)).toBe('/api/hop/900000?depth=2&client=hurl');
    expect(hopPath(HOP_SCENARIOS[2], 900_200)).toBe(
      '/api/hop/900200?depth=1&client=hurl&fail=refuse',
    );
  });

  it('maps a transaction by the order id of its URL, else by the call interval holding its start', () => {
    expect(hopCallOf('/api/hop/900100', '2031-01-01T00:00:00Z', calls)?.scenario).toBe('timeout');
    expect(hopCallOf(null, '2030-01-01T00:00:21Z', calls)?.scenario).toBe('refuse');
    expect(hopCallOf(null, '2030-01-01T00:00:08Z', calls)).toBeUndefined();
  });
});
