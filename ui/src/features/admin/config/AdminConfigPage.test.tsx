// SPDX-License-Identifier: Apache-2.0
//
// Configuration changes `/admin/config` in the whole app (router, URL state, transport, generated
// client; only the HTTP boundary is stubbed), docs/15 §9.10, 05 §2.11-5/-6, T-ACPT-18 automated
// part: the current configuration with locked keys ("local yml only") and drift, the preview that
// needs a reason, the diff dialog = the server's preview (rejected keys per instance), apply →
// results by instance with state labels and polling that stops once the change is final, the
// history, revert with one confirmation, the refusal that names the key (422
// remote_key_not_allowed), the kill switch that sends nothing before its confirmation, collected
// strings as text (T-UI-005), '—' for missing values (T-UI-007) and Korean labels (T-UI-008).
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../../test/renderApp';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { resetWrittenUrls } from '../../../state/useUrlState';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-S29' };
export const REQ_REMOTE = { type: 'req', description: 'SEC-23' };
export const REQ_XSS = { type: 'req', description: 'SEC-60' };

const FIND = { timeout: 5000 };
const CHANGE = '0a1b2c3d-4e5f-4a6b-8c7d-000000000001';
const REVERT = '0a1b2c3d-4e5f-4a6b-8c7d-000000000002';
const KILL = '0a1b2c3d-4e5f-4a6b-8c7d-000000000003';
const SLOW = 'trace.slow-threshold-ms';
const QS = 'capture.query-string';

let stub: ApiStub;

function appConfig(names: [string, string] = ['order-1', 'order-2']) {
  return {
    remote_allowed_keys: [SLOW, 'enabled'],
    remote_allowed_by_instance: [
      { instance_id: 11, name: names[0], online: true, patterns: ['trace.**', 'enabled'] },
      { instance_id: 12, name: names[1], online: true, patterns: ['trace.**', 'enabled'] },
    ],
    current: { [SLOW]: '1000' } as Record<string, string>,
    drift: [
      { instance_id: 12, key: SLOW, value: '1000', reason: 'rejected' },
      { instance_id: 12, key: null, value: 'hash-b', reason: 'config_hash' },
    ],
  };
}

const EFFECTIVE = [
  {
    key: SLOW,
    value: '1000',
    source: 'server',
    remote_allowed: true,
    server_value: '1000',
    server_status: 'applied',
  },
  {
    key: QS,
    value: null,
    source: null,
    remote_allowed: false,
    server_value: null,
    server_status: null,
  },
  {
    key: 'enabled',
    value: null,
    source: null,
    remote_allowed: true,
    server_value: null,
    server_status: null,
  },
];

function change(id: string, overrides: Record<string, unknown> = {}) {
  return {
    change_id: id,
    app_id: 1,
    kind: 'config',
    status: 'previewed',
    reason: 'raise the slow threshold',
    requested_at: '2026-11-24T10:00:00.000Z',
    requested_by: 'u-1',
    applied_at: null,
    applied_by: null,
    revert_of: null,
    targets: { all: true, instance_ids: [] },
    preview: {
      diff: [{ key: SLOW, before: '1000', after: '2000' }],
      rejected_keys_by_instance: [{ instance_id: 12, keys: [SLOW] }],
    },
    results: [],
    ...overrides,
  };
}

const RESULTS = [
  {
    instance_id: 11,
    status: 'applied',
    applied_keys: [SLOW],
    rejected_keys: [],
    rejected: [],
    applied_at: '2026-11-24T10:00:02.000Z',
    pushed_at: '2026-11-24T10:00:01.000Z',
    duration_ms: 120,
    effective_config_hash: 'h1',
    error: null,
  },
  {
    instance_id: 12,
    status: 'rejected',
    applied_keys: [],
    rejected_keys: [SLOW],
    rejected: [{ key: SLOW, by: 'server', code: 'not_allowed' }],
    applied_at: null,
    pushed_at: null,
    duration_ms: null,
    effective_config_hash: null,
    error: null,
  },
];

function answer(config = appConfig()) {
  stub.on('GET /api/v1/admin/apps/1/config', () => envelope(config));
  stub.on('GET /api/v1/instances/11/effective-config', () => envelope(EFFECTIVE));
  stub.on('GET /api/v1/admin/apps/1/config/changes', () =>
    envelope([change(CHANGE, { status: 'applied', results: RESULTS })], { next_cursor: null }),
  );
  stub.on('GET /api/v1/admin/users', () =>
    envelope([
      {
        id: 'u-1',
        username: 'alice',
        roles: ['admin'],
        permissions: [],
        enabled: true,
        must_change_password: false,
        locked_until: null,
        created_at: '2026-11-01T00:00:00.000Z',
        updated_at: '2026-11-01T00:00:00.000Z',
        kiosk_session: null,
      },
    ]),
  );
}

async function open(query = 'app=1', language: 'en' | 'ko' = 'en') {
  signedIn(stub, { roles: ['admin'] });
  const app = renderApp(`/admin/config${query === '' ? '' : `?${query}`}`, { language });
  const page = await screen.findByTestId('admin-config', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return { app, page };
}

function row(tableId: string, key: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="${tableId}"] [data-row-key="${key}"]`,
  );
  if (element === null) {
    throw new Error(`no row ${key} in ${tableId}`);
  }
  return element;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('admin config: current configuration (05 §2.11-5)', () => {
  it('only remotely allowed keys are editable; the others are locked with "local yml only"; drift is marked', async () => {
    answer();
    const { page } = await open();
    expect(page).toHaveAttribute('data-state', 'ready');
    await waitFor(() => {
      expect(row('admin-config-current', SLOW)).toHaveAttribute('data-editable', 'true');
    }, FIND);
    expect(screen.getByTestId(`admin-config-field-${SLOW}`)).toBeEnabled();
    const qs = row('admin-config-current', QS);
    expect(qs).toHaveAttribute('data-editable', 'false');
    expect(within(qs).getByTestId('admin-config-locked')).toHaveTextContent(
      'Change it in the local argos-agent.yml',
    );
    expect(screen.queryByTestId(`admin-config-field-${QS}`)).not.toBeInTheDocument();
    // The kill switch key is changed in its own section, never through the form.
    expect(
      within(row('admin-config-current', 'enabled')).getByTestId('admin-config-locked'),
    ).toHaveAttribute('data-lock', 'killSwitch');
    // T-UI-007: a value the server does not know is the dash, not empty.
    expect(qs.querySelector('[data-column="value"]')).toHaveTextContent('—');
    // Drift: instance 12 rejected the held value; its configuration hash differs.
    const drift = within(row('admin-config-current', SLOW)).getByTestId('admin-config-drift');
    expect(drift).toHaveAttribute('data-drift', 'true');
    expect(drift).toHaveTextContent('order-2 (rejected by the agent)');
    expect(screen.getByTestId('admin-config-hash-drift')).toHaveTextContent('order-2');
  });
});

describe('admin config: change, diff, apply, results, history, revert (T-ACPT-18)', () => {
  it('needs a reason; the diff dialog shows the preview; apply opens the results that stop polling when final', async () => {
    answer();
    let reads = 0;
    stub.on('POST /api/v1/admin/apps/1/config/changes', () => envelope(change(CHANGE), {}, 201));
    stub.on(`POST /api/v1/admin/config/changes/${CHANGE}/apply`, () =>
      envelope(
        change(CHANGE, {
          status: 'applying',
          results: [{ ...RESULTS[0], status: 'pending', applied_at: null }],
        }),
        {},
        202,
      ),
    );
    stub.on(`GET /api/v1/admin/config/changes/${CHANGE}`, () => {
      reads += 1;
      return envelope(
        reads === 1
          ? change(CHANGE, {
              status: 'applying',
              results: [{ ...RESULTS[0], status: 'pending', applied_at: null }],
            })
          : change(CHANGE, {
              status: 'applied',
              applied_at: '2026-11-24T10:00:02.000Z',
              results: RESULTS,
            }),
      );
    });
    const { app } = await open();
    const field = await screen.findByTestId(`admin-config-field-${SLOW}`, {}, FIND);
    fireEvent.change(field, { target: { value: '2000' } });
    // No reason: no preview possible.
    expect(screen.getByTestId('admin-config-preview')).toBeDisabled();
    expect(screen.getByTestId('admin-config-problem')).toHaveAttribute('data-problem', 'required');
    fireEvent.change(screen.getByTestId('admin-config-reason'), {
      target: { value: 'raise the slow threshold' },
    });
    expect(screen.getByTestId('admin-config-preview')).toBeEnabled();
    fireEvent.click(screen.getByTestId('admin-config-preview'));
    const diff = await screen.findByTestId('admin-config-diff', {}, FIND);
    expect(stub.calls('POST /api/v1/admin/apps/1/config/changes')[0]?.body).toEqual({
      reason: 'raise the slow threshold',
      changes: { [SLOW]: '2000' },
      targets: { all: true },
    });
    // The rows are the server's preview, not the form.
    const rows = within(diff).getAllByTestId('admin-config-diff-row');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute('data-key', SLOW);
    expect(
      within(rows[0] as HTMLElement).getByTestId('admin-config-diff-before'),
    ).toHaveTextContent('1000');
    expect(within(rows[0] as HTMLElement).getByTestId('admin-config-diff-after')).toHaveTextContent(
      '2000',
    );
    expect(within(diff).getByTestId('admin-config-diff-rejected')).toHaveTextContent(
      `order-2: ${SLOW}`,
    );
    fireEvent.click(within(diff).getByTestId('admin-config-apply'));
    await waitFor(() => {
      expect(app.url()).toContain(`change=${CHANGE}`);
    }, FIND);
    expect(stub.calls(`POST /api/v1/admin/config/changes/${CHANGE}/apply`)).toHaveLength(1);
    // Results by instance; the change becomes final through polling.
    await waitFor(() => {
      expect(screen.getByTestId('admin-config-change')).toHaveAttribute('data-status', 'applied');
    }, FIND);
    expect(row('admin-config-results', '11')).toHaveAttribute('data-status', 'applied');
    expect(
      within(row('admin-config-results', '11')).getByTestId('admin-config-result-status'),
    ).toHaveTextContent('Applied');
    const rejected = row('admin-config-results', '12');
    expect(within(rejected).getByTestId('admin-config-result-status')).toHaveTextContent(
      'Rejected',
    );
    expect(rejected).toHaveTextContent(SLOW);
    // Polling stopped: no further reads once final.
    const readsWhenFinal = reads;
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1_500));
    });
    expect(reads).toBe(readsWhenFinal);
    expect(screen.queryByTestId('admin-config-polling')).not.toBeInTheDocument();
    // The form was cleared after apply.
    expect(screen.getByTestId('admin-config-reason')).toHaveValue('');
  });

  it('lists the history with the requester and reverts after exactly one confirmation with a reason', async () => {
    answer();
    stub.on(`GET /api/v1/admin/config/changes/${CHANGE}`, () =>
      envelope(change(CHANGE, { status: 'applied', results: RESULTS })),
    );
    stub.on(`GET /api/v1/admin/config/changes/${REVERT}`, () =>
      envelope(change(REVERT, { status: 'applied', revert_of: CHANGE, results: RESULTS })),
    );
    stub.on(`POST /api/v1/admin/config/changes/${CHANGE}/revert`, () =>
      envelope(change(REVERT, { status: 'applying', revert_of: CHANGE }), {}, 201),
    );
    const { app } = await open();
    await waitFor(() => {
      expect(row('admin-config-history', CHANGE)).toHaveTextContent('alice');
    }, FIND);
    fireEvent.click(
      within(row('admin-config-history', CHANGE)).getByTestId('admin-config-history-open'),
    );
    const revert = await screen.findByTestId('admin-config-revert', {}, FIND);
    fireEvent.click(revert);
    const dialog = await screen.findByTestId('admin-confirm', {}, FIND);
    expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('admin-config-revert-reason'), {
      target: { value: 'back to 1 s' },
    });
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(app.url()).toContain(`change=${REVERT}`);
    }, FIND);
    const calls = stub.calls(`POST /api/v1/admin/config/changes/${CHANGE}/revert`);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.body).toEqual({ reason: 'back to 1 s' });
    await waitFor(() => {
      expect(screen.getByTestId('admin-config-change')).toHaveAttribute('data-change-id', REVERT);
    }, FIND);
  });

  it('a 422 remote_key_not_allowed names the key', async () => {
    answer();
    stub.on('POST /api/v1/admin/apps/1/config/changes', () =>
      errorBody(422, 'remote_key_not_allowed', {
        details: [{ field: `changes.${SLOW}`, reason: 'not_allowed_by_targets' }],
      }),
    );
    await open();
    fireEvent.change(await screen.findByTestId(`admin-config-field-${SLOW}`, {}, FIND), {
      target: { value: '2000' },
    });
    fireEvent.change(screen.getByTestId('admin-config-reason'), { target: { value: 'r' } });
    fireEvent.click(screen.getByTestId('admin-config-preview'));
    const error = await screen.findByTestId('admin-config-error', {}, FIND);
    expect(error).toHaveAttribute('data-kind', 'keyNotAllowed');
    expect(error).toHaveTextContent(`No target instance allows ${SLOW} to be changed remotely`);
    expect(screen.queryByTestId('admin-config-diff')).not.toBeInTheDocument();
  });
});

describe('admin config: kill switch (05 §2.11-6)', () => {
  it('sends nothing without the confirmation; confirmed with a reason it turns on and shows the result', async () => {
    answer();
    stub.on('POST /api/v1/admin/apps/1/kill-switch', () =>
      envelope(change(KILL, { kind: 'kill_switch', status: 'applying' }), {}, 202),
    );
    stub.on(`GET /api/v1/admin/config/changes/${KILL}`, () =>
      envelope(change(KILL, { kind: 'kill_switch', status: 'applied', results: RESULTS })),
    );
    await open();
    const section = await screen.findByTestId('admin-kill-switch', {}, FIND);
    const toggle = within(section).getByTestId('admin-kill-switch-toggle');
    expect(toggle).toHaveAttribute('data-action', 'on');
    fireEvent.click(toggle);
    let dialog = await screen.findByTestId('admin-confirm', {}, FIND);
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      'the application keeps running',
    );
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent('WARN');
    fireEvent.click(within(dialog).getByTestId('admin-confirm-cancel'));
    await waitFor(() => {
      expect(screen.queryByTestId('admin-confirm')).not.toBeInTheDocument();
    }, FIND);
    expect(stub.calls('POST /api/v1/admin/apps/1/kill-switch')).toHaveLength(0);

    // One instance, with a reason.
    fireEvent.change(within(section).getByTestId('admin-kill-switch-scope'), {
      target: { value: '11' },
    });
    fireEvent.click(toggle);
    dialog = await screen.findByTestId('admin-confirm', {}, FIND);
    expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    expect(stub.calls('POST /api/v1/admin/apps/1/kill-switch')).toHaveLength(0);
    fireEvent.change(within(dialog).getByTestId('admin-kill-switch-reason'), {
      target: { value: 'incident 42' },
    });
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    const result = await screen.findByTestId('admin-kill-switch-result', {}, FIND);
    expect(result).toHaveAttribute('data-change-id', KILL);
    expect(result).toHaveTextContent('Recorded in the audit log.');
    const calls = stub.calls('POST /api/v1/admin/apps/1/kill-switch');
    expect(calls).toHaveLength(1);
    expect(calls[0]?.body).toEqual({ enabled: false, reason: 'incident 42', instance_ids: [11] });
  });

  it('offers to turn it off when the held enabled value is false', async () => {
    answer({ ...appConfig(), current: { [SLOW]: '1000', enabled: 'false' } });
    await open();
    const section = await screen.findByTestId('admin-kill-switch', {}, FIND);
    expect(section).toHaveAttribute('data-active', 'true');
    expect(within(section).getByTestId('admin-kill-switch-toggle')).toHaveAttribute(
      'data-action',
      'off',
    );
  });
});

describe('admin config: states, text rendering and Korean (T-UI-005, T-UI-008)', () => {
  it('T-UI-005 instance names and values with markup stay text', async () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror="window.__xss=1">';
    answer({ ...appConfig([payload, XSS_PAYLOADS[1] ?? payload]), current: { [SLOW]: payload } });
    stub.on('GET /api/v1/instances/11/effective-config', () =>
      envelope([{ ...EFFECTIVE[0], value: payload, server_value: payload }]),
    );
    await open();
    await waitFor(() => {
      expect(row('admin-config-current', SLOW)).toHaveTextContent(payload);
    }, FIND);
    expect(findInjectedMarkup(screen.getByTestId('admin-config'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-008 result and change states in Korean', async () => {
    answer();
    stub.on(`GET /api/v1/admin/config/changes/${CHANGE}`, () =>
      envelope(change(CHANGE, { status: 'applied', results: RESULTS })),
    );
    await open(`app=1&change=${CHANGE}`, 'ko');
    await waitFor(() => {
      expect(screen.getByTestId('admin-config-change-status')).toHaveTextContent('적용됨');
    }, FIND);
    expect(
      within(row('admin-config-results', '12')).getByTestId('admin-config-result-status'),
    ).toHaveTextContent('거부');
    expect(screen.getAllByTestId('admin-config-locked')[0]).toHaveTextContent(
      '로컬 argos-agent.yml',
    );
  });

  it('without an application asks to choose one; a failing config shows the error state', async () => {
    answer();
    const first = await open('');
    expect(first.page).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('admin-config-empty')).toHaveTextContent('Choose an application');
    first.app.unmount();
    stub.on('GET /api/v1/admin/apps/1/config', () => errorBody(500, 'internal'));
    const second = await open();
    expect(second.page).toHaveAttribute('data-state', 'error');
  });
});
