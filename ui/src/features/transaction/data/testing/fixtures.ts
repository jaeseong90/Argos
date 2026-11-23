// SPDX-License-Identifier: Apache-2.0
//
// Typed fixtures of the transaction detail contract (docs/14 §4.6, TransactionDetail of
// docs/api/openapi.yaml) for unit and component tests, including the categories the E2E seed
// cannot produce yet (LOST profiles, external failure kinds, masked_by_role, virtual threads:
// ADR-0010 C-207 (5)). Every field the contract requires is present, so a test that compiles
// against these types sees the server's shape; collected strings carry the Untrusted brand.
// Test-only module: nothing in the application imports it.
import { untrusted } from '../../../../api/untrusted';
import type {
  ResponseMeta,
  TransactionBaseline,
  TransactionDetail,
  TransactionProfile,
  TransactionStep,
} from '../transactionApi';

/** A 19-digit tx_id above 2^53 (C-175: must survive as a string). */
export const FIXTURE_TX_ID = '7312345678901234567';
/** The transaction's start in the fixtures. */
export const FIXTURE_TS = '2026-11-14T00:00:01.000Z';
export const FIXTURE_TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736';

/** One profile step: `seq`, `type` and timing given, the rest defaults or overrides. */
export function stepFixture(
  step: Pick<TransactionStep, 'seq' | 'type'> & Partial<TransactionStep>,
): TransactionStep {
  return {
    parent_seq: 0,
    start_offset_ms: 0,
    elapsed_ms: 0,
    error: false,
    repeat: 1,
    ...step,
  };
}

/**
 * A SQL step with masked parameters (types only). Like the server (TransactionDetailService.step),
 * a SQL step carries its statement key as `sql_id` and no `name_id`.
 */
export function sqlStepFixture(
  seq: number,
  overrides: Partial<TransactionStep> = {},
): TransactionStep {
  return stepFixture({
    seq,
    type: 'SQL',
    start_offset_ms: 10,
    elapsed_ms: 40,
    exec_ms: 35,
    fetch_ms: 5,
    rows: 1,
    sql_id: '15837492011223344556',
    sql: untrusted('SELECT * FROM orders WHERE id = ?'),
    target: untrusted('mysql://db1/shop'),
    params: untrusted('[Long]'),
    params_mode: 'masked',
    status: 0,
    ...overrides,
  });
}

export function profileFixture(overrides: Partial<TransactionProfile> = {}): TransactionProfile {
  return {
    state: 'captured',
    steps_pending: false,
    retention_exceeded: false,
    steps: [
      stepFixture({
        seq: 1,
        type: 'DBC',
        start_offset_ms: 3,
        elapsed_ms: 7,
        name: untrusted('HikariPool-1'),
        target: untrusted('mysql://db1/shop'),
      }),
      sqlStepFixture(2),
    ],
    // The server's value for these steps on the default 100 ms transaction: 100 − (7 + 40).
    self_time_ms: 53,
    self_time_ratio: 0.53,
    step_truncated: false,
    total_steps: 2,
    params_mode: 'masked',
    ...overrides,
  };
}

/** A complete detail (captured profile, measured CPU and allocation, no error). */
export function transactionDetailFixture(
  overrides: Partial<TransactionDetail> = {},
): TransactionDetail {
  return {
    tx_id: FIXTURE_TX_ID,
    trace_id: FIXTURE_TRACE_ID,
    span_id: 'a1b2c3d4e5f60718',
    parent_span_id: null,
    app: { app_id: 3, name: untrusted('order-api'), env: untrusted('prod') },
    instance: { instance_id: 12, name: untrusted('web-01:8080') },
    kind: 'HTTP',
    service: untrusted('GET /api/orders/{id}'),
    service_id: '9876543210987654321',
    url: untrusted('/api/orders/123'),
    http_method: untrusted('GET'),
    http_status: 200,
    ts: FIXTURE_TS,
    elapsed_ms: 100,
    error: false,
    error_class: null,
    error_class_id: null,
    error_group_id: null,
    version: untrusted('1.4.2'),
    thread: untrusted('http-nio-8080-exec-7'),
    cpu_time_ms: 12,
    alloc_bytes: 4_194_304,
    measured: { cpu: true, alloc: true },
    sql: { count: 1, time_ms: 40, wait_ms: 7 },
    ext: { count: 0, time_ms: 0 },
    remote_ip: '10.0.1.0',
    ip_mode: 'masked',
    user_id: null,
    user_id_mode: 'off',
    request_id: untrusted('req-7f3a'),
    headers: { 'X-Request-Id': untrusted('req-7f3a') },
    caller: null,
    profile: profileFixture(),
    flags: {
      dispatch_count: 0,
      incoming_trace: false,
      late: false,
      leaked: false,
      streaming: false,
      summarized: false,
      timeout: false,
      virtual_thread: false,
    },
    links: { trace: `/traces/${FIXTURE_TRACE_ID}?ts=${FIXTURE_TS}`, logs: null },
    lookup: 'ts_hint',
    duplicates: [],
    ...overrides,
  };
}

/** Response meta of an enveloped answer (docs/14 §2.5). */
export function metaFixture(overrides: Partial<ResponseMeta> = {}): ResponseMeta {
  return { request_id: 'req-fixture', generated_at: FIXTURE_TS, ...overrides };
}

/** A baseline with a normal sample of the same instance. */
export function baselineFixture(overrides: Partial<TransactionBaseline> = {}): TransactionBaseline {
  return {
    tx_id: FIXTURE_TX_ID,
    service: untrusted('GET /api/orders/{id}'),
    service_id: '9876543210987654321',
    from: '2026-11-13T23:30:01.000Z',
    to: '2026-11-14T00:30:01.000Z',
    service_count: 420,
    service_p50_ms: 35,
    service_p95_ms: 180,
    percentile_of_this: 0.71,
    sample_normal_tx_id: '7312345678900000001',
    sample_normal_ts: '2026-11-14T00:00:00.500Z',
    sample_same_instance: true,
    ...overrides,
  };
}
