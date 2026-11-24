// SPDX-License-Identifier: Apache-2.0
//
// Pending registrations (05 §2.11-3, F-S30, docs/15 §9.10, WO-39): the columns ((name, env), host,
// attempt times, attempts), approve with group and Apdex T → the new application's token shown once
// and nowhere after closing, the "approved without token" answer (5 tokens), reject only after the
// confirmation (cancel sends nothing), agent-sent names and hosts as text (T-UI-005).
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
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
import { appBody, domContains, pendingBody, TOKEN_VALUE } from '../testing/fixtures';
import { adminApp } from '../testing/renderAdmin';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-S30' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/admin/pending-registrations';

let stub: ApiStub;

function renderPending() {
  const app = adminApp('/admin/pending');
  return { ...render(app.element), ...app };
}

async function ready(state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId('admin-pending', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on(LIST, () => envelope([pendingBody(4), pendingBody(5, { name: untrusted('other') })]));
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('pending registrations (05 §2.11-3)', () => {
  it('lists (name, env), host, attempt times and attempts', async () => {
    renderPending();
    await ready();
    const row = document.querySelector<HTMLElement>('[data-row-key="4"]');
    expect(row?.querySelector('[data-column="name_env"]')).toHaveTextContent('new-api · stage');
    expect(row?.querySelector('[data-column="host"]')).toHaveTextContent('10.0.0.5');
    expect(row?.querySelector('[data-column="attempts"]')).toHaveTextContent('4');
    expect(row?.querySelector('[data-column="first_attempt"] time')).not.toBeNull();
    expect(row?.querySelector('[data-column="last_attempt"] time')).not.toBeNull();
  });

  it('approves with group and Apdex T and shows the token once, gone after closing', async () => {
    stub.on('POST /api/v1/admin/pending-registrations/4/approve', () =>
      envelope(
        {
          app: appBody(9),
          token: {
            token: TOKEN_VALUE,
            token_id: '0b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9aa', // gitleaks:allow (test fixture, not a real token)
            prefix: 'argos_TESTtoke',
            expires_at: '2027-11-24T00:00:00.000Z',
          },
        },
        {},
        201,
      ),
    );
    const view = renderPending();
    await ready();
    fireEvent.click(
      within(
        document.querySelector<HTMLElement>('[data-row-key="4"]') ?? document.body,
      ).getByTestId('admin-pending-approve'),
    );
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-pending-subject')).toHaveTextContent(
      'new-api · stage',
    );
    fireEvent.change(within(dialog).getByTestId('admin-pending-group'), {
      target: { value: 'shop' },
    });
    fireEvent.change(within(dialog).getByTestId('admin-pending-apdex'), {
      target: { value: '250' },
    });
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    const once = await screen.findByTestId('admin-token-once', {}, FIND);
    expect(within(once).getByTestId('admin-token-once-value')).toHaveValue(TOKEN_VALUE);
    expect(stub.calls('POST /api/v1/admin/pending-registrations/4/approve')[0]?.body).toEqual({
      apdex_t_ms: 250,
      group: 'shop',
    });
    fireEvent.click(within(once).getByTestId('admin-token-once-close'));
    await waitFor(() => {
      expect(screen.queryByTestId('admin-token-once')).toBeNull();
    });
    expect(domContains(TOKEN_VALUE)).toBe(false);
    expect(
      view.queryClient
        .getQueryCache()
        .getAll()
        .some((q) => JSON.stringify(q.state.data ?? null).includes(TOKEN_VALUE)),
    ).toBe(false);
    expect(view.url()).not.toContain(TOKEN_VALUE);
  });

  it('says when the application was created without a token (5 active tokens)', async () => {
    stub.on('POST /api/v1/admin/pending-registrations/4/approve', () =>
      errorBody(409, 'invalid_state', {
        details: [
          { field: 'approval', reason: 'applied' },
          { field: 'token', reason: 'not_issued' },
        ],
      }),
    );
    renderPending();
    await ready();
    fireEvent.click(screen.getAllByTestId('admin-pending-approve')[0] as HTMLElement);
    fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
    expect(await screen.findByTestId('admin-pending-no-token', {}, FIND)).toHaveTextContent(
      '5 active tokens',
    );
    expect(screen.queryByTestId('admin-token-once')).toBeNull();
  });

  it('rejects after the confirmation only; cancel sends nothing', async () => {
    stub.on('POST /api/v1/admin/pending-registrations/5/reject', () => noContent());
    renderPending();
    await ready();
    const reject = within(
      document.querySelector<HTMLElement>('[data-row-key="5"]') ?? document.body,
    ).getByTestId('admin-pending-reject');
    fireEvent.click(reject);
    fireEvent.click(await screen.findByTestId('admin-confirm-cancel'));
    expect(stub.calls('POST /api/v1/admin/pending-registrations/5/reject')).toHaveLength(0);
    fireEvent.click(reject);
    fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls('POST /api/v1/admin/pending-registrations/5/reject')).toHaveLength(1);
    }, FIND);
  });

  it('shows the empty state with what to expect', async () => {
    stub.on(LIST, () => envelope([]));
    renderPending();
    await ready('empty');
    expect(screen.getByTestId('admin-pending-empty')).toHaveTextContent('asks for approval');
  });

  it('T-UI-005 renders XSS payloads in names, environments and hosts as text', async () => {
    stub.on(LIST, () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          pendingBody(index + 1, {
            name: untrusted(payload),
            env: untrusted(payload),
            host: untrusted(payload),
          }),
        ),
      ),
    );
    renderPending();
    const page = await ready();
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
