// SPDX-License-Identifier: Apache-2.0
//
// Application administration (05 §2.11-1 and §2.11-7, docs/15 §9.10, WO-39) under its route with
// the URL schema, over the API stub: the eight columns with the API values (tokens from
// `tokens_active`, a dash while the server sends none; T-UI-007), the registration policy with the
// environment default marked, create (request body, recommended rules applied, then the install
// guide), delete only after the name is typed again, the install guide with name, environment and
// collector address filled in and the canary checklist, read only for operators, names as text
// (T-UI-005) and the Korean catalog (T-UI-006/008).
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../../test/apiStub';
import { untrusted } from '../../../api/untrusted';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import type { Role } from '../../../state/authStore';
import { appBody } from '../testing/fixtures';
import { adminApp } from '../testing/renderAdmin';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-S28' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/apps';

let stub: ApiStub;

function renderApps(path = '/admin/apps', role: Role = 'admin', language: 'en' | 'ko' = 'en') {
  const app = adminApp(path, { role, language });
  return { ...render(app.element), ...app };
}

async function ready(state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId('admin-apps', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

function cell(appId: number, column: string): HTMLElement {
  const row = document.querySelector<HTMLElement>(
    `[data-table-id="admin-apps"] [data-row-key="${String(appId)}"]`,
  );
  const element = row?.querySelector<HTMLElement>(`[data-column="${column}"]`) ?? null;
  if (element === null) {
    throw new Error(`no cell ${String(appId)}/${column}`);
  }
  return element;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on(LIST, () =>
    envelope([
      appBody(1, { name: untrusted('order-api'), group: untrusted('shop') }),
      appBody(2, {
        name: untrusted('billing'),
        env: untrusted('stage'),
        registration_policy: 'approval',
        registration_policy_inherited: true,
        recommended_rules_applied: false,
        last_seen_at: null,
        tokens_active: 2,
      }),
    ]),
  );
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('application list (05 §2.11-1)', () => {
  it('shows the eight columns with the API values, a dash for what the API does not give', async () => {
    renderApps();
    await ready();
    expect(cell(1, 'name')).toHaveTextContent('order-api');
    expect(cell(1, 'env')).toHaveTextContent('prod');
    expect(cell(1, 'group')).toHaveTextContent('shop');
    expect(cell(1, 'instances')).toHaveTextContent('2 up / 3');
    expect(cell(1, 'tokens')).toHaveTextContent('1');
    expect(cell(2, 'tokens')).toHaveTextContent('2');
    expect(cell(1, 'registration_policy')).toHaveTextContent('Automatic');
    expect(cell(2, 'registration_policy')).toHaveTextContent('Approval (environment default)');
    expect(cell(1, 'recommended_rules')).toHaveTextContent('Applied');
    expect(cell(2, 'recommended_rules')).toHaveTextContent('Not applied');
    expect(cell(2, 'last_seen')).toHaveTextContent('—');
    expect(cell(1, 'last_seen').querySelector('time')).not.toBeNull();
  });

  it('filters by name through the URL (q=) and offers to clear it', async () => {
    const view = renderApps('/admin/apps?q=bill');
    await ready();
    expect(document.querySelector('[data-row-key="1"]')).toBeNull();
    expect(document.querySelector('[data-row-key="2"]')).not.toBeNull();
    expect(view.url()).toContain('q=bill');
  });

  it('is read only for operators: no create, edit or delete, the install guide stays', async () => {
    renderApps('/admin/apps', 'operator');
    const page = await ready();
    expect(page).toHaveAttribute('data-editable', 'false');
    expect(screen.queryByTestId('admin-app-new')).toBeNull();
    expect(screen.queryAllByTestId('admin-app-edit')).toHaveLength(0);
    expect(screen.queryAllByTestId('admin-app-delete')).toHaveLength(0);
    expect(screen.getAllByTestId('admin-app-install')).toHaveLength(2);
    expect(screen.getByTestId('admin-apps-read-only')).toBeInTheDocument();
  });

  it('shows the empty state with the next action', async () => {
    stub.on(LIST, () => envelope([]));
    renderApps();
    await ready('empty');
    expect(screen.getByTestId('admin-apps-empty')).toHaveTextContent('Create an application');
    expect(screen.getByTestId('admin-app-new-empty')).toBeInTheDocument();
  });

  it('T-UI-005 renders XSS payloads in names, environments and groups as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          appBody(index + 1, {
            name: untrusted(payload),
            env: untrusted(payload),
            group: untrusted(payload),
          }),
        ),
      ),
    );
    renderApps();
    const page = await ready();
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
    expect(cell(1, 'name')).toHaveTextContent(XSS_PAYLOADS[0] ?? '');
  });

  it('T-UI-006 T-UI-008 renders in Korean from the admin catalog', async () => {
    renderApps('/admin/apps', 'admin', 'ko');
    await ready();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('애플리케이션');
    expect(cell(2, 'registration_policy')).toHaveTextContent('승인 (환경 기본값)');
    expect(cell(2, 'recommended_rules')).toHaveTextContent('미적용');
  });
});

describe('create, edit and delete (docs/15 §9.10)', () => {
  it('creates with Apdex T, group and policy, applies the recommended rules, then opens the install guide', async () => {
    stub.on('POST /api/v1/apps', () =>
      envelope(
        appBody(5, { name: untrusted('pay-api'), recommended_rules_applied: false }),
        {},
        201,
      ),
    );
    stub.on('POST /api/v1/alerts/templates/apply', () => envelope({ created: ['a'], skipped: [] }));
    const view = renderApps();
    await ready();
    fireEvent.click(screen.getByTestId('admin-app-new'));
    const editor = await screen.findByTestId('admin-app-editor');
    fireEvent.change(within(editor).getByTestId('admin-app-field-name'), {
      target: { value: ' pay-api ' },
    });
    fireEvent.change(within(editor).getByTestId('admin-app-field-group'), {
      target: { value: 'payments' },
    });
    fireEvent.change(within(editor).getByTestId('admin-app-field-apdex_t_ms'), {
      target: { value: '300' },
    });
    fireEvent.change(within(editor).getByTestId('admin-app-field-registration_policy'), {
      target: { value: 'approval' },
    });
    fireEvent.click(within(editor).getByTestId('admin-app-editor-save'));
    await waitFor(() => {
      expect(stub.calls('POST /api/v1/alerts/templates/apply')).toHaveLength(1);
    }, FIND);
    expect(stub.calls('POST /api/v1/apps')[0]?.body).toEqual({
      name: 'pay-api',
      env: 'prod',
      apdex_t_ms: 300,
      group: 'payments',
      registration_policy: 'approval',
    });
    expect(stub.calls('POST /api/v1/alerts/templates/apply')[0]?.body).toEqual({ app: 5 });
    await waitFor(() => {
      expect(view.url()).toContain('app=5');
    }, FIND);
  });

  it('refuses an Apdex T outside 1..65535 without a request', async () => {
    renderApps();
    await ready();
    fireEvent.click(screen.getByTestId('admin-app-new'));
    const editor = await screen.findByTestId('admin-app-editor');
    fireEvent.change(within(editor).getByTestId('admin-app-field-name'), {
      target: { value: 'x' },
    });
    fireEvent.change(within(editor).getByTestId('admin-app-field-apdex_t_ms'), {
      target: { value: '0' },
    });
    fireEvent.click(within(editor).getByTestId('admin-app-editor-save'));
    expect(await screen.findByTestId('admin-app-field-error')).toHaveTextContent('Apdex T');
    expect(stub.calls('POST /api/v1/apps')).toHaveLength(0);
  });

  it('edits only the changed fields, and marks an inherited registration policy', async () => {
    stub.on('PATCH /api/v1/apps/2', () => envelope(appBody(2)));
    renderApps();
    await ready();
    fireEvent.click(within(cell(2, 'actions')).getByTestId('admin-app-edit'));
    const editor = await screen.findByTestId('admin-app-editor');
    expect(within(editor).getByTestId('admin-app-policy-inherited')).toBeInTheDocument();
    expect(within(editor).queryByTestId('admin-app-field-name')).toBeNull();
    fireEvent.change(within(editor).getByTestId('admin-app-field-apdex_t_ms'), {
      target: { value: '800' },
    });
    fireEvent.click(within(editor).getByTestId('admin-app-field-recommended_rules'));
    fireEvent.click(within(editor).getByTestId('admin-app-editor-save'));
    await waitFor(() => {
      expect(stub.calls('PATCH /api/v1/apps/2')).toHaveLength(1);
    }, FIND);
    // The inherited policy is set explicitly once saved (C-231 (7)).
    expect(stub.calls('PATCH /api/v1/apps/2')[0]?.body).toEqual({
      apdex_t_ms: 800,
      registration_policy: 'approval',
    });
  });

  it('deletes only after the application name is typed again', async () => {
    stub.on('DELETE /api/v1/apps/1', () =>
      envelope({ app_id: 1, rules_deleted: 4, sessions_closed: 2, tokens_revoked: 3 }, {}, 202),
    );
    renderApps();
    await ready();
    fireEvent.click(within(cell(1, 'actions')).getByTestId('admin-app-delete'));
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      'agents disconnect within 5 seconds',
    );
    const ok = within(dialog).getByTestId('admin-confirm-ok');
    expect(ok).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('admin-app-delete-confirm'), {
      target: { value: 'order-ap' },
    });
    expect(ok).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('admin-app-delete-confirm'), {
      target: { value: 'order-api' },
    });
    expect(ok).toBeEnabled();
    fireEvent.click(ok);
    expect(await screen.findByTestId('admin-app-deleted', {}, FIND)).toHaveTextContent(
      '3 tokens revoked, 2 agent sessions closed, 4 alert rules deleted',
    );
    expect(stub.calls('DELETE /api/v1/apps/1')[0]?.query.get('confirm')).toBe('order-api');
  });

  it('sends nothing when the delete dialog is cancelled', async () => {
    renderApps();
    await ready();
    fireEvent.click(within(cell(1, 'actions')).getByTestId('admin-app-delete'));
    fireEvent.click(await screen.findByTestId('admin-confirm-cancel'));
    expect(screen.queryByTestId('admin-confirm')).toBeNull();
    expect(stub.requests.filter((r) => r.method === 'DELETE')).toHaveLength(0);
  });
});

describe('install guide (05 §2.11-7)', () => {
  it('fills -javaagent, argos-agent.yml and --check with the application and the collector address', async () => {
    const view = renderApps();
    await ready();
    fireEvent.click(within(cell(1, 'actions')).getByTestId('admin-app-install'));
    const guide = await screen.findByTestId('admin-install', {}, FIND);
    expect(view.url()).toContain('app=1');
    const host = window.location.hostname;
    expect(guide).toHaveAttribute('data-collector-address', `${host}:9100`);
    const javaagent = within(guide).getByTestId('admin-install-javaagent');
    expect(javaagent).toHaveTextContent('-javaagent:/opt/argos-agent/argos-agent.jar');
    expect(javaagent).toHaveTextContent(`-Dargos.server.address=${host}:9100`);
    expect(javaagent).toHaveTextContent('-Dargos.app.name=order-api');
    expect(javaagent).toHaveTextContent('-Dargos.app.env=prod');
    const yml = within(guide).getByTestId('admin-install-yml');
    expect(yml).toHaveTextContent('name: order-api');
    expect(yml).toHaveTextContent('token-file: /opt/argos-agent/token');
    expect(within(guide).getByTestId('admin-install-check')).toHaveTextContent('--check');
    expect(within(guide).getByTestId('admin-install-compat')).toHaveTextContent(/README\.md/);
    expect(within(guide).getByTestId('admin-install-address-note')).toBeInTheDocument();
    const items = within(guide).getAllByTestId('admin-install-canary-item');
    expect(items).toHaveLength(4);
    // Administrators can go and issue a token for it.
    expect(within(guide).getByTestId('admin-install-tokens-link')).toHaveAttribute(
      'href',
      '/admin/tokens?app=1',
    );
  });

  it('copies a snippet to the clipboard and closes with Esc, dropping app= from the URL', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    const view = renderApps('/admin/apps?app=1');
    await ready();
    const guide = await screen.findByTestId('admin-install', {}, FIND);
    fireEvent.click(within(guide).getByTestId('admin-install-copy-check'));
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(
        'java -jar /opt/argos-agent/argos-agent.jar --check --config /opt/argos-agent/argos-agent.yml',
      );
    });
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => {
      expect(screen.queryByTestId('admin-install')).toBeNull();
    });
    expect(view.url()).not.toContain('app=');
    Reflect.deleteProperty(navigator, 'clipboard');
  });

  it('offers no token link to operators', async () => {
    renderApps('/admin/apps?app=2', 'operator');
    await ready();
    const guide = await screen.findByTestId('admin-install', {}, FIND);
    expect(within(guide).queryByTestId('admin-install-tokens-link')).toBeNull();
    expect(within(guide).getByTestId('admin-install-javaagent')).toHaveTextContent(
      '-Dargos.app.env=stage',
    );
  });
});
