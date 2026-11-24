// SPDX-License-Identifier: Apache-2.0
//
// Privacy `/admin/privacy` in the whole app (only the HTTP boundary is stubbed), docs/15 §9.10,
// 05 §2.11-9, docs/16 §6: the capture modes each instance reports (T-UI-007 '—' when unknown,
// T-UI-008 Korean labels), the server's default IP mode, the deletion request (30-day limit checked
// before sending, "rollups are not changed" confirmation, the value cleared after the request and
// never in the URL, a query key or browser storage: SEC-33), the job followed by id every 2 s until
// done, the 429 message, and the retention panel that asks for an explicit confirmation before a
// shorter value is sent.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
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
export const REQ = { type: 'req', description: 'SEC-33' };
export const REQ_PRIVACY = { type: 'req', description: 'F-S31' };

const FIND = { timeout: 5000 };
const JOB = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000aa';
const SUBJECT = 'kim.secret-user@example.com';

let stub: ApiStub;

function instance(id: number, name: string, modes: Record<string, string | null>) {
  return {
    instance_id: id,
    name,
    app: { app_id: 1, name: 'order-api', env: 'prod' },
    state: 'UP',
    host: 'h',
    pid: 1,
    agent_version: '1.0.0',
    compat: 'ok',
    config_hash: 'h',
    config_drift: false,
    protocol_version: 1,
    version: null,
    jvm_vendor: null,
    jvm_version: null,
    os: null,
    cgroup: null,
    virtual_threads: null,
    started_at: null,
    connected_at: null,
    first_seen_at: '2026-11-01T00:00:00.000Z',
    last_seen_at: null,
    last_heartbeat_at: null,
    archived_at: null,
    clock_skew_ms: null,
    drops_24h: null,
    modules_disabled: null,
    remote_config_allow: null,
    ip_mode: null,
    user_id_mode: null,
    query_string_mode: null,
    sql_param_mode: null,
    ...modes,
  };
}

function job(status: string, overrides: Record<string, unknown> = {}) {
  return {
    job_id: JOB,
    status,
    subject_kind: 'user_id',
    subject_sha256: 'ab12',
    app_id: 1,
    from: '2026-11-01T00:00:00.000Z',
    to: '2026-11-02T00:00:00.000Z',
    created_at: '2026-11-24T10:00:00.000Z',
    started_at: null,
    finished_at: null,
    rows_affected: null,
    rollups_excluded: true,
    error: null,
    warnings: [],
    tables: [
      { name: 'xlog', partition: '2026-11-01', mutation_id: 'm1', done: status === 'done' },
      { name: 'profile_step', partition: '2026-11-01', mutation_id: 'm2', done: status === 'done' },
    ],
    ...overrides,
  };
}

const RETENTION = {
  values: {
    xlog_days: 30,
    profile_days: 7,
    stat_raw_days: 14,
    metric_raw_days: 14,
    rollup_1m_days: 90,
    rollup_1h_days: 365,
  },
  config: {
    xlog_days: 30,
    profile_days: 7,
    stat_raw_days: 14,
    metric_raw_days: 14,
    rollup_1m_days: 90,
    rollup_1h_days: 365,
  },
  sources: { xlog_days: 'config' },
  tables: null,
  pending_drops: 0,
};

function answer(
  instances = [
    instance(11, 'order-1', {
      ip_mode: 'masked',
      user_id_mode: 'hashed',
      query_string_mode: 'off',
      sql_param_mode: 'masked',
    }),
    instance(12, 'order-2', {}),
  ],
) {
  stub.on('GET /api/v1/apps/1/instances', () => envelope(instances));
  stub.on('GET /api/v1/admin/settings/privacy', () =>
    envelope({ default_ip_mode: 'masked', default_ip_mode_source: 'settings' }),
  );
  stub.on('GET /api/v1/admin/settings/retention', () => envelope(RETENTION));
}

async function open(query = 'app=1', language: 'en' | 'ko' = 'en') {
  signedIn(stub, { roles: ['admin'] });
  const app = renderApp(`/admin/privacy${query === '' ? '' : `?${query}`}`, { language });
  const page = await screen.findByTestId('admin-privacy', {}, FIND);
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
  sessionStorage.clear();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('admin privacy: modes by instance (05 §2.11-9)', () => {
  it('T-UI-007 shows the four modes each instance reports and the dash when unknown', async () => {
    answer();
    await open();
    await waitFor(() => {
      expect(row('admin-privacy-modes', '11')).toHaveTextContent('Masked');
    }, FIND);
    const first = row('admin-privacy-modes', '11');
    expect(first.querySelector('[data-column="user_id_mode"]')).toHaveTextContent('Hashed');
    expect(first.querySelector('[data-column="query_string_mode"]')).toHaveTextContent('Off');
    expect(first.querySelector('[data-column="sql_param_mode"]')).toHaveTextContent('Masked');
    expect(
      row('admin-privacy-modes', '12').querySelector('[data-column="ip_mode"]'),
    ).toHaveTextContent('—');
    expect(screen.getByTestId('admin-privacy-settings')).toHaveAttribute('data-mode', 'masked');
  });

  it('T-UI-005 instance names stay text; T-UI-008 modes in Korean', async () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror="window.__xss=1">';
    answer([instance(11, payload, { ip_mode: 'off' })]);
    await open('app=1', 'ko');
    await waitFor(() => {
      expect(row('admin-privacy-modes', '11')).toHaveTextContent(payload);
    }, FIND);
    expect(row('admin-privacy-modes', '11')).toHaveTextContent('수집 안 함');
    expect(findInjectedMarkup(screen.getByTestId('admin-privacy'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('changes the server default IP mode', async () => {
    answer();
    stub.on('PUT /api/v1/admin/settings/privacy', () =>
      envelope({ default_ip_mode: 'off', default_ip_mode_source: 'settings' }),
    );
    await open();
    fireEvent.change(await screen.findByTestId('admin-privacy-ip-mode', {}, FIND), {
      target: { value: 'off' },
    });
    fireEvent.click(screen.getByTestId('admin-privacy-settings-save'));
    await screen.findByTestId('admin-privacy-settings-saved', {}, FIND);
    expect(stub.calls('PUT /api/v1/admin/settings/privacy')[0]?.body).toEqual({
      default_ip_mode: 'off',
    });
  });
});

describe('admin privacy: deletion request and job (docs/16 §6.4, SEC-33)', () => {
  it('refuses more than 30 days before sending anything', async () => {
    answer();
    await open();
    fireEvent.change(await screen.findByTestId('admin-privacy-delete-value', {}, FIND), {
      target: { value: SUBJECT },
    });
    fireEvent.change(screen.getByTestId('admin-privacy-delete-from'), {
      target: { value: '2026-10-01T00:00' },
    });
    fireEvent.change(screen.getByTestId('admin-privacy-delete-to'), {
      target: { value: '2026-11-01T00:01' },
    });
    expect(screen.getByTestId('admin-privacy-delete-problem')).toHaveAttribute(
      'data-problem',
      'rangeTooLong',
    );
    expect(screen.getByTestId('admin-privacy-delete-submit')).toBeDisabled();
    fireEvent.change(screen.getByTestId('admin-privacy-delete-to'), {
      target: { value: '2026-10-31T00:00' },
    });
    expect(screen.getByTestId('admin-privacy-delete-submit')).toBeEnabled();
    expect(stub.calls('POST /api/v1/admin/privacy/deletions')).toHaveLength(0);
  });

  it('confirms, sends once, clears the value, keeps it out of the URL, caches and storage, and follows the job to done', async () => {
    answer();
    let reads = 0;
    stub.on('POST /api/v1/admin/privacy/deletions', () =>
      envelope(
        {
          job_id: JOB,
          status: 'queued',
          subject_kind: 'user_id',
          subject_sha256: 'ab12',
          from: '2026-11-01T00:00:00.000Z',
          to: '2026-11-02T00:00:00.000Z',
          app_id: 1,
          rollups_excluded: true,
          notices: [],
        },
        {},
        202,
      ),
    );
    stub.on(`GET /api/v1/admin/privacy/jobs/${JOB}`, () => {
      reads += 1;
      return envelope(
        reads === 1
          ? job('running')
          : job('done', {
              rows_affected: 42,
              finished_at: '2026-11-24T10:00:05.000Z',
              warnings: ['ip_masked_scope'],
            }),
      );
    });
    const { app } = await open();
    fireEvent.change(await screen.findByTestId('admin-privacy-delete-value', {}, FIND), {
      target: { value: SUBJECT },
    });
    fireEvent.change(screen.getByTestId('admin-privacy-delete-from'), {
      target: { value: '2026-11-01T00:00' },
    });
    fireEvent.change(screen.getByTestId('admin-privacy-delete-to'), {
      target: { value: '2026-11-02T00:00' },
    });
    fireEvent.click(screen.getByTestId('admin-privacy-delete-submit'));
    const dialog = await screen.findByTestId('admin-confirm', {}, FIND);
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      'Rollups are not changed',
    );
    expect(dialog).not.toHaveTextContent(SUBJECT);
    expect(stub.calls('POST /api/v1/admin/privacy/deletions')).toHaveLength(0);
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(app.url()).toContain(`job=${JOB}`);
    }, FIND);
    const calls = stub.calls('POST /api/v1/admin/privacy/deletions');
    expect(calls).toHaveLength(1);
    // SEC-33 (ADR-0010 C-241 (3)): the subject travels in the JSON body only, never in the URL.
    expect(calls[0]?.body).toMatchObject({ user_id: SUBJECT, app_id: 1 });
    expect([...(calls[0]?.query.keys() ?? [])]).toEqual([]);
    // The value is gone from the form, the URL and browser storage.
    expect(screen.getByTestId('admin-privacy-delete-value')).toHaveValue('');
    expect(app.url()).not.toContain('kim');
    expect(window.location.href).not.toContain('kim');
    const storage = [localStorage, sessionStorage].flatMap((area) =>
      Array.from(
        { length: area.length },
        (_, i) => `${area.key(i) ?? ''}=${area.getItem(area.key(i) ?? '') ?? ''}`,
      ),
    );
    expect(storage.join('\n')).not.toContain('kim');
    expect(document.body.textContent).not.toContain(SUBJECT);
    // No request URL carries the subject: the deletion has it in its body, later requests ask by
    // job id only.
    for (const request of stub.requests) {
      expect(`${request.path}?${request.query.toString()}`).not.toContain('kim');
    }
    // Followed every 2 s until done.
    await waitFor(
      () => {
        expect(row('admin-privacy-jobs', JOB)).toHaveAttribute('data-status', 'done');
      },
      { timeout: 6000 },
    );
    const done = row('admin-privacy-jobs', JOB);
    expect(done.querySelector('[data-column="tables"]')).toHaveTextContent('2 / 2');
    expect(done.querySelector('[data-column="rows_affected"]')).toHaveTextContent('42');
    expect(done.querySelector('[data-column="warnings"]')).toHaveTextContent('/24');
  }, 15_000);

  it('a 429 says how many requests an hour are allowed', async () => {
    answer();
    stub.on('POST /api/v1/admin/privacy/deletions', () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '600' } }),
    );
    await open();
    fireEvent.change(await screen.findByTestId('admin-privacy-delete-value', {}, FIND), {
      target: { value: '10.0.0.1' },
    });
    fireEvent.change(screen.getByTestId('admin-privacy-delete-kind'), { target: { value: 'ip' } });
    fireEvent.click(screen.getByTestId('admin-privacy-delete-submit'));
    fireEvent.click(
      within(await screen.findByTestId('admin-confirm', {}, FIND)).getByTestId('admin-confirm-ok'),
    );
    const error = await screen.findByTestId('admin-privacy-delete-error', {}, FIND);
    expect(error).toHaveAttribute('data-kind', 'rateLimited');
    expect(error).toHaveTextContent('At most 5 deletion requests an hour');
    expect(stub.calls('POST /api/v1/admin/privacy/deletions')[0]?.body).toMatchObject({
      ip: '10.0.0.1',
    });
  });
});

describe('admin privacy: retention panel', () => {
  it('a shorter value needs the explicit confirmation and is sent with confirm', async () => {
    answer();
    stub.on('PUT /api/v1/admin/settings/retention', () =>
      envelope({
        applied: true,
        apply_error: null,
        changes: [{ table: 'xlog', from_days: 30, to_days: 10 }],
        queued_drops: 3,
        sources: { xlog_days: 'settings' },
        values: { ...RETENTION.values, xlog_days: 10 },
      }),
    );
    await open();
    const field = await screen.findByTestId('admin-retention-field-xlog_days', {}, FIND);
    expect(field).toHaveValue(30);
    fireEvent.change(field, { target: { value: '10' } });
    fireEvent.click(screen.getByTestId('admin-retention-save'));
    const dialog = await screen.findByTestId('admin-confirm', {}, FIND);
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      'Transactions (XLog)',
    );
    expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    expect(stub.calls('PUT /api/v1/admin/settings/retention')).toHaveLength(0);
    fireEvent.click(within(dialog).getByTestId('admin-retention-confirm'));
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    const result = await screen.findByTestId('admin-retention-result', {}, FIND);
    expect(result).toHaveAttribute('data-applied', 'true');
    expect(result).toHaveTextContent('3 partitions');
    expect(stub.calls('PUT /api/v1/admin/settings/retention')[0]?.body).toEqual({
      xlog_days: 10,
      confirm: true,
    });
  });
});
