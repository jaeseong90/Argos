// SPDX-License-Identifier: Apache-2.0
//
// Thread dumps tab and viewer (05 §2.7-5, docs/15 §9.8, WO-37): operators only (viewers get the
// reason), the last 20 done dumps with other users' dumps closed (SEC-50), the newest readable dump
// shown by default and a deep link `cmd` followed until done, state groups and counts equal to the
// result, ×N for identical stacks, deadlock and virtual-thread badges, search written to `q`, the
// transaction link, truncation and depth-cut notices, the .txt download equal to the format=text
// result with a file name from the instance id and time only, collected strings as text (SEC-60),
// and a 5,000-thread dump rendered collapsed (time as a reference).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'SEC-50' }, { type: 'req', description: 'SEC-61' }
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../../test/apiStub';
import { untrusted } from '../../../api/untrusted';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import enCommands from '../../../locales/en/commands.json';
import { resetWrittenUrls } from '../../../state/useUrlState';
import {
  commandView,
  dumpResult,
  frames,
  instance,
  OTHER,
  appTree,
  signIn,
  thread,
} from '../commands/testing/fixtures';

import { MAX_DUMP_FRAMES } from './dumpModel';
import { DumpsTab, recentDumps } from './DumpsTab';

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/instances/7/commands';

let stub: ApiStub;

/** Renders `ui` at the instance detail route (fixtures appTree). */
function renderAt(ui: ReactNode, path?: string) {
  const tree = appTree(ui, path);
  return { ...render(tree.element), url: tree.url };
}

beforeEach(() => {
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

const LOCK_A = untrusted('<0x00000000000000a1> (a java.lang.Object)');
const LOCK_B = untrusted('<0x00000000000000b2> (a java.lang.Object)');
const POOL = frames(
  'jdk.internal.misc.Unsafe.park(Native Method)',
  'org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)',
);

function richDump(id: string, truncated = false) {
  return dumpResult(
    [
      ...Array.from({ length: 9 }, (_, i) =>
        thread({
          id: 100 + i,
          name: `http-nio-exec-${String(i)}`,
          state: untrusted('WAITING (parking)'),
          frames: POOL,
        }),
      ),
      thread({
        id: 1,
        name: 'dl-left',
        state: untrusted('BLOCKED'),
        locks_held: [LOCK_A],
        waiting_on: LOCK_B,
        frames: frames('com.shop.A.left(A.java:1)'),
      }),
      thread({
        id: 2,
        name: 'dl-right',
        state: untrusted('BLOCKED'),
        locks_held: [LOCK_B],
        waiting_on: LOCK_A,
        frames: frames('com.shop.A.right(A.java:2)'),
      }),
      thread({
        id: 3,
        name: 'vt-order',
        virtual: true,
        tx_id: '7312000000000042',
        frames: frames('com.shop.OrderService.place(OrderService.java:88)'),
      }),
      thread({
        id: 4,
        name: 'vt-idle',
        virtual: true,
        state: untrusted('TIMED_WAITING'),
        frames: frames('java.lang.VirtualThread.sleepNanos(VirtualThread.java:1)'),
      }),
      thread({
        id: 5,
        name: 'deep',
        frames: Array.from({ length: MAX_DUMP_FRAMES }, (_, i) =>
          untrusted(`com.deep.F.f${String(i)}(F.java:1)`),
        ),
      }),
    ],
    { command_id: id, truncated, deadlocks: [{ thread_ids: [1, 2] }] },
  );
}

function open(role: 'viewer' | 'operator' | 'admin', list: unknown[], path?: string) {
  signIn(role);
  stub.on(LIST, () => envelope(list));
  return renderAt(<DumpsTab appId={3} instance={instance()} />, path);
}

describe('dumps tab: access and list', () => {
  it('viewers get the reason and no call', async () => {
    open('viewer', []);
    expect(await screen.findByTestId('dumps-forbidden')).toHaveTextContent(
      enCommands.dumps.operatorOnly,
    );
    expect(stub.calls(LIST)).toHaveLength(0);
  });

  it("keeps the last 20 done dumps, closes other users' dumps and shows the newest readable one", async () => {
    const theirs = commandView({ requested_by: OTHER, requested_at: '2026-11-24T11:00:00.000Z' });
    const mine = Array.from({ length: 24 }, (_, i) =>
      commandView({ requested_at: `2026-11-24T10:${String(59 - i).padStart(2, '0')}:00.000Z` }),
    );
    const others = [
      commandView({ type: 'self_diagnosis' }),
      commandView({ status: 'failed', error: 'agent_failed' }),
    ];
    const list = [theirs, ...others, ...mine];
    expect(recentDumps(list)).toHaveLength(20);
    const newest = mine[0];
    if (newest === undefined) {
      throw new Error('fixture');
    }
    stub.on(`GET /api/v1/commands/${newest.command_id}`, () => envelope(newest));
    stub.on(`GET /api/v1/commands/${newest.command_id}/result`, () =>
      envelope(richDump(newest.command_id)),
    );
    open('operator', list);
    const items = await screen.findByTestId('dumps-list', {}, FIND);
    expect(items).toHaveAttribute('data-count', '20');
    const closed = screen.getByTestId(`dumps-item-${theirs.command_id}`);
    expect(closed).toHaveAttribute('aria-disabled', 'true');
    expect(closed).toHaveTextContent(enCommands.dumps.notYours);
    const viewer = await screen.findByTestId('dump-viewer', {}, FIND);
    expect(viewer).toHaveAttribute('data-command-id', newest.command_id);
    expect(screen.getByTestId(`dumps-item-${newest.command_id}`)).toHaveAttribute(
      'aria-current',
      'true',
    );
    // 'Compare two dumps' is a later version: the slot is there, it does nothing.
    expect(screen.getByTestId('dumps-compare')).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('dump viewer (05 §2.7-5)', () => {
  it('shows counts by state = result, ×N, deadlock, VT, tx link, depth cut, and searches into q', async () => {
    const dump = commandView();
    stub.on(`GET /api/v1/commands/${dump.command_id}`, () => envelope(dump));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, () =>
      envelope(richDump(dump.command_id)),
    );
    const { url } = open(
      'operator',
      [dump],
      `/apps/3/instances/7?tab=dumps&cmd=${dump.command_id}`,
    );
    const viewer = await screen.findByTestId('dump-viewer', {}, FIND);
    expect(viewer).toHaveAttribute('data-total', '14');
    expect(screen.getByTestId('dump-count-WAITING')).toHaveAttribute('data-count', '9');
    expect(screen.getByTestId('dump-count-BLOCKED')).toHaveAttribute('data-count', '2');
    expect(screen.getByTestId('dump-count-RUNNABLE')).toHaveAttribute('data-count', '2');
    expect(screen.getByTestId('dump-count-TIMED_WAITING')).toHaveAttribute('data-count', '1');
    expect(screen.getByTestId('dump-deadlock-badge')).toBeInTheDocument();
    expect(screen.getByTestId('dump-deadlocks')).toHaveTextContent('dl-left');
    expect(screen.getByTestId('dump-virtual-count')).toHaveTextContent('2 virtual threads');
    const waiting = screen.getByTestId('dump-section-WAITING');
    expect(within(waiting).getByTestId('dump-stack-count')).toHaveTextContent('×9');
    expect(screen.getAllByTestId('dump-stack-deadlock')).toHaveLength(2);
    expect(screen.getByTestId('dump-tx-link').getAttribute('href')).toBe(
      '/apps/3/transactions/7312000000000042',
    );
    expect(screen.getByTestId('dump-truncated')).toHaveTextContent('64 frames');
    const locks = screen.getAllByTestId('dump-lock-row');
    expect(locks.map((row) => row.getAttribute('data-lock')).sort()).toEqual([
      '0x00000000000000a1',
      '0x00000000000000b2',
    ]);

    fireEvent.change(screen.getByTestId('dump-search'), { target: { value: 'OrderService' } });
    await waitFor(() => {
      expect(new URLSearchParams(url().split('?')[1]).get('q')).toBe('OrderService');
    }, FIND);
    // The router renders the written URL in a transition: wait for the filtered view.
    expect(await screen.findByTestId('dump-search-count', {}, FIND)).toHaveAttribute(
      'data-count',
      '1',
    );
    expect(screen.queryByTestId('dump-section-WAITING')).not.toBeInTheDocument();
  });

  it('says a truncated dump is cut and offers the full file; the download equals format=text', async () => {
    const dump = commandView({ truncated: true, finished_at: '2026-11-24T10:15:00.123Z' });
    const raw = '"main" #1 prio=5\n   java.lang.Thread.State: RUNNABLE\n\tat x.Y.z(Y.java:1)\n';
    stub.on(`GET /api/v1/commands/${dump.command_id}`, () => envelope(dump));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, (request) =>
      request.query.get('format') === 'text'
        ? envelope({
            ...richDump(dump.command_id, true),
            format: 'text',
            threads: null,
            deadlocks: null,
            text: raw,
          })
        : envelope(richDump(dump.command_id, true)),
    );
    const blobs: Blob[] = [];
    const createObjectURL = vi.fn((blob: Blob) => {
      blobs.push(blob);
      return 'blob:test';
    });
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const clicked: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this.download);
    });
    open('operator', [dump]);
    await screen.findByTestId('dump-viewer', {}, FIND);
    expect(screen.getByTestId('dump-truncated')).toHaveTextContent(enCommands.viewer.truncated);
    fireEvent.click(screen.getByTestId('dump-download'));
    await waitFor(() => {
      expect(blobs).toHaveLength(1);
    }, FIND);
    expect(await blobs[0]?.text()).toBe(raw);
    expect(blobs[0]?.type).toMatch(/^text\/plain/);
    expect(clicked).toEqual(['argos-thread-dump-7-20261124T101500Z.txt']);
    const textCalls = stub
      .calls(`GET /api/v1/commands/${dump.command_id}/result`)
      .filter((c) => c.query.get('format') === 'text');
    expect(textCalls).toHaveLength(1);
  });

  it('follows a just-requested dump (deep link from the active screen) until it is done', async () => {
    const id = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000dd';
    let polls = 0;
    stub.on(`GET /api/v1/commands/${id}`, () => {
      polls += 1;
      return envelope(commandView({ command_id: id, status: polls < 2 ? 'sent' : 'done' }));
    });
    stub.on(`GET /api/v1/commands/${id}/result`, () => envelope(richDump(id)));
    open('operator', [], `/apps/3/instances/7?tab=dumps&cmd=${id}`);
    const pending = await screen.findByTestId('dump-selected-feedback', {}, FIND);
    expect(pending).toHaveAttribute('data-state', 'sent');
    await screen.findByTestId('dump-viewer', {}, { timeout: 8000 });
  }, 15_000);

  it('T-UI-005 renders XSS seeds in thread names, frames and locks as text', async () => {
    const dump = commandView();
    const threads = XSS_PAYLOADS.map((payload, i) =>
      thread({
        id: i,
        name: payload,
        frames: [untrusted(payload)],
        locks_held: [untrusted(payload)],
        waiting_on: untrusted(payload),
      }),
    );
    stub.on(`GET /api/v1/commands/${dump.command_id}`, () => envelope(dump));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, () =>
      envelope(dumpResult(threads, { command_id: dump.command_id })),
    );
    const { container } = open('operator', [dump]);
    await screen.findByTestId('dump-viewer', {}, FIND);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('renders a 5,000-thread dump with collapsed groups (reference time)', async () => {
    const dump = commandView();
    const threads = Array.from({ length: 5000 }, (_, i) =>
      thread({
        id: i,
        name: `worker-${String(i)}`,
        state: untrusted(i % 2 === 0 ? 'RUNNABLE' : 'WAITING'),
        frames: frames(`com.app.Job.run${String(i % 300)}(Job.java:1)`),
      }),
    );
    stub.on(`GET /api/v1/commands/${dump.command_id}`, () => envelope(dump));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, () =>
      envelope(dumpResult(threads, { command_id: dump.command_id })),
    );
    const started = performance.now();
    open('operator', [dump]);
    const viewer = await screen.findByTestId('dump-viewer', {}, { timeout: 15_000 });
    const elapsed = performance.now() - started;
    expect(viewer).toHaveAttribute('data-total', '5000');
    expect(screen.getByTestId('dump-section-RUNNABLE')).toHaveAttribute('data-open', 'false');
    fireEvent.click(screen.getByTestId('dump-section-toggle-RUNNABLE'));
    // 150 distinct stacks: the first 100 render, the rest behind "Show more".
    expect(
      within(screen.getByTestId('dump-section-RUNNABLE')).getAllByTestId('dump-stack'),
    ).toHaveLength(100);
    expect(screen.getByTestId('dump-section-more-RUNNABLE')).toBeInTheDocument();
    console.info(
      `[wo37-dump-render] 5000 threads rendered in ${elapsed.toFixed(0)} ms (reference, shared machine)`,
    );
  }, 30_000);
});
