// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the alert rule screens (docs/14 §4.13, docs/15 §9.9, 05 §2.4, WO-38) through the
// generated client: the rule list, one rule with its history and template differences, create,
// replace, delete (soft), enable / disable (distinct from delete), clone to another application
// and environment, the 7-day preview of an unsaved rule, the default templates and their bulk
// apply, plus the channel names a rule chooses from (`GET /alerts/channels`: names and ids only,
// a channel's URL never enters the rule screens). No hand-written requests (03 §3.3); errors come
// back as ApiError (docs/14 §2.6: 422 template_variable_forbidden, 400 metric/preview_unsupported,
// 429 with Retry-After).
//
// Query keys live here under the `alert-rules` prefix (queryKeys.ts belongs to the history and
// channel screens); a rule change invalidates the prefix.
import { api, expectNoContent, unwrap, type EnvelopeOf } from './client';
import type { components } from './generated/schema';

export type AlertRule = components['schemas']['AlertRule'];
export type AlertRuleDetail = components['schemas']['AlertRuleDetail'];
export type AlertRuleRequest = components['schemas']['AlertRuleRequest'];
export type AlertRulePreview = components['schemas']['AlertRulePreview'];
export type AlertRuleHistoryEntry = components['schemas']['AlertRuleHistoryEntry'];
export type AlertTemplate = components['schemas']['AlertTemplate'];
export type AlertTemplatePart = components['schemas']['AlertTemplatePart'];
export type AlertRuleDefaults = components['schemas']['AlertRuleDefaults'];
export type AlertTemplateDiff = components['schemas']['AlertTemplateDiff'];
export type AlertTemplatesApplied = components['schemas']['AlertTemplatesApplied'];
export type AlertChannelView = components['schemas']['ChannelView'];
export type AlertTargetType = components['schemas']['AlertRuleTargetRequest']['type'];
export type AlertRuleOperator = components['schemas']['AlertRuleConditionRequest']['operator'];
/** Metric wire names of the contract (`AlertRuleRef.metric` enum, ADR-0010 C-227 (4)). */
export type AlertMetricWire = components['schemas']['AlertRuleRef']['metric'];
/** Units of the contract (`AlertRuleRef.unit` enum). */
export type AlertMetricUnitWire = NonNullable<components['schemas']['AlertRuleRef']['unit']>;

/** Days of the rule preview (05 §2.4-2: the last 7 days; the server's maximum). */
export const PREVIEW_DAYS = 7;

export interface AlertRuleListFilter {
  readonly app?: number | undefined;
  readonly env?: string | undefined;
  /** Only enabled (true) or only disabled (false) rules; undefined: both. */
  readonly enabled?: boolean | undefined;
}

export const alertRuleKeys = {
  all: () => ['alert-rules'] as const,
  list: (filter: AlertRuleListFilter = {}) =>
    [
      'alert-rules',
      'list',
      { app: filter.app ?? null, env: filter.env ?? null, enabled: filter.enabled ?? null },
    ] as const,
  detail: (ruleId: string) => ['alert-rules', 'detail', ruleId] as const,
  templates: () => ['alert-rules', 'templates'] as const,
  channels: () => ['alert-rules', 'channels'] as const,
} as const;

interface ListQuery {
  app?: number;
  env?: string;
  enabled?: boolean;
}

/** `GET /alerts/rules`: rules that are not deleted, newest first, at most 1,000. */
export async function listAlertRules(
  filter: AlertRuleListFilter = {},
  signal?: AbortSignal,
): Promise<readonly AlertRule[]> {
  const query: ListQuery = {};
  if (filter.app !== undefined) {
    query.app = filter.app;
  }
  if (filter.env !== undefined) {
    query.env = filter.env;
  }
  if (filter.enabled !== undefined) {
    query.enabled = filter.enabled;
  }
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules', 'get'>>(
    api().client.GET('/api/v1/alerts/rules', {
      params: { query },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** `GET /alerts/rules/{ruleId}`: the rule, its 20 newest versions and template differences. */
export async function getAlertRule(ruleId: string, signal?: AbortSignal): Promise<AlertRuleDetail> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules/{ruleId}', 'get'>>(
    api().client.GET('/api/v1/alerts/rules/{ruleId}', {
      params: { path: { ruleId } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** `POST /alerts/rules` (201). */
export async function createAlertRule(body: AlertRuleRequest): Promise<AlertRule> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules', 'post'>>(
    api().client.POST('/api/v1/alerts/rules', { body }),
  );
  return result.data;
}

/** `PUT /alerts/rules/{ruleId}`: the whole rule. */
export async function updateAlertRule(ruleId: string, body: AlertRuleRequest): Promise<AlertRule> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules/{ruleId}', 'put'>>(
    api().client.PUT('/api/v1/alerts/rules/{ruleId}', { params: { path: { ruleId } }, body }),
  );
  return result.data;
}

/** `DELETE /alerts/rules/{ruleId}` (soft delete; its events stay readable). */
export async function deleteAlertRule(ruleId: string): Promise<void> {
  await expectNoContent(
    api().client.DELETE('/api/v1/alerts/rules/{ruleId}', { params: { path: { ruleId } } }),
  );
}

/** `POST /alerts/rules/{ruleId}/enable` or `/disable` (a disabled rule stays, unlike delete). */
export async function setAlertRuleEnabled(ruleId: string, enabled: boolean): Promise<void> {
  const params = { params: { path: { ruleId } } };
  await expectNoContent(
    enabled
      ? api().client.POST('/api/v1/alerts/rules/{ruleId}/enable', params)
      : api().client.POST('/api/v1/alerts/rules/{ruleId}/disable', params),
  );
}

/** `POST /alerts/rules/{ruleId}/clone`: a copy for another application and environment (201). */
export async function cloneAlertRule(
  ruleId: string,
  target: { readonly app: number; readonly env: string | null },
): Promise<AlertRule> {
  const body: components['schemas']['AlertRuleCloneRequest'] =
    target.env === null ? { app: target.app } : { app: target.app, env: target.env };
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules/{ruleId}/clone', 'post'>>(
    api().client.POST('/api/v1/alerts/rules/{ruleId}/clone', {
      params: { path: { ruleId } },
      body,
    }),
  );
  return result.data;
}

/** `POST /alerts/rules/preview`: the firings of an unsaved rule over the last `days` (≤ 7). */
export async function previewAlertRule(
  rule: AlertRuleRequest,
  signal?: AbortSignal,
  days: number = PREVIEW_DAYS,
): Promise<AlertRulePreview> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/rules/preview', 'post'>>(
    api().client.POST('/api/v1/alerts/rules/preview', {
      body: { rule, days },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** `GET /alerts/templates`: the 14 default templates with their parts (rules). */
export async function listAlertTemplates(signal?: AbortSignal): Promise<readonly AlertTemplate[]> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/templates', 'get'>>(
    api().client.GET('/api/v1/alerts/templates', signal === undefined ? {} : { signal }),
  );
  return result.data;
}

/** `POST /alerts/templates/apply`: creates the application rules of the templates. */
export async function applyAlertTemplates(request: {
  readonly app: number;
  readonly env?: string | undefined;
  readonly keys?: readonly string[] | undefined;
}): Promise<AlertTemplatesApplied> {
  const body: components['schemas']['AlertTemplatesApplyRequest'] = { app: request.app };
  if (request.env !== undefined) {
    body.env = request.env;
  }
  if (request.keys !== undefined) {
    body.keys = [...request.keys];
  }
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/templates/apply', 'post'>>(
    api().client.POST('/api/v1/alerts/templates/apply', { body }),
  );
  return result.data;
}

/** A channel as the rule screens see it: id, name, type and state, never its destination. */
export interface AlertChannelChoice {
  readonly id: string;
  readonly name: AlertChannelView['name'];
  readonly type: AlertChannelView['type'];
  readonly enabled: boolean;
}

/** `GET /alerts/channels` reduced to the choices of a rule (the masked target is dropped). */
export async function listAlertChannelChoices(
  signal?: AbortSignal,
): Promise<readonly AlertChannelChoice[]> {
  const result = await unwrap<EnvelopeOf<'/api/v1/alerts/channels', 'get'>>(
    api().client.GET('/api/v1/alerts/channels', signal === undefined ? {} : { signal }),
  );
  return result.data.map((channel) => ({
    id: channel.channel_id,
    name: channel.name,
    type: channel.type,
    enabled: channel.enabled,
  }));
}
