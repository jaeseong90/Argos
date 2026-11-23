// SPDX-License-Identifier: Apache-2.0
//
// Alert rule calls through the generated client (docs/14 §4.13): list filters as query
// parameters, create / replace / delete, enable and disable as their own operations (not a delete),
// clone with the target application and optional environment, the preview with days 7, the
// templates and their bulk apply, the channel choices without destinations, and errors as ApiError
// (422 template_variable_forbidden, 429 Retry-After).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  noContent,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';
import {
  channelBody,
  detailBody,
  PREVIEW,
  RULE_ID,
  ruleBody,
  TEMPLATES,
} from '../features/alerts/rules/testing/fixtures';

import {
  alertRuleKeys,
  applyAlertTemplates,
  cloneAlertRule,
  createAlertRule,
  deleteAlertRule,
  getAlertRule,
  listAlertChannelChoices,
  listAlertRules,
  listAlertTemplates,
  previewAlertRule,
  setAlertRuleEnabled,
  updateAlertRule,
  type AlertRuleRequest,
} from './alertRules';
import { ApiError } from './errors';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };

const BODY: AlertRuleRequest = {
  name: 'Errors',
  target: { type: 'app', app_id: 1, ids: [] },
  metric: 'error_rate',
  condition: { operator: '>', warn: 0.05 },
};

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('alert rule API (docs/14 §4.13)', () => {
  it('lists rules with the application, environment and enabled filters', async () => {
    stub.on('GET /api/v1/alerts/rules', () => envelope([ruleBody()]));
    expect(await listAlertRules({ app: 1, env: 'prod', enabled: false })).toHaveLength(1);
    expect(
      Object.fromEntries(stub.calls('GET /api/v1/alerts/rules')[0]?.query.entries() ?? []),
    ).toEqual({ app: '1', env: 'prod', enabled: 'false' });
    await listAlertRules();
    expect([...(stub.calls('GET /api/v1/alerts/rules')[1]?.query.keys() ?? [])]).toEqual([]);
  });

  it('reads one rule with its history and creates, replaces and deletes rules', async () => {
    stub.on(`GET /api/v1/alerts/rules/${RULE_ID}`, () => envelope(detailBody()));
    stub.on('POST /api/v1/alerts/rules', () => envelope(ruleBody(), {}, 201));
    stub.on(`PUT /api/v1/alerts/rules/${RULE_ID}`, () => envelope(ruleBody()));
    stub.on(`DELETE /api/v1/alerts/rules/${RULE_ID}`, () => noContent());
    expect((await getAlertRule(RULE_ID)).history).toHaveLength(2);
    expect((await createAlertRule(BODY)).rule_id).toBe(RULE_ID);
    expect(stub.calls('POST /api/v1/alerts/rules')[0]?.body).toEqual(BODY);
    await updateAlertRule(RULE_ID, BODY);
    expect(stub.calls(`PUT /api/v1/alerts/rules/${RULE_ID}`)[0]?.body).toEqual(BODY);
    await deleteAlertRule(RULE_ID);
    expect(stub.calls(`DELETE /api/v1/alerts/rules/${RULE_ID}`)).toHaveLength(1);
  });

  it('enables and disables with their own operations, never a delete', async () => {
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/enable`, () => noContent());
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/disable`, () => noContent());
    await setAlertRuleEnabled(RULE_ID, false);
    await setAlertRuleEnabled(RULE_ID, true);
    expect(
      stub.requests.map((r) => `${r.method} ${r.path}`).filter((r) => r.includes('rules')),
    ).toEqual([
      `POST /api/v1/alerts/rules/${RULE_ID}/disable`,
      `POST /api/v1/alerts/rules/${RULE_ID}/enable`,
    ]);
  });

  it('clones to an application with an optional environment', async () => {
    stub.on(`POST /api/v1/alerts/rules/${RULE_ID}/clone`, () => envelope(ruleBody(), {}, 201));
    await cloneAlertRule(RULE_ID, { app: 2, env: null });
    await cloneAlertRule(RULE_ID, { app: 2, env: 'stage' });
    const bodies = stub.calls(`POST /api/v1/alerts/rules/${RULE_ID}/clone`).map((c) => c.body);
    expect(bodies).toEqual([{ app: 2 }, { app: 2, env: 'stage' }]);
  });

  it('previews 7 days of an unsaved rule', async () => {
    stub.on('POST /api/v1/alerts/rules/preview', () => envelope(PREVIEW));
    expect((await previewAlertRule(BODY)).count).toBe(2);
    expect(stub.calls('POST /api/v1/alerts/rules/preview')[0]?.body).toEqual({
      rule: BODY,
      days: 7,
    });
  });

  it('lists the templates and applies them in bulk', async () => {
    stub.on('GET /api/v1/alerts/templates', () => envelope(TEMPLATES));
    stub.on('POST /api/v1/alerts/templates/apply', () => envelope({ created: ['r'], skipped: [] }));
    expect((await listAlertTemplates()).map((t) => t.key)).toEqual(['error_rate', 'response_time']);
    await applyAlertTemplates({ app: 1, keys: ['error_rate'] });
    expect(stub.calls('POST /api/v1/alerts/templates/apply')[0]?.body).toEqual({
      app: 1,
      keys: ['error_rate'],
    });
  });

  it('reduces channels to id, name, type and state (no destination)', async () => {
    stub.on('GET /api/v1/alerts/channels', () => envelope([channelBody()]));
    const choices = await listAlertChannelChoices();
    expect(choices).toEqual([
      { id: channelBody().channel_id, name: 'ops-webhook', type: 'webhook', enabled: true },
    ]);
    expect(JSON.stringify(choices)).not.toContain('hooks.example.com');
  });

  it('throws ApiError with the code and Retry-After', async () => {
    stub.on('POST /api/v1/alerts/rules', () => errorBody(422, 'template_variable_forbidden'));
    stub.on('POST /api/v1/alerts/rules/preview', () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '5' } }),
    );
    await expect(createAlertRule(BODY)).rejects.toMatchObject({
      status: 422,
      code: 'template_variable_forbidden',
    });
    const error = await previewAlertRule(BODY).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfterSeconds).toBe(5);
  });

  it('keys every rule query under the alert-rules prefix', () => {
    expect(alertRuleKeys.list({ app: 1 })).toEqual([
      'alert-rules',
      'list',
      { app: 1, env: null, enabled: null },
    ]);
    expect(alertRuleKeys.detail(RULE_ID)[0]).toBe('alert-rules');
    expect(alertRuleKeys.templates()[0]).toBe('alert-rules');
  });
});
