// SPDX-License-Identifier: Apache-2.0
//
// Notification channels (05 §2.4-5, docs/15 §9.9, docs/16 §8, WO-38): the list columns, create
// and edit with the target re-entered and only `target_masked` shown after saving (the typed URL
// leaves the DOM), the 422 destination refusal shown at once with its reason, the test send
// result (delivered, status code, duration; no body), the signing-key rotation with the one-time
// secret that is in no cache or storage after the dialog closes, delete with the 409 "in use"
// message, read-only for operators, and channel names as text (T-UI-005).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import type { AlertChannel } from '../../../api/alerts';
import { untrusted } from '../../../api/untrusted';
import { installDomStubs } from '../../../components/testing/dom';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { createI18n } from '../../../i18n';
import { useAuthStore, type Role } from '../../../state/authStore';

import { AlertChannelsPage } from './AlertChannelsPage';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-52' };
export const REQ_DEST = { type: 'req', description: 'SEC-51' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/alerts/channels';
const TYPED_URL = 'https://hooks.example.com/services/T000/B000/very-secret-token';
const MASKED = 'https://hooks.example.com:443/****';

let stub: ApiStub;
let channels: AlertChannel[];

function channel(id: string, overrides: Partial<AlertChannel> = {}): AlertChannel {
  return {
    channel_id: id,
    type: 'webhook',
    name: untrusted('ops-webhook'),
    target_masked: MASKED,
    allow_http: false,
    enabled: true,
    signing_enabled: true,
    status: 'ok',
    consecutive_failures: 0,
    last_success_at: '2026-11-23T00:00:00.000Z',
    last_failure_at: null,
    created_at: '2026-11-01T00:00:00.000Z',
    updated_at: '2026-11-01T00:00:00.000Z',
    ...overrides,
  };
}

function renderPage(role: Role, language: 'en' | 'ko' = 'en') {
  useAuthStore.setState({ status: 'authenticated', role, permissions: [] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const i18n = createI18n(language);
  const wrapper = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
    </QueryClientProvider>
  );
  const view = render(<AlertChannelsPage />, { wrapper });
  return { ...view, queryClient };
}

async function ready(): Promise<HTMLElement> {
  const page = await screen.findByTestId('alert-channels', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return page;
}

function tableRow(channelId: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(
    `[data-table-id="alert-channels"] [data-row-key="${channelId}"]`,
  );
  if (element === null) {
    throw new Error(`no row ${channelId}`);
  }
  return element;
}

/** Everything a cache or storage holds, as one string. */
function cachedText(queryClient: QueryClient): string {
  const queries = queryClient
    .getQueryCache()
    .getAll()
    .map((query) => query.state.data);
  const mutations = queryClient
    .getMutationCache()
    .getAll()
    .map((mutation) => [mutation.state.data, mutation.state.variables]);
  const storage: string[] = [];
  for (const area of [localStorage, sessionStorage]) {
    for (let i = 0; i < area.length; i += 1) {
      const key = area.key(i) ?? '';
      storage.push(`${key}=${area.getItem(key) ?? ''}`);
    }
  }
  return JSON.stringify({ queries, mutations, storage, url: window.location.href });
}

/** True when the text is anywhere in the document: text nodes, field values or attributes. */
function domContains(text: string): boolean {
  const values = [...document.querySelectorAll<HTMLInputElement>('input, textarea')].map(
    (field) => field.value,
  );
  const attributes = [...document.querySelectorAll('*')].flatMap((element) =>
    [...element.attributes].map((attribute) => attribute.value),
  );
  return [document.body.textContent, ...values, ...attributes].some((value) =>
    value.includes(text),
  );
}

beforeEach(() => {
  installDomStubs();
  localStorage.clear();
  sessionStorage.clear();
  stub = installApiStub();
  channels = [channel('c-1')];
  stub.on(LIST, () => envelope(channels));
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('channel list (docs/15 §9.9)', () => {
  it('shows type, name, masked destination, last success and failure and consecutive failures', async () => {
    channels = [
      channel('c-1'),
      channel('c-2', {
        type: 'email',
        name: untrusted('on-call mail'),
        target_masked: 'o***@example.com',
        status: 'failing',
        consecutive_failures: 4,
        last_failure_at: '2026-11-23T00:10:00.000Z',
      }),
    ];
    renderPage('admin');
    const page = await ready();
    expect(page).toHaveAttribute('data-state', 'ready');
    const headers = [...document.querySelectorAll('[data-table-id="alert-channels"] th')].map(
      (th) => th.getAttribute('data-column'),
    );
    expect(headers).toEqual([
      'type',
      'name',
      'target_masked',
      'last_success_at',
      'last_failure_at',
      'consecutive_failures',
      'actions',
    ]);
    const mail = tableRow('c-2');
    expect(mail.querySelector('[data-column="type"]')).toHaveTextContent('E-mail');
    expect(mail.querySelector('[data-column="target_masked"]')).toHaveTextContent(
      'o***@example.com',
    );
    expect(mail.querySelector('[data-column="consecutive_failures"]')).toHaveTextContent('4');
    expect(tableRow('c-1').querySelector('[data-column="last_failure_at"]')).toHaveTextContent('—');
  });

  it('an operator sees the list read-only (no create, edit, test, rotate or delete)', async () => {
    renderPage('operator');
    const page = await ready();
    expect(page).toHaveAttribute('data-editable', 'false');
    expect(screen.getByTestId('alert-channels-read-only')).toBeInTheDocument();
    expect(screen.queryByTestId('alert-channel-new')).not.toBeInTheDocument();
    expect(screen.queryByTestId('alert-channel-test')).not.toBeInTheDocument();
    expect(screen.queryByTestId('alert-channel-delete')).not.toBeInTheDocument();
  });

  it('an empty list tells an administrator to add a channel', async () => {
    channels = [];
    renderPage('admin');
    const page = await ready();
    expect(page).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('alert-channels-empty')).toHaveTextContent('Add a webhook');
    expect(screen.getByTestId('alert-channel-new-empty')).toBeInTheDocument();
  });

  it('T-UI-005 renders channel names as text', async () => {
    channels = XSS_PAYLOADS.slice(0, 5).map((payload, index) =>
      channel(`x-${String(index)}`, { name: untrusted(payload) }),
    );
    renderPage('admin');
    await ready();
    expect(findInjectedMarkup(screen.getByTestId('alert-channels'))).toEqual([]);
    expect(xssFired()).toBe(false);
    expect(tableRow('x-0')).toHaveTextContent(XSS_PAYLOADS[0] ?? '');
  });
});

describe('create and edit with masking (SEC-52, docs/16 §8)', () => {
  it('after saving only the masked destination is shown; the typed URL leaves the DOM', async () => {
    channels = [];
    const created = channel('c-9', {
      name: untrusted('team-hook'),
      target_masked: MASKED,
      signing_enabled: true,
    });
    stub.on('POST /api/v1/alerts/channels', () => {
      channels = [created];
      return envelope({ channel: created, signing_key: null }, {}, 201);
    });
    renderPage('admin');
    await ready();
    fireEvent.click(screen.getByTestId('alert-channel-new-empty'));
    const editor = await screen.findByTestId('alert-channel-editor', {}, FIND);
    fireEvent.change(within(editor).getByTestId('alert-channel-field-name'), {
      target: { value: 'team-hook' },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-target'), {
      target: { value: TYPED_URL },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-secret'), {
      target: { value: 's'.repeat(40) },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    await waitFor(() => {
      expect(screen.queryByTestId('alert-channel-editor')).not.toBeInTheDocument();
    }, FIND);
    expect(stub.calls('POST /api/v1/alerts/channels')[0]?.body).toEqual({
      type: 'webhook',
      name: 'team-hook',
      target: TYPED_URL,
      secret: 's'.repeat(40),
    });
    await waitFor(() => {
      expect(tableRow('c-9')).toHaveTextContent(MASKED);
    }, FIND);
    expect(domContains('very-secret-token')).toBe(false);
    expect(domContains('s'.repeat(40))).toBe(false);
  });

  it('editing re-enters the destination: empty keeps it, a new one is sent in full', async () => {
    stub.on('PUT /api/v1/alerts/channels/c-1', (request) =>
      envelope(
        channel('c-1', {
          name: untrusted((request.body as { name?: string }).name ?? 'ops-webhook'),
        }),
      ),
    );
    renderPage('admin');
    await ready();
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-edit'));
    const editor = await screen.findByTestId('alert-channel-editor', {}, FIND);
    expect(within(editor).getByTestId('alert-channel-current-target')).toHaveTextContent(MASKED);
    expect(within(editor).getByTestId('alert-channel-field-target')).toHaveValue('');
    expect(within(editor).getByTestId('alert-channel-field-type')).toBeDisabled();
    fireEvent.change(within(editor).getByTestId('alert-channel-field-name'), {
      target: { value: 'ops-2' },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    await waitFor(() => {
      expect(screen.queryByTestId('alert-channel-editor')).not.toBeInTheDocument();
    }, FIND);
    expect(stub.calls('PUT /api/v1/alerts/channels/c-1')[0]?.body).toEqual({ name: 'ops-2' });
  });

  it('SEC-51 a refused destination shows its reason at once and keeps the dialog open', async () => {
    stub.on('POST /api/v1/alerts/channels', () =>
      errorBody(422, 'webhook_destination_blocked', {
        details: [{ field: 'target', reason: 'link_local' }],
      }),
    );
    renderPage('admin');
    await ready();
    fireEvent.click(screen.getByTestId('alert-channel-new'));
    const editor = await screen.findByTestId('alert-channel-editor', {}, FIND);
    fireEvent.change(within(editor).getByTestId('alert-channel-field-name'), {
      target: { value: 'meta' },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-target'), {
      target: { value: 'https://169.254.169.254/latest' },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    const message = await within(editor).findByTestId('alert-channel-destination-error', {}, FIND);
    expect(message).toHaveAttribute('data-code', 'link_local');
    expect(message).toHaveTextContent('link-local');
    expect(within(editor).getByTestId('alert-channel-field-target')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it('SEC-51 shows the Korean reason of a private address and the 400 recipient error', async () => {
    stub.on('POST /api/v1/alerts/channels', (request) =>
      (request.body as { type: string }).type === 'email'
        ? errorBody(400, 'invalid_request', {
            details: [{ field: 'target', reason: 'invalid_address' }],
          })
        : errorBody(422, 'webhook_destination_blocked', {
            details: [{ field: 'target', reason: 'private' }],
          }),
    );
    renderPage('admin', 'ko');
    await ready();
    fireEvent.click(screen.getByTestId('alert-channel-new'));
    const editor = await screen.findByTestId('alert-channel-editor', {}, FIND);
    fireEvent.change(within(editor).getByTestId('alert-channel-field-name'), {
      target: { value: 'lan' },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-target'), {
      target: { value: 'https://10.0.0.1/hook' },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    const blocked = await within(editor).findByTestId('alert-channel-destination-error', {}, FIND);
    expect(blocked).toHaveTextContent('사설망');
    fireEvent.change(within(editor).getByTestId('alert-channel-field-type'), {
      target: { value: 'email' },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-target'), {
      target: { value: 'not-an-address' },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    const invalid = await within(editor).findByTestId('alert-channel-field-error', {}, FIND);
    expect(invalid).toHaveTextContent('이메일 주소');
  });
});

describe('test send, key rotation and delete', () => {
  it('a test send shows delivered, the status code and the duration only', async () => {
    stub.on('POST /api/v1/alerts/channels/c-1/test', () =>
      envelope({ delivered: true, status_code: 204, duration_ms: 87, error: null }),
    );
    renderPage('admin');
    await ready();
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-test'));
    const result = await screen.findByTestId('alert-channel-test-result', {}, FIND);
    await waitFor(() => {
      expect(result).toHaveAttribute('data-delivered', 'true');
    }, FIND);
    expect(result).toHaveAttribute('data-status-code', '204');
    expect(result).toHaveTextContent('Delivered');
    expect(result).toHaveTextContent('Status 204');
    expect(result).toHaveTextContent('87');
  });

  it('a failed test send names the error code, never a response body', async () => {
    stub.on('POST /api/v1/alerts/channels/c-1/test', () =>
      envelope({ delivered: false, status_code: 500, duration_ms: 12, error: 'http_status' }),
    );
    renderPage('admin');
    await ready();
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-test'));
    const result = await screen.findByTestId('alert-channel-test-result', {}, FIND);
    await waitFor(() => {
      expect(result).toHaveAttribute('data-delivered', 'false');
    }, FIND);
    expect(result).toHaveTextContent('Not delivered');
    expect(result).toHaveTextContent('error status');
  });

  it('SEC-52 rotation: confirm, show the secret once, and keep it in no cache or storage', async () => {
    const SECRET = 'whsec_0123456789abcdef0123456789abcdef'; // gitleaks:allow (test fixture, not a real secret)
    stub.on('POST /api/v1/alerts/channels/c-1/signing-key/rotate', () =>
      envelope({ signing_key: SECRET, previous_valid_until: '2026-11-30T00:00:00.000Z' }),
    );
    const { queryClient } = renderPage('admin');
    await ready();
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-rotate-key'));
    const confirm = await screen.findByTestId('alert-channel-confirm', {}, FIND);
    expect(stub.calls('POST /api/v1/alerts/channels/c-1/signing-key/rotate')).toHaveLength(0);
    fireEvent.click(within(confirm).getByTestId('alert-channel-confirm-ok'));
    const once = await screen.findByTestId('alert-channel-secret-once', {}, FIND);
    expect(within(once).getByTestId('alert-channel-secret-value')).toHaveValue(SECRET);
    expect(within(once).getByTestId('alert-channel-secret-previous')).toBeInTheDocument();
    expect(cachedText(queryClient)).not.toContain(SECRET);
    fireEvent.click(within(once).getByTestId('alert-channel-secret-close'));
    await waitFor(() => {
      expect(screen.queryByTestId('alert-channel-secret-once')).not.toBeInTheDocument();
    }, FIND);
    expect(domContains(SECRET)).toBe(false);
    expect(cachedText(queryClient)).not.toContain(SECRET);
  });

  it('SEC-52 a generated signing secret of a new webhook is shown once and then gone', async () => {
    const SECRET = 'generated-secret-0123456789abcdef0123'; // gitleaks:allow (test fixture, not a real secret)
    stub.on('POST /api/v1/alerts/channels', () =>
      envelope({ channel: channel('c-5'), signing_key: SECRET }, {}, 201),
    );
    const { queryClient } = renderPage('admin');
    await ready();
    fireEvent.click(screen.getByTestId('alert-channel-new'));
    const editor = await screen.findByTestId('alert-channel-editor', {}, FIND);
    fireEvent.change(within(editor).getByTestId('alert-channel-field-name'), {
      target: { value: 'new' },
    });
    fireEvent.change(within(editor).getByTestId('alert-channel-field-target'), {
      target: { value: 'https://hooks.example.com/x' },
    });
    fireEvent.click(within(editor).getByTestId('alert-channel-save'));
    const once = await screen.findByTestId('alert-channel-secret-once', {}, FIND);
    expect(within(once).getByTestId('alert-channel-secret-value')).toHaveValue(SECRET);
    fireEvent.keyDown(once, { key: 'Escape' });
    await waitFor(() => {
      expect(screen.queryByTestId('alert-channel-secret-once')).not.toBeInTheDocument();
    }, FIND);
    expect(domContains(SECRET)).toBe(false);
    expect(cachedText(queryClient)).not.toContain(SECRET);
  });

  it('delete asks first; a channel still used by a rule says so (409)', async () => {
    stub.on('DELETE /api/v1/alerts/channels/c-1', () =>
      errorBody(409, 'invalid_state', {
        details: [{ field: 'channel', reason: 'referenced_by_rule' }],
      }),
    );
    renderPage('admin');
    await ready();
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-delete'));
    const confirm = await screen.findByTestId('alert-channel-confirm', {}, FIND);
    fireEvent.click(within(confirm).getByTestId('alert-channel-confirm-ok'));
    const inUse = await within(confirm).findByTestId('alert-channel-in-use', {}, FIND);
    expect(inUse).toHaveTextContent('A rule still uses this channel');
    stub.on('DELETE /api/v1/alerts/channels/c-1', () => {
      channels = [];
      return noContent();
    });
    // A second try is not offered for the conflict; cancel and delete again after the rule change.
    fireEvent.keyDown(confirm, { key: 'Escape' });
    fireEvent.click(within(tableRow('c-1')).getByTestId('alert-channel-delete'));
    const again = await screen.findByTestId('alert-channel-confirm', {}, FIND);
    fireEvent.click(within(again).getByTestId('alert-channel-confirm-ok'));
    await waitFor(() => {
      expect(screen.getByTestId('alert-channels')).toHaveAttribute('data-state', 'empty');
    }, FIND);
  });
});
