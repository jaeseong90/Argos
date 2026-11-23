// SPDX-License-Identifier: Apache-2.0
//
// The rule editor (05 §2.4, docs/15 §9.9) under its route with the URL schema, over the API stub:
// every 05 §2.4-1 field, a saved rule loaded with ratio thresholds in % and channels by name, the
// forbidden template variable as an immediate error and the server's 422 with the same text,
// "create from template" (drawer fill, multi-part choice, URL key, changed-field dots, the saved
// rule's server diff), the save request body and the navigation after it, disable distinct from
// delete, the revision history, collected text as text (T-UI-005, runbook only through safeHref)
// and the Korean labels (T-UI-008).
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

import { CHANNEL_ID, detailBody, RULE_ID, ruleBody, stubReads } from './testing/fixtures';

// uPlot reads matchMedia when it is imported (the preview chart): install the stand-ins first.
await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});
const { rulesApp } = await import('./testing/renderRules');

function renderRules(path: string, options: Parameters<typeof rulesApp>[1] = {}) {
  const app = rulesApp(path, options);
  return { ...render(app.element), router: app.router, url: app.url };
}

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };
export const REQ_TEMPLATE = { type: 'req', description: 'SEC-39' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const DETAIL = `GET /api/v1/alerts/rules/${RULE_ID}`;

/** Every 05 §2.4-1 field by its API name (plan §1.4 test ids). */
const FIELDS = [
  'name',
  'metric',
  'kind',
  'target_app',
  'target_env',
  'target_instance',
  'target_service',
  'target_host',
  'window',
  'for',
  'evaluation_unit',
  'operator',
  'warn',
  'critical',
  'min_samples',
  'no_data',
  'recover_threshold',
  'recover_for',
  'warmup_grace',
  'active_time',
  'mute_schedules',
  'channels_warn',
  'channels_critical',
  'renotify_interval',
  'runbook_url',
  'message_template',
] as const;

let stub: ApiStub;

function field(name: string): HTMLInputElement {
  return screen.getByTestId<HTMLInputElement>(`alert-rule-field-${name}`);
}

async function ready(): Promise<HTMLElement> {
  const editor = await screen.findByTestId('alert-rule-editor', {}, FIND);
  await waitFor(() => {
    expect(editor).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return editor;
}

function dots(): (string | null)[] {
  return screen
    .queryAllByTestId('alert-rule-diff-dot')
    .map((dot) => dot.getAttribute('data-field'))
    .sort();
}

beforeEach(() => {
  stub = installApiStub();
  stubReads(stub);
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('rule editor fields (05 §2.4-1)', () => {
  it('renders every rule field in the target, condition, schedule and notification sections', async () => {
    renderRules('/alerts/rules/new?app=1');
    await ready();
    for (const section of ['target', 'condition', 'schedule', 'notify', 'preview']) {
      expect(screen.getByTestId(`alert-rule-section-${section}`)).toBeInTheDocument();
    }
    for (const name of FIELDS) {
      expect(screen.getByTestId(`alert-rule-field-${name}`)).toBeInTheDocument();
    }
    // The ids of the other target types stay visible but disabled for an application target.
    expect(field('target_instance')).toBeDisabled();
    expect(field('target_host')).toBeDisabled();
    fireEvent.change(field('target_type'), { target: { value: 'instance' } });
    expect(field('target_instance')).toBeEnabled();
    // Ratio thresholds are entered in percent.
    expect(screen.getByTestId('alert-rule-unit-warn')).toHaveTextContent('%');
    // Active time slots and mute schedules can be added.
    fireEvent.click(screen.getByTestId('alert-rule-active-time-add'));
    expect(screen.getAllByTestId('alert-rule-active-time-row')).toHaveLength(1);
    fireEvent.click(screen.getByTestId('alert-rule-mute-schedule-add'));
    expect(screen.getAllByTestId('alert-rule-mute-schedule-row')).toHaveLength(1);
  });

  it('loads a saved rule: thresholds in %, the channel chosen by name, the revision history', async () => {
    stub.on(DETAIL, () =>
      envelope(
        detailBody({
          history: [
            {
              version: 3,
              changed_by: 'u-1',
              changed_at: '2026-11-21T08:30:00Z',
              changed_by_name: untrusted('alice'),
            },
            {
              version: 1,
              changed_by: 'u-2',
              changed_at: '2026-11-20T00:00:00Z',
              changed_by_name: null,
            },
          ],
        }),
      ),
    );
    renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    expect(field('name').value).toBe('Error rate high');
    expect(field('warn').value).toBe('5');
    expect(field('critical').value).toBe('10');
    expect(field('metric').value).toBe('error_rate');
    const channel = await screen.findByTestId(`alert-rule-channel-warn-${CHANNEL_ID}`, {}, FIND);
    expect(channel).toBeChecked();
    expect(within(field('channels_warn')).getByText('ops-webhook')).toBeInTheDocument();
    // No channel URL anywhere in the editor (05 §2.4-5: rules choose channels by name).
    expect(document.body.textContent).not.toContain('hooks.example.com');
    const rows = screen.getAllByTestId('alert-rule-history-row');
    expect(rows.map((row) => row.getAttribute('data-version'))).toEqual(['3', '1']);
    expect(rows[0]).toHaveTextContent('alice');
    // Without a user name the user id is shown.
    expect(rows[1]).toHaveTextContent('u-2');
  });
});

describe('message template variables (SEC-39, docs/16 §6.11)', () => {
  it('T-SEC-39 reports a forbidden variable while typing and accepts the allowed ones', async () => {
    renderRules('/alerts/rules/new?app=1');
    await ready();
    const template = field('message_template');
    fireEvent.change(template, { target: { value: 'Down: {{app}} {{user_id}}' } });
    expect(screen.getByTestId('alert-rule-template-error')).toHaveTextContent(
      'Only the listed variables can be used in the message template.',
    );
    // The error names the forbidden reference (as text).
    expect(screen.getByTestId('alert-rule-template-forbidden')).toHaveTextContent('{{user_id}}');
    fireEvent.change(template, { target: { value: 'Down: {{ app }} {{started_at}} {{runbook}}' } });
    expect(screen.queryByTestId('alert-rule-template-error')).toBeNull();
    // A filter or a dotted path is not a variable either.
    fireEvent.change(template, { target: { value: '{{app|upper}}' } });
    expect(screen.getByTestId('alert-rule-template-error')).toBeInTheDocument();
  });

  it('completes an open {{ with the allowed variables', async () => {
    renderRules('/alerts/rules/new?app=1');
    await ready();
    const template = field('message_template');
    fireEvent.change(template, { target: { value: 'At {{sta', selectionStart: 8 } });
    template.setSelectionRange(8, 8);
    fireEvent.select(template);
    const suggestions = await screen.findByTestId('alert-rule-template-suggestions', {}, FIND);
    expect(
      within(suggestions)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['{{started_at}}']);
    fireEvent.mouseDown(screen.getByTestId('alert-rule-template-suggestion-started_at'));
    expect(field('message_template').value).toBe('At {{started_at}}');
    // The variable buttons insert at the caret.
    fireEvent.click(screen.getByTestId('alert-rule-template-variable-link'));
    expect(field('message_template').value).toContain('{{link}}');
  });

  it('T-SEC-39 maps the server 422 template_variable_forbidden to the same text', async () => {
    stub.on('POST /api/v1/alerts/rules', () =>
      errorBody(422, 'template_variable_forbidden', {
        details: [{ field: 'message_template', reason: 'variable_not_allowed' }],
      }),
    );
    renderRules('/alerts/rules/new?app=1');
    await ready();
    fireEvent.change(field('name'), { target: { value: 'Errors' } });
    fireEvent.change(field('warn'), { target: { value: '5' } });
    fireEvent.change(field('message_template'), { target: { value: '{{app}}' } });
    fireEvent.click(screen.getByTestId('alert-rule-save'));
    expect(await screen.findByTestId('alert-rule-template-error', {}, FIND)).toHaveTextContent(
      'Only the listed variables can be used in the message template.',
    );
  });
});

describe('create from template (05 §2.4-3)', () => {
  it('fills the form from the drawer, keeps the key in the URL and marks the changed fields', async () => {
    const view = renderRules('/alerts/rules/new?app=1', { state: { templates: true } });
    const drawer = await screen.findByTestId('alert-rule-template-drawer', {}, FIND);
    fireEvent.click(
      await within(drawer).findByTestId('alert-rule-template-error_rate-choose', {}, FIND),
    );
    await ready();
    expect(screen.queryByTestId('alert-rule-template-drawer')).toBeNull();
    expect(field('name').value).toBe('Error rate');
    expect(field('warn').value).toBe('5');
    expect(field('for').value).toBe('5m');
    expect(new URLSearchParams(view.url().split('?')[1]).get('template')).toBe('error_rate');
    expect(screen.getByTestId('alert-rule-template-key')).toHaveAttribute('data-key', 'error_rate');
    expect(dots()).toEqual([]);
    fireEvent.change(field('warn'), { target: { value: '3' } });
    fireEvent.change(field('for'), { target: { value: '0s' } });
    fireEvent.change(field('min_samples'), { target: { value: '1' } });
    expect(dots()).toEqual(['condition', 'for', 'min_samples']);
    // The same duration written differently is not a change.
    fireEvent.change(field('for'), { target: { value: '300s' } });
    expect(dots()).toEqual(['condition', 'min_samples']);
  });

  it('asks which part of a two-metric template and fills that part', async () => {
    renderRules('/alerts/rules/new?app=1', { state: { templates: true } });
    const drawer = await screen.findByTestId('alert-rule-template-drawer', {}, FIND);
    const item = await within(drawer).findByTestId('alert-rule-template-response_time', {}, FIND);
    expect(item).toHaveAttribute('data-parts', '2');
    fireEvent.click(within(item).getByTestId('alert-rule-template-response_time-part-1'));
    await ready();
    expect(field('metric').value).toBe('response_time_p99');
    expect(field('warn').value).toBe('3000');
    expect(screen.getByTestId('alert-rule-unit-warn')).toHaveTextContent('ms');
  });

  it('restores the template of the URL on a new rule', async () => {
    renderRules('/alerts/rules/new?app=1&template=error_rate');
    await ready();
    expect(field('name').value).toBe('Error rate');
  });

  it('T-SV-033 marks the fields of a saved rule from its server template_diff before the templates load', async () => {
    stub.on('GET /api/v1/alerts/templates', () => new Promise<Response>(() => undefined));
    stub.on(DETAIL, () =>
      envelope(
        detailBody({
          rule: ruleBody({ template_key: 'error_rate', template_version: 1, window: '10m' }),
          template_diff: {
            template_key: 'error_rate',
            rule_version: 1,
            template_version: 2,
            changes: [{ field: 'window', rule: '10m', template: '5m' }],
          },
        }),
      ),
    );
    renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    await waitFor(() => {
      expect(dots()).toEqual(['window']);
    }, FIND);
  });
});

describe('save, clone, disable and delete (05 §2.4-4)', () => {
  it('sends the whole rule (thresholds as 0..1) and opens the saved rule', async () => {
    stub.on('POST /api/v1/alerts/rules', () =>
      envelope(ruleBody({ rule_id: 'new-rule-1', name: untrusted('Checkout errors') }), {}, 201),
    );
    stub.on('GET /api/v1/alerts/rules/new-rule-1', () =>
      envelope(detailBody({ rule: ruleBody({ rule_id: 'new-rule-1' }) })),
    );
    const view = renderRules('/alerts/rules/new?app=1');
    await ready();
    fireEvent.change(field('name'), { target: { value: 'Checkout errors' } });
    fireEvent.change(field('warn'), { target: { value: '3' } });
    fireEvent.change(field('critical'), { target: { value: '7.5' } });
    fireEvent.change(field('runbook_url'), {
      target: { value: 'https://wiki.example.com/runbook' },
    });
    fireEvent.click(
      await screen.findByTestId(`alert-rule-channel-critical-${CHANNEL_ID}`, {}, FIND),
    );
    fireEvent.click(screen.getByTestId('alert-rule-save'));
    await waitFor(() => {
      expect(view.url()).toBe('/alerts/rules/new-rule-1');
    }, FIND);
    const body = stub.calls('POST /api/v1/alerts/rules')[0]?.body as Record<string, unknown>;
    expect(body).toMatchObject({
      name: 'Checkout errors',
      enabled: true,
      target: { type: 'app', app_id: 1, ids: [] },
      metric: 'error_rate',
      condition: { operator: '>', warn: 0.03, critical: 0.075 },
      channels: { warn: [], critical: [CHANNEL_ID] },
      runbook_url: 'https://wiki.example.com/runbook',
      min_samples: 20,
    });
    expect(body).not.toHaveProperty('message_template');
  });

  it('blocks the save while a field is invalid and shows why', async () => {
    renderRules('/alerts/rules/new?app=1');
    await ready();
    fireEvent.click(screen.getByTestId('alert-rule-save'));
    expect(await screen.findByTestId('alert-rule-invalid', {}, FIND)).toBeInTheDocument();
    expect(screen.getByTestId('alert-rule-error-name')).toHaveTextContent('Required.');
    expect(screen.getByTestId('alert-rule-error-warn')).toHaveTextContent(
      'Enter a warning or a critical threshold.',
    );
    expect(stub.calls('POST /api/v1/alerts/rules')).toHaveLength(0);
  });

  it('maps the server 400 details to their fields', async () => {
    stub.on(`PUT /api/v1/alerts/rules/${RULE_ID}`, () =>
      errorBody(400, 'invalid_request', {
        details: [{ field: 'target.app_id', reason: 'required' }],
      }),
    );
    renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    fireEvent.click(screen.getByTestId('alert-rule-save'));
    expect(await screen.findByTestId('alert-rule-error-target_app', {}, FIND)).toHaveTextContent(
      'Required.',
    );
  });

  it('disables a rule without deleting it, and deletes only after the confirmation', async () => {
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/disable`, () => noContent());
    stub.on(`DELETE /api/v1/alerts/rules/${RULE_ID}`, () => noContent());
    const view = renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    const toggle = screen.getByTestId('alert-rule-toggle');
    expect(toggle).toHaveAttribute('data-enabled', 'true');
    fireEvent.click(toggle);
    await waitFor(() => {
      expect(stub.calls(`POST /api/v1/alerts/rules/${RULE_ID}/disable`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(0);
    await waitFor(() => {
      expect(screen.getByTestId('alert-rule-toggle')).toHaveAttribute('data-enabled', 'false');
    }, FIND);
    fireEvent.click(screen.getByTestId('alert-rule-delete'));
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(0);
    fireEvent.click(
      within(screen.getByTestId('alert-rule-delete-confirm')).getByTestId(
        'alert-rule-delete-confirm-ok',
      ),
    );
    await waitFor(() => {
      expect(view.url()).toBe('/alerts/rules');
    }, FIND);
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(1);
  });
});

describe('collected text and labels (T-UI-005, T-UI-008)', () => {
  it('T-UI-005 renders rule, channel and user names as text and a runbook only through safeHref', async () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror=window.__xss=1>';
    stub.on('GET /api/v1/alerts/channels', () =>
      envelope([
        {
          channel_id: CHANNEL_ID,
          type: 'webhook',
          name: payload,
          target_masked: 'https://hooks.example.com/****',
          enabled: true,
          status: 'ok',
          allow_http: false,
          signing_enabled: true,
          consecutive_failures: 0,
          last_success_at: null,
          last_failure_at: null,
          created_at: '2026-11-01T00:00:00Z',
          updated_at: '2026-11-01T00:00:00Z',
        },
      ]),
    );
    stub.on(DETAIL, () =>
      envelope(
        detailBody({
          rule: ruleBody({
            name: untrusted(payload),
            runbook_url: untrusted(['javascript', 'alert(1)'].join(':')),
          }),
          history: [
            {
              version: 3,
              changed_by: 'u-1',
              changed_at: null,
              changed_by_name: untrusted(payload),
            },
          ],
        }),
      ),
    );
    const { container } = renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    expect(screen.getByTestId('alert-rule-editor-name')).toHaveTextContent(payload);
    await screen.findByTestId(`alert-rule-channel-warn-${CHANNEL_ID}`, {}, FIND);
    expect(screen.getByTestId('alert-rule-history-row')).toHaveTextContent(payload);
    // A javascript: runbook is refused: an error and no link.
    expect(screen.queryByTestId('alert-rule-runbook-link')).toBeNull();
    fireEvent.click(screen.getByTestId('alert-rule-save'));
    expect(await screen.findByTestId('alert-rule-error-runbook_url', {}, FIND)).toBeInTheDocument();
    fireEvent.change(field('runbook_url'), { target: { value: 'https://wiki.example.com/r' } });
    expect(screen.getByTestId('alert-rule-runbook-link')).toHaveAttribute(
      'href',
      'https://wiki.example.com/r',
    );
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-008 shows the sections, kinds and no-data handling in Korean', async () => {
    renderRules('/alerts/rules/new?app=1', { language: 'ko' });
    await ready();
    expect(screen.getByTestId('alert-rule-section-condition')).toHaveTextContent('조건');
    expect(screen.getByTestId('alert-rule-section-schedule')).toHaveTextContent('활성 시간대');
    expect(within(field('no_data')).getByRole('option', { name: '무시' })).toBeInTheDocument();
    expect(
      within(field('evaluation_unit')).getByRole('option', { name: '인스턴스별' }),
    ).toBeInTheDocument();
    expect(within(field('metric')).getByRole('option', { name: '에러율' })).toBeInTheDocument();
  });

  it('T-UI-007 leaves an absent threshold empty rather than 0', async () => {
    stub.on(DETAIL, () =>
      envelope(
        detailBody({
          rule: ruleBody({ condition: { operator: '>', warn: 0.05, critical: null } }),
        }),
      ),
    );
    renderRules(`/alerts/rules/${RULE_ID}`);
    await ready();
    expect(field('critical').value).toBe('');
    expect(field('warn').value).toBe('5');
  });
});

describe('keyboard (docs/15 §13)', () => {
  it('completes a variable with the arrow keys and Enter', async () => {
    const user = userEvent.setup();
    renderRules('/alerts/rules/new?app=1');
    await ready();
    const template = field('message_template');
    await user.click(template);
    await user.keyboard('{{{{s');
    await screen.findByTestId('alert-rule-template-suggestions', {}, FIND);
    await user.keyboard('{ArrowDown}{Enter}');
    expect(field('message_template').value).toBe('{{severity}}');
  });
});
