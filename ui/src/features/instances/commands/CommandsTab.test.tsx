// SPDX-License-Identifier: Apache-2.0
//
// Commands tab (05 §2.7-4, docs/16 §5, WO-37): buttons by role (viewer none, operator thread dump,
// single stack and self diagnosis, administrator also reload and environment dump; SEC-21), the
// confirmation that describes the impact, the request followed by 1 s polling to the end state,
// end reasons (ttl …) and request errors in words (instance offline, another command running,
// rate limited with Retry-After, no master key), the history (requester name, duration, size,
// status, truncation, results of others closed, SEC-50), the audit link for administrators, and
// collected strings (user names, results) as text (SEC-60).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'SEC-21' }, { type: 'req', description: 'SEC-50' }, { type: 'req', description: 'SEC-61' }
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import enCommands from '../../../locales/en/commands.json';
import { resetWrittenUrls } from '../../../state/useUrlState';

import { CommandsTab } from './CommandsTab';
import { commandView, instance, ME, OTHER, appTree, signIn, textResult } from './testing/fixtures';

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/instances/7/commands';
const POST = 'POST /api/v1/instances/7/commands';

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
  Reflect.deleteProperty(window, '__xss');
});

function open(role: 'viewer' | 'operator' | 'admin', list: unknown[] = []) {
  signIn(role);
  stub.on(LIST, () => envelope(list));
  return renderAt(<CommandsTab appId={3} instance={instance()} />);
}

function buttons(): string[] {
  return [...document.querySelectorAll<HTMLElement>('[data-testid^="command-run-"]')].map(
    (b) => b.getAttribute('data-testid') ?? '',
  );
}

describe('commands tab: buttons by role (05 §2.7-4, SEC-21)', () => {
  it('a viewer gets no button, no history call, and the reason', async () => {
    open('viewer');
    expect(await screen.findByTestId('command-buttons-forbidden')).toHaveTextContent(
      enCommands.tab.viewerNotice,
    );
    expect(buttons()).toEqual([]);
    expect(screen.getByTestId('command-history-forbidden')).toBeInTheDocument();
    expect(stub.calls(LIST)).toHaveLength(0);
    expect(screen.queryByTestId('command-audit-link')).not.toBeInTheDocument();
  });

  it('an operator gets thread dump, single stack and self diagnosis', async () => {
    open('operator');
    await screen.findByTestId('command-run-thread_dump');
    expect(buttons()).toEqual([
      'command-run-thread_dump',
      'command-run-thread_stack',
      'command-run-self_diagnosis',
    ]);
    expect(screen.queryByTestId('command-audit-link')).not.toBeInTheDocument();
  });

  it('an administrator gets all five and the audit link filtered to the instance', async () => {
    open('admin');
    await screen.findByTestId('command-run-env_dump');
    expect(buttons()).toEqual([
      'command-run-thread_dump',
      'command-run-thread_stack',
      'command-run-self_diagnosis',
      'command-run-reload_config',
      'command-run-env_dump',
    ]);
    expect(screen.getByTestId('command-audit-link').getAttribute('href')).toBe(
      '/admin/audit?f.instance=7',
    );
  });
});

describe('commands tab: run, poll and errors', () => {
  it('confirms with the impact, requests, polls every second until done and refreshes the history', async () => {
    const id = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000aa';
    let polls = 0;
    stub.on(POST, () => envelope({ command_id: id, status: 'queued', ttl_s: 60 }, {}, 202));
    stub.on(`GET /api/v1/commands/${id}`, () => {
      polls += 1;
      return envelope(
        commandView({
          command_id: id,
          type: 'self_diagnosis',
          status: polls === 1 ? 'queued' : polls === 2 ? 'sent' : 'done',
          finished_at: polls >= 3 ? '2026-11-24T10:00:02.000Z' : null,
        }),
      );
    });
    open('operator');
    fireEvent.click(await screen.findByTestId('command-run-self_diagnosis'));
    const dialog = screen.getByTestId('command-dialog-self_diagnosis');
    expect(within(dialog).getByTestId('command-dialog-impact')).toHaveTextContent(
      enCommands.impact.self_diagnosis,
    );
    // Cancel sends nothing.
    fireEvent.click(within(dialog).getByTestId('command-dialog-cancel'));
    expect(stub.calls(POST)).toHaveLength(0);
    fireEvent.click(screen.getByTestId('command-run-self_diagnosis'));
    fireEvent.click(screen.getByTestId('command-dialog-confirm'));
    const feedback = await screen.findByTestId('command-feedback', {}, FIND);
    expect(stub.calls(POST)[0]?.body).toEqual({ type: 'self_diagnosis' });
    await waitFor(
      () => {
        expect(feedback).toHaveAttribute('data-state', 'done');
      },
      { timeout: 8000 },
    );
    expect(polls).toBeGreaterThanOrEqual(3);
    expect(feedback).toHaveTextContent(enCommands.status.done);
    // The end refreshed the history.
    await waitFor(() => {
      expect(stub.calls(LIST).length).toBeGreaterThanOrEqual(2);
    }, FIND);
    expect(screen.getByTestId('command-run-open-result')).toBeInTheDocument();
  }, 20_000);

  it('asks thread_stack for digits and sends tx_id or thread_id', async () => {
    const id = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000bb';
    stub.on(POST, () => envelope({ command_id: id, status: 'queued', ttl_s: 60 }, {}, 202));
    stub.on(`GET /api/v1/commands/${id}`, () =>
      envelope(commandView({ command_id: id, type: 'thread_stack', status: 'sent' })),
    );
    open('operator');
    fireEvent.click(await screen.findByTestId('command-run-thread_stack'));
    const confirm = screen.getByTestId('command-dialog-confirm');
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByTestId('command-dialog-arg'), { target: { value: '12a' } });
    expect(confirm).toBeDisabled();
    fireEvent.click(screen.getByTestId('command-dialog-kind-thread_id'));
    fireEvent.change(screen.getByTestId('command-dialog-arg'), { target: { value: '42' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => {
      expect(stub.calls(POST)[0]?.body).toEqual({
        type: 'thread_stack',
        args: { thread_id: '42' },
      });
    }, FIND);
  });

  it('says why a request failed: offline, busy, rate limited with Retry-After, no master key', async () => {
    const cases: [Response, string][] = [
      [errorBody(409, 'instance_offline'), enCommands.error.instance_offline],
      [errorBody(409, 'invalid_state'), enCommands.error.busy],
      [errorBody(429, 'rate_limited', { headers: { 'Retry-After': '42' } }), 'Try again in 42 s.'],
      [errorBody(409, 'secret_unavailable'), enCommands.error.secret_unavailable],
    ];
    open('operator');
    for (const [response, text] of cases) {
      stub.on(POST, () => response.clone());
      fireEvent.click(await screen.findByTestId('command-run-thread_dump'));
      fireEvent.click(screen.getByTestId('command-dialog-confirm'));
      await waitFor(() => {
        const feedback = screen.getByTestId('command-feedback');
        expect(feedback).toHaveAttribute('data-state', 'request-error');
        expect(feedback).toHaveTextContent(text);
      }, FIND);
    }
  });

  it('shows the end reason of a failed or expired command (TTL expiry)', async () => {
    const id = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000cc';
    stub.on(POST, () => envelope({ command_id: id, status: 'queued', ttl_s: 60 }, {}, 202));
    stub.on(`GET /api/v1/commands/${id}`, () =>
      envelope(commandView({ command_id: id, status: 'expired', error: 'ttl', finished_at: null })),
    );
    open('operator');
    fireEvent.click(await screen.findByTestId('command-run-thread_dump'));
    fireEvent.click(screen.getByTestId('command-dialog-confirm'));
    await waitFor(() => {
      const feedback = screen.getByTestId('command-feedback');
      expect(feedback).toHaveAttribute('data-state', 'expired');
      expect(feedback).toHaveAttribute('data-reason', 'ttl');
      expect(feedback).toHaveTextContent(`${enCommands.status.expired}: ${enCommands.reason.ttl}`);
    }, FIND);
  });
});

describe('commands tab: history and results (SEC-50, SEC-60)', () => {
  it("lists requester, duration, size, status with reason and truncation; others' results are closed", async () => {
    const mine = commandView({ type: 'thread_dump', requested_by_name: 'alice', truncated: true });
    const theirs = commandView({
      type: 'self_diagnosis',
      requested_by: OTHER,
      requested_by_name: 'bob',
    });
    const failed = commandView({
      type: 'thread_stack',
      status: 'failed',
      error: 'cooldown',
      duration_ms: null,
      result_size: null,
    });
    open('operator', [mine, theirs, failed]);
    const row = await screen.findByTestId(`command-row-${mine.command_id}`, {}, FIND);
    expect(screen.getByTestId(`command-requester-${mine.command_id}`)).toHaveTextContent('alice');
    expect(within(row).getByText(enCommands.history.truncated)).toBeInTheDocument();
    expect(screen.getByTestId(`command-open-${mine.command_id}`).getAttribute('href')).toBe(
      `/apps/3/instances/7?tab=dumps&cmd=${mine.command_id}`,
    );
    expect(screen.getByTestId(`command-unreadable-${theirs.command_id}`)).toHaveTextContent(
      enCommands.history.notYours,
    );
    expect(screen.getByTestId(`command-status-${failed.command_id}`)).toHaveTextContent(
      enCommands.reason.cooldown,
    );
  });

  it("an administrator opens anyone's self diagnosis below the history", async () => {
    const theirs = commandView({ type: 'self_diagnosis', requested_by: OTHER });
    stub.on(`GET /api/v1/commands/${theirs.command_id}/result`, () =>
      envelope(
        textResult(
          'self_diagnosis',
          JSON.stringify({ format: 1, premain_ms: 812.5, modules: [] }),
          theirs.command_id,
        ),
      ),
    );
    open('admin', [theirs]);
    fireEvent.click(await screen.findByTestId(`command-open-${theirs.command_id}`, {}, FIND));
    const panel = await screen.findByTestId('command-result', {}, FIND);
    await within(panel).findByTestId('self-diagnosis-report', {}, FIND);
    expect(panel).toHaveTextContent('812.5');
    expect(ME).not.toBe(OTHER);
  });

  it('T-UI-005 renders XSS seeds in user names and results as text', async () => {
    const rows = XSS_PAYLOADS.map((payload) =>
      commandView({ type: 'reload_config', requested_by_name: payload }),
    );
    const first = rows[0];
    if (first === undefined) {
      throw new Error('no payload');
    }
    stub.on(`GET /api/v1/commands/${first.command_id}/result`, () =>
      envelope(textResult('reload_config', XSS_PAYLOADS.join('\n'), first.command_id)),
    );
    const { container } = open('admin', rows);
    await screen.findByTestId(`command-row-${first.command_id}`, {}, FIND);
    fireEvent.click(screen.getByTestId(`command-open-${first.command_id}`));
    await screen.findByTestId('command-result', {}, FIND);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
