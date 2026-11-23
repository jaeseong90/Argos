// SPDX-License-Identifier: Apache-2.0
//
// Detail data hooks over the real transport and generated client (the network is stubbed at the
// fetch boundary, test/apiStub.ts): GET /transactions/{txId}?ts= with the hint normalised, the
// typed not-found state of ADR-0007 C-50 (clock_skew_possible, with or without a hint), errors for
// ErrorState, no request for a malformed id, 64-bit ids kept as strings (C-175), the baseline, the
// alias cache priming (one request per detail), the export path and the transport download that
// saves the server's bytes unchanged.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'D-11' }, { type: 'req', description: 'SEC-80' }
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import { isApiError } from '../../../api/errors';
import { createQueryClient } from '../../../api/queryClient';

import {
  baselineFixture,
  FIXTURE_TS,
  FIXTURE_TX_ID,
  transactionDetailFixture,
} from './testing/fixtures';
import { downloadTransactionExport, exportHref, fetchTransaction } from './transactionApi';
import {
  primeTransaction,
  transactionKeys,
  useTransaction,
  useTransactionBaseline,
} from './useTransaction';

const DETAIL = `GET /api/v1/transactions/${FIXTURE_TX_ID}`;
const BASELINE = `GET /api/v1/transactions/${FIXTURE_TX_ID}/baseline`;
const EXPORT = `GET /api/v1/transactions/${FIXTURE_TX_ID}/export`;

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

function harness() {
  const client = createQueryClient();
  function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, wrapper: Wrapper };
}

describe('useTransaction (docs/14 §4.6, ADR-0007 C-50)', () => {
  it('fetches the detail with the ts hint of the URL (epoch ms) as ISO-8601 UTC', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID, Date.parse(FIXTURE_TS)), {
      wrapper,
    });
    expect(result.current.state.status).toBe('pending');
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    const [request] = stub.calls(DETAIL);
    expect(request?.query.get('ts')).toBe(FIXTURE_TS);
    expect(result.current.hint).toBe(FIXTURE_TS);
    const state = result.current.state;
    if (state.status !== 'success') {
      throw new Error('expected success');
    }
    // 64-bit ids stay decimal strings end to end (C-175).
    expect(state.detail.tx_id).toBe(FIXTURE_TX_ID);
    expect(typeof state.detail.service_id).toBe('string');
    expect(state.meta.request_id).toBe('req-test');
  });

  it('sends no ts without a hint (the server restores the time from the id)', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID), { wrapper });
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    expect(stub.calls(DETAIL)[0]?.query.has('ts')).toBe(false);
  });

  it('C-50: 404 clock_skew_possible becomes not_found with the reason and whether a hint was sent', async () => {
    stub.on(DETAIL, () =>
      errorBody(404, 'not_found', {
        details: [{ field: 'tx_id', reason: 'clock_skew_possible' }],
        requestId: 'req-404',
      }),
    );
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID), { wrapper });
    await waitFor(() => {
      expect(result.current.state).toEqual({
        status: 'not_found',
        reason: 'clock_skew_possible',
        hinted: false,
        requestId: 'req-404',
      });
    });
    expect(stub.calls(DETAIL)).toHaveLength(1);
    const hinted = renderHook(() => useTransaction(FIXTURE_TX_ID, FIXTURE_TS), { wrapper });
    await waitFor(() => {
      expect(hinted.result.current.state).toMatchObject({
        status: 'not_found',
        reason: 'clock_skew_possible',
        hinted: true,
      });
    });
  });

  it('a 404 without the C-50 detail is not_found; other failures are errors for ErrorState', async () => {
    stub.on(DETAIL, () => errorBody(404, 'not_found'));
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID), { wrapper });
    await waitFor(() => {
      expect(result.current.state).toMatchObject({ status: 'not_found', reason: 'not_found' });
    });
    stub.on(`GET /api/v1/transactions/42`, () =>
      errorBody(400, 'invalid_request', { requestId: 'req-400' }),
    );
    const bad = renderHook(() => useTransaction('42'), { wrapper });
    await waitFor(() => {
      expect(bad.result.current.state).toMatchObject({
        status: 'error',
        info: { status: 400, code: 'invalid_request', requestId: 'req-400' },
      });
    });
  });

  it('requests nothing for an id that is not a decimal tx_id', () => {
    const { wrapper } = harness();
    for (const id of ['', 'abc', '-1', '1e5', '123456789012345678901']) {
      const { result } = renderHook(() => useTransaction(id), { wrapper });
      expect(result.current.state, id).toEqual({ status: 'invalid' });
    }
    expect(stub.requests.filter((r) => r.path.startsWith('/api/v1/transactions'))).toEqual([]);
  });

  it('a detail stored by the /tx alias (primeTransaction) is not fetched again', async () => {
    stub.on(DETAIL, () => envelope(transactionDetailFixture()));
    const { client, wrapper } = harness();
    const fetched = await fetchTransaction(FIXTURE_TX_ID);
    primeTransaction(client, FIXTURE_TX_ID, fetched);
    expect(client.getQueryData(transactionKeys.detail(FIXTURE_TX_ID, FIXTURE_TS))).toBe(fetched);
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID, Date.parse(FIXTURE_TS)), {
      wrapper,
    });
    expect(result.current.state.status).toBe('success');
    expect(stub.calls(DETAIL)).toHaveLength(1);
  });

  it('refetches a captured profile whose steps are still pending; a final detail stays cached', async () => {
    let pending = true;
    stub.on(DETAIL, () =>
      envelope(
        transactionDetailFixture({
          profile: { ...transactionDetailFixture().profile, steps: [], steps_pending: pending },
        }),
      ),
    );
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransaction(FIXTURE_TX_ID, FIXTURE_TS), { wrapper });
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    pending = false;
    act(() => {
      result.current.refetch();
    });
    await waitFor(() => {
      expect(stub.calls(DETAIL)).toHaveLength(2);
    });
    await waitFor(() => {
      const state = result.current.state;
      expect(state.status === 'success' && state.detail.profile.steps_pending).toBe(false);
    });
  });
});

describe('useTransactionBaseline (docs/14 §4.6)', () => {
  it('asks for the baseline with the detail ts and keeps the normal sample as a string id', async () => {
    stub.on(BASELINE, () => envelope(baselineFixture()));
    const { wrapper } = harness();
    const { result } = renderHook(() => useTransactionBaseline(FIXTURE_TX_ID, FIXTURE_TS), {
      wrapper,
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe('success');
    });
    expect(stub.calls(BASELINE)[0]?.query.get('ts')).toBe(FIXTURE_TS);
    const state = result.current.state;
    expect(state.status === 'success' && state.baseline.sample_normal_tx_id).toBe(
      '7312345678900000001',
    );
  });

  it('waits while disabled and reports failures as errors', async () => {
    const { wrapper } = harness();
    const idle = renderHook(() => useTransactionBaseline(FIXTURE_TX_ID, null, { enabled: false }), {
      wrapper,
    });
    expect(idle.result.current.state).toEqual({ status: 'idle' });
    expect(stub.calls(BASELINE)).toHaveLength(0);
    stub.on(BASELINE, () => errorBody(503, 'storage_unavailable'));
    const failing = renderHook(() => useTransactionBaseline(FIXTURE_TX_ID, FIXTURE_TS), {
      wrapper,
    });
    await waitFor(
      () => {
        expect(failing.result.current.state).toMatchObject({
          status: 'error',
          info: { status: 503 },
        });
      },
      { timeout: 5000 },
    );
  });
});

describe('transaction JSON export (docs/14 §4.6, C-176 (1): the server export, audited)', () => {
  it('exportHref is the export path with the ts hint, for a download anchor', () => {
    expect(exportHref(FIXTURE_TX_ID)).toBe(`/api/v1/transactions/${FIXTURE_TX_ID}/export`);
    expect(exportHref(FIXTURE_TX_ID, FIXTURE_TS)).toBe(
      `/api/v1/transactions/${FIXTURE_TX_ID}/export?ts=${encodeURIComponent(FIXTURE_TS)}`,
    );
  });

  it('downloads through the transport and saves the server bytes unchanged under its name', async () => {
    const serverBytes = new TextEncoder().encode(
      JSON.stringify({ data: transactionDetailFixture(), meta: { request_id: 'r' } }),
    );
    stub.on(
      EXPORT,
      () =>
        new Response(serverBytes, {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'Content-Disposition': `attachment; filename="argos-transaction-${FIXTURE_TX_ID}.json"`,
          },
        }),
    );
    const saved: { blob: Blob; name: string }[] = [];
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: (blob: Blob) => {
        saved.push({ blob, name: '' });
        return 'blob:http://localhost:3000/x';
      },
    });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => undefined });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      const last = saved.at(-1);
      if (last !== undefined) {
        last.name = this.download;
      }
    });
    try {
      const name = await downloadTransactionExport(FIXTURE_TX_ID, FIXTURE_TS, {
        setTimeout: () => undefined,
      });
      expect(name).toBe(`argos-transaction-${FIXTURE_TX_ID}.json`);
      expect(stub.calls(EXPORT)[0]?.query.get('ts')).toBe(FIXTURE_TS);
      expect(saved).toHaveLength(1);
      const [file] = saved;
      if (file === undefined) {
        throw new Error('nothing saved');
      }
      expect(file.name).toBe(name);
      expect(file.blob.type).toBe('application/json');
      // Byte for byte (compared as plain arrays: jsdom's Blob returns another realm's buffer).
      expect(Array.from(new Uint8Array(await file.blob.arrayBuffer()))).toEqual(
        Array.from(serverBytes),
      );

      stub.on(EXPORT, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' } }));
      const error: unknown = await downloadTransactionExport(FIXTURE_TX_ID).catch(
        (e: unknown) => e,
      );
      expect(isApiError(error) && error.status).toBe(429);
      expect(isApiError(error) && error.retryAfterSeconds).toBe(30);
      expect(saved).toHaveLength(1);
    } finally {
      Reflect.deleteProperty(URL, 'createObjectURL');
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });
});
