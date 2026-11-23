// SPDX-License-Identifier: Apache-2.0
//
// Trace data over the real transport and generated client (network stubbed at the fetch boundary):
// GET /traces/{traceId} with the ts hint and window (docs/14 §4.7), the not-found state of an empty
// window, errors (429 of the HEAVY 30d window with Retry-After), no request for a malformed id, the
// response model (missing kinds, profile states, network/wait, critical path, truncation) and the
// trace JSON export of the received response (ADR-0010 C-207 (1)).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U03' }, { type: 'req', description: 'D-05' }, { type: 'req', description: 'D-07' }
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import { ApiError } from '../../../api/errors';
import { createQueryClient } from '../../../api/queryClient';
import { untrusted } from '../../../api/untrusted';

import {
  CALLEE_TX_ID,
  FIXTURE_TRACE_ID,
  ROOT_TX_ID,
  traceFixture,
  traceNodeFixture,
} from './testing/fixtures';
import { isTraceIdForm, saveTraceJson, traceFileName } from './traceApi';
import {
  criticalPathSet,
  missingKind,
  networkWaitMs,
  nodeProfileState,
  traceNotFoundReason,
  traceTruncated,
} from './traceModel';
import { useTrace } from './useTrace';

const TRACE = `GET /api/v1/traces/${FIXTURE_TRACE_ID}`;
const TS = '2026-11-14T00:00:01.000Z';

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

function wrapper() {
  const client = createQueryClient();
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useTrace (docs/14 §4.7)', () => {
  it('sends the ts hint (URL epoch ms as ISO) and the window, and keeps the whole response', async () => {
    stub.on(TRACE, () => envelope(traceFixture(), { truncated: false }));
    const { result } = renderHook(() => useTrace(FIXTURE_TRACE_ID, Date.parse(TS), '7d'), {
      wrapper: wrapper(),
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    const query = stub.calls(TRACE)[0]?.query;
    expect(query?.get('ts')).toBe(TS);
    expect(query?.get('window')).toBe('7d');
    const state = result.current.state;
    if (state.status !== 'success') {
      throw new Error('expected success');
    }
    expect(state.trace.root_tx_id).toBe(ROOT_TX_ID);
    expect(state.response.data).toBe(state.trace);
    expect(state.response.meta).toBe(state.meta);
  });

  it('sends neither key without them (the server falls back to the id time or 24 h)', async () => {
    stub.on(TRACE, () => envelope(traceFixture()));
    const { result } = renderHook(() => useTrace(FIXTURE_TRACE_ID), { wrapper: wrapper() });
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    const query = stub.calls(TRACE)[0]?.query;
    expect(query?.has('ts')).toBe(false);
    expect(query?.has('window')).toBe(false);
  });

  it('404 not_in_window is not_found with its reason; a 429 of the 30d window is an error with Retry-After', async () => {
    stub.on(TRACE, () =>
      errorBody(404, 'not_found', { details: [{ field: 'trace_id', reason: 'not_in_window' }] }),
    );
    const { result } = renderHook(() => useTrace(FIXTURE_TRACE_ID), { wrapper: wrapper() });
    await waitFor(() => {
      expect(result.current.state).toMatchObject({ status: 'not_found', reason: 'not_in_window' });
    });
    stub.on(TRACE, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '12' } }));
    const heavy = renderHook(() => useTrace(FIXTURE_TRACE_ID, null, '30d'), {
      wrapper: wrapper(),
    });
    await waitFor(() => {
      expect(heavy.result.current.state).toMatchObject({
        status: 'error',
        info: { status: 429, retryAfterSeconds: 12 },
      });
    });
    expect(stub.calls(TRACE).filter((r) => r.query.get('window') === '30d')).toHaveLength(1);
  });

  it('requests nothing for an id that is not 32 hex digits', () => {
    for (const id of ['', 'xyz', '4bf92f35', '0'.repeat(32), `${FIXTURE_TRACE_ID}0`]) {
      const { result } = renderHook(() => useTrace(id), { wrapper: wrapper() });
      expect(result.current.state, id).toEqual({ status: 'invalid' });
    }
    expect(isTraceIdForm(FIXTURE_TRACE_ID.toUpperCase())).toBe(true);
    expect(stub.calls(TRACE)).toHaveLength(0);
  });
});

describe('trace response model (docs/15 §9.5, 05 §2.8)', () => {
  it('reads the network/wait part, profile states and the critical path as the server sent them', () => {
    const trace = traceFixture();
    const [root, callee] = trace.nodes;
    if (root === undefined || callee === undefined) {
      throw new Error('fixture');
    }
    expect(networkWaitMs(root)).toBeNull();
    expect(networkWaitMs(callee)).toBe(15);
    expect(nodeProfileState(root)).toBe('captured');
    expect(nodeProfileState(callee)).toBe('sampled_out');
    expect(nodeProfileState(traceNodeFixture({ tx_id: '1', profile_state: 'lost' }))).toBe('lost');
    expect(nodeProfileState(traceNodeFixture({ tx_id: '1', profile_state: 'weird' }))).toBe(
      'unknown',
    );
    expect([...criticalPathSet(trace)]).toEqual([ROOT_TX_ID, CALLEE_TX_ID]);
  });

  it('names both missing kinds and the truncation at 500 transactions', () => {
    const trace = traceFixture({
      missing: [
        { tx_id: '11', parent_tx_id: ROOT_TX_ID, reason: 'not_instrumented' },
        { tx_id: '12', parent_tx_id: ROOT_TX_ID, reason: 'not_yet_collected' },
      ],
      external_nodes: [
        {
          caller_tx_id: ROOT_TX_ID,
          host: untrusted('payments.example'),
          path_pattern: null,
          elapsed_ms: 30,
          status: null,
          error: true,
        },
      ],
    });
    expect(trace.missing.map(missingKind)).toEqual(['not_instrumented', 'not_yet_collected']);
    expect(trace.external_nodes[0]?.path_pattern).toBeNull();
    expect(traceTruncated({ request_id: 'r', generated_at: TS, truncated: true })).toBe(true);
    expect(traceTruncated({ request_id: 'r', generated_at: TS })).toBe(false);
  });

  it('maps not-found answers', () => {
    const http = (status: number, details: { field: string; reason: string }[] = []) =>
      new ApiError({ kind: 'http', status, code: 'not_found', message: 'm', details });
    expect(traceNotFoundReason(http(404, [{ field: 'trace_id', reason: 'not_in_window' }]))).toBe(
      'not_in_window',
    );
    expect(traceNotFoundReason(http(404))).toBe('not_found');
    expect(traceNotFoundReason(http(400))).toBeNull();
    expect(traceNotFoundReason('x')).toBeNull();
  });
});

describe('trace JSON export (ADR-0010 C-207 (1): the received response, saveFile)', () => {
  it('saves {data, meta} as received under argos-trace-<id>.json, without a request', async () => {
    const blobs: Blob[] = [];
    const names: string[] = [];
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: (blob: Blob) => {
        blobs.push(blob);
        return 'blob:http://localhost:3000/t';
      },
    });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => undefined });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    try {
      const response = {
        data: traceFixture(),
        meta: { request_id: 'req-trace', generated_at: TS, truncated: false },
      };
      const before = stub.requests.length;
      const name = saveTraceJson(response, { setTimeout: () => undefined });
      expect(name).toBe(traceFileName(FIXTURE_TRACE_ID));
      expect(name).toBe(`argos-trace-${FIXTURE_TRACE_ID}.json`);
      expect(names).toEqual([name]);
      expect(blobs[0]?.type).toBe('application/json');
      const text = (await blobs[0]?.text()) ?? '';
      expect(JSON.parse(text)).toEqual(response);
      expect(stub.requests.length).toBe(before);
    } finally {
      Reflect.deleteProperty(URL, 'createObjectURL');
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });
});
