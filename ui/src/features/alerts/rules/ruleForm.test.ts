// SPDX-License-Identifier: Apache-2.0
//
// The rule editor's model (05 §2.4-1, docs/14 §4.13): saved rule → form → request round trip with
// ratio thresholds in %, the server's RuleCodec limits as client errors, the preview request
// without notification parts, the server detail → field mapping, the template comparison on
// canonical values, the message template rule (SEC-39, docs/16 §6.11 + started_at) and the metric
// catalog's units, targets and preview support (server AlertMetric).
import { describe, expect, it } from 'vitest';

import {
  forbiddenReferences,
  insertVariable,
  openReference,
  suggestVariables,
  TEMPLATE_VARIABLES,
} from './messageTemplate';
import {
  ALERT_METRICS,
  METRIC_CATALOG,
  metricInfo,
  toApiValue,
  toInputValue,
} from './metricCatalog';
import {
  diffFieldsOf,
  diffFromTemplate,
  durationSeconds,
  emptyForm,
  fieldOfDetail,
  formFromRule,
  previewable,
  toPreviewRequest,
  toRequest,
  validateForm,
  type RuleForm,
} from './ruleForm';
import { ruleBody, TEMPLATES } from './testing/fixtures';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };
export const REQ_TEMPLATE = { type: 'req', description: 'SEC-39' };

function valid(patch: Partial<RuleForm> = {}): RuleForm {
  return { ...emptyForm(1), name: 'Errors', warn: '5', ...patch };
}

describe('form ↔ request', () => {
  it('turns a saved rule into the form and back into the same rule (ratio in % on screen)', () => {
    const rule = ruleBody({
      recover: { threshold: 0.02, for: '2m' },
      active_time: [{ days: ['Sat', 'Mon'], from: '09:00', to: '24:00' }],
      mute_schedules: [{ cron: '0 2 * * *', duration: '2h', reason: null }],
      runbook_url: null,
    });
    const form = formFromRule(rule);
    expect(form.warn).toBe('5');
    expect(form.critical).toBe('10');
    expect(form.recoverThreshold).toBe('2');
    expect(form.activeTime[0]?.days).toEqual(['Mon', 'Sat']);
    const request = toRequest(form);
    expect(request.condition).toEqual({ operator: '>', warn: 0.05, critical: 0.1 });
    expect(request.recover).toEqual({ threshold: 0.02, for: '2m' });
    expect(request.target).toEqual({ type: 'app', app_id: 1, env: 'prod', ids: [] });
    expect(request.mute_schedules).toEqual([{ cron: '0 2 * * *', duration: '2h' }]);
    expect(request).not.toHaveProperty('runbook_url');
    expect(request.channels).toEqual(rule.channels);
  });

  it('keeps thresholds of other units as entered and drops binary noise of ratios', () => {
    expect(toInputValue(0.07, 'ratio')).toBe('7');
    expect(toApiValue('7', 'ratio')).toBe(0.07);
    expect(toApiValue('1500', 'ms')).toBe(1500);
    expect(toApiValue('', 'ms')).toBeNull();
    expect(toInputValue(null, 'ratio')).toBe('');
  });

  it('sends instance ids for an instance target and no application for a server rule', () => {
    expect(toRequest(valid({ targetType: 'instance', instanceIds: '12, 15,' })).target).toEqual({
      type: 'instance',
      app_id: 1,
      ids: ['12', '15'],
    });
    const server = toRequest(
      valid({ targetType: 'server', appId: null, metric: 'argos_server_dropped_total' }),
    );
    expect(server.target).toEqual({ type: 'server', ids: [] });
  });

  it('previews without channels, runbook and template, with a placeholder name', () => {
    const preview = toPreviewRequest(
      valid({
        name: '',
        runbookUrl: 'https://wiki',
        messageTemplate: '{{app}}',
        channelsWarn: ['c'],
      }),
      'Preview',
    );
    expect(preview.name).toBe('Preview');
    expect(preview).not.toHaveProperty('channels');
    expect(preview).not.toHaveProperty('runbook_url');
    expect(preview).not.toHaveProperty('message_template');
  });
});

describe('client checks (server RuleCodec limits)', () => {
  it('accepts a valid rule and reports each invalid field', () => {
    expect(validateForm(valid())).toEqual({});
    const errors = validateForm(
      valid({
        name: ' ',
        warn: '',
        critical: '',
        window: '2s',
        for: '25h',
        renotifyInterval: '1m',
        runbookUrl: ['javascript', 'alert(1)'].join(':'),
        minSamples: '-1',
        targetType: 'instance',
        instanceIds: '12,abc',
        activeTime: [{ days: ['Mon'], from: '18:00', to: '09:00' }],
        muteSchedules: [{ cron: '0 2 * *', duration: '8d', reason: '' }],
      }),
    );
    expect(errors).toEqual({
      name: 'required',
      warn: 'thresholdRequired',
      window: 'outOfRange',
      for: 'outOfRange',
      renotify_interval: 'outOfRange',
      runbook_url: 'url',
      min_samples: 'number',
      target_instance: 'ids',
      active_time: 'slot',
      mute_schedules: 'slot',
    });
  });

  it('orders warn and critical by the operator and allows 0 renotify (no reminders)', () => {
    expect(validateForm(valid({ warn: '10', critical: '5' })).critical).toBe('notBeyondWarn');
    expect(validateForm(valid({ operator: '<', warn: '10', critical: '5' }))).toEqual({});
    expect(validateForm(valid({ renotifyInterval: '0' }))).toEqual({});
  });

  it('rejects a target type the metric does not support and a missing application', () => {
    expect(validateForm(valid({ metric: 'ext_call_p95' })).target_type).toBe('notForMetric');
    expect(validateForm(valid({ appId: null })).target_app).toBe('required');
    expect(
      validateForm(valid({ targetType: 'service', evaluationUnit: 'instance' })).evaluation_unit,
    ).toBe('notForTarget');
  });

  it('T-SEC-39 reports a forbidden template variable', () => {
    expect(validateForm(valid({ messageTemplate: '{{app}} {{sql}}' })).message_template).toBe(
      'templateForbidden',
    );
  });

  it('previews only when the evaluated fields are valid', () => {
    expect(previewable(validateForm(valid({ name: '', runbookUrl: 'x' })))).toBe(true);
    expect(previewable(validateForm(valid({ warn: '' })))).toBe(false);
  });

  it('parses rule durations like the server', () => {
    expect(durationSeconds('1h30m')).toBe(5400);
    expect(durationSeconds('0')).toBe(0);
    expect(durationSeconds('5 m')).toBeNull();
    expect(durationSeconds('7d')).toBe(604_800);
  });

  it('maps server details to the form fields', () => {
    expect(fieldOfDetail('target.app_id')).toBe('target_app');
    expect(fieldOfDetail('condition.critical')).toBe('critical');
    expect(fieldOfDetail('active_time[2].to')).toBe('active_time');
    expect(fieldOfDetail('recover.for')).toBe('recover_for');
    expect(fieldOfDetail(null)).toBeNull();
    expect(fieldOfDetail('unknown')).toBeNull();
  });
});

describe('template differences (05 §2.4-3)', () => {
  const part = TEMPLATES[0]?.defaults[0];
  if (part === undefined) {
    throw new Error('fixture template missing');
  }

  it('finds no difference right after filling from the template', () => {
    expect(diffFromTemplate(formFromRule(part.rule, 1), part.rule)).toEqual([]);
  });

  it('compares canonical values: same duration in another spelling is no change', () => {
    const form = formFromRule(part.rule, 1);
    expect(diffFromTemplate({ ...form, for: '300s', window: '5m' }, part.rule)).toEqual([]);
    expect(
      diffFromTemplate(
        { ...form, warn: '3', noData: 'warn', messageTemplate: '{{app}}' },
        part.rule,
      ),
    ).toEqual(['condition', 'no_data', 'message_template']);
    // Name, target and channels are the rule's own fields (server TemplateDiff.FIELDS).
    expect(diffFromTemplate({ ...form, name: 'Mine', channelsWarn: ['c'] }, part.rule)).toEqual([]);
  });

  it('reads the fields of the server diff', () => {
    expect(
      diffFieldsOf({
        template_key: 'error_rate',
        rule_version: 1,
        template_version: 2,
        changes: [
          { field: 'window', rule: '10m', template: '5m' },
          { field: 'condition', rule: {}, template: {} },
        ],
      }),
    ).toEqual(['window', 'condition']);
    expect(diffFieldsOf(null)).toEqual([]);
  });
});

describe('message template (docs/16 §6.11, ADR-0010 C-226 (6))', () => {
  it('T-SEC-39 allows exactly the 14 variables, started_at included', () => {
    expect(TEMPLATE_VARIABLES).toEqual([
      'app',
      'env',
      'instance',
      'service',
      'host',
      'metric',
      'value',
      'threshold',
      'severity',
      'duration',
      'started_at',
      'link',
      'runbook',
      'rule',
    ]);
    expect(forbiddenReferences('{{ app }} {{started_at}}')).toEqual([]);
    expect(forbiddenReferences('{{user_id}} {{app.name}} {{app|upper}} {{ }}')).toEqual([
      '{{user_id}}',
      '{{app.name}}',
      '{{app|upper}}',
      '{{ }}',
    ]);
  });

  it('suggests and inserts variables at the caret', () => {
    expect(openReference('Hi {{se', 7)).toEqual({ start: 3, partial: 'se' });
    expect(openReference('Hi {{app}} x', 12)).toBeNull();
    expect(suggestVariables('se')).toEqual(['service', 'severity']);
    expect(insertVariable('Hi {{se', 7, 'severity')).toEqual({
      text: 'Hi {{severity}}',
      caret: 15,
    });
    expect(insertVariable('Hi ', 3, 'app')).toEqual({ text: 'Hi {{app}}', caret: 10 });
  });
});

describe('metric catalog (12 §5.3, server AlertMetric)', () => {
  it('knows every metric with its unit and target types', () => {
    expect(ALERT_METRICS).toHaveLength(31);
    expect(METRIC_CATALOG.error_rate).toMatchObject({
      unit: 'ratio',
      request: true,
      preview: true,
    });
    expect(METRIC_CATALOG.tps).toMatchObject({ unit: 'per_second', request: false });
    expect(METRIC_CATALOG.ext_call_p95.targets).toEqual(['ext_host']);
    expect(METRIC_CATALOG.instance_state).toMatchObject({ unit: 'state', preview: false });
    expect(METRIC_CATALOG.batch_elapsed_seconds.preview).toBe(false);
    expect(METRIC_CATALOG.agent_token_expiry_days).toMatchObject({
      unit: 'days',
      targets: ['server'],
    });
    expect(METRIC_CATALOG.error_group_new.available).toBe(false);
    expect(metricInfo('no_such_metric')).toBeNull();
  });
});
