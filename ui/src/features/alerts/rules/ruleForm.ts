// SPDX-License-Identifier: Apache-2.0
//
// Form model of the rule editor (05 §2.4-1, docs/14 §4.13 rule schema): the editable text of every
// field, built from a saved rule or a template part and turned back into an AlertRuleRequest.
// Thresholds are entered in the metric's display unit (ratio as %, metricCatalog.ts); durations
// are the API text ("30s", "5m", "1h30m"). Validation mirrors the server's RuleCodec limits so the
// editor shows errors while typing; the server stays the authority (its 400 details map back to
// the fields with fieldOfDetail). The template diff compares the fields the server compares
// (TemplateDiff.FIELDS) on canonical values (durations in seconds, thresholds as API numbers).
import type {
  AlertRule,
  AlertRuleDefaults,
  AlertRuleOperator,
  AlertRuleRequest,
  AlertTemplateDiff,
} from '../../../api/alertRules';
import { safeHref } from '../../../components/safeHref';

import { forbiddenReferences, TEMPLATE_MAX_CHARS } from './messageTemplate';
import { metricInfo, toApiValue, toInputValue, type AlertTargetType } from './metricCatalog';

export type Operator = AlertRuleOperator;
export type NoData = NonNullable<AlertRuleRequest['no_data']>;
export type EvaluationUnit = NonNullable<AlertRuleRequest['evaluation_unit']>;
export type RuleKind = NonNullable<AlertRuleRequest['kind']>;

export const OPERATORS: readonly Operator[] = ['>', '>=', '<', '<=', '==', '!='];
export const NO_DATA_MODES: readonly NoData[] = ['ignore', 'warn', 'critical'];
export const EVALUATION_UNITS: readonly EvaluationUnit[] = ['app', 'instance'];
export const RULE_KINDS: readonly RuleKind[] = ['HTTP', 'BATCH'];
export const TARGET_TYPES: readonly AlertTargetType[] = [
  'app',
  'instance',
  'service',
  'ext_host',
  'server',
];
export const WEEK_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
export type WeekDay = (typeof WEEK_DAYS)[number];

/** Server limits (RuleCodec). */
export const RULE_LIMITS = {
  nameChars: 128,
  targetIds: 100,
  slots: 14,
  urlChars: 1024,
  reasonChars: 256,
  minWindowS: 5,
  maxWindowS: 24 * 3600,
  maxForS: 24 * 3600,
  minRenotifyS: 5 * 60,
  maxMuteS: 7 * 24 * 3600,
  maxMinSamples: 1_000_000,
} as const;

export interface ActiveTimeSlot {
  readonly days: readonly WeekDay[];
  readonly from: string;
  readonly to: string;
}

export interface MuteScheduleRow {
  readonly cron: string;
  readonly duration: string;
  readonly reason: string;
}

/** The editable state of a rule (every value as the input shows it). */
export interface RuleForm {
  readonly name: string;
  readonly enabled: boolean;
  readonly templateKey: string | null;
  readonly templateVersion: number | null;
  readonly targetType: AlertTargetType;
  readonly appId: number | null;
  /** '' = every environment of the application. */
  readonly env: string;
  /** Instance ids (instance target), comma separated. */
  readonly instanceIds: string;
  /** Service keys (service target), comma separated decimal ids. */
  readonly serviceIds: string;
  /** External host keys (ext_host target), comma separated decimal ids. */
  readonly hostIds: string;
  readonly kind: RuleKind;
  readonly metric: string;
  readonly window: string;
  readonly for: string;
  readonly evaluationUnit: EvaluationUnit;
  readonly operator: Operator;
  /** Display unit (ratio in %). */
  readonly warn: string;
  readonly critical: string;
  readonly minSamples: string;
  readonly noData: NoData;
  readonly recoverThreshold: string;
  readonly recoverFor: string;
  readonly warmupGrace: string;
  readonly activeTime: readonly ActiveTimeSlot[];
  readonly muteSchedules: readonly MuteScheduleRow[];
  readonly channelsWarn: readonly string[];
  readonly channelsCritical: readonly string[];
  readonly renotifyInterval: string;
  readonly runbookUrl: string;
  readonly messageTemplate: string;
}

/** A new rule: error rate of the application (the defaults of the server where it has them). */
export function emptyForm(appId: number | null): RuleForm {
  return {
    name: '',
    enabled: true,
    templateKey: null,
    templateVersion: null,
    targetType: 'app',
    appId,
    env: '',
    instanceIds: '',
    serviceIds: '',
    hostIds: '',
    kind: 'HTTP',
    metric: 'error_rate',
    window: '5m',
    for: '5m',
    evaluationUnit: 'app',
    operator: '>',
    warn: '',
    critical: '',
    minSamples: '20',
    noData: 'ignore',
    recoverThreshold: '',
    recoverFor: '',
    warmupGrace: '3m',
    activeTime: [],
    muteSchedules: [],
    channelsWarn: [],
    channelsCritical: [],
    renotifyInterval: '30m',
    runbookUrl: '',
    messageTemplate: '',
  };
}

function weekDays(days: readonly string[]): WeekDay[] {
  return WEEK_DAYS.filter((day) => days.includes(day));
}

type RuleLike = Pick<
  AlertRuleDefaults,
  | 'name'
  | 'enabled'
  | 'template_key'
  | 'template_version'
  | 'target'
  | 'kind'
  | 'metric'
  | 'window'
  | 'for'
  | 'evaluation_unit'
  | 'condition'
  | 'min_samples'
  | 'no_data'
  | 'recover'
  | 'warmup_grace'
  | 'active_time'
  | 'mute_schedules'
  | 'channels'
  | 'renotify_interval'
  | 'runbook_url'
  | 'message_template'
>;

function idsText(target: RuleLike['target'], type: AlertTargetType): string {
  return target.type === type ? target.ids.join(', ') : '';
}

/** The form of a saved rule or a template part. */
export function formFromRule(rule: RuleLike | AlertRule, appOverride?: number | null): RuleForm {
  const unit = metricInfo(rule.metric)?.unit ?? null;
  return {
    name: rule.name,
    enabled: rule.enabled,
    templateKey: rule.template_key,
    templateVersion: rule.template_version,
    targetType: rule.target.type,
    appId:
      rule.target.type === 'server'
        ? null
        : appOverride !== undefined && appOverride !== null
          ? appOverride
          : rule.target.app_id,
    env: rule.target.env ?? '',
    instanceIds: idsText(rule.target, 'instance'),
    serviceIds: idsText(rule.target, 'service'),
    hostIds: idsText(rule.target, 'ext_host'),
    kind: rule.kind,
    metric: rule.metric,
    window: rule.window,
    for: rule.for,
    evaluationUnit: rule.evaluation_unit,
    operator: rule.condition.operator,
    warn: toInputValue(rule.condition.warn, unit),
    critical: toInputValue(rule.condition.critical, unit),
    minSamples: String(rule.min_samples),
    noData: rule.no_data,
    recoverThreshold: toInputValue(rule.recover?.threshold, unit),
    recoverFor: rule.recover?.for ?? '',
    warmupGrace: rule.warmup_grace,
    activeTime: rule.active_time.map((slot) => ({
      days: weekDays(slot.days),
      from: slot.from,
      to: slot.to,
    })),
    muteSchedules: rule.mute_schedules.map((row) => ({
      cron: row.cron,
      duration: row.duration,
      reason: row.reason ?? '',
    })),
    channelsWarn: [...rule.channels.warn],
    channelsCritical: [...rule.channels.critical],
    renotifyInterval: rule.renotify_interval,
    runbookUrl: rule.runbook_url ?? '',
    messageTemplate: rule.message_template ?? '',
  };
}

// --- durations -----------------------------------------------------------------------------------

const DURATION = /^(?:\d{1,6}[dhms])+$/;
const DURATION_PART = /(\d{1,6})([dhms])/g;
const UNIT_SECONDS: Readonly<Record<string, number>> = { d: 86_400, h: 3_600, m: 60, s: 1 };

/** Seconds of a rule duration ("1h30m", "0"), or null when it is not one. */
export function durationSeconds(text: string): number | null {
  const t = text.trim();
  if (t === '0') {
    return 0;
  }
  if (!DURATION.test(t)) {
    return null;
  }
  let total = 0;
  for (const match of t.matchAll(DURATION_PART)) {
    total += Number(match[1]) * (UNIT_SECONDS[match[2] ?? 's'] ?? 1);
  }
  return total;
}

// --- validation ----------------------------------------------------------------------------------

/** Form field ids (the `alert-rule-field-<id>` test ids use the API field names). */
export type RuleField =
  | 'name'
  | 'metric'
  | 'kind'
  | 'target_type'
  | 'target_app'
  | 'target_env'
  | 'target_instance'
  | 'target_service'
  | 'target_host'
  | 'window'
  | 'for'
  | 'evaluation_unit'
  | 'operator'
  | 'warn'
  | 'critical'
  | 'min_samples'
  | 'no_data'
  | 'recover_threshold'
  | 'recover_for'
  | 'warmup_grace'
  | 'active_time'
  | 'mute_schedules'
  | 'channels_warn'
  | 'channels_critical'
  | 'renotify_interval'
  | 'runbook_url'
  | 'message_template';

/** Error codes of the client checks (and of mapped server details); the editor translates them. */
export type FieldError =
  | 'required'
  | 'length'
  | 'duration'
  | 'outOfRange'
  | 'number'
  | 'thresholdRequired'
  | 'notBeyondWarn'
  | 'ids'
  | 'notForTarget'
  | 'notForMetric'
  | 'url'
  | 'slot'
  | 'templateForbidden'
  | 'invalid';

export type FieldErrors = Partial<Record<RuleField, FieldError>>;

const IDS = /^\d{1,20}$/;
const HH_MM = /^(?:[01]\d|2[0-3]):[0-5]\d$|^24:00$/;

/** The ids of a comma separated list (blank entries dropped). */
export function parseIds(text: string): string[] {
  return text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '');
}

function idsField(type: AlertTargetType): RuleField | null {
  switch (type) {
    case 'instance':
      return 'target_instance';
    case 'service':
      return 'target_service';
    case 'ext_host':
      return 'target_host';
    case 'app':
    case 'server':
      return null;
  }
}

function idsOf(form: RuleForm): string {
  switch (form.targetType) {
    case 'instance':
      return form.instanceIds;
    case 'service':
      return form.serviceIds;
    case 'ext_host':
      return form.hostIds;
    case 'app':
    case 'server':
      return '';
  }
}

function checkDuration(
  errors: FieldErrors,
  field: RuleField,
  text: string,
  bounds: { min?: number; max?: number; allowEmpty?: boolean; zeroAllowed?: boolean },
): void {
  if (text.trim() === '') {
    if (bounds.allowEmpty !== true) {
      errors[field] = 'required';
    }
    return;
  }
  const seconds = durationSeconds(text);
  if (seconds === null) {
    errors[field] = 'duration';
    return;
  }
  if (seconds === 0 && bounds.zeroAllowed === true) {
    return;
  }
  if (
    (bounds.min !== undefined && seconds < bounds.min) ||
    (bounds.max !== undefined && seconds > bounds.max)
  ) {
    errors[field] = 'outOfRange';
  }
}

function checkNumber(errors: FieldErrors, field: RuleField, text: string): void {
  if (text.trim() !== '' && !Number.isFinite(Number(text.trim()))) {
    errors[field] = 'number';
  }
}

/** Client checks of every field (the server's RuleCodec rules). */
export function validateForm(form: RuleForm): FieldErrors {
  const errors: FieldErrors = {};
  const name = form.name.trim();
  if (name === '') {
    errors.name = 'required';
  } else if (form.name.length > RULE_LIMITS.nameChars) {
    errors.name = 'length';
  }
  const info = metricInfo(form.metric);
  if (form.metric === '') {
    errors.metric = 'required';
  } else if (info !== null && !info.targets.includes(form.targetType)) {
    errors.target_type = 'notForMetric';
  }
  if (form.targetType !== 'server' && form.appId === null) {
    errors.target_app = 'required';
  }
  const field = idsField(form.targetType);
  if (field !== null) {
    const ids = parseIds(idsOf(form));
    if (ids.length > RULE_LIMITS.targetIds || ids.some((id) => !IDS.test(id))) {
      errors[field] = 'ids';
    }
  }
  checkDuration(errors, 'window', form.window, {
    min: RULE_LIMITS.minWindowS,
    max: RULE_LIMITS.maxWindowS,
  });
  checkDuration(errors, 'for', form.for, { max: RULE_LIMITS.maxForS, zeroAllowed: true });
  if (
    form.evaluationUnit === 'instance' &&
    form.targetType !== 'app' &&
    form.targetType !== 'instance'
  ) {
    errors.evaluation_unit = 'notForTarget';
  }
  checkNumber(errors, 'warn', form.warn);
  checkNumber(errors, 'critical', form.critical);
  if (errors.warn === undefined && errors.critical === undefined) {
    const warn = toApiValue(form.warn, null);
    const critical = toApiValue(form.critical, null);
    if (warn === null && critical === null) {
      errors.warn = 'thresholdRequired';
    } else if (warn !== null && critical !== null) {
      const ordered =
        form.operator === '>' || form.operator === '>='
          ? warn <= critical
          : form.operator === '<' || form.operator === '<='
            ? warn >= critical
            : true;
      if (!ordered) {
        errors.critical = 'notBeyondWarn';
      }
    }
  }
  const minSamples = form.minSamples.trim();
  if (minSamples !== '') {
    const n = Number(minSamples);
    if (!Number.isInteger(n) || n < 0 || n > RULE_LIMITS.maxMinSamples) {
      errors.min_samples = 'number';
    }
  }
  checkNumber(errors, 'recover_threshold', form.recoverThreshold);
  checkDuration(errors, 'recover_for', form.recoverFor, {
    max: RULE_LIMITS.maxForS,
    allowEmpty: true,
    zeroAllowed: true,
  });
  checkDuration(errors, 'warmup_grace', form.warmupGrace, {
    max: RULE_LIMITS.maxForS,
    zeroAllowed: true,
  });
  if (
    form.activeTime.length > RULE_LIMITS.slots ||
    form.activeTime.some(
      (slot) =>
        slot.days.length === 0 ||
        !HH_MM.test(slot.from) ||
        slot.from === '24:00' ||
        !HH_MM.test(slot.to) ||
        slot.to <= slot.from,
    )
  ) {
    errors.active_time = 'slot';
  }
  if (
    form.muteSchedules.length > RULE_LIMITS.slots ||
    form.muteSchedules.some((row) => {
      const seconds = durationSeconds(row.duration);
      return (
        row.cron.trim().split(/\s+/).length !== 5 ||
        seconds === null ||
        seconds === 0 ||
        seconds > RULE_LIMITS.maxMuteS ||
        row.reason.length > RULE_LIMITS.reasonChars
      );
    })
  ) {
    errors.mute_schedules = 'slot';
  }
  const renotify = durationSeconds(form.renotifyInterval);
  if (form.renotifyInterval.trim() === '') {
    errors.renotify_interval = 'required';
  } else if (renotify === null) {
    errors.renotify_interval = 'duration';
  } else if (renotify !== 0 && renotify < RULE_LIMITS.minRenotifyS) {
    errors.renotify_interval = 'outOfRange';
  }
  const runbook = form.runbookUrl.trim();
  if (
    runbook !== '' &&
    (runbook.length > RULE_LIMITS.urlChars ||
      !/^https?:\/\//i.test(runbook) ||
      safeHref(runbook) === undefined)
  ) {
    errors.runbook_url = 'url';
  }
  if (form.messageTemplate.length > TEMPLATE_MAX_CHARS) {
    errors.message_template = 'length';
  } else if (forbiddenReferences(form.messageTemplate).length > 0) {
    errors.message_template = 'templateForbidden';
  }
  return errors;
}

/** True when the rule part the preview evaluates (target, metric, condition) is valid. */
export function previewable(errors: FieldErrors): boolean {
  const relevant: readonly RuleField[] = [
    'metric',
    'target_type',
    'target_app',
    'target_instance',
    'target_service',
    'target_host',
    'window',
    'for',
    'evaluation_unit',
    'warn',
    'critical',
    'min_samples',
  ];
  return relevant.every((field) => errors[field] === undefined);
}

// --- request -------------------------------------------------------------------------------------

/** The API rule of a form (call after validateForm found no errors). */
export function toRequest(form: RuleForm): AlertRuleRequest {
  const unit = metricInfo(form.metric)?.unit ?? null;
  const target: AlertRuleRequest['target'] = { type: form.targetType, ids: [] };
  if (form.targetType !== 'server' && form.appId !== null) {
    target.app_id = form.appId;
  }
  if (form.targetType !== 'server' && form.env.trim() !== '') {
    target.env = form.env.trim();
  }
  target.ids = parseIds(idsOf(form));
  const condition: AlertRuleRequest['condition'] = { operator: form.operator };
  const warn = toApiValue(form.warn, unit);
  const critical = toApiValue(form.critical, unit);
  if (warn !== null) {
    condition.warn = warn;
  }
  if (critical !== null) {
    condition.critical = critical;
  }
  const body: AlertRuleRequest = {
    name: form.name.trim(),
    enabled: form.enabled,
    target,
    kind: form.kind,
    metric: form.metric,
    window: form.window.trim(),
    for: form.for.trim(),
    evaluation_unit: form.evaluationUnit,
    condition,
    no_data: form.noData,
    warmup_grace: form.warmupGrace.trim(),
    active_time: form.activeTime.map((slot) => ({
      days: [...slot.days],
      from: slot.from,
      to: slot.to,
    })),
    mute_schedules: form.muteSchedules.map((row) =>
      row.reason.trim() === ''
        ? { cron: row.cron.trim(), duration: row.duration.trim() }
        : { cron: row.cron.trim(), duration: row.duration.trim(), reason: row.reason },
    ),
    channels: { warn: [...form.channelsWarn], critical: [...form.channelsCritical] },
    renotify_interval: form.renotifyInterval.trim(),
  };
  if (form.templateKey !== null) {
    body.template_key = form.templateKey;
  }
  if (form.templateVersion !== null) {
    body.template_version = form.templateVersion;
  }
  if (form.minSamples.trim() !== '') {
    body.min_samples = Number(form.minSamples.trim());
  }
  const recoverThreshold = toApiValue(form.recoverThreshold, unit);
  if (recoverThreshold !== null || form.recoverFor.trim() !== '') {
    const recover: NonNullable<AlertRuleRequest['recover']> = {};
    if (recoverThreshold !== null) {
      recover.threshold = recoverThreshold;
    }
    if (form.recoverFor.trim() !== '') {
      recover.for = form.recoverFor.trim();
    }
    body.recover = recover;
  }
  if (form.runbookUrl.trim() !== '') {
    body.runbook_url = form.runbookUrl.trim();
  }
  if (form.messageTemplate.trim() !== '') {
    body.message_template = form.messageTemplate;
  }
  return body;
}

/**
 * The rule the preview evaluates: the request without the parts the evaluation does not use
 * (channels, runbook, message template, schedules), with a placeholder name while none is typed.
 */
export function toPreviewRequest(form: RuleForm, placeholderName: string): AlertRuleRequest {
  const body: AlertRuleRequest = { ...toRequest(form) };
  delete body.channels;
  delete body.runbook_url;
  delete body.message_template;
  return { ...body, name: body.name === '' ? placeholderName : body.name };
}

// --- server errors --------------------------------------------------------------------------------

/** The form field of a server error detail (`target.app_id`, `condition.warn`, ...), or null. */
export function fieldOfDetail(field: string | null): RuleField | null {
  if (field === null) {
    return null;
  }
  const head = field.replace(/\[\d+\].*$/, '');
  switch (head) {
    case 'name':
    case 'metric':
    case 'kind':
    case 'window':
    case 'for':
    case 'evaluation_unit':
    case 'min_samples':
    case 'no_data':
    case 'warmup_grace':
    case 'active_time':
    case 'mute_schedules':
    case 'renotify_interval':
    case 'runbook_url':
    case 'message_template':
      return head;
    case 'target.app_id':
      return 'target_app';
    case 'target.env':
      return 'target_env';
    case 'target.type':
      return 'target_type';
    case 'target.ids':
      return 'target_instance';
    case 'condition':
    case 'condition.warn':
      return 'warn';
    case 'condition.critical':
      return 'critical';
    case 'condition.operator':
      return 'operator';
    case 'recover':
    case 'recover.threshold':
      return 'recover_threshold';
    case 'recover.for':
      return 'recover_for';
    case 'channels':
    case 'channels.warn':
      return 'channels_warn';
    case 'channels.critical':
      return 'channels_critical';
    default:
      return null;
  }
}

/** Field error code of a server detail reason. */
export function errorOfReason(reason: string): FieldError {
  switch (reason) {
    case 'required':
      return 'required';
    case 'length':
      return 'length';
    case 'invalid_duration':
      return 'duration';
    case 'out_of_range':
      return 'outOfRange';
    case 'threshold_required':
      return 'thresholdRequired';
    case 'not_beyond_warn':
      return 'notBeyondWarn';
    case 'not_for_target':
      return 'notForTarget';
    case 'not_for_metric':
      return 'notForMetric';
    case 'invalid_url':
      return 'url';
    case 'variable_not_allowed':
      return 'templateForbidden';
    default:
      return 'invalid';
  }
}

// --- template differences ----------------------------------------------------------------------

/** Fields the server compares with the template (TemplateDiff.FIELDS). */
export const DIFF_FIELDS = [
  'kind',
  'metric',
  'window',
  'for',
  'evaluation_unit',
  'condition',
  'recover',
  'min_samples',
  'no_data',
  'warmup_grace',
  'active_time',
  'mute_schedules',
  'renotify_interval',
  'message_template',
] as const;
export type DiffField = (typeof DIFF_FIELDS)[number];

function canonicalDuration(text: string | null | undefined): number | string | null {
  if (text === null || text === undefined || text.trim() === '') {
    return null;
  }
  return durationSeconds(text) ?? text.trim();
}

type Comparable = Pick<
  AlertRuleRequest,
  | 'kind'
  | 'metric'
  | 'window'
  | 'for'
  | 'evaluation_unit'
  | 'condition'
  | 'recover'
  | 'min_samples'
  | 'no_data'
  | 'warmup_grace'
  | 'active_time'
  | 'mute_schedules'
  | 'renotify_interval'
  | 'message_template'
>;

function canonical(field: DiffField, rule: Comparable): unknown {
  switch (field) {
    case 'kind':
      return rule.kind ?? 'HTTP';
    case 'metric':
      return rule.metric;
    case 'window':
    case 'for':
    case 'warmup_grace':
    case 'renotify_interval':
      return canonicalDuration(rule[field]);
    case 'evaluation_unit':
      return rule.evaluation_unit ?? 'app';
    case 'condition':
      return [
        rule.condition.operator,
        rule.condition.warn ?? null,
        rule.condition.critical ?? null,
      ];
    case 'recover':
      return rule.recover === undefined
        ? null
        : [rule.recover.threshold ?? null, canonicalDuration(rule.recover.for)];
    case 'min_samples':
      return rule.min_samples ?? null;
    case 'no_data':
      return rule.no_data ?? 'ignore';
    case 'active_time':
      return (rule.active_time ?? []).map((slot) => [weekDays(slot.days), slot.from, slot.to]);
    case 'mute_schedules':
      return (rule.mute_schedules ?? []).map((row) => [
        row.cron.trim(),
        canonicalDuration(row.duration),
        row.reason ?? null,
      ]);
    case 'message_template':
      return rule.message_template === undefined || rule.message_template.trim() === ''
        ? null
        : rule.message_template;
  }
}

/** The template defaults as a comparable request. */
function defaultsComparable(defaults: AlertRuleDefaults): Comparable {
  return {
    kind: defaults.kind,
    metric: defaults.metric,
    window: defaults.window,
    for: defaults.for,
    evaluation_unit: defaults.evaluation_unit,
    condition: {
      operator: defaults.condition.operator,
      ...(defaults.condition.warn === null ? {} : { warn: defaults.condition.warn }),
      ...(defaults.condition.critical === null ? {} : { critical: defaults.condition.critical }),
    },
    ...(defaults.recover === null ||
    (defaults.recover.threshold === null && defaults.recover.for === null)
      ? {}
      : {
          recover: {
            ...(defaults.recover.threshold === null
              ? {}
              : { threshold: defaults.recover.threshold }),
            ...(defaults.recover.for === null ? {} : { for: defaults.recover.for }),
          },
        }),
    min_samples: defaults.min_samples,
    no_data: defaults.no_data,
    warmup_grace: defaults.warmup_grace,
    active_time: defaults.active_time,
    mute_schedules: defaults.mute_schedules.map((row) =>
      row.reason === null
        ? { cron: row.cron, duration: row.duration }
        : { ...row, reason: row.reason },
    ),
    renotify_interval: defaults.renotify_interval,
    ...(defaults.message_template === null ? {} : { message_template: defaults.message_template }),
  };
}

/** Fields of the form that differ from the template part (the unsaved "changed" dots). */
export function diffFromTemplate(form: RuleForm, defaults: AlertRuleDefaults): DiffField[] {
  const current = toRequest(form);
  const template = defaultsComparable(defaults);
  return DIFF_FIELDS.filter(
    (field) =>
      JSON.stringify(canonical(field, current)) !== JSON.stringify(canonical(field, template)),
  );
}

/** Fields of a saved rule's server diff that the form marks. */
export function diffFieldsOf(diff: AlertTemplateDiff | null): DiffField[] {
  if (diff === null) {
    return [];
  }
  const fields = new Set(diff.changes.map((change) => change.field));
  return DIFF_FIELDS.filter((field) => fields.has(field));
}

/** The form field that carries the dot of a diff field. */
export function dotFieldOf(field: DiffField): RuleField {
  switch (field) {
    case 'condition':
      return 'operator';
    case 'recover':
      return 'recover_threshold';
    default:
      return field;
  }
}
