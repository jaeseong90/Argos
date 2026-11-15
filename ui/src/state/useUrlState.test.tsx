// SPDX-License-Identifier: Apache-2.0
//
// The React side of the URL state (docs/15 §4.2, §5): the route loader validates and rewrites the
// URL to its canonical form with one restore notice; useUrlState().set is the only writer (time
// range and live replace the history entry, other changes push; foreign keys stay; over 2 KB is
// refused with a notice); useTimeRange dispatches the reducer and runs the resume resync.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'SEC-64' }
import { act, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../api/untrusted';
import { XSS_PAYLOADS } from '../components/testing/xssPayloads';

import { useUiStore } from './uiStore';
import { buildUrl, CommonQ, parseUrl, URL_WRITE_LIMIT, XLogQ } from './urlState';
import { onResumeLive, useTimeRange, type TimeRangeApi } from './useTimeRange';
import {
  urlLoader,
  useUrlState,
  validateUrl,
  type RouteHandle,
  type UrlStateApi,
} from './useUrlState';

const T0 = Date.UTC(2026, 10, 14, 1, 0, 0);

/** What the probe component last rendered (written in an effect, read by the tests). */
const sink: {
  api: UrlStateApi<typeof XLogQ> | null;
  common: UrlStateApi<typeof CommonQ> | null;
  time: TimeRangeApi | null;
} = { api: null, common: null, time: null };

function Probe() {
  const api = useUrlState(XLogQ);
  // A shell part (top bar) writes with the common schema on every route.
  const common = useUrlState(CommonQ);
  const time = useTimeRange();
  useEffect(() => {
    sink.api = api;
    sink.common = common;
    sink.time = time;
  });
  return <p data-testid="probe">{api.state.kind}</p>;
}

function setup(path: string) {
  // XLog is a live screen: its route handle carries the screen with `live` (routes/routeTree.tsx).
  const handle: RouteHandle & { screen: { live: boolean } } = {
    urlSchema: XLogQ,
    scope: 'app',
    screen: { live: true },
  };
  const router = createMemoryRouter(
    [
      { path: '/apps/:appId/xlog', loader: urlLoader(XLogQ), handle, Component: Probe },
      { path: '/other', loader: urlLoader(CommonQ), Component: Probe },
    ],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return {
    router,
    url: () => `${router.state.location.pathname}${router.state.location.search}`,
  };
}

/** Waits until the probe rendered and its effect published the hooks' values. */
async function rendered(): Promise<void> {
  await screen.findByTestId('probe');
  await waitFor(() => {
    expect(sink.api).not.toBeNull();
    expect(sink.time).not.toBeNull();
  });
}

function current(): UrlStateApi<typeof XLogQ> {
  if (sink.api === null) throw new Error('not rendered');
  return sink.api;
}

beforeEach(() => {
  sink.api = null;
  sink.common = null;
  sink.time = null;
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(T0);
  localStorage.clear();
  useUiStore.setState({
    restoreNotice: null,
    restorePath: null,
    urlTooLong: false,
    resumeAvailable: false,
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('T-UI-003 route loader (docs/15 §4.2 "로더 URL 검증")', () => {
  it('T-UI-003 rewrites a URL to its canonical form without a notice when every item is valid', async () => {
    const app = setup('/apps/1/xlog?range=1h&kind=batch&f=service:eq:17');
    await rendered();
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-1h&kind=BATCH&f.service=17');
    });
    expect(useUiStore.getState().restoreNotice).toBeNull();
  });

  it('T-SEC-64 the canonical URL of every XSS seed filter value is a fixed point (no redirect loop)', () => {
    // A redirect target that the browser re-encodes differently (e.g. "'" → %27) must not be
    // redirected again: the loader compares URL-parser spellings (T-E2E-common-18).
    for (const payload of XSS_PAYLOADS) {
      const state = {
        ...parseUrl(XLogQ, '').state,
        filters: [{ field: 'service', op: 'eq' as const, value: untrusted(payload) }],
      };
      const target = new Request(`http://localhost${buildUrl('/apps/1/xlog', XLogQ, state)}`);
      const result = validateUrl(XLogQ, target);
      expect(result, payload).not.toBeInstanceOf(Response);
    }
    const raw = validateUrl(XLogQ, new Request("http://localhost/apps/1/xlog?f.service='x"));
    expect(raw).not.toBeInstanceOf(Response);
    expect((raw as { filters: readonly { value: string }[] }).filters[0]?.value).toBe("'x");
  });

  it('T-SEC-64 restores invalid items to defaults and raises one notice', async () => {
    const app = setup('/apps/1/xlog?from=bogus&kind=nope&f.instance=3&color=rainbow');
    await rendered();
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?f.instance=3');
    });
    expect(
      useUiStore
        .getState()
        .restoreNotice?.map((i) => i.key)
        .sort(),
    ).toEqual(['color', 'from', 'kind']);
  });
});

describe('T-UI-003 useUrlState().set (docs/15 §4.2)', () => {
  it('T-UI-003 replaces the history entry for time and live, pushes for filters and selection', async () => {
    const app = setup('/apps/1/xlog');
    await rendered();
    act(() => {
      current().set({ time: { kind: 'relative', range: '1h' }, live: true });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-1h&live=1');
    });
    expect(app.router.state.historyAction).toBe('REPLACE');
    act(() => {
      current().set({ filters: [{ field: 'service', op: 'eq', value: untrusted('17') }] });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-1h&live=1&f.service=17');
    });
    expect(app.router.state.historyAction).toBe('PUSH');
    act(() => {
      current().set({ sel: { msMin: 0, msMax: 100, tsFrom: T0 - 1000, tsTo: T0 } });
    });
    await waitFor(() => {
      expect(app.url()).toContain('sel=ms:0-100');
    });
    expect(app.router.state.historyAction).toBe('PUSH');
  });

  it('T-UI-003 keeps query keys that the writing schema does not own', async () => {
    const app = setup('/apps/1/xlog?color=service');
    await rendered();
    // color belongs to the XLog schema; a write with the common schema keeps it.
    act(() => {
      sink.common?.set({ kind: 'ALL' });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?kind=ALL&color=service');
    });
  });

  it('T-UI-003 refuses a URL longer than 2 KB and raises the notice instead', async () => {
    const app = setup('/apps/1/xlog');
    await rendered();
    const filters = Array.from({ length: 3 }, (_, i) => ({
      field: `f${String(i)}`,
      op: 'eq' as const,
      value: untrusted('x'.repeat(1000)),
    }));
    let written = true;
    act(() => {
      written = current().set({ filters });
    });
    expect(written).toBe(false);
    expect(useUiStore.getState().urlTooLong).toBe(true);
    expect(app.url()).toBe('/apps/1/xlog');
    expect(URL_WRITE_LIMIT).toBe(2048);
  });

  it('T-UI-003 refuses a state that fails the zod schema (from >= to)', async () => {
    const app = setup('/apps/1/xlog');
    await rendered();
    let written = true;
    act(() => {
      written = current().set({ time: { kind: 'absolute', from: T0, to: T0 } });
    });
    expect(written).toBe(false);
    expect(app.url()).toBe('/apps/1/xlog');
  });
});

describe('T-UI-002 useTimeRange (docs/15 §5)', () => {
  function timeApi(): TimeRangeApi {
    if (sink.time === null) throw new Error('not rendered');
    return sink.time;
  }

  it('T-UI-002 resolves the range at the current clock and gives query keys a 10 s clock', async () => {
    setup('/apps/1/xlog?from=now-1h');
    await rendered();
    vi.setSystemTime(T0 + 7_000);
    expect(timeApi().queryRange.to % 10_000).toBe(0);
  });

  it('T-UI-002 the live toggle remembers the choice that "now" restores', async () => {
    const app = setup('/apps/1/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:30:00.000Z');
    await rendered();
    act(() => {
      timeApi().dispatch({ type: 'setLive', live: true });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-30m&live=1');
    });
    expect(localStorage.getItem('argos.ui.live')).toBe('1');
    act(() => {
      timeApi().dispatch({ type: 'prev' });
    });
    await waitFor(() => {
      expect(app.url()).not.toContain('live=1');
    });
    act(() => {
      timeApi().dispatch({ type: 'now' });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-30m&live=1');
    });
  });

  it('T-UI-002 "now" works when storage throws (private window)', async () => {
    const app = setup('/apps/1/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T01:00:00.000Z');
    await rendered();
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    act(() => {
      timeApi().dispatch({ type: 'now' });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?from=now-1h');
    });
    spy.mockRestore();
  });

  it('T-UI-002 an XLog drag while live offers resume; resume returns to live 15m and runs the resync with the gap', async () => {
    const app = setup('/apps/1/xlog?from=now-1h&live=1');
    await rendered();
    const gaps: { from: number; to: number }[] = [];
    const off = onResumeLive((gap) => gaps.push(gap));
    act(() => {
      timeApi().dispatch({
        type: 'xlogDrag',
        sel: { msMin: 0, msMax: 500, tsFrom: T0 - 600_000, tsTo: T0 - 300_000 },
      });
    });
    await waitFor(() => {
      expect(app.url()).toContain('sel=');
    });
    expect(app.url()).not.toContain('live=1');
    expect(timeApi().resumeAvailable).toBe(true);
    vi.setSystemTime(T0 + 120_000);
    act(() => {
      timeApi().dispatch({ type: 'resume' });
    });
    await waitFor(() => {
      expect(app.url()).toBe('/apps/1/xlog?live=1');
    });
    // The gap runs from the end of the frozen window (the drag time) to the resume time; the
    // clock advances a few ms while React renders (shouldAdvanceTime).
    expect(gaps).toHaveLength(1);
    expect(gaps[0]?.from).toBeGreaterThanOrEqual(T0);
    expect(gaps[0]?.from).toBeLessThan(T0 + 1_000);
    expect(gaps[0]?.to).toBeGreaterThanOrEqual(T0 + 120_000);
    expect(gaps[0]?.to).toBeLessThan(T0 + 121_000);
    off();
  });
});
