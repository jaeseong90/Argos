// SPDX-License-Identifier: Apache-2.0
//
// Users and roles (docs/15 §9.10, docs/14 §4.14, docs/16 §7.10, ADR-0010 C-109·C-136 (3), WO-39):
// the columns, create with one role → the generated initial password shown once and in no DOM,
// cache, storage or URL after closing, role change and disable after a confirmation (sessions
// end), end sessions, unlock only while locked, set a password the user must change (policy
// minimum before any request), `pii.view` only for administrators, no delete or disable of one's
// own account, user names as text (T-UI-005) and Korean role labels (T-UI-008).
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
import { domContains, PASSWORD_VALUE, userBody } from '../testing/fixtures';
import { adminApp, ME_ID } from '../testing/renderAdmin';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-49' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/admin/users';
const U2 = '7f000000-0000-4000-8000-000000000002';
const U3 = '7f000000-0000-4000-8000-000000000003';

let stub: ApiStub;

function renderUsers(language: 'en' | 'ko' = 'en') {
  const app = adminApp('/admin/users', { language });
  return { ...render(app.element), ...app };
}

async function ready(state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId('admin-users', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

function row(id: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="admin-users"] [data-row-key="${id}"]`,
  );
  if (element === null) {
    throw new Error(`no row ${id}`);
  }
  return element;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on(LIST, () =>
    envelope([
      userBody(ME_ID, { username: untrusted('me-admin'), roles: ['admin'] }),
      userBody(U2, {
        username: untrusted('kim'),
        roles: ['operator'],
        locked_until: new Date(Date.now() + 600_000).toISOString(),
        must_change_password: true,
      }),
      userBody(U3, { username: untrusted('lee'), roles: ['admin'], permissions: ['pii.view'] }),
    ]),
  );
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('user list (docs/15 §9.10)', () => {
  it('shows role, enabled, lockout, must-change and personal-data columns', async () => {
    renderUsers();
    await ready();
    expect(within(row(U2)).getByTestId('admin-user-name')).toHaveTextContent('kim');
    expect(within(row(U2)).getByTestId('admin-user-role')).toHaveValue('operator');
    expect(row(U2).querySelector('[data-column="locked"] [data-locked="true"]')).not.toBeNull();
    expect(row(U2).querySelector('[data-column="must_change_password"]')).toHaveTextContent('Yes');
    expect(row(U3).querySelector('[data-column="locked"]')).toHaveTextContent('No');
    // pii.view only for administrators.
    expect(within(row(U3)).getByTestId('admin-user-pii')).toBeChecked();
    expect(within(row(U2)).queryByTestId('admin-user-pii')).toBeNull();
    // Unlock only while locked.
    expect(within(row(U2)).getByTestId('admin-user-unlock')).toBeInTheDocument();
    expect(within(row(U3)).queryByTestId('admin-user-unlock')).toBeNull();
  });

  it("never offers deleting, disabling or demoting one's own account", async () => {
    renderUsers();
    await ready();
    expect(within(row(ME_ID)).getByTestId('admin-user-delete')).toBeDisabled();
    expect(within(row(ME_ID)).getByTestId('admin-user-enabled')).toBeDisabled();
    expect(within(row(ME_ID)).getByTestId('admin-user-role')).toBeDisabled();
    expect(within(row(U2)).getByTestId('admin-user-delete')).toBeEnabled();
  });

  it('T-UI-005 renders XSS payloads in user names as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          userBody(`7f000000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`, {
            username: untrusted(payload),
          }),
        ),
      ),
    );
    renderUsers();
    const page = await ready();
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-008 shows role labels in Korean', async () => {
    renderUsers('ko');
    await ready();
    const select = within(row(U2)).getByTestId('admin-user-role');
    expect(within(select).getByRole('option', { name: '운영자' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('사용자');
  });
});

describe('user changes (docs/14 §4.14)', () => {
  it('creates a user and shows the initial password once, nowhere after closing', async () => {
    stub.on('POST /api/v1/admin/users', () =>
      envelope({ user: userBody(U2), initial_password: PASSWORD_VALUE }, {}, 201),
    );
    const view = renderUsers();
    await ready();
    fireEvent.click(screen.getByTestId('admin-user-new'));
    const dialog = await screen.findByTestId('admin-confirm');
    const ok = within(dialog).getByTestId('admin-confirm-ok');
    expect(ok).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('admin-user-field-username'), {
      target: { value: 'park' },
    });
    fireEvent.change(within(dialog).getByTestId('admin-user-field-role'), {
      target: { value: 'operator' },
    });
    fireEvent.click(ok);
    const once = await screen.findByTestId('admin-token-once', {}, FIND);
    expect(within(once).getByTestId('admin-token-once-value')).toHaveValue(PASSWORD_VALUE);
    expect(once).toHaveTextContent('Initial password');
    expect(stub.calls('POST /api/v1/admin/users')[0]?.body).toEqual({
      username: 'park',
      roles: ['operator'],
      must_change_password: true,
    });
    fireEvent.click(within(once).getByTestId('admin-token-once-close'));
    await waitFor(() => {
      expect(screen.queryByTestId('admin-token-once')).toBeNull();
    });
    expect(domContains(PASSWORD_VALUE)).toBe(false);
    expect(
      view.queryClient
        .getQueryCache()
        .getAll()
        .some((q) => JSON.stringify(q.state.data ?? null).includes(PASSWORD_VALUE)),
    ).toBe(false);
    expect(view.queryClient.getMutationCache().getAll()).toHaveLength(0);
    expect(view.url()).not.toContain(PASSWORD_VALUE);
  });

  it('refuses a user name the server would refuse, without a request', async () => {
    renderUsers();
    await ready();
    fireEvent.click(screen.getByTestId('admin-user-new'));
    const dialog = await screen.findByTestId('admin-confirm');
    for (const bad of ['', '-lead', 'a b', 'x'.repeat(65)]) {
      fireEvent.change(within(dialog).getByTestId('admin-user-field-username'), {
        target: { value: bad },
      });
      expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    }
    expect(stub.calls('POST /api/v1/admin/users')).toHaveLength(0);
  });

  it('changes the role after a confirmation that the sessions end', async () => {
    stub.on(`PATCH /api/v1/admin/users/${U2}`, () => envelope(userBody(U2, { roles: ['viewer'] })));
    renderUsers();
    await ready();
    fireEvent.change(within(row(U2)).getByTestId('admin-user-role'), {
      target: { value: 'viewer' },
    });
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      'Their sessions end now',
    );
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls(`PATCH /api/v1/admin/users/${U2}`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`PATCH /api/v1/admin/users/${U2}`)[0]?.body).toEqual({ roles: ['viewer'] });
  });

  it('ends sessions, unlocks, disables and deletes through their endpoints', async () => {
    stub.on(`POST /api/v1/admin/users/${U2}/sessions/revoke`, () => noContent());
    stub.on(`POST /api/v1/admin/users/${U2}/unlock`, () => noContent());
    stub.on(`PATCH /api/v1/admin/users/${U2}`, () => envelope(userBody(U2, { enabled: false })));
    stub.on(`DELETE /api/v1/admin/users/${U2}`, () => noContent());
    renderUsers();
    await ready();
    for (const [button, route] of [
      ['admin-user-sessions-revoke', `POST /api/v1/admin/users/${U2}/sessions/revoke`],
      ['admin-user-unlock', `POST /api/v1/admin/users/${U2}/unlock`],
      ['admin-user-enabled', `PATCH /api/v1/admin/users/${U2}`],
      ['admin-user-delete', `DELETE /api/v1/admin/users/${U2}`],
    ] as const) {
      await waitFor(() => {
        expect(screen.queryByTestId('admin-confirm')).toBeNull();
      }, FIND);
      fireEvent.click(within(row(U2)).getByTestId(button));
      fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
      await waitFor(() => {
        expect(stub.calls(route)).toHaveLength(1);
      }, FIND);
    }
    expect(stub.calls(`PATCH /api/v1/admin/users/${U2}`)[0]?.body).toEqual({ enabled: false });
  });

  it('sets a new password only from the policy minimum length, never echoing it', async () => {
    stub.on(`POST /api/v1/admin/users/${U2}/password/reset`, () => noContent());
    renderUsers();
    await ready();
    fireEvent.click(within(row(U2)).getByTestId('admin-user-reset-password'));
    const dialog = await screen.findByTestId('admin-confirm');
    const field = within(dialog).getByTestId('admin-user-field-password');
    expect(field).toHaveAttribute('type', 'password');
    fireEvent.change(field, { target: { value: 'short' } });
    expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    fireEvent.change(field, { target: { value: 'Temporary-Pass-2026' } });
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls(`POST /api/v1/admin/users/${U2}/password/reset`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`POST /api/v1/admin/users/${U2}/password/reset`)[0]?.body).toEqual({
      password: 'Temporary-Pass-2026', // gitleaks:allow (test fixture, not a real secret)
    });
    await waitFor(() => {
      expect(screen.queryByTestId('admin-confirm')).toBeNull();
    });
    expect(domContains('Temporary-Pass-2026')).toBe(false);
  });

  it('grants pii.view to an administrator after a confirmation', async () => {
    stub.on(`PATCH /api/v1/admin/users/${U3}`, () => envelope(userBody(U3, { roles: ['admin'] })));
    renderUsers();
    await ready();
    fireEvent.click(within(row(U3)).getByTestId('admin-user-pii'));
    fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls(`PATCH /api/v1/admin/users/${U3}`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`PATCH /api/v1/admin/users/${U3}`)[0]?.body).toEqual({ permissions: [] });
  });
});
