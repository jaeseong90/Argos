// SPDX-License-Identifier: Apache-2.0
//
// Instance administration (05 §2.11-2 and §2.11-8, docs/15 §9.10, WO-39): the nine columns with
// the API values, the outdated / unsupported agent badge, the configuration hash that differs from
// the application's majority highlighted, missing values as the dash (T-UI-007), the archived list
// (archived=true), archive for operators and delete only for archived instances (administrators),
// the 7-day automatic archive note, the collection ranking tab (per_instance of the collection
// health, ranked, the dash for no source) opened from the dashboard link's URL, a row opening the
// instance detail, agent-sent text as text (T-UI-005) and Korean state labels (T-UI-008).
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  installApiStub,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import { untrusted } from '../../../api/untrusted';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import type { Role } from '../../../state/authStore';
import { appBody, collectionBody, collectionHealthBody, instanceBody } from '../testing/fixtures';
import { adminApp } from '../testing/renderAdmin';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-S28' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps/1/instances';

let stub: ApiStub;

function renderInstances(
  path = '/admin/instances?app=1',
  role: Role = 'admin',
  language: 'en' | 'ko' = 'en',
) {
  const app = adminApp(path, { role, language });
  return { ...render(app.element), ...app };
}

async function ready(testId = 'admin-instances', state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId(testId, {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

function cell(table: string, key: number, column: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="${table}"] [data-row-key="${String(key)}"] [data-column="${column}"]`,
  );
  if (element === null) {
    throw new Error(`no cell ${table} ${String(key)}/${column}`);
  }
  return element;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on('GET /api/v1/apps', () => envelope([appBody(1), appBody(2)]));
  stub.on(LIST, (request) =>
    request.query.get('archived') === 'true'
      ? envelope([
          instanceBody(9, {
            archived_at: '2026-11-20T00:00:00.000Z',
            state: 'DOWN',
            last_heartbeat_at: null,
          }),
        ])
      : envelope([
          instanceBody(1, { config_hash: untrusted('hash-a') }),
          instanceBody(2, {
            config_hash: untrusted('hash-a'),
            compat: 'old',
            agent_version: untrusted('0.9.0'),
            state: 'WARN',
            modules_disabled: [untrusted('jdbc'), untrusted('executor')],
            drops_24h: 42,
            clock_skew_ms: 1500,
          }),
          instanceBody(3, {
            config_hash: untrusted('hash-b'),
            compat: 'unsupported',
            state: 'DOWN',
            ip_mode: 'off',
            drops_24h: null,
            clock_skew_ms: null,
            modules_disabled: null,
          }),
        ]),
  );
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('instance list (05 §2.11-2)', () => {
  it('shows the nine columns with compatibility badges, drift highlight and dashes for missing values', async () => {
    renderInstances();
    await ready();
    const t = 'admin-instances';
    expect(cell(t, 1, 'state')).toHaveTextContent('UP');
    expect(cell(t, 2, 'state')).toHaveTextContent('WARN');
    expect(cell(t, 3, 'state')).toHaveTextContent('DOWN');
    expect(cell(t, 1, 'name')).toHaveTextContent('host-1:8080');
    expect(
      within(cell(t, 2, 'agent_version')).getByTestId('admin-instance-compat'),
    ).toHaveTextContent('Outdated');
    expect(
      within(cell(t, 3, 'agent_version')).getByTestId('admin-instance-compat'),
    ).toHaveTextContent('Not supported');
    expect(within(cell(t, 1, 'agent_version')).queryByTestId('admin-instance-compat')).toBeNull();
    // hash-a is the majority; hash-b differs.
    expect(
      within(cell(t, 1, 'config_hash')).getByTestId('admin-instance-config-hash'),
    ).toHaveAttribute('data-drift', 'false');
    expect(
      within(cell(t, 3, 'config_hash')).getByTestId('admin-instance-config-hash'),
    ).toHaveAttribute('data-drift', 'true');
    expect(cell(t, 1, 'last_heartbeat').querySelector('time')).not.toBeNull();
    expect(cell(t, 2, 'drops')).toHaveTextContent('42');
    expect(cell(t, 3, 'drops')).toHaveTextContent('—');
    expect(cell(t, 2, 'clock_skew')).toHaveTextContent('1.5s');
    expect(cell(t, 3, 'clock_skew')).toHaveTextContent('—');
    expect(cell(t, 2, 'modules_disabled')).toHaveTextContent('jdbc, executor');
    expect(cell(t, 1, 'modules_disabled')).toHaveTextContent('None');
    expect(cell(t, 3, 'modules_disabled')).toHaveTextContent('—');
    expect(cell(t, 1, 'ip_mode')).toHaveTextContent('Masked');
    expect(cell(t, 3, 'ip_mode')).toHaveTextContent('Off');
    expect(screen.getByTestId('admin-instances-auto-archive')).toHaveTextContent('7 days');
  });

  it('archives only instances that are not connected and deletes only archived ones', async () => {
    stub.on('POST /api/v1/instances/3/archive', () => noContent());
    stub.on('DELETE /api/v1/instances/9', () => noContent());
    const view = renderInstances();
    await ready();
    const t = 'admin-instances';
    expect(within(cell(t, 1, 'actions')).getByTestId('admin-instance-archive')).toBeDisabled();
    expect(within(cell(t, 1, 'actions')).queryByTestId('admin-instance-delete')).toBeNull();
    fireEvent.click(within(cell(t, 3, 'actions')).getByTestId('admin-instance-archive'));
    fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls('POST /api/v1/instances/3/archive')).toHaveLength(1);
    }, FIND);
    // The archived list.
    fireEvent.click(screen.getByTestId('admin-instances-archived'));
    await waitFor(() => {
      expect(view.url()).toContain('archived=1');
    });
    await waitFor(() => {
      expect(document.querySelector('[data-row-key="9"]')).not.toBeNull();
    }, FIND);
    expect(stub.calls(LIST).some((c) => c.query.get('archived') === 'true')).toBe(true);
    expect(within(cell(t, 9, 'actions')).queryByTestId('admin-instance-archive')).toBeNull();
    fireEvent.click(within(cell(t, 9, 'actions')).getByTestId('admin-instance-delete'));
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent('command history');
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls('DELETE /api/v1/instances/9')).toHaveLength(1);
    }, FIND);
  });

  it('gives operators no delete on archived instances (administrators only)', async () => {
    renderInstances('/admin/instances?app=1&archived=1', 'operator');
    await ready();
    expect(screen.queryAllByTestId('admin-instance-delete')).toHaveLength(0);
    expect(screen.queryAllByTestId('admin-instance-archive')).toHaveLength(0);
  });

  it('opens the instance detail from a row', async () => {
    const view = renderInstances();
    await ready();
    const row = document.querySelector<HTMLElement>(
      '[data-table-id="admin-instances"] [data-row-key="2"]',
    );
    row?.focus();
    fireEvent.keyDown(row as HTMLElement, { key: 'Enter' });
    await screen.findByTestId('instance-detail-stub', {}, FIND);
    expect(view.url()).toMatch(/^\/apps\/1\/instances\/2/);
  });

  it('T-UI-005 renders XSS payloads in instance names, versions and hashes as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          instanceBody(index + 1, {
            name: untrusted(payload),
            agent_version: untrusted(payload),
            config_hash: untrusted(payload),
            modules_disabled: [untrusted(payload)],
          }),
        ),
      ),
    );
    renderInstances();
    const page = await ready();
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-006 T-UI-008 renders in Korean with the glossary labels', async () => {
    renderInstances('/admin/instances?app=1', 'admin', 'ko');
    await ready();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('인스턴스');
    expect(screen.getByTestId('admin-instances-tab-collection')).toHaveTextContent('수집 상태');
    expect(cell('admin-instances', 3, 'state')).toHaveTextContent('DOWN');
    expect(cell('admin-instances', 3, 'ip_mode')).toHaveTextContent('수집 안 함');
    expect(
      within(cell('admin-instances', 2, 'agent_version')).getByTestId('admin-instance-compat'),
    ).toHaveTextContent('구버전');
  });
});

describe('collection health ranking (05 §2.11-8, C-211 (2))', () => {
  beforeEach(() => {
    stub.on('GET /api/v1/apps/1/collection-health', () =>
      envelope(
        collectionHealthBody([
          collectionBody(1, { drops: 5, excluded: 1, skew_ms: -300, lag_s: 1.5 }),
          collectionBody(2, { drops: null, excluded: 80, skew_ms: null, lag_s: null }),
          collectionBody(3, { drops: 50, excluded: 3, skew_ms: 2000, lag_s: 0.2 }),
        ]),
      ),
    );
  });

  function order(): string[] {
    return Array.from(
      document.querySelectorAll('[data-table-id="admin-collection"] [data-row-key]'),
    ).map((row) => row.getAttribute('data-row-key') ?? '');
  }

  it('opens on the ranking from the dashboard link URL, ranked by excluded requests', async () => {
    renderInstances('/admin/instances?tab=collection&app=1&from=now-1h&sort=-excluded');
    await ready('admin-collection');
    expect(screen.getByTestId('admin-instances')).toHaveAttribute('data-tab', 'collection');
    expect(order()).toEqual(['2', '3', '1']);
    const query = stub.calls('GET /api/v1/apps/1/collection-health')[0]?.query;
    expect(Date.parse(query?.get('to') ?? '') - Date.parse(query?.get('from') ?? '')).toBe(
      3_600_000,
    );
    // No source is the dash, never 0 (D-23).
    expect(cell('admin-collection', 2, 'drops')).toHaveTextContent('—');
    expect(cell('admin-collection', 2, 'lag_s')).toHaveTextContent('—');
  });

  it('re-ranks by the chosen key through sort= (drops, then lag, then clock offset magnitude)', async () => {
    const view = renderInstances('/admin/instances?tab=collection&app=1');
    await ready('admin-collection');
    expect(order()).toEqual(['3', '1', '2']);
    fireEvent.change(screen.getByTestId('admin-collection-sort'), { target: { value: 'lag_s' } });
    await waitFor(() => {
      expect(order()).toEqual(['1', '3', '2']);
    });
    expect(view.url()).toMatch(/sort=lag_s(%3A|:)desc/);
    fireEvent.change(screen.getByTestId('admin-collection-sort'), { target: { value: 'skew_ms' } });
    await waitFor(() => {
      expect(order()).toEqual(['3', '1', '2']);
    });
  });

  it('switches tabs through the URL', async () => {
    const view = renderInstances();
    await ready();
    fireEvent.click(screen.getByTestId('admin-instances-tab-collection'));
    await ready('admin-collection');
    expect(view.url()).toContain('tab=collection');
    fireEvent.click(screen.getByTestId('admin-instances-tab-list'));
    await ready();
    expect(view.url()).not.toContain('tab=');
  });
});
