// SPDX-License-Identifier: Apache-2.0
//
// Fixtures of the rule screens' tests (docs/14 §4.13 schemas). Test-only module: nothing in the
// application imports it.
import { envelope, type ApiStub } from '../../../../../test/apiStub';
import type {
  AlertChannelView,
  AlertRule,
  AlertRuleDetail,
  AlertTemplate,
} from '../../../../api/alertRules';
import { untrusted } from '../../../../api/untrusted';

export const RULE_ID = '9c4d0000-0000-4000-8000-0000000000aa';
export const CHANNEL_ID = '7d1c0000-0000-4000-8000-000000000001';

function appBody(appId: number, name: string, env: string): Record<string, unknown> {
  return {
    app_id: appId,
    name,
    env,
    group: null,
    apdex_t_ms: 500,
    created_at: '2026-11-01T00:00:00.000Z',
    instances_total: 2,
    instances_up: 2,
    last_seen_at: '2026-11-14T00:00:00.000Z',
    recommended_rules_applied: true,
    registered_from_app_id: null,
    registration_policy: 'auto',
    slow_threshold_ms: 1000,
    kinds: null,
  };
}

export const APPS = [appBody(1, 'order-api', 'prod'), appBody(2, 'billing', 'stage')];

export function ruleBody(overrides: Partial<AlertRule> = {}): AlertRule {
  return {
    rule_id: RULE_ID,
    version: 3,
    created_by: 'u-1',
    created_at: '2026-11-20T00:00:00Z',
    updated_at: '2026-11-21T08:30:00Z',
    name: untrusted('Error rate high'),
    enabled: true,
    template_key: null,
    template_version: null,
    target: { app_id: 1, env: untrusted('prod'), type: 'app', ids: [] },
    kind: 'HTTP',
    metric: 'error_rate',
    window: '5m',
    for: '5m',
    evaluation_unit: 'app',
    condition: { operator: '>', warn: 0.05, critical: 0.1 },
    min_samples: 20,
    no_data: 'ignore',
    recover: null,
    warmup_grace: '3m',
    active_time: [],
    mute_schedules: [],
    channels: { warn: [CHANNEL_ID], critical: [] },
    renotify_interval: '30m',
    runbook_url: null,
    message_template: null,
    ...overrides,
  };
}

export function detailBody(overrides: Partial<AlertRuleDetail> = {}): AlertRuleDetail {
  return {
    rule: ruleBody(),
    history: [
      { version: 3, changed_by: 'u-1', changed_at: '2026-11-21T08:30:00Z', changed_by_name: null },
      { version: 1, changed_by: null, changed_at: '2026-11-20T00:00:00Z', changed_by_name: null },
    ],
    template_diff: null,
    ...overrides,
  };
}

export function channelBody(overrides: Partial<AlertChannelView> = {}): AlertChannelView {
  return {
    channel_id: CHANNEL_ID,
    type: 'webhook',
    name: untrusted('ops-webhook'),
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
    ...overrides,
  };
}

// Template parts as the server sends them: rule bodies without a template key or version
// (DefaultTemplates parts, AlertRuleDefaults.template_key null); the editor takes both from the
// template.
export const TEMPLATES: AlertTemplate[] = [
  {
    key: 'error_rate',
    name: 'Error rate',
    version: 2,
    applies_when: 'always',
    defaults: [
      {
        scope: 'app',
        rule: {
          name: untrusted('Error rate'),
          enabled: true,
          template_key: null,
          template_version: null,
          target: { app_id: null, env: null, type: 'app', ids: [] },
          kind: 'HTTP',
          metric: 'error_rate',
          window: '5m',
          for: '5m',
          evaluation_unit: 'app',
          condition: { operator: '>', warn: 0.05, critical: 0.1 },
          min_samples: 20,
          no_data: 'ignore',
          recover: null,
          warmup_grace: '3m',
          active_time: [],
          mute_schedules: [],
          channels: { warn: [], critical: [] },
          renotify_interval: '30m',
          runbook_url: null,
          message_template: null,
        },
      },
    ],
  },
  {
    key: 'response_time',
    name: 'Response time',
    version: 1,
    applies_when: 'always',
    defaults: [
      {
        scope: 'app',
        rule: {
          name: untrusted('Response time p95'),
          enabled: true,
          template_key: null,
          template_version: null,
          target: { app_id: null, env: null, type: 'app', ids: [] },
          kind: 'HTTP',
          metric: 'response_time_p95',
          window: '5m',
          for: '10m',
          evaluation_unit: 'app',
          condition: { operator: '>', warn: 1000, critical: 3000 },
          min_samples: 20,
          no_data: 'ignore',
          recover: null,
          warmup_grace: '3m',
          active_time: [],
          mute_schedules: [],
          channels: { warn: [], critical: [] },
          renotify_interval: '30m',
          runbook_url: null,
          message_template: null,
        },
      },
      {
        scope: 'app',
        rule: {
          name: untrusted('Response time p99'),
          enabled: true,
          template_key: null,
          template_version: null,
          target: { app_id: null, env: null, type: 'app', ids: [] },
          kind: 'HTTP',
          metric: 'response_time_p99',
          window: '5m',
          for: '10m',
          evaluation_unit: 'app',
          condition: { operator: '>', warn: 3000, critical: null },
          min_samples: 20,
          no_data: 'ignore',
          recover: null,
          warmup_grace: '3m',
          active_time: [],
          mute_schedules: [],
          channels: { warn: [], critical: [] },
          renotify_interval: '30m',
          runbook_url: null,
          message_template: null,
        },
      },
    ],
  },
];

export const PREVIEW = {
  count: 2,
  max_duration_s: 900,
  step_s: 1800,
  series: {
    ts: ['2026-11-16T00:00:00Z', '2026-11-16T00:30:00Z', '2026-11-16T01:00:00Z'],
    value: [0.01, 0.08, null],
  },
  firings: [
    { ts_start: '2026-11-16T00:30:00Z', ts_end: '2026-11-16T00:45:00Z', peak_value: 0.08 },
    { ts_start: '2026-11-18T03:00:00Z', ts_end: '2026-11-18T03:10:00Z', peak_value: 0.07 },
  ],
};

/** The read calls every rule screen makes, answered. */
export function stubReads(stub: ApiStub, rules: AlertRule[] = [ruleBody()]): void {
  stub.on('GET /api/v1/apps', () => envelope(APPS));
  stub.on('GET /api/v1/alerts/rules', () => envelope(rules));
  stub.on('GET /api/v1/alerts/channels', () => envelope([channelBody()]));
  stub.on('GET /api/v1/alerts/templates', () => envelope(TEMPLATES));
  stub.on(`GET /api/v1/alerts/rules/${RULE_ID}`, () => envelope(detailBody()));
  stub.on('POST /api/v1/alerts/rules/preview', () => envelope(PREVIEW));
}
