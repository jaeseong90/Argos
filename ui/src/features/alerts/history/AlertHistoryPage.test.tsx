// SPDX-License-Identifier: Apache-2.0
//
// Alert history `/alerts` (05 §2.9, docs/15 §9.9, WO-38): the ten required columns and every
// filter as a server parameter (2.9-2, 2.9-3), the pinned active alerts with severity counts and
// channel status (2.9-1), the every-application view (2.9-6), the four row actions with the ACK
// note limits and its 409, the mute dialog (30-day cap), the statistics tab (2.9-5), the alert=
// highlight, cursor pages, realtime refresh, collected strings as text (T-UI-005), missing values
// (T-UI-007), Korean state and reason labels (T-UI-008) and the role rules (viewer read-only).
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import type { FakeSocket, FakeSockets } from '../../../../test/fakeWebSocket';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../../test/renderApp';
import type { AlertChannel, AlertEvent, AlertEventItem } from '../../../api/alerts';
import { untrusted } from '../../../api/untrusted';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { resetWrittenUrls } from '../../../state/useUrlState';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U10' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const FROM_ISO = '2026-11-23T00:00:00.000Z';
const TO_ISO = '2026-11-23T01:00:00.000Z';
const RANGE = `from=${FROM_ISO}&to=${TO_ISO}`;
const RULE_ID = '0b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9aa';
const EVENTS = 'GET /api/v1/alerts/events';

let stub: ApiStub;
let sockets: FakeSockets;

function event(id: string, overrides: Partial<AlertEvent> = {}): AlertEvent {
  return {
    event_id: id,
    ts: '2026-11-23T00:30:00.000Z',
    from_state: 'PENDING',
    state: 'FIRING',
    severity: 'critical',
    app: { id: 1, name: untrusted('order-api') },
    env: untrusted('prod'),
    target: { type: 'instance', id: '42', name: untrusted('web-1') },
    rule: { id: RULE_ID, name: untrusted('Error rate high'), metric: 'error_rate', unit: 'ratio' },
    value: 0.25,
    threshold: 0.05,
    duration_s: 120,
    deliveries: [{ channel_id: 'c-1', status: 'failed', attempts: 3, last_error_code: 'timeout' }],
    acked_by: null,
    acked_at: null,
    note: null,
    deeplink: `/apps/1/dashboard?from=2026-11-23T00:15:00.000Z&to=2026-11-23T00:45:00.000Z&alert=${id}`,
    reason: 'condition',
    ...overrides,
  };
}

function active(id: string, overrides: Partial<AlertEventItem> = {}): AlertEventItem {
  return {
    event_id: id,
    ts: '2026-11-23T00:30:00.000Z',
    state: 'FIRING',
    severity: 'critical',
    app: { id: 1, name: untrusted('order-api') },
    env: untrusted('prod'),
    target: { type: 'instance', id: '42', name: untrusted('web-1') },
    rule: { id: RULE_ID, name: untrusted('Error rate high'), metric: 'error_rate', unit: 'ratio' },
    value: 0.25,
    threshold: 0.05,
    duration_s: 600,
    acked_by: null,
    acked_at: null,
    note: null,
    deeplink: `/apps/1/dashboard?alert=${id}`,
    ...overrides,
  };
}

function channel(id: string, overrides: Partial<AlertChannel> = {}): AlertChannel {
  return {
    channel_id: id,
    type: 'webhook',
    name: untrusted('ops-webhook'),
    target_masked: 'https://hooks.example:443/****',
    allow_http: false,
    enabled: true,
    signing_enabled: true,
    status: 'failing',
    consecutive_failures: 2,
    last_success_at: '2026-11-22T10:00:00.000Z',
    last_failure_at: '2026-11-23T00:31:00.000Z',
    created_at: '2026-11-01T00:00:00.000Z',
    updated_at: '2026-11-01T00:00:00.000Z',
    ...overrides,
  };
}

function rule(id: string, name: string) {
  return { rule_id: id, name: untrusted(name) };
}

interface Answers {
  readonly events?: AlertEvent[];
  readonly active?: AlertEventItem[];
  readonly channels?: AlertChannel[];
}

function answer(answers: Answers = {}): void {
  stub.on(EVENTS, () => envelope(answers.events ?? [event('e-1')], { next_cursor: null }));
  stub.on('GET /api/v1/overview/alerts', () => envelope(answers.active ?? [active('e-1')]));
  stub.on('GET /api/v1/alerts/channels', () => envelope(answers.channels ?? [channel('c-1')]));
  stub.on('GET /api/v1/alerts/rules', () =>
    envelope([
      rule(RULE_ID, 'Error rate high'),
      rule('1b6f2f8e-3f0e-4c1a-9a43-2d7b51c0e9ab', 'Slow'),
    ]),
  );
  stub.on('GET /api/v1/alerts/mutes', () => envelope([]));
  stub.on('GET /api/v1/alerts/stats', () => envelope([]));
}

async function open(query: string, roles: string[] = ['viewer'], language: 'en' | 'ko' = 'en') {
  signedIn(stub, { roles });
  answer(currentAnswers);
  const app = renderApp(`/alerts?${query}`, { language });
  const page = await screen.findByTestId('alerts-history', {}, FIND);
  await waitFor(() => {
    expect(['ready', 'empty', 'error']).toContain(page.getAttribute('data-state'));
  }, FIND);
  return { app, page };
}

let currentAnswers: Answers = {};

function lastEventsQuery(): Record<string, string> {
  return Object.fromEntries(stub.calls(EVENTS).at(-1)?.query.entries() ?? []);
}

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="alerts-events"]');
  if (element === null) {
    throw new Error('events table not rendered');
  }
  return element;
}

function row(eventId: string): HTMLElement {
  const element = table().querySelector<HTMLElement>(`[data-row-key="${eventId}"]`);
  if (element === null) {
    throw new Error(`no row ${eventId}`);
  }
  return element;
}

function cell(eventId: string, column: string): HTMLElement {
  const element = row(eventId).querySelector<HTMLElement>(`[data-column="${column}"]`);
  if (element === null) {
    throw new Error(`no cell ${eventId}/${column}`);
  }
  return element;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  sockets = resetShellState();
  resetWrittenUrls();
  stub = installApiStub();
  currentAnswers = {};
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('alert history: columns and filters (05 §2.9-2, 2.9-3)', () => {
  it('T-UI-004 shows the ten required columns plus the actions column', async () => {
    await open(RANGE);
    const headers = [...table().querySelectorAll('th')].map((th) => th.getAttribute('data-column'));
    expect(headers).toEqual([
      'ts',
      'app',
      'target',
      'rule',
      'severity',
      'transition',
      'value',
      'duration',
      'deliveries',
      'acked_by',
      'actions',
    ]);
    expect(cell('e-1', 'app')).toHaveTextContent('order-api');
    expect(cell('e-1', 'app')).toHaveTextContent('prod');
    expect(cell('e-1', 'target')).toHaveTextContent('Instance');
    expect(cell('e-1', 'target')).toHaveTextContent('web-1');
    expect(cell('e-1', 'rule')).toHaveTextContent('Error rate high');
    expect(cell('e-1', 'severity')).toHaveTextContent('Critical');
    const reason = within(cell('e-1', 'transition')).getByTestId('alerts-reason');
    expect(reason).toHaveAttribute('data-reason', 'condition');
    expect(within(cell('e-1', 'transition')).getByTestId('alerts-from-state')).toHaveAttribute(
      'data-state',
      'PENDING',
    );
    const value = within(cell('e-1', 'value')).getByTestId('alert-value');
    expect(value).toHaveAttribute('data-unit', 'ratio');
    expect(value).toHaveTextContent('25');
    expect(value).toHaveTextContent('%');
    const delivery = within(cell('e-1', 'deliveries')).getByTestId('alerts-delivery');
    expect(delivery).toHaveAttribute('data-status', 'failed');
    expect(delivery).toHaveAttribute('data-attempts', '3');
    expect(delivery).toHaveTextContent('ops-webhook');
    expect(delivery).toHaveTextContent('timed out');
  });

  it('T-UI-003 sends every URL filter as a server parameter (range, app, env, severity, state, rule, failed)', async () => {
    await open(
      `${RANGE}&env=prod&app=1&severity=critical&state=acked&rule=${RULE_ID}&failedOnly=1`,
    );
    expect(lastEventsQuery()).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      app: '1',
      env: 'prod',
      severity: 'critical',
      state: 'ACKED',
      rule: RULE_ID,
      delivery: 'failed',
    });
  });

  it('T-UI-003 changing a filter writes the URL and asks the server again', async () => {
    const { app } = await open(RANGE);
    fireEvent.change(screen.getByTestId('alerts-filter-severity'), {
      target: { value: 'warn' },
    });
    await waitFor(() => {
      expect(app.url()).toContain('severity=warn');
      expect(lastEventsQuery().severity).toBe('warn');
    }, FIND);
    fireEvent.change(screen.getByTestId('alerts-filter-state'), { target: { value: 'nodata' } });
    await waitFor(() => {
      expect(lastEventsQuery().state).toBe('NO_DATA');
    }, FIND);
    fireEvent.change(screen.getByTestId('alerts-filter-rule'), { target: { value: RULE_ID } });
    await waitFor(() => {
      expect(lastEventsQuery().rule).toBe(RULE_ID);
    }, FIND);
    fireEvent.click(screen.getByTestId('alerts-filter-failed-only'));
    await waitFor(() => {
      expect(app.url()).toContain('failedOnly=1');
      expect(lastEventsQuery().delivery).toBe('failed');
    }, FIND);
  });

  it('loads the next page with the cursor of the last one', async () => {
    let page = 0;
    signedIn(stub);
    answer();
    stub.on(EVENTS, (request) => {
      page += 1;
      return request.query.get('cursor') === 'next-1'
        ? envelope(
            [event('e-2', { state: 'RESOLVED', from_state: 'FIRING', reason: 'resolved' })],
            {
              next_cursor: null,
            },
          )
        : envelope([event('e-1')], { next_cursor: 'next-1' });
    });
    renderApp(`/alerts?${RANGE}`);
    const more = await screen.findByTestId('alerts-load-more', {}, FIND);
    fireEvent.click(more);
    await waitFor(() => {
      expect(row('e-2')).toBeInTheDocument();
    }, FIND);
    expect(page).toBe(2);
    expect(stub.calls(EVENTS).at(-1)?.query.get('cursor')).toBe('next-1');
    expect(screen.queryByTestId('alerts-load-more')).not.toBeInTheDocument();
  });

  it('an empty range says what to do next; a failed request offers a retry', async () => {
    currentAnswers = { events: [] };
    const { page } = await open(RANGE);
    expect(page).toHaveAttribute('data-state', 'empty');
    const empty = screen.getByTestId('alerts-history-empty');
    expect(empty).toHaveTextContent('Widen the range');
    stub.on(EVENTS, () => errorBody(503, 'storage_unavailable'));
    fireEvent.click(screen.getByTestId('alerts-refresh'));
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'error');
    }, FIND);
    expect(screen.getByTestId('alerts-history-error')).toBeInTheDocument();
  });
});

describe('alert history: pinned panel and every-application view (05 §2.9-1, 2.9-6)', () => {
  it('lists the active alerts of every application with severity counts and channel status', async () => {
    currentAnswers = {
      active: [
        active('e-1'),
        active('e-9', {
          state: 'ACKED',
          severity: 'warn',
          app: { id: 3, name: untrusted('billing') },
          acked_by: untrusted('bob'),
        }),
      ],
    };
    const { page } = await open(RANGE);
    expect(page).toHaveAttribute('data-scope', 'all');
    expect(screen.getByTestId('alerts-scope-all')).toBeInTheDocument();
    expect(lastEventsQuery()).not.toHaveProperty('app');
    const pinned = screen.getByTestId('alerts-pinned');
    const rows = await within(pinned).findAllByTestId('alerts-pinned-row', {}, FIND);
    expect(rows.map((r) => [r.dataset.eventId, r.dataset.state, r.dataset.severity])).toEqual([
      ['e-1', 'FIRING', 'critical'],
      ['e-9', 'ACKED', 'warn'],
    ]);
    // Who acknowledged it, as a separate word.
    expect(rows[1]).toHaveTextContent(/acknowledged by bob/);
    expect(screen.getByTestId('alerts-pinned-count-critical')).toHaveAttribute('data-count', '1');
    expect(screen.getByTestId('alerts-pinned-count-warn')).toHaveAttribute('data-count', '1');
    const status = await screen.findByTestId('alerts-channel-status-row', {}, FIND);
    expect(status).toHaveAttribute('data-channel-id', 'c-1');
    expect(status).toHaveAttribute('data-last-failure', '2026-11-23T00:31:00.000Z');
    expect(status).toHaveTextContent('2 consecutive failures');
  });

  it('keeps the selected application in the pinned rows and the request', async () => {
    currentAnswers = {
      active: [active('e-1'), active('e-9', { app: { id: 3, name: untrusted('billing') } })],
    };
    const { page } = await open(`${RANGE}&app=1`);
    expect(page).toHaveAttribute('data-scope', 'app');
    const rows = await screen.findAllByTestId('alerts-pinned-row', {}, FIND);
    expect(rows.map((r) => r.dataset.eventId)).toEqual(['e-1']);
    expect(lastEventsQuery().app).toBe('1');
    fireEvent.click(screen.getByTestId('alerts-filter-all-apps'));
    await waitFor(() => {
      expect(lastEventsQuery()).not.toHaveProperty('app');
      expect(screen.getAllByTestId('alerts-pinned-row')).toHaveLength(2);
    }, FIND);
  });

  it('alert= highlights that event in the table and in the pinned panel', async () => {
    currentAnswers = { events: [event('e-0'), event('e-1')], active: [active('e-1')] };
    await open(`${RANGE}&alert=e-1`);
    await waitFor(() => {
      expect(row('e-1')).toHaveAttribute('data-highlighted', 'true');
    }, FIND);
    expect(row('e-0')).not.toHaveAttribute('data-highlighted');
    const pinned = await screen.findByTestId('alerts-pinned-row', {}, FIND);
    expect(pinned).toHaveAttribute('data-highlighted', 'true');
  });
});

describe('alert history: row actions (05 §2.9-4)', () => {
  it('a viewer gets the deep link only; an operator also edits, acknowledges and mutes', async () => {
    await open(RANGE);
    const actions = cell('e-1', 'actions');
    const link = within(actions).getByTestId('alerts-action-deeplink');
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', expect.stringContaining('/apps/1/dashboard?') as string);
    expect(within(actions).queryByTestId('alerts-action-edit-rule')).not.toBeInTheDocument();
    expect(within(actions).queryByTestId('alerts-action-ack')).not.toBeInTheDocument();
    expect(within(actions).queryByTestId('alerts-action-mute')).not.toBeInTheDocument();
    expect(screen.queryByTestId('alerts-mutes')).not.toBeInTheDocument();
    expect(stub.calls('GET /api/v1/alerts/mutes')).toHaveLength(0);
  });

  it('opens the rule editor of the row (operator)', async () => {
    const { app } = await open(RANGE, ['operator']);
    const edit = within(cell('e-1', 'actions')).getByTestId('alerts-action-edit-rule');
    expect(edit).toHaveAttribute(
      'href',
      expect.stringContaining(`/alerts/rules/${RULE_ID}`) as string,
    );
    fireEvent.click(edit);
    await waitFor(() => {
      expect(app.url()).toContain(`/alerts/rules/${RULE_ID}`);
    }, FIND);
  });

  it('ACK: only a current FIRING event, a note of at most 512 characters, then the lists refresh', async () => {
    currentAnswers = {
      events: [
        event('e-1'),
        event('e-old', { state: 'FIRING' }),
        event('e-res', { state: 'RESOLVED', from_state: 'FIRING', reason: 'resolved' }),
      ],
      active: [active('e-1')],
    };
    await open(RANGE, ['operator']);
    expect(within(cell('e-old', 'actions')).queryByTestId('alerts-action-ack')).toBeNull();
    expect(within(cell('e-res', 'actions')).queryByTestId('alerts-action-ack')).toBeNull();
    stub.on('POST /api/v1/alerts/events/e-1/ack', () =>
      envelope({
        event_id: 'e-1',
        state: 'ACKED',
        acked_by: 'alice',
        acked_at: '2026-11-23T00:40:00.000Z',
        note: 'looking',
      }),
    );
    const before = stub.calls('GET /api/v1/overview/alerts').length;
    fireEvent.click(within(cell('e-1', 'actions')).getByTestId('alerts-action-ack'));
    const modal = await screen.findByTestId('alerts-ack-modal', {}, FIND);
    const note = within(modal).getByTestId('alerts-ack-note');
    expect(note).toHaveAttribute('maxLength', '512');
    expect(note).toHaveFocus();
    fireEvent.change(note, { target: { value: '가'.repeat(400) } });
    expect(within(modal).getByTestId('alerts-ack-too-long')).toBeInTheDocument();
    expect(within(modal).getByTestId('alerts-ack-submit')).toBeDisabled();
    fireEvent.change(note, { target: { value: 'looking' } });
    fireEvent.click(within(modal).getByTestId('alerts-ack-submit'));
    await waitFor(() => {
      expect(screen.queryByTestId('alerts-ack-modal')).not.toBeInTheDocument();
    }, FIND);
    expect(stub.calls('POST /api/v1/alerts/events/e-1/ack')[0]?.body).toEqual({ note: 'looking' });
    // The ['alerts'] prefix is invalidated: the pinned panel and the badge ask again.
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/overview/alerts').length).toBeGreaterThan(before);
    }, FIND);
  });

  it('ACK: a 409 says the alert is no longer firing', async () => {
    await open(RANGE, ['operator']);
    stub.on('POST /api/v1/alerts/events/e-1/ack', () => errorBody(409, 'invalid_state'));
    fireEvent.click(within(cell('e-1', 'actions')).getByTestId('alerts-action-ack'));
    const modal = await screen.findByTestId('alerts-ack-modal', {}, FIND);
    fireEvent.click(within(modal).getByTestId('alerts-ack-submit'));
    const conflict = await within(modal).findByTestId('alerts-ack-conflict', {}, FIND);
    expect(conflict).toHaveTextContent('no longer firing');
    expect(within(modal).getByTestId('alerts-ack-submit')).toBeDisabled();
  });

  it('mute this target: prefilled from the row, at most 30 days, listed and ended from the mutes', async () => {
    await open(RANGE, ['operator']);
    let mutes: unknown[] = [];
    stub.on('GET /api/v1/alerts/mutes', () => envelope(mutes));
    stub.on('POST /api/v1/alerts/mutes', (request) => {
      const body = request.body as { from: string; to: string; target: unknown; reason?: string };
      mutes = [
        {
          mute_id: 'm-1',
          from: body.from,
          to: body.to,
          target: { app: 1, env: 'prod', instance: 42, rule: RULE_ID },
          reason: body.reason ?? null,
          created_at: body.from,
          created_by: 'u-1',
        },
      ];
      return envelope(mutes[0], {}, 201);
    });
    stub.on('DELETE /api/v1/alerts/mutes/m-1', () => {
      mutes = [];
      return new Response(null, { status: 204 });
    });
    fireEvent.click(within(cell('e-1', 'actions')).getByTestId('alerts-action-mute'));
    const modal = await screen.findByTestId('alerts-mute-modal', {}, FIND);
    expect(within(modal).getByTestId('alerts-mute-target-app')).toHaveTextContent('order-api');
    expect(within(modal).getByTestId('alerts-mute-target-env')).toHaveTextContent('prod');
    expect(within(modal).getByTestId('alerts-mute-target-instance')).toHaveTextContent('web-1');
    expect(within(modal).getByTestId('alerts-mute-target-rule')).toHaveTextContent(
      'Error rate high',
    );
    const durations = [
      ...within(modal).getByTestId('alerts-mute-duration').querySelectorAll('option'),
    ].map((option) => Number(option.value));
    expect(Math.max(...durations)).toBe(720);
    fireEvent.change(within(modal).getByTestId('alerts-mute-duration'), {
      target: { value: '720' },
    });
    fireEvent.change(within(modal).getByTestId('alerts-mute-reason'), {
      target: { value: 'deploy window' },
    });
    fireEvent.click(within(modal).getByTestId('alerts-mute-submit'));
    await waitFor(() => {
      expect(screen.queryByTestId('alerts-mute-modal')).not.toBeInTheDocument();
    }, FIND);
    const body = stub.calls('POST /api/v1/alerts/mutes')[0]?.body as {
      from: string;
      to: string;
      target: unknown;
      reason: string;
    };
    expect(body.target).toEqual({ app: 1, env: 'prod', instance: 42, rule: RULE_ID });
    expect(body.reason).toBe('deploy window');
    expect(Date.parse(body.to) - Date.parse(body.from)).toBe(30 * 24 * 3600 * 1000);
    const muteRow = await screen.findByTestId('alerts-mute-row', {}, FIND);
    expect(muteRow).toHaveTextContent('deploy window');
    fireEvent.click(within(muteRow).getByTestId('alerts-mute-delete'));
    await waitFor(() => {
      expect(screen.queryByTestId('alerts-mute-row')).not.toBeInTheDocument();
    }, FIND);
    expect(stub.calls('DELETE /api/v1/alerts/mutes/m-1')).toHaveLength(1);
  });
});

describe('alert history: statistics tab (05 §2.9-5)', () => {
  it('asks 7 days, then 30 days, and shows the per-rule averages with missing ones as a dash', async () => {
    signedIn(stub);
    answer();
    stub.on('GET /api/v1/alerts/stats', () =>
      envelope([
        {
          rule_id: RULE_ID,
          name: untrusted('Error rate high'),
          firings: 12,
          avg_duration_s: 300,
          avg_time_to_ack_s: null,
          avg_time_to_resolve_s: 0,
        },
      ]),
    );
    const app = renderApp(`/alerts?${RANGE}&tab=stats`);
    const tableEl = await waitFor(() => {
      const element = document.querySelector<HTMLElement>('[data-table-id="alerts-stats"]');
      expect(element).not.toBeNull();
      return element as HTMLElement;
    }, FIND);
    expect(
      Object.fromEntries(stub.calls('GET /api/v1/alerts/stats')[0]?.query.entries() ?? []),
    ).toEqual({});
    const statsRow = within(tableEl).getAllByTestId('data-table-row')[0] as HTMLElement;
    expect(statsRow.querySelector('[data-column="firings"]')).toHaveTextContent('12');
    expect(statsRow.querySelector('[data-column="avg_time_to_ack_s"]')).toHaveTextContent('—');
    expect(statsRow.querySelector('[data-column="avg_time_to_resolve_s"]')).not.toHaveTextContent(
      '—',
    );
    fireEvent.click(screen.getByTestId('alerts-stats-days-30'));
    await waitFor(() => {
      expect(app.url()).toContain('days=30');
      expect(stub.calls('GET /api/v1/alerts/stats').at(-1)?.query.get('days')).toBe('30');
    }, FIND);
  });
});

describe('alert history: safety and labels', () => {
  it('T-UI-005 renders rule names, targets, notes, users and channel names as text', async () => {
    const payloads = XSS_PAYLOADS.slice(0, 6);
    currentAnswers = {
      events: payloads.map((payload, index) =>
        event(`x-${String(index)}`, {
          rule: { id: RULE_ID, name: untrusted(payload), metric: 'error_rate', unit: 'ratio' },
          target: { type: 'service', id: '9', name: untrusted(payload) },
          app: { id: 1, name: untrusted(payload) },
          env: untrusted(payload),
          acked_by: untrusted(payload),
          note: untrusted(payload),
          state: 'ACKED',
          from_state: 'FIRING',
          reason: 'acked',
        }),
      ),
      active: [
        active('x-0', {
          rule: {
            id: RULE_ID,
            name: untrusted(payloads[0] ?? ''),
            metric: 'error_rate',
            unit: 'ratio',
          },
        }),
      ],
      channels: [channel('c-1', { name: untrusted(payloads[1] ?? '') })],
    };
    await open(RANGE);
    await screen.findByTestId('alerts-channel-status-row', {}, FIND);
    expect(findInjectedMarkup(screen.getByTestId('alerts-history'))).toEqual([]);
    expect(xssFired()).toBe(false);
    expect(cell('x-0', 'rule')).toHaveTextContent(payloads[0] ?? '');
    expect(cell('x-1', 'acked_by')).toHaveTextContent(payloads[1] ?? '');
  });

  it('T-UI-007 shows a missing value, threshold and duration as a dash and 0 as a value', async () => {
    currentAnswers = {
      events: [
        event('n-1', { value: null, threshold: null, duration_s: null }),
        event('z-1', { value: 0, threshold: 0, duration_s: 0 }),
      ],
    };
    await open(RANGE);
    const missing = within(cell('n-1', 'value')).getByTestId('alert-value');
    expect(missing).toHaveAttribute('data-value', 'null');
    expect(missing).toHaveAttribute('data-threshold', 'null');
    expect(missing).toHaveTextContent('—');
    expect(within(cell('n-1', 'duration')).getByTestId('alerts-duration')).toHaveTextContent('—');
    const zero = within(cell('z-1', 'value')).getByTestId('alert-value');
    expect(zero).toHaveAttribute('data-value', '0');
    expect(zero).not.toHaveTextContent('—');
    expect(within(cell('z-1', 'duration')).getByTestId('alerts-duration')).not.toHaveTextContent(
      '—',
    );
  });

  it('T-UI-008 labels states and reasons in Korean; an old row without a reason shows a dash', async () => {
    currentAnswers = {
      events: [
        event('k-1', { from_state: 'FIRING', state: 'RESOLVED', reason: 'resolved' }),
        event('k-2', { from_state: 'FIRING', state: 'RESOLVED', reason: 'target_gone' }),
        event('k-3', { from_state: 'OK', state: 'NO_DATA', reason: 'no_data' }),
        event('k-4', { from_state: 'FIRING', state: 'ACKED', reason: null }),
      ],
    };
    await open(RANGE, ['viewer'], 'ko');
    expect(cell('k-1', 'transition')).toHaveTextContent('발생');
    expect(cell('k-1', 'transition')).toHaveTextContent('해제');
    expect(within(cell('k-1', 'transition')).getByTestId('alerts-reason')).toHaveTextContent(
      '복구',
    );
    expect(within(cell('k-2', 'transition')).getByTestId('alerts-reason')).toHaveTextContent(
      '대상 사라짐',
    );
    expect(cell('k-3', 'transition')).toHaveTextContent('데이터 없음');
    expect(cell('k-4', 'transition')).toHaveTextContent('확인');
    const old = within(cell('k-4', 'transition')).getByTestId('alerts-reason');
    expect(old).toHaveAttribute('data-reason', 'none');
    expect(old).toHaveTextContent('—');
    expect(screen.getByText('알림 이력', { selector: 'h1' })).toBeInTheDocument();
  });
});

describe('alert history: realtime (docs/14 §5.3 alert)', () => {
  async function subscribedAlert(): Promise<{ socket: FakeSocket; id: string }> {
    const socket = sockets.last();
    act(() => {
      socket.serverOpen();
    });
    await waitFor(() => {
      expect(socket.framesOf('subscribe').some((f) => f.topic === 'alert')).toBe(true);
    }, FIND);
    act(() => {
      socket.ackAll();
    });
    const frame = socket.framesOf('subscribe').find((f) => f.topic === 'alert');
    return { socket, id: String(frame?.id) };
  }

  it('in live mode an alert push refreshes the pinned panel and the events', async () => {
    const user = userEvent.setup();
    await open('from=now-1h&live=1');
    await user.click(document.body);
    const { socket, id } = await subscribedAlert();
    const pinnedBefore = stub.calls('GET /api/v1/overview/alerts').length;
    const eventsBefore = stub.calls(EVENTS).length;
    act(() => {
      socket.serverSend({
        op: 'push',
        id,
        topic: 'alert',
        t: '2026-11-23T00:41:00.000Z',
        data: { event: { event_id: 'e-5' } },
      });
    });
    await waitFor(() => {
      expect(stub.calls('GET /api/v1/overview/alerts').length).toBeGreaterThan(pinnedBefore);
      expect(stub.calls(EVENTS).length).toBeGreaterThan(eventsBefore);
    }, FIND);
  });

  it('without live mode it does not subscribe to the alert topic', async () => {
    await open(RANGE);
    const socket = sockets.sockets.at(-1);
    expect(socket?.framesOf('subscribe').some((f) => f.topic === 'alert') ?? false).toBe(false);
  });
});
