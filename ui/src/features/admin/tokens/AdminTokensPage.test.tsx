// SPDX-License-Identifier: Apache-2.0
//
// Agent tokens (05 §2.11-4, docs/15 §9.10, docs/16 §2.1, SEC-02, WO-39): the columns with the
// prefix only, issue with the expiry (default 365 days) and the token shown once ("will not be
// shown again", copy), and after the dialog closes the token is in no DOM node, query or mutation
// cache, browser storage or URL; rotate with the 7-day grace in the dialog and the answer; revoke
// with a reason and the "N live sessions end" warning (instances_using); the "expires soon" badge
// at the 30-day boundary; prefixes and issuers as text (T-UI-005).
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
import { appBody, domContains, issuedBody, TOKEN_VALUE, tokenBody } from '../testing/fixtures';
import { adminApp } from '../testing/renderAdmin';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-02' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DAY = 86_400_000;
const T1 = '11111111-1111-4111-8111-111111111111';
const T2 = '22222222-2222-4222-8222-222222222222';
const T3 = '33333333-3333-4333-8333-333333333333';

let stub: ApiStub;

function renderTokens(path = '/admin/tokens?app=1') {
  const app = adminApp(path);
  return { ...render(app.element), ...app };
}

async function ready(state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId('admin-tokens', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

function row(tokenId: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="admin-tokens"] [data-row-key="${tokenId}"]`,
  );
  if (element === null) {
    throw new Error(`no row ${tokenId}`);
  }
  return element;
}

/** Every place a once-shown secret must not be after its dialog closed (SEC-02). */
function secretLeaks(
  value: string,
  queryClient: ReturnType<typeof adminApp>['queryClient'],
  url: string,
): string[] {
  const leaks: string[] = [];
  if (domContains(value)) leaks.push('DOM');
  const queries = queryClient.getQueryCache().getAll();
  if (queries.some((q) => JSON.stringify(q.state.data ?? null).includes(value))) {
    leaks.push('query cache');
  }
  const mutations = queryClient.getMutationCache().getAll();
  if (mutations.some((m) => JSON.stringify(m.state.data ?? null).includes(value))) {
    leaks.push('mutation cache');
  }
  for (const storage of [localStorage, sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i) ?? '';
      if (key.includes(value) || (storage.getItem(key) ?? '').includes(value)) {
        leaks.push('storage');
      }
    }
  }
  if (url.includes(value) || window.location.href.includes(value)) leaks.push('URL');
  return leaks;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on('GET /api/v1/apps', () =>
    envelope([appBody(1, { name: untrusted('order-api') }), appBody(2)]),
  );
  const now = Date.now();
  stub.on('GET /api/v1/admin/apps/1/tokens', () =>
    envelope([
      tokenBody(T1, {
        instances_using: 3,
        last_used_at: '2026-11-24T00:00:00.000Z',
        expires_at: new Date(now + 400 * DAY).toISOString(),
      }),
      tokenBody(T2, { expires_at: new Date(now + 10 * DAY).toISOString(), created_by: null }),
      tokenBody(T3, { state: 'revoked', revoked_at: '2026-11-20T00:00:00.000Z' }),
    ]),
  );
  try {
    localStorage.clear();
    sessionStorage.clear();
  } catch {
    // jsdom always has storage.
  }
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('token list (05 §2.11-4)', () => {
  it('lists prefix, issuer, expiry with the expires-soon badge, last use, live sessions and state', async () => {
    renderTokens();
    await ready();
    const first = row(T1);
    expect(within(first).getByTestId('admin-token-prefix')).toHaveTextContent('argos_11111111');
    expect(first.querySelector('[data-column="created_by"]')).toHaveTextContent('admin');
    expect(first.querySelector('[data-column="instances_using"]')).toHaveTextContent('3');
    expect(first.querySelector('[data-column="state"]')).toHaveTextContent('Active');
    expect(within(first).queryByTestId('admin-token-expiring')).toBeNull();
    // 10 days left: within the 30-day warn threshold of the default template.
    expect(within(row(T2)).getByTestId('admin-token-expiring')).toHaveTextContent('Expires soon');
    expect(row(T2).querySelector('[data-column="created_by"]')).toHaveTextContent('Server');
    expect(row(T2).querySelector('[data-column="last_used_at"]')).toHaveTextContent('—');
    // A revoked token offers no action.
    expect(within(row(T3)).queryByTestId('admin-token-revoke')).toBeNull();
    expect(row(T3).querySelector('[data-column="state"]')).toHaveTextContent('Revoked');
  });

  it('switches the application through app= and shows the empty state with the next action', async () => {
    stub.on('GET /api/v1/admin/apps/2/tokens', () => envelope([]));
    const view = renderTokens();
    await ready();
    fireEvent.change(screen.getByTestId('admin-app-select'), { target: { value: '2' } });
    await ready('empty');
    expect(view.url()).toContain('app=2');
    expect(screen.getByTestId('admin-tokens-empty')).toHaveTextContent('Issue a token');
  });

  it('T-UI-005 renders XSS payloads in issuer names as text', async () => {
    stub.on('GET /api/v1/admin/apps/1/tokens', () =>
      envelope(
        XSS_PAYLOADS.map((payload, index) =>
          tokenBody(`0000000${String(index)}-0000-4000-8000-000000000000`, {
            created_by: untrusted(payload),
          }),
        ),
      ),
    );
    renderTokens();
    const page = await ready();
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('issue, rotate and revoke (docs/16 §2.1, SEC-02)', () => {
  it('issues with the expiry, shows the token once, and leaves no trace after the dialog closes', async () => {
    stub.on('POST /api/v1/admin/apps/1/tokens', () =>
      envelope(issuedBody({ collector_address: 'collector.corp:9100' }), {}, 201),
    );
    const view = renderTokens();
    await ready();
    fireEvent.click(screen.getByTestId('admin-token-issue'));
    const dialog = await screen.findByTestId('admin-confirm');
    const days = within(dialog).getByTestId('admin-token-expires-days');
    expect(days).toHaveValue(365);
    fireEvent.change(days, { target: { value: '90' } });
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    const once = await screen.findByTestId('admin-token-once', {}, FIND);
    expect(within(once).getByTestId('admin-token-once-value')).toHaveValue(TOKEN_VALUE);
    expect(within(once).getByTestId('admin-token-once-notice')).toHaveTextContent(
      'will not be shown again',
    );
    expect(stub.calls('POST /api/v1/admin/apps/1/tokens')[0]?.body).toEqual({
      expires_in_days: 90,
    });
    expect(stub.calls('POST /api/v1/admin/apps/1/tokens')[0]?.headers.get('X-Argos-CSRF')).toBe(
      'csrf-token-1',
    );
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    fireEvent.click(within(once).getByTestId('admin-token-once-copy'));
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(TOKEN_VALUE);
    });
    Reflect.deleteProperty(navigator, 'clipboard');
    fireEvent.click(within(once).getByTestId('admin-token-once-close'));
    await waitFor(() => {
      expect(screen.queryByTestId('admin-token-once')).toBeNull();
    });
    expect(secretLeaks(TOKEN_VALUE, view.queryClient, view.url())).toEqual([]);
    // The install guide uses the collector address of the answer (not a secret).
    fireEvent.click(screen.getByTestId('admin-tokens-install'));
    const guide = await screen.findByTestId('admin-install');
    expect(guide).toHaveAttribute('data-collector-address', 'collector.corp:9100');
    expect(guide.textContent).not.toContain(TOKEN_VALUE);
  });

  it('refuses an expiry outside 1..3650 days without a request', async () => {
    renderTokens();
    await ready();
    fireEvent.click(screen.getByTestId('admin-token-issue'));
    const dialog = await screen.findByTestId('admin-confirm');
    fireEvent.change(within(dialog).getByTestId('admin-token-expires-days'), {
      target: { value: '0' },
    });
    expect(within(dialog).getByTestId('admin-confirm-ok')).toBeDisabled();
    expect(stub.calls('POST /api/v1/admin/apps/1/tokens')).toHaveLength(0);
  });

  it("rotates with the 7-day grace and shows the new token with the old one's end", async () => {
    stub.on(`POST /api/v1/admin/tokens/${T1}/rotate`, () =>
      envelope(
        issuedBody({ grace_until: '2026-12-01T00:00:00.000Z', rotated_token_id: T1 }),
        {},
        201,
      ),
    );
    const view = renderTokens();
    await ready();
    fireEvent.click(within(row(T1)).getByTestId('admin-token-rotate'));
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent('7 days');
    fireEvent.click(within(dialog).getByTestId('admin-confirm-ok'));
    const once = await screen.findByTestId('admin-token-once', {}, FIND);
    expect(within(once).getByTestId('admin-token-once-previous')).toBeInTheDocument();
    expect(stub.calls(`POST /api/v1/admin/tokens/${T1}/rotate`)[0]?.body).toEqual({
      grace_days: 7,
    });
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => {
      expect(screen.queryByTestId('admin-token-once')).toBeNull();
    });
    expect(secretLeaks(TOKEN_VALUE, view.queryClient, view.url())).toEqual([]);
  });

  it('revokes only with a reason and warns that the N live sessions end', async () => {
    stub.on(`POST /api/v1/admin/tokens/${T1}/revoke`, () => noContent());
    renderTokens();
    await ready();
    fireEvent.click(within(row(T1)).getByTestId('admin-token-revoke'));
    const dialog = await screen.findByTestId('admin-confirm');
    expect(within(dialog).getByTestId('admin-token-revoke-sessions')).toHaveTextContent(
      '3 live agent sessions end immediately',
    );
    expect(within(dialog).getByTestId('admin-confirm-impact')).toHaveTextContent(
      '3 live agent sessions end within 5 seconds',
    );
    const ok = within(dialog).getByTestId('admin-confirm-ok');
    expect(ok).toBeDisabled();
    fireEvent.change(within(dialog).getByTestId('admin-token-revoke-reason'), {
      target: { value: 'host decommissioned' },
    });
    fireEvent.click(ok);
    await waitFor(() => {
      expect(stub.calls(`POST /api/v1/admin/tokens/${T1}/revoke`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`POST /api/v1/admin/tokens/${T1}/revoke`)[0]?.body).toEqual({
      reason: 'host decommissioned',
    });
    await waitFor(() => {
      expect(screen.queryByTestId('admin-confirm')).toBeNull();
    });
  });

  it('keeps the dialog open with the error when the server refuses (5 active tokens)', async () => {
    stub.on('POST /api/v1/admin/apps/1/tokens', () =>
      errorBody(409, 'invalid_state', { requestId: 'req-409' }),
    );
    renderTokens();
    await ready();
    fireEvent.click(screen.getByTestId('admin-token-issue'));
    fireEvent.click(await screen.findByTestId('admin-confirm-ok'));
    expect(await screen.findByTestId('admin-confirm-error', {}, FIND)).toHaveTextContent('req-409');
    expect(screen.queryByTestId('admin-token-once')).toBeNull();
  });
});
