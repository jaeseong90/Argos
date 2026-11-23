// SPDX-License-Identifier: Apache-2.0
//
// The active transactions screen in the whole app (router, URL state, transport, generated client;
// only the HTTP boundary is stubbed and the WebSocket is a fake socket), docs/15 §9.6, 05 §2.6:
// the REST snapshot and the `active` topic replacing it (no merge), per-instance bars with the
// four bands and the instance state (T-UI-008), the nine columns with "Not collected" apart from
// the dash (T-UI-007), default sort by elapsed and the client-side filters (T-UI-004), custom
// thresholds, pause, "as of N s ago" on the server clock, the stale badge and its recovery, row
// actions (stack and dump commands for operators, disabled with the reason for viewers, instance
// detail and recent completions links), collected strings as text (T-UI-005),
// empty and error states and the Korean catalog (T-UI-006).
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import type { FakeSocket, FakeSockets } from '../../../test/fakeWebSocket';
import {
  appBody,
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import type { ActiveTransactions } from '../../api/active';
import { untrusted } from '../../api/untrusted';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../../components/testing/xssPayloads';
import enActive from '../../locales/en/active.json';
import { useConnectionStore } from '../../realtime/connectionStore';
import { useUiStore } from '../../state/uiStore';
import { resetWrittenUrls } from '../../state/useUrlState';
import { MISSING_DASH } from '../../utils/missing';

import {
  activeInstance,
  activeItem,
  activeSnapshot,
  scenario,
  SNAPSHOT_AT,
} from './testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U04' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const ACTIVE = 'GET /api/v1/apps/1/active';

let stub: ApiStub;
let sockets: FakeSockets;

function answer(data: ActiveTransactions = scenario(), generatedAt = '2026-11-23T10:00:02.000Z') {
  stub.on(ACTIVE, () => envelope(data, { generated_at: generatedAt, request_id: 'req-active' }));
}

async function openActive(query = '', language: 'en' | 'ko' = 'en', roles = ['viewer']) {
  signedIn(stub, { roles });
  const app = renderApp(`/apps/1/active${query === '' ? '' : `?${query}`}`, { language });
  const page = await screen.findByTestId('active-page', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return { app, page };
}

function rowIds(): string[] {
  return [...document.querySelectorAll<HTMLElement>('[data-testid^="active-row-"]')]
    .map((row) => row.getAttribute('data-testid') ?? '')
    .filter((id) => /^active-row-\d+$/.test(id))
    .map((id) => id.replace('active-row-', ''));
}

/** Opens the socket and returns the id of the screen's `active` subscription. */
async function subscribed(): Promise<{ socket: FakeSocket; id: string }> {
  const socket = sockets.last();
  act(() => {
    socket.serverOpen();
  });
  await waitFor(() => {
    expect(socket.framesOf('subscribe').some((f) => f.topic === 'active')).toBe(true);
  }, FIND);
  act(() => {
    socket.ackAll();
  });
  const frame = socket.framesOf('subscribe').find((f) => f.topic === 'active');
  return { socket, id: String(frame?.id) };
}

function push(socket: FakeSocket, id: string, t: string, data: unknown): void {
  act(() => {
    socket.serverSend({ op: 'push', id, topic: 'active', t, data });
  });
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  // URL writes of the previous test must not chain into this one (useUrlState freshUrl).
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('active: snapshot, bars, summary and list (05 §2.6-1, -2, -5)', () => {
  it('T-UI-004 asks kind=ALL once, lists HTTP rows by elapsed descending and keeps bars and summary app-wide', async () => {
    answer();
    const { page } = await openActive();
    expect(page).toHaveAttribute('data-state', 'ready');
    expect(stub.calls(ACTIVE)[0]?.query.get('kind')).toBe('ALL');
    // Global kind default HTTP: the BATCH one (104) is left out of the list only.
    expect(rowIds()).toEqual(['101', '201', '102', '103', '202']);
    expect(screen.getByTestId('active-summary-total')).toHaveAttribute('data-value', '6');
    expect(screen.getByTestId('active-summary-slow')).toHaveAttribute('data-value', '2');
    expect(screen.getByTestId('active-summary-max')).toHaveAttribute('data-value', '4');
    expect(screen.getByTestId('active-summary-max')).toHaveAttribute('data-instance', '1');
    expect(screen.getByTestId('active-summary-max-name')).toHaveTextContent('order-1');
    const bar1 = screen.getByTestId('active-bar-1');
    expect(bar1).toHaveAttribute('data-count', '4');
    expect(bar1).toHaveAttribute('data-buckets', '1,1,1,1');
    expect(bar1).toHaveAttribute('data-approx', 'false');
    expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,0,1');
    // Numbers next to the colours (never colour only).
    expect(screen.getByTestId('active-bar-numbers-1')).toHaveTextContent('< 1 s1');
    // Sorting by another column is the table's own (dataComplete) and written to the URL.
    fireEvent.click(screen.getByTestId('data-table-sort-elapsed_ms'));
    await waitFor(() => {
      expect(rowIds()).toEqual(['202', '103', '102', '201', '101']);
    }, FIND);
  });

  it('T-UI-007 renders the nine columns of the response; user and IP "Not collected" apart from the dash', async () => {
    answer();
    await openActive('kind=ALL');
    expect(rowIds()).toContain('104');
    expect(screen.getByTestId('active-cell-instance-101')).toHaveTextContent('order-1');
    expect(screen.getByTestId('active-cell-service-101')).toHaveTextContent('/api/orders');
    expect(screen.getByTestId('active-cell-elapsed-101')).toHaveAttribute('data-value', '12500');
    expect(screen.getByTestId('active-cell-elapsed-101')).toHaveAttribute('data-band', '3');
    expect(screen.getByTestId('active-row-101')).toHaveAttribute('data-elapsed-band', '3');
    expect(screen.getByTestId('active-cell-state-101')).toHaveTextContent(
      'Running SQLSELECT * FROM orders WHERE id = ?',
    );
    expect(screen.getByTestId('active-cell-state-102')).toHaveTextContent('Callingpay.example.com');
    expect(screen.getByTestId('active-cell-state-103')).toHaveTextContent(
      'Connection waitjdbc:postgresql://db:5432/shop',
    );
    expect(screen.getByTestId('active-cell-state-104')).toHaveTextContent('Running method');
    expect(screen.getByTestId('active-cell-state-201')).toHaveTextContent('Async wait');
    expect(screen.getByTestId('active-cell-thread-101')).toHaveTextContent(
      'http-nio-8080-exec-101',
    );
    expect(screen.getByTestId('active-cell-virtual-103')).toBeInTheDocument();
    expect(screen.queryByTestId('active-cell-virtual-101')).not.toBeInTheDocument();
    expect(screen.getByTestId('active-cell-user-101')).toHaveTextContent('user-101');
    expect(screen.getByTestId('active-cell-ip-101')).toHaveTextContent('10.0.0.1');
    expect(screen.getByTestId('active-cell-kind-104')).toHaveTextContent('Batch');
    expect(screen.getByTestId('active-cell-started-101')).toHaveAttribute(
      'data-value',
      '2026-11-23T09:59:47.500Z',
    );
    // Mode off: "Not collected"; collected but absent: the dash.
    expect(screen.getByTestId('active-cell-user-201')).toHaveTextContent('Not collected');
    expect(screen.getByTestId('active-cell-user-201')).toHaveAttribute(
      'data-missing',
      'notCollected',
    );
    expect(screen.getByTestId('active-cell-ip-201')).toHaveTextContent('Not collected');
    expect(screen.getByTestId('active-cell-user-202')).toHaveTextContent(MISSING_DASH);
    expect(screen.getByTestId('active-cell-ip-202')).toHaveTextContent(MISSING_DASH);
  });

  it('T-UI-008 shows the instance state of a bar (WARN, STALE, DOWN) with its label; UP shows none', async () => {
    answer(
      activeSnapshot(
        [
          activeInstance(1, 'a'),
          activeInstance(2, 'b', { state: 'WARN' }),
          activeInstance(3, 'c', { state: 'STALE' }),
          activeInstance(4, 'd', { state: 'DOWN' }),
        ],
        [activeItem(1, '1', 100), activeItem(3, '3', 100)],
      ),
    );
    await openActive();
    expect(screen.getByTestId('active-bar-1')).toHaveAttribute('data-state', 'UP');
    expect(screen.queryByTestId('active-bar-status-1')).not.toBeInTheDocument();
    for (const [id, status, label] of [
      [2, 'WARN', 'WARN'],
      [3, 'STALE', 'STALE'],
      [4, 'DOWN', 'DOWN'],
    ] as const) {
      expect(screen.getByTestId(`active-bar-${String(id)}`)).toHaveAttribute('data-state', status);
      const badge = screen.getByTestId(`active-bar-status-${String(id)}`);
      expect(badge).toHaveAttribute('data-status', status);
      expect(badge.textContent).toContain(label);
    }
  });

  it('marks a truncated instance and an approximate split', async () => {
    const items = Array.from({ length: 3 }, (_, i) => activeItem(1, String(i + 1), 2_000 + i));
    const data = activeSnapshot([activeInstance(1, 'a')], items);
    const first = data.per_instance[0];
    if (first === undefined) throw new Error('fixture');
    answer({ ...data, per_instance: [{ ...first, count: 900, truncated: true }] });
    await openActive();
    expect(screen.getByTestId('active-bar-truncated-1')).toBeInTheDocument();
    expect(screen.getByTestId('active-bar-approx-1')).toBeInTheDocument();
    expect(screen.getByTestId('active-bar-1')).toHaveAttribute('data-buckets', '897,3,0,0');
    expect(screen.getByTestId('active-summary-total')).toHaveAttribute('data-value', '900');
  });
});

describe('active: filters (05 §2.6-1, -3, T-UI-004)', () => {
  it('a bar click toggles f.instance: the list follows, the bars and the summary stay', async () => {
    answer();
    const { app } = await openActive();
    fireEvent.click(screen.getByTestId('active-bar-button-2'));
    await waitFor(() => {
      expect(app.url()).toContain('f.instance=2');
      expect(rowIds()).toEqual(['201', '202']);
    }, FIND);
    expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('active-bar-1')).toBeInTheDocument();
    expect(screen.getByTestId('active-summary-total')).toHaveAttribute('data-value', '6');
    // The chip names the instance (key in the URL, name on the chip).
    expect(screen.getByTestId('filter-chip-bar').textContent).toContain('order-2');
    fireEvent.click(screen.getByTestId('active-bar-button-2'));
    await waitFor(() => {
      expect(app.url()).not.toContain('f.instance');
      expect(rowIds()).toHaveLength(5);
    }, FIND);
  });

  it('state, service key and kind filters from the URL and the controls', async () => {
    answer();
    const { app } = await openActive('kind=ALL&f.service=502');
    expect(rowIds()).toEqual(['201', '102']);
    fireEvent.click(screen.getByTestId('active-state-filter-ASYNC'));
    await waitFor(() => {
      expect(app.url()).toContain('f.state=ASYNC');
      expect(rowIds()).toEqual(['201']);
    }, FIND);
    expect(screen.getByTestId('active-state-filter-ASYNC')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByTestId('active-state-filter-all'));
    await waitFor(() => {
      expect(app.url()).not.toContain('f.state');
      expect(rowIds()).toEqual(['201', '102']);
    }, FIND);
  });

  it('includes a service by its key from a cell; unknown URL filters are named, not applied', async () => {
    answer();
    const { app } = await openActive('f.status=500');
    expect(screen.getByTestId('active-ignored-filters')).toHaveTextContent('status');
    expect(rowIds()).toHaveLength(5);
    const cell = screen.getByTestId('active-cell-service-101').closest('td');
    if (cell === null) throw new Error('no cell');
    fireEvent.click(within(cell).getByTestId('cell-filter-include'));
    await waitFor(() => {
      expect(app.url()).toContain('f.service=501');
      expect(rowIds()).toEqual(['101', '103']);
    }, FIND);
  });

  it('an empty filtered list offers to clear the filters and to show all kinds', async () => {
    answer();
    const { app } = await openActive('f.state=SQL&kind=BATCH');
    expect(screen.getByTestId('empty-state')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('active-empty-all-kinds'));
    await waitFor(() => {
      expect(app.url()).toContain('kind=ALL');
      expect(rowIds()).toEqual(['101']);
    }, FIND);
  });
});

describe('active: kind filter follows the live snapshot (C-225 (7))', () => {
  it('offers Batch while the snapshot has a running BATCH although the app recorded none, and not after it', async () => {
    answer();
    signedIn(stub);
    // No finished batch job in the last 7 days (C-205 (4)): only the snapshot can enable Batch.
    stub.on('GET /api/v1/apps/1', () =>
      envelope(appBody(1, 'order-api', 'prod', { kinds: ['HTTP'] })),
    );
    const app = renderApp('/apps/1/active');
    const page = await screen.findByTestId('active-page', {}, FIND);
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'ready');
    }, FIND);
    // The scenario has a running BATCH (104): Batch is selectable and selecting it lists that row.
    await waitFor(() => {
      expect(screen.getByTestId('kind-filter-batch')).not.toHaveAttribute('aria-disabled');
    }, FIND);
    fireEvent.click(screen.getByTestId('kind-filter-batch'));
    await waitFor(() => {
      expect(app.url()).toContain('kind=BATCH');
      expect(rowIds()).toEqual(['104']);
    }, FIND);
    // A snapshot without BATCH (the job finished) falls back to the app's stored kinds.
    const { socket, id } = await subscribed();
    push(
      socket,
      id,
      '2026-11-23T10:00:08.000Z',
      activeSnapshot(
        [activeInstance(1, 'order-1')],
        [activeItem(1, '301', 4_000)],
        '2026-11-23T10:00:05.000Z',
      ),
    );
    await waitFor(() => {
      expect(page).toHaveAttribute('data-source', 'ws');
      expect(screen.getByTestId('kind-filter-batch')).toHaveAttribute('aria-disabled', 'true');
    }, FIND);
    // Leaving the screen drops its snapshot kinds.
    act(() => {
      void app.router.navigate('/apps/1/dashboard?from=now-1h');
    });
    await waitFor(() => {
      expect(screen.queryByTestId('active-page')).not.toBeInTheDocument();
      expect(useUiStore.getState().snapshotKinds).toBeNull();
      expect(screen.getByTestId('kind-filter-batch')).toHaveAttribute('aria-disabled', 'true');
    }, FIND);
  });
});

describe('active: thresholds (05 §2.6-1 "설정 가능")', () => {
  it('custom thresholds recount bars and rows, persist across reloads, and a wrong order is refused', async () => {
    answer();
    const { app } = await openActive();
    const set = (values: readonly string[]) => {
      values.forEach((value, index) => {
        fireEvent.change(screen.getByTestId(`active-threshold-${String(index + 1)}`), {
          target: { value },
        });
      });
      fireEvent.click(screen.getByTestId('active-thresholds-apply'));
    };
    set(['5', '3', '8']);
    expect(screen.getByTestId('active-thresholds-error')).toHaveAttribute(
      'data-problem',
      'notAscending',
    );
    expect(screen.getByTestId('active-bar-1')).toHaveAttribute('data-buckets', '1,1,1,1');
    set(['2', '5', '10']);
    await waitFor(() => {
      // 12.5 s → ≥10, 5 s → 5–10, 2 s → 2–5, 0.3 s → < 2.
      expect(screen.getByTestId('active-bar-1')).toHaveAttribute('data-buckets', '1,1,1,1');
      // order-2: 9 s → 5–10, 0.7 s → < 2.
      expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,1,0');
    }, FIND);
    expect(screen.getByTestId('active-row-201')).toHaveAttribute('data-elapsed-band', '2');
    expect(screen.getByTestId('active-summary-slow')).toHaveAttribute('data-value', '1');
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBe('[2,5,10]');
    expect(screen.queryByTestId('active-thresholds-error')).not.toBeInTheDocument();
    app.unmount();
    await openActive();
    expect(screen.getByTestId('active-threshold-1')).toHaveValue(2);
    expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,1,0');
    fireEvent.click(screen.getByTestId('active-thresholds-reset'));
    await waitFor(() => {
      expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,0,1');
    }, FIND);
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBeNull();
  });
});

describe('active: realtime (docs/14 §5, docs/15 §10)', () => {
  it('subscribes to active with kind ALL; a push replaces the snapshot as a whole and a broken one is dropped', async () => {
    answer();
    const { page } = await openActive();
    const { socket, id } = await subscribed();
    expect(socket.framesOf('subscribe').find((f) => f.topic === 'active')?.params).toEqual({
      app: 1,
      kind: 'ALL',
    });
    push(socket, id, '2026-11-23T10:00:08.000Z', { per_instance: 'broken' });
    expect(page).toHaveAttribute('data-source', 'rest');
    const next = activeSnapshot(
      [activeInstance(1, 'order-1')],
      [activeItem(1, '301', 4_000, { service: untrusted('/api/new') })],
      '2026-11-23T10:00:05.000Z',
    );
    push(socket, id, '2026-11-23T10:00:08.000Z', next);
    await waitFor(() => {
      expect(page).toHaveAttribute('data-source', 'ws');
    }, FIND);
    // No merge: the old rows and the second instance are gone.
    expect(rowIds()).toEqual(['301']);
    expect(screen.queryByTestId('active-bar-2')).not.toBeInTheDocument();
    expect(screen.getByTestId('active-summary-total')).toHaveAttribute('data-value', '1');
    // As of: push t − snapshot_at on the server clock (3 s), whatever the browser clock says.
    expect(
      Number(screen.getByTestId('active-asof').getAttribute('data-seconds')),
    ).toBeLessThanOrEqual(4);
    expect(
      Number(screen.getByTestId('active-asof').getAttribute('data-seconds')),
    ).toBeGreaterThanOrEqual(3);
    expect(screen.getByTestId('active-asof')).toHaveTextContent(/As of [34] s ago/);
    // A reconnect resubscribes and refetches REST (onResync).
    const restCalls = stub.calls(ACTIVE).length;
    act(() => {
      socket.serverClose(1006);
    });
    await waitFor(
      () => {
        expect(sockets.sockets.length).toBeGreaterThan(1);
      },
      { timeout: 8000 },
    );
    act(() => {
      sockets.last().serverOpen();
    });
    await waitFor(() => {
      expect(
        sockets
          .last()
          .framesOf('subscribe')
          .some((f) => f.topic === 'active'),
      ).toBe(true);
    }, FIND);
    await waitFor(() => {
      expect(stub.calls(ACTIVE).length).toBeGreaterThan(restCalls);
    }, FIND);
  }, 20_000);

  it('pause freezes the display while pushes arrive; resume shows the newest snapshot', async () => {
    answer();
    const { page } = await openActive();
    const { socket, id } = await subscribed();
    fireEvent.click(screen.getByTestId('active-pause'));
    expect(page).toHaveAttribute('data-paused', 'true');
    expect(screen.getByTestId('active-asof')).toHaveTextContent(/^Paused · as of/);
    push(
      socket,
      id,
      '2026-11-23T10:00:08.000Z',
      activeSnapshot([activeInstance(1, 'order-1')], [activeItem(1, '401', 1_500)]),
    );
    // Still the REST rows.
    expect(rowIds()).toEqual(['101', '201', '102', '103', '202']);
    expect(page).toHaveAttribute('data-source', 'rest');
    fireEvent.click(screen.getByTestId('active-pause'));
    await waitFor(() => {
      expect(rowIds()).toEqual(['401']);
    }, FIND);
    expect(page).toHaveAttribute('data-paused', 'false');
  });

  it('shows "Updates stopped N s" while the socket is silent and clears it with the next snapshot', async () => {
    answer();
    await openActive();
    const { socket, id } = await subscribed();
    expect(screen.queryByTestId('active-stale-badge')).not.toBeInTheDocument();
    act(() => {
      useConnectionStore.setState({
        lastMessageAt: Date.now() - 15_000,
        activeSince: Date.now() - 20_000,
      });
    });
    const badge = await screen.findByTestId('active-stale-badge', {}, FIND);
    // The second tick may lag the store write by up to 1 s.
    expect(badge).toHaveTextContent(/Updates stopped 1[4-7] s/);
    push(socket, id, '2026-11-23T10:00:08.000Z', scenario());
    await waitFor(() => {
      expect(screen.queryByTestId('active-stale-badge')).not.toBeInTheDocument();
    }, FIND);
  });

  it('falls back to REST polling after 30 s without a server message', async () => {
    answer();
    await openActive();
    expect(screen.queryByTestId('active-polling')).not.toBeInTheDocument();
    act(() => {
      useConnectionStore.setState({
        subscriptions: 1,
        lastMessageAt: Date.now() - 31_000,
        activeSince: Date.now() - 40_000,
      });
    });
    expect(await screen.findByTestId('active-polling', {}, FIND)).toBeInTheDocument();
    const before = stub.calls(ACTIVE).length;
    await waitFor(
      () => {
        expect(stub.calls(ACTIVE).length).toBeGreaterThan(before);
      },
      { timeout: 8000 },
    );
  }, 15_000);
});

describe('active: row actions (05 §2.6-4, T-ACPT-02 automated part)', () => {
  it('a viewer sees stack and dump disabled with the operator reason; recent completions links to the XLog', async () => {
    answer();
    const { app } = await openActive('env=prod');
    expect(screen.queryByTestId('active-row-actions')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('active-cell-elapsed-101'));
    const actions = await screen.findByTestId('active-row-actions', {}, FIND);
    expect(actions).toHaveAttribute('data-tx-id', '101');
    expect(screen.getByTestId('active-row-101')).toHaveAttribute('data-selected', 'true');
    for (const testId of ['active-action-stack', 'active-action-dump']) {
      const button = screen.getByTestId(testId);
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('data-reason', enActive.actions.operatorRequired);
      const described = document.getElementById(button.getAttribute('aria-describedby') ?? '');
      expect(described).toHaveTextContent(enActive.actions.operatorRequired);
      fireEvent.click(button);
    }
    // No command was requested for the viewer.
    expect(stub.requests.some((r) => r.path.endsWith('/commands'))).toBe(false);
    expect(app.url()).toContain('/apps/1/active');
    const recent = screen.getByTestId('active-action-recent');
    const href = recent.getAttribute('href') ?? '';
    expect(href.startsWith('/apps/1/xlog?')).toBe(true);
    const query = new URLSearchParams(href.split('?')[1]);
    expect(query.get('f.service')).toBe('501');
    expect(query.get('env')).toBe('prod');
    // 15 minutes is the default range (written as nothing); kind HTTP the default kind.
    expect(query.get('from')).toBeNull();
    // A BATCH transaction carries its kind.
    fireEvent.click(screen.getByTestId('active-actions-clear'));
    expect(screen.queryByTestId('active-row-actions')).not.toBeInTheDocument();
  });

  it('T-ACPT-02 an operator gets the thread stack: thread_stack with the tx id, polled until done, state and tx match shown', async () => {
    answer();
    const commandId = '6f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f';
    let polls = 0;
    stub.on('POST /api/v1/instances/1/commands', () =>
      envelope({ command_id: commandId, status: 'queued', ttl_s: 60 }, {}, 202),
    );
    stub.on(`GET /api/v1/commands/${commandId}`, () => {
      polls += 1;
      const done = polls >= 2;
      return envelope({
        command_id: commandId,
        instance_id: 1,
        type: 'thread_stack',
        args: { tx_id: '101' },
        status: done ? 'done' : 'sent',
        error: null,
        requested_by: 'u-1',
        requested_at: '2026-11-23T10:00:00.000Z',
        sent_at: '2026-11-23T10:00:00.100Z',
        finished_at: done ? '2026-11-23T10:00:00.900Z' : null,
        expires_at: new Date(Date.now() + 60_000).toISOString(),
        duration_ms: done ? 800 : null,
        result_size: done ? 420 : null,
        truncated: done ? false : null,
      });
    });
    stub.on(`GET /api/v1/commands/${commandId}/result`, () =>
      envelope({
        command_id: commandId,
        instance_id: 1,
        type: 'thread_stack',
        format: 'json',
        content_type: 'application/json',
        size_bytes: 420,
        truncated: false,
        text: null,
        deadlocks: [],
        threads: [
          {
            id: 41,
            name: 'http-nio-8080-exec-101',
            state: 'RUNNABLE',
            virtual: false,
            locks_held: [],
            waiting_on: null,
            frames: [
              'java.net.SocketInputStream.read(SocketInputStream.java:141)',
              'com.shop.OrderDao.find(OrderDao.java:42)',
            ],
            tx_id: '101',
          },
        ],
      }),
    );
    await openActive('', 'en', ['operator']);
    fireEvent.click(screen.getByTestId('active-cell-elapsed-101'));
    const stack = await screen.findByTestId('active-action-stack', {}, FIND);
    expect(stack).not.toHaveAttribute('aria-disabled');
    fireEvent.click(stack);
    const modal = await screen.findByTestId('thread-stack-modal', {}, FIND);
    const shown = await within(modal).findByTestId('thread-stack', {}, { timeout: 8000 });
    expect(stub.calls('POST /api/v1/instances/1/commands')[0]?.body).toEqual({
      type: 'thread_stack',
      args: { tx_id: '101' },
    });
    expect(polls).toBeGreaterThanOrEqual(2);
    expect(shown).toHaveAttribute('data-tx-match', 'match');
    expect(shown).toHaveAttribute('data-state', 'RUNNABLE');
    expect(within(modal).getByTestId('thread-stack-name')).toHaveTextContent(
      'http-nio-8080-exec-101',
    );
    expect(within(modal).getByTestId('thread-stack-tx-link')).toHaveAttribute(
      'href',
      '/apps/1/transactions/101',
    );
    expect(modal).toHaveTextContent('com.shop.OrderDao.find(OrderDao.java:42)');
    fireEvent.click(within(modal).getByTestId('thread-stack-close'));
    expect(screen.queryByTestId('thread-stack-modal')).not.toBeInTheDocument();
  }, 20_000);

  it('an operator dump requests thread_dump and opens the dumps tab with the command; errors are told', async () => {
    answer();
    const commandId = '7a2b3c4d-5e6f-4a7b-9c8d-1e2f3a4b5c6d';
    let reply: () => Response = () => errorBody(409, 'instance_offline');
    stub.on('POST /api/v1/instances/1/commands', () => reply());
    const { app } = await openActive('env=prod', 'en', ['operator']);
    fireEvent.click(screen.getByTestId('active-cell-elapsed-101'));
    fireEvent.click(await screen.findByTestId('active-action-dump', {}, FIND));
    const feedback = await screen.findByTestId('active-dump-feedback', {}, FIND);
    await waitFor(() => {
      expect(feedback).toHaveAttribute('data-state', 'request-error');
    }, FIND);
    expect(app.url()).toContain('/apps/1/active');
    reply = () => envelope({ command_id: commandId, status: 'queued', ttl_s: 60 }, {}, 202);
    fireEvent.click(screen.getByTestId('active-action-dump'));
    await waitFor(() => {
      expect(app.url()).toContain('/apps/1/instances/1?');
    }, FIND);
    const query = new URLSearchParams(app.url().split('?')[1]);
    expect(query.get('tab')).toBe('dumps');
    expect(query.get('cmd')).toBe(commandId);
    expect(query.get('env')).toBe('prod');
    expect(stub.calls('POST /api/v1/instances/1/commands').at(-1)?.body).toEqual({
      type: 'thread_dump',
    });
  });

  it('instance names in the bars and rows link to the instance detail with the carried keys', async () => {
    answer();
    await openActive('env=prod');
    const detail = screen.getByTestId('active-bar-detail-1');
    expect(detail.getAttribute('href')).toBe('/apps/1/instances/1?env=prod');
    const cell = screen.getByTestId('active-instance-link-201');
    expect(cell.getAttribute('href')).toBe('/apps/1/instances/2?env=prod');
  });

  it('a BATCH row links with kind=BATCH; a row that finishes stays selected and says so', async () => {
    answer();
    await openActive('kind=ALL');
    const { socket, id } = await subscribed();
    fireEvent.click(screen.getByTestId('active-cell-elapsed-104'));
    const recent = await screen.findByTestId('active-action-recent', {}, FIND);
    expect(new URLSearchParams((recent.getAttribute('href') ?? '').split('?')[1]).get('kind')).toBe(
      'BATCH',
    );
    push(
      socket,
      id,
      '2026-11-23T10:00:08.000Z',
      activeSnapshot([activeInstance(1, 'order-1')], [activeItem(1, '501', 1_000)]),
    );
    await waitFor(() => {
      expect(screen.getByTestId('active-row-actions')).toHaveAttribute('data-running', 'false');
    }, FIND);
    expect(screen.getByTestId('active-row-finished')).toBeInTheDocument();
  });

  it('the alert rule link opens the rules of the active_transactions metric for an operator', async () => {
    answer();
    await openActive('', 'en', ['operator']);
    const link = screen.getByTestId('active-rule-link');
    const href = link.getAttribute('href') ?? '';
    expect(href.startsWith('/alerts/rules?')).toBe(true);
    const query = new URLSearchParams(href.split('?')[1]);
    expect(query.get('f.metric')).toBe('active_transactions');
    expect(query.get('f.app_id')).toBe('1');
  });

  it('a viewer sees the rule link disabled with the reason', async () => {
    answer();
    await openActive();
    const link = screen.getByTestId('active-rule-link');
    expect(link.tagName).toBe('SPAN');
    expect(link).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('active: collected strings (T-UI-005, SEC-61)', () => {
  it('T-UI-005 renders XSS payloads in instance, service, thread, SQL, host, DB target and user as text', async () => {
    const instances = XSS_PAYLOADS.map((payload, index) => activeInstance(index + 1, payload));
    const states = ['SQL', 'HTTP', 'DBC'] as const;
    const items = XSS_PAYLOADS.map((payload, index) =>
      activeItem(index + 1, String(1000 + index), 1_000 * (index + 1), {
        service: untrusted(payload),
        thread: untrusted(payload),
        state: states[index % 3],
        state_detail: untrusted(payload),
        user_id: untrusted(payload),
      }),
    );
    answer(activeSnapshot(instances, items));
    const { page } = await openActive();
    for (const [index, payload] of XSS_PAYLOADS.entries()) {
      const tx = String(1000 + index);
      expect(screen.getByTestId(`active-cell-service-${tx}`).textContent).toBe(payload);
      expect(screen.getByTestId(`active-cell-instance-${tx}`).textContent).toBe(payload);
      expect(screen.getByTestId(`active-cell-user-${tx}`).textContent).toBe(payload);
      expect(screen.getByTestId(`active-cell-state-detail-${tx}`).textContent).toBe(payload);
    }
    fireEvent.click(screen.getByTestId('active-cell-elapsed-1000'));
    await screen.findByTestId('active-row-actions', {}, FIND);
    expect(findInjectedMarkup(page)).toEqual([]);
    // Chip names of the filters (collected names) are text too.
    fireEvent.click(screen.getByTestId('active-bar-button-1'));
    await waitFor(() => {
      expect(screen.getByTestId('filter-chip-bar').textContent).toContain(XSS_PAYLOADS[0]);
    }, FIND);
    expect(findInjectedMarkup(screen.getByTestId('filter-chip-bar'))).toEqual([]);
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('active: empty, error and language (T-UI-006)', () => {
  it('says that no instance is connected', async () => {
    answer(activeSnapshot([], [], null));
    const { page } = await openActive();
    expect(page).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('active-empty')).toHaveTextContent('No instance is connected.');
    expect(screen.getByTestId('active-asof')).toHaveTextContent('As of');
  });

  it('says that nothing is running while instances are connected', async () => {
    answer(activeSnapshot([activeInstance(1, 'order-1')], []));
    const { page } = await openActive();
    expect(page).toHaveAttribute('data-state', 'ready');
    expect(screen.getByTestId('active-empty')).toHaveTextContent('Nothing is running now.');
    expect(screen.getByTestId('active-bar-1')).toHaveAttribute('data-count', '0');
    expect(screen.getByTestId('active-summary-max')).toHaveAttribute('data-value', '0');
  });

  it('shows the error with its request id and retries', async () => {
    stub.on(ACTIVE, () => errorBody(503, 'storage_unavailable', { requestId: 'req-active-503' }));
    const { page } = await openActive();
    expect(page).toHaveAttribute('data-state', 'error');
    expect(screen.getByTestId('active-error')).toHaveTextContent('req-active-503');
    answer();
    fireEvent.click(within(screen.getByTestId('active-error')).getByTestId('error-state-retry'));
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'ready');
    }, FIND);
  });

  it('T-UI-006 renders from the ko catalog', async () => {
    answer();
    const { page } = await openActive('kind=ALL', 'ko');
    expect(page.textContent).not.toContain('active:');
    expect(screen.getByTestId('active-cell-state-101')).toHaveTextContent('SQL 실행 중');
    expect(screen.getByTestId('active-cell-user-201')).toHaveTextContent('수집 안 함');
    expect(screen.getByTestId('active-asof')).toHaveTextContent('초 전 기준');
    expect(SNAPSHOT_AT).toBe('2026-11-23T10:00:00.000Z');
  });
});
