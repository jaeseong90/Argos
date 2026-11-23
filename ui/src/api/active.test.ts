// SPDX-License-Identifier: Apache-2.0
//
// Active transactions API (docs/14 §4.9, §5, WO-35): the REST call asks for every kind through
// the generated client, the WS `active` payload is validated with zod (a broken payload is dropped
// as a whole, missing nullable fields become null), and the REST snapshot is polled every 5 s only
// in the polling fallback (docs/15 §10), never while the tab is hidden.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U04' }
import { focusManager, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../test/apiStub';
import { activeItem, scenario } from '../features/active/testing/fixtures';

import {
  ACTIVE_MAX_ITEMS,
  activeRefetchInterval,
  getActive,
  parseActivePush,
  useActiveQuery,
} from './active';
import { createQueryClient } from './queryClient';

export const REQ = { type: 'req', description: 'F-U04' };

const ROUTE = 'GET /api/v1/apps/7/active';
let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
  stub.on(ROUTE, () => envelope(scenario(), { generated_at: '2026-11-23T10:00:02.000Z' }));
});

afterEach(() => {
  uninstallApiStub();
  vi.useRealTimers();
  focusManager.setFocused(undefined);
});

describe('active API: REST (docs/14 §4.9)', () => {
  it('asks GET /apps/{appId}/active?kind=ALL and returns data and meta', async () => {
    const result = await getActive(7);
    expect(stub.calls(ROUTE)).toHaveLength(1);
    expect(stub.calls(ROUTE)[0]?.query.get('kind')).toBe('ALL');
    expect(result.data.per_instance).toHaveLength(2);
    expect(result.meta.generated_at).toBe('2026-11-23T10:00:02.000Z');
  });
});

describe('active API: WS payload (docs/14 §5.2)', () => {
  it('accepts the REST schema and keeps every field', () => {
    const data = scenario();
    const parsed = parseActivePush(JSON.parse(JSON.stringify(data)));
    expect(parsed).toEqual(data);
  });

  it('missing nullable fields become null; virtual_thread defaults to false', () => {
    const item: Record<string, unknown> = { ...activeItem(1, '5', 100) };
    for (const key of [
      'service',
      'service_id',
      'state_detail',
      'thread',
      'user_id',
      'user_id_mode',
    ]) {
      Reflect.deleteProperty(item, key);
    }
    Reflect.deleteProperty(item, 'virtual_thread');
    const parsed = parseActivePush({ snapshot_at: null, per_instance: [], items: [item] });
    expect(parsed?.items[0]).toMatchObject({
      service: null,
      service_id: null,
      state_detail: null,
      thread: null,
      user_id: null,
      user_id_mode: null,
      virtual_thread: false,
    });
  });

  it('drops a payload with a wrong type, a bad tx id, wrong bucket count or too many items', () => {
    const data = JSON.parse(JSON.stringify(scenario())) as Record<string, unknown>;
    expect(parseActivePush({ ...data, items: 'x' })).toBeNull();
    expect(parseActivePush({ ...data, items: [{ ...activeItem(1, 'abc', 1) }] })).toBeNull();
    expect(
      parseActivePush({
        ...data,
        per_instance: [{ ...(data.per_instance as object[])[0], buckets: [1, 2, 3] }],
      }),
    ).toBeNull();
    const many = Array.from({ length: ACTIVE_MAX_ITEMS + 1 }, (_, i) =>
      activeItem(1, String(i), 1),
    );
    expect(parseActivePush({ ...data, items: many })).toBeNull();
    expect(parseActivePush(null)).toBeNull();
  });
});

describe('active API: polling fallback (docs/15 §10)', () => {
  function wrapper() {
    const client = createQueryClient();
    function Wrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client }, children);
    }
    return Wrapper;
  }

  it('refetches every 5 s only while polling', async () => {
    expect(activeRefetchInterval(true)).toBe(5_000);
    expect(activeRefetchInterval(false)).toBe(false);
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { result, rerender } = renderHook(
      ({ polling }: { polling: boolean }) => useActiveQuery(7, polling),
      { wrapper: wrapper(), initialProps: { polling: false } },
    );
    await waitFor(() => {
      expect(result.current.data).toBeDefined();
    });
    await vi.advanceTimersByTimeAsync(12_000);
    expect(stub.calls(ROUTE)).toHaveLength(1);
    rerender({ polling: true });
    await vi.advanceTimersByTimeAsync(5_100);
    await waitFor(() => {
      expect(stub.calls(ROUTE)).toHaveLength(2);
    });
    await vi.advanceTimersByTimeAsync(5_100);
    await waitFor(() => {
      expect(stub.calls(ROUTE)).toHaveLength(3);
    });
  });

  it('does not poll while the tab is hidden', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { result } = renderHook(() => useActiveQuery(7, true), { wrapper: wrapper() });
    await waitFor(() => {
      expect(result.current.data).toBeDefined();
    });
    focusManager.setFocused(false);
    await vi.advanceTimersByTimeAsync(16_000);
    expect(stub.calls(ROUTE)).toHaveLength(1);
  });
});
