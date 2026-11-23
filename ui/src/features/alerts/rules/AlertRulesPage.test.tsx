// SPDX-License-Identifier: Apache-2.0
//
// The rule list (05 §2.4, docs/15 §9.9) under its route with the URL schema, over the API stub:
// the columns (application and environment, metric, thresholds in the metric's unit, channels by
// name, state, last change), the application and state filters as server parameters, clone to
// another application (request body), disable distinct from delete (confirmation), "create from
// template" opening the editor with the drawer, collected names as text (T-UI-005), a missing
// threshold apart from 0 (T-UI-007) and the empty state with its next action.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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

import { channelBody, RULE_ID, ruleBody, stubReads } from './testing/fixtures';

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
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/alerts/rules';
const SERVER_RULE = '9c4d0000-0000-4000-8000-0000000000bb';

let stub: ApiStub;

function table(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-table-id="alert-rules"]');
  if (element === null) {
    throw new Error('rules table not rendered');
  }
  return element;
}

function row(ruleId: string): HTMLElement {
  const element = table().querySelector<HTMLElement>(`[data-row-key="${ruleId}"]`);
  if (element === null) {
    throw new Error(`no row ${ruleId}`);
  }
  return element;
}

function cell(ruleId: string, column: string): HTMLElement {
  const element = row(ruleId).querySelector<HTMLElement>(`[data-column="${column}"]`);
  if (element === null) {
    throw new Error(`no cell ${ruleId}/${column}`);
  }
  return element;
}

async function ready(state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId('alert-rules', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

function lastQuery(): Record<string, string> {
  return Object.fromEntries(stub.calls(LIST).at(-1)?.query.entries() ?? []);
}

beforeEach(() => {
  stub = installApiStub();
  stubReads(stub, [
    ruleBody(),
    ruleBody({
      rule_id: SERVER_RULE,
      name: untrusted('Collector certificate'),
      enabled: false,
      target: { app_id: null, env: null, type: 'server', ids: [] },
      metric: 'argos_server_tls_cert_expiry_days',
      condition: { operator: '<', warn: 30, critical: null },
      channels: { warn: [], critical: [] },
    }),
  ]);
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('rule list (05 §2.4)', () => {
  it('lists every rule with its application, metric, thresholds in unit, channels and state', async () => {
    renderRules('/alerts/rules');
    await ready();
    await waitFor(() => {
      expect(cell(RULE_ID, 'app')).toHaveTextContent('order-api');
    }, FIND);
    expect(cell(RULE_ID, 'app')).toHaveTextContent('prod');
    expect(cell(RULE_ID, 'metric')).toHaveTextContent('Error rate');
    expect(
      within(cell(RULE_ID, 'thresholds')).getByTestId('alert-rule-thresholds'),
    ).toHaveTextContent('> 5% / 10%');
    await waitFor(() => {
      expect(cell(RULE_ID, 'channels')).toHaveTextContent('ops-webhook');
    }, FIND);
    expect(cell(RULE_ID, 'enabled')).toHaveTextContent('Enabled');
    expect(cell(RULE_ID, 'evaluation_unit')).toHaveTextContent('Whole application');
    // The server's own rule: no application, days as a duration, a missing critical threshold as
    // the dash (T-UI-007), disabled, no clone action.
    expect(cell(SERVER_RULE, 'app')).toHaveTextContent('Argos server');
    expect(cell(SERVER_RULE, 'thresholds')).toHaveTextContent('< 30d / —');
    expect(cell(SERVER_RULE, 'enabled')).toHaveTextContent('Disabled');
    expect(within(row(SERVER_RULE)).queryByTestId('alert-rule-clone')).toBeNull();
    expect(within(cell(RULE_ID, 'name')).getByRole('link')).toHaveAttribute(
      'href',
      `/alerts/rules/${RULE_ID}`,
    );
  });

  it('sends the application and state filters to the server', async () => {
    const view = renderRules('/alerts/rules?app=1');
    await ready();
    expect(lastQuery()).toEqual({ app: '1' });
    fireEvent.change(screen.getByTestId('alert-rules-filter-enabled'), {
      target: { value: 'disabled' },
    });
    await waitFor(() => {
      expect(lastQuery()).toEqual({ app: '1', enabled: 'false' });
    }, FIND);
    expect(view.url()).toContain('enabled=disabled');
    fireEvent.click(screen.getByTestId('alert-rules-filter-app-clear'));
    await waitFor(() => {
      expect(lastQuery()).toEqual({ enabled: 'false' });
    }, FIND);
  });

  it('clones a rule to another application with the chosen application in the body', async () => {
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/clone`, () =>
      envelope(ruleBody({ rule_id: 'clone-1' }), {}, 201),
    );
    renderRules('/alerts/rules');
    await ready();
    fireEvent.click(within(row(RULE_ID)).getByTestId('alert-rule-clone'));
    const dialog = await screen.findByTestId('alert-rule-clone-modal', {}, FIND);
    expect(within(dialog).getByTestId('alert-rule-clone-submit')).toBeDisabled();
    const select = within(within(dialog).getByTestId('alert-rule-clone-app')).getByRole('combobox');
    fireEvent.mouseDown(select);
    fireEvent.click(await screen.findByTestId('app-select-option-2', {}, FIND));
    fireEvent.click(within(dialog).getByTestId('alert-rule-clone-submit'));
    await waitFor(() => {
      expect(stub.calls(`POST /api/v1/alerts/rules/${RULE_ID}/clone`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`POST /api/v1/alerts/rules/${RULE_ID}/clone`)[0]?.body).toEqual({ app: 2 });
    await waitFor(() => {
      expect(screen.queryByTestId('alert-rule-clone-modal')).toBeNull();
    }, FIND);
  });

  it('disables a rule (it stays in the list) and deletes only after the confirmation', async () => {
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/disable`, () => noContent());
    stub.on(`DELETE /api/v1/alerts/rules/${RULE_ID}`, () => noContent());
    renderRules('/alerts/rules');
    await ready();
    fireEvent.click(within(row(RULE_ID)).getByTestId('alert-rule-toggle'));
    await waitFor(() => {
      expect(stub.calls(`POST /api/v1/alerts/rules/${RULE_ID}/disable`)).toHaveLength(1);
    }, FIND);
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(0);
    // The list is asked again after the change.
    await waitFor(() => {
      expect(stub.calls(LIST).length).toBeGreaterThan(1);
    }, FIND);
    fireEvent.click(within(row(RULE_ID)).getByTestId('alert-rule-delete'));
    const confirm = await screen.findByTestId('alert-rule-delete-confirm', {}, FIND);
    expect(confirm).toHaveTextContent('Error rate high');
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(0);
    fireEvent.click(within(confirm).getByTestId('alert-rule-delete-confirm-ok'));
    await waitFor(() => {
      expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(1);
    }, FIND);
  });

  it('opens the editor with the template drawer from "Create from template"', async () => {
    const view = renderRules('/alerts/rules?app=1');
    await ready();
    fireEvent.click(screen.getByTestId('alert-rule-from-template'));
    await waitFor(() => {
      expect(view.url()).toBe('/alerts/rules/new?app=1');
    }, FIND);
    expect(await screen.findByTestId('alert-rule-template-drawer', {}, FIND)).toBeInTheDocument();
  });

  it('shows the empty state with the template action', async () => {
    stub.on(LIST, () => envelope([]));
    renderRules('/alerts/rules');
    await ready('empty');
    expect(screen.getByTestId('alert-rules-empty-template')).toBeInTheDocument();
  });

  it('T-UI-005 renders rule and channel names as text', async () => {
    const payload = XSS_PAYLOADS[1] ?? '"><svg onload=window.__xss=1>';
    stub.on(LIST, () => envelope([ruleBody({ name: untrusted(payload) })]));
    stub.on('GET /api/v1/alerts/channels', () =>
      envelope([channelBody({ name: untrusted(payload) })]),
    );
    const { container } = renderRules('/alerts/rules');
    await ready();
    expect(cell(RULE_ID, 'name')).toHaveTextContent(payload);
    await waitFor(() => {
      expect(cell(RULE_ID, 'channels')).toHaveTextContent(payload);
    }, FIND);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
