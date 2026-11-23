// SPDX-License-Identifier: Apache-2.0
//
// Typed fixtures of the trace contract (docs/14 §4.7, Trace of docs/api/openapi.yaml) for unit and
// component tests, including what the E2E seed cannot produce before WO-14 (three-hop traces,
// external nodes with failures, missing nodes of both kinds; ADR-0010 C-207 (5)). Collected
// strings carry the Untrusted brand. Test-only module: nothing in the application imports it.
import { untrusted } from '../../../../api/untrusted';
import type { Trace, TraceNode } from '../traceApi';

export const FIXTURE_TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736';
export const ROOT_TX_ID = '7312345678901234567';
export const CALLEE_TX_ID = '7312345678901299999';

/** A transaction node; `tx_id` given, the rest defaults or overrides. */
export function traceNodeFixture(node: Pick<TraceNode, 'tx_id'> & Partial<TraceNode>): TraceNode {
  return {
    app: untrusted('order-api'),
    app_id: 3,
    env: untrusted('prod'),
    service: untrusted('GET /api/orders/{id}'),
    service_id: '9876543210987654321',
    instance: untrusted('web-01:8080'),
    instance_id: 12,
    ts: '2026-11-14T00:00:01.000Z',
    elapsed_ms: 100,
    status: 200,
    error: false,
    version: untrusted('1.4.2'),
    parent_tx_id: null,
    caller_step: null,
    profile_state: 'captured',
    ...node,
  };
}

/** A two-application trace: the root calls one callee over HTTP (caller step 60 ms, callee 45 ms). */
export function traceFixture(overrides: Partial<Trace> = {}): Trace {
  return {
    trace_id: FIXTURE_TRACE_ID,
    root_tx_id: ROOT_TX_ID,
    nodes: [
      traceNodeFixture({ tx_id: ROOT_TX_ID }),
      traceNodeFixture({
        tx_id: CALLEE_TX_ID,
        app: untrusted('stock-api'),
        app_id: 4,
        service: untrusted('GET /api/stock/{sku}'),
        instance: untrusted('stock-01:8080'),
        instance_id: 21,
        ts: '2026-11-14T00:00:01.020Z',
        elapsed_ms: 45,
        parent_tx_id: ROOT_TX_ID,
        caller_step: { elapsed_ms: 60, network_wait_ms: 15 },
        profile_state: 'sampled_out',
      }),
    ],
    external_nodes: [],
    missing: [],
    critical_path: [ROOT_TX_ID, CALLEE_TX_ID],
    window: {
      basis: 'ts_hint',
      from: '2026-11-13T23:00:01.000Z',
      to: '2026-11-14T01:00:01.000Z',
    },
    ...overrides,
  };
}
