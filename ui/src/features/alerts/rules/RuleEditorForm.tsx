// SPDX-License-Identifier: Apache-2.0
//
// The fields of the rule editor (05 §2.4-1, docs/15 §9.9 sections): target, kind and metric /
// condition (window, for, evaluation unit, operator with warn and critical thresholds in the
// metric's unit, minimum samples, no-data handling, recovery threshold and duration, warm-up
// grace) / schedule (active time slots, recurring mute schedules) / notification (channels per
// severity chosen by name, renotify interval, runbook URL, message template with the allowed
// variables only). Each field is a label, the control and its error or hint (plain elements: the
// lint keeps Ant Design inside src/components, F-U16); every control carries the test id `alert-rule-field-<API field>`. A field that differs from the
// template the rule was made from has a dot (05 §2.4-3). Collected or entered names (applications,
// channels) render through SafeText.
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { AlertChannelChoice } from '../../../api/alertRules';
import type { App } from '../../../api/apps';
import { safeHref } from '../../../components/safeHref';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { EnvSelect, AppSelect  } from '../../../routes/Shell';
import { channelTypeLabel } from '../common/labels';

import { useMetricLabel, useRuleLabels } from './labels';
import { MessageTemplateField } from './MessageTemplateField';
import { ALERT_METRICS, metricInfo, METRIC_CATALOG, type AlertTargetType } from './metricCatalog';
import {
  EVALUATION_UNITS,
  NO_DATA_MODES,
  OPERATORS,
  RULE_KINDS,
  TARGET_TYPES,
  WEEK_DAYS,
  type ActiveTimeSlot,
  type FieldErrors,
  type MuteScheduleRow,
  type RuleField,
  type RuleForm,
  type WeekDay,
} from './ruleForm';

export interface RuleEditorFormProps {
  readonly form: RuleForm;
  readonly onChange: (patch: Partial<RuleForm>) => void;
  readonly errors: FieldErrors;
  /** Form fields that carry a "differs from the template" dot. */
  readonly diffDots: ReadonlyMap<RuleField, string>;
  readonly apps: readonly App[];
  readonly channels: readonly AlertChannelChoice[];
  readonly channelsLoading: boolean;
  /** Text of the template error (client check or server 422), or null. */
  readonly templateError: string | null;
}

interface FieldProps {
  readonly field: RuleField;
  readonly label: string;
  readonly errors: FieldErrors;
  readonly diffDots: ReadonlyMap<RuleField, string>;
  readonly hint?: string | undefined;
  readonly htmlFor?: string | undefined;
  readonly children: ReactNode;
}

/** "Differs from the template" dot after a label (05 §2.4-3). */
function DiffDot({ field }: { readonly field: string }) {
  const { t } = useTranslation(['common', 'alerts']);
  return (
    <span
      className="alert-rule-diff-dot"
      data-testid="alert-rule-diff-dot"
      data-field={field}
      title={t('alerts:rules.templates.changed')}
      role="img"
      aria-label={t('alerts:rules.templates.changed')}
    />
  );
}

/** Label, control, then the error (or the hint) of one field. */
function Field({ field, label, errors, diffDots, hint, htmlFor, children }: FieldProps) {
  const labels = useRuleLabels();
  const error = errors[field];
  const diff = diffDots.get(field);
  const helpId = useId();
  return (
    <div
      className={`alert-rule-field${error === undefined ? '' : ' alert-rule-field--error'}`}
      data-field={field}
    >
      {htmlFor === undefined ? (
        <span className="alert-rule-field__label">
          {label}
          {diff === undefined ? null : <DiffDot field={diff} />}
        </span>
      ) : (
        <label className="alert-rule-field__label" htmlFor={htmlFor}>
          {label}
          {diff === undefined ? null : <DiffDot field={diff} />}
        </label>
      )}
      <div className="alert-rule-field__control">{children}</div>
      {error === undefined ? (
        hint === undefined ? null : (
          <p id={helpId} className="alert-rule-field__hint">
            {hint}
          </p>
        )
      ) : (
        <p
          id={helpId}
          className="alert-rule-field__error"
          role="alert"
          data-testid={`alert-rule-error-${field}`}
        >
          {labels.error(error)}
        </p>
      )}
    </div>
  );
}

const THRESHOLD_FIELDS = ['warn', 'critical'] as const;
const CHANNEL_FIELDS = ['channels_warn', 'channels_critical'] as const;
const CHANNEL_KEYS = {
  channels_warn: 'channelsWarn',
  channels_critical: 'channelsCritical',
} as const;
const CHANNEL_SEVERITY = { channels_warn: 'warn', channels_critical: 'critical' } as const;

function toggle<T>(list: readonly T[], value: T): T[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

export function RuleEditorForm({
  form,
  onChange,
  errors,
  diffDots,
  apps,
  channels,
  channelsLoading,
  templateError,
}: RuleEditorFormProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const labels = useRuleLabels();
  const metricLabel = useMetricLabel();
  const id = useId();
  const fieldId = (field: RuleField): string => `${id}-${field}`;
  const info = metricInfo(form.metric);
  const unitSuffix =
    info === null
      ? ''
      : info.unit === 'ratio'
        ? '%'
        : info.unit === 'ms'
          ? 'ms'
          : info.unit === 'seconds'
            ? t('alerts:rules.unit.seconds')
            : info.unit === 'days'
              ? t('alerts:rules.unit.days')
              : info.unit === 'per_second'
                ? '/s'
                : '';
  const allowedTargets: readonly AlertTargetType[] = info?.targets ?? TARGET_TYPES;
  const envs: string[] = [...new Set<string>(apps.map((app) => app.env))];
  const common = { errors, diffDots };
  const templateLabelId = `${id}-template-label`;

  const setSlot = (index: number, patch: Partial<ActiveTimeSlot>): void => {
    onChange({
      activeTime: form.activeTime.map((slot, i) => (i === index ? { ...slot, ...patch } : slot)),
    });
  };
  const setMute = (index: number, patch: Partial<MuteScheduleRow>): void => {
    onChange({
      muteSchedules: form.muteSchedules.map((row, i) => (i === index ? { ...row, ...patch } : row)),
    });
  };
  const idsInput = (
    field: 'target_instance' | 'target_service' | 'target_host',
    type: AlertTargetType,
    value: string,
    key: 'instanceIds' | 'serviceIds' | 'hostIds',
  ) => (
    <Field
      field={field}
      label={t(`alerts:rules.fields.${field}`)}
      hint={
        form.targetType === type
          ? t('alerts:rules.hints.ids')
          : t('alerts:rules.hints.idsOtherType', { type: labels.targetType(type) })
      }
      htmlFor={fieldId(field)}
      {...common}
    >
      <input
        id={fieldId(field)}
        className="alert-rule-input"
        value={value}
        disabled={form.targetType !== type}
        data-testid={`alert-rule-field-${field}`}
        onChange={(event) => {
          onChange({ [key]: event.target.value });
        }}
      />
    </Field>
  );

  return (
    <div className="alert-rule-form">
      <section className="alert-rule-section" data-testid="alert-rule-section-target">
        <h2 className="alert-rule-section__title">{t('alerts:rules.sections.target')}</h2>
        <div className="alert-rule-grid">
          <Field
            field="name"
            label={t('alerts:rules.fields.name')}
            htmlFor={fieldId('name')}
            {...common}
          >
            <input
              id={fieldId('name')}
              className="alert-rule-input"
              value={form.name}
              maxLength={128}
              data-testid="alert-rule-field-name"
              onChange={(event) => {
                onChange({ name: event.target.value });
              }}
            />
          </Field>
          <div className="alert-rule-field">
            <span className="alert-rule-field__label">{t('alerts:rules.fields.enabled')}</span>
            <label className="alert-rule-check">
              <input
                type="checkbox"
                checked={form.enabled}
                data-testid="alert-rule-field-enabled"
                onChange={(event) => {
                  onChange({ enabled: event.target.checked });
                }}
              />
              {form.enabled ? t('alerts:rules.enabled.on') : t('alerts:rules.enabled.off')}
            </label>
          </div>
          <Field
            field="metric"
            label={t('alerts:rules.fields.metric')}
            htmlFor={fieldId('metric')}
            hint={
              info !== null && !info.available
                ? t('alerts:rules.hints.metricUnavailable')
                : undefined
            }
            {...common}
          >
            <select
              id={fieldId('metric')}
              className="alert-rule-input"
              value={form.metric}
              data-testid="alert-rule-field-metric"
              onChange={(event) => {
                const metric = event.target.value;
                const next = metricInfo(metric);
                const patch: { -readonly [K in keyof RuleForm]?: RuleForm[K] } = { metric };
                if (next !== null && !next.targets.includes(form.targetType)) {
                  patch.targetType = next.targets[0] ?? 'app';
                }
                if (next !== null && !next.request) {
                  patch.minSamples = '0';
                }
                onChange(patch);
              }}
            >
              {info === null && form.metric !== '' ? (
                <option value={form.metric}>{form.metric}</option>
              ) : null}
              {ALERT_METRICS.map((metric) => (
                <option key={metric} value={metric}>
                  {metricLabel(metric)}
                  {METRIC_CATALOG[metric].available
                    ? ''
                    : ` (${t('alerts:rules.hints.notComputed')})`}
                </option>
              ))}
            </select>
          </Field>
          <Field
            field="kind"
            label={t('alerts:rules.fields.kind')}
            htmlFor={fieldId('kind')}
            {...common}
          >
            <select
              id={fieldId('kind')}
              className="alert-rule-input"
              value={form.kind}
              data-testid="alert-rule-field-kind"
              onChange={(event) => {
                onChange({ kind: event.target.value as RuleForm['kind'] });
              }}
            >
              {RULE_KINDS.map((kind) => (
                <option key={kind} value={kind}>
                  {labels.kind(kind)}
                </option>
              ))}
            </select>
          </Field>
          <Field
            field="target_type"
            label={t('alerts:rules.fields.target_type')}
            htmlFor={fieldId('target_type')}
            {...common}
          >
            <select
              id={fieldId('target_type')}
              className="alert-rule-input"
              value={form.targetType}
              data-testid="alert-rule-field-target_type"
              onChange={(event) => {
                const targetType = event.target.value as AlertTargetType;
                onChange(
                  targetType === 'server'
                    ? { targetType, appId: null, env: '' }
                    : targetType === 'app' || targetType === 'instance'
                      ? { targetType }
                      : { targetType, evaluationUnit: 'app' },
                );
              }}
            >
              {TARGET_TYPES.map((type) => (
                <option key={type} value={type} disabled={!allowedTargets.includes(type)}>
                  {labels.targetType(type)}
                </option>
              ))}
            </select>
          </Field>
          <Field field="target_app" label={t('alerts:rules.fields.target_app')} {...common}>
            <span className="alert-rule-select" data-testid="alert-rule-field-target_app">
              <AppSelect
                apps={apps}
                value={form.appId}
                disabled={form.targetType === 'server'}
                onChange={(appId) => {
                  onChange({ appId });
                }}
              />
            </span>
          </Field>
          <Field
            field="target_env"
            label={t('alerts:rules.fields.target_env')}
            hint={t('alerts:rules.hints.env')}
            {...common}
          >
            <span className="alert-rule-select" data-testid="alert-rule-field-target_env">
              <EnvSelect
                envs={envs}
                value={form.env === '' ? null : form.env}
                disabled={form.targetType === 'server'}
                onChange={(env) => {
                  onChange({ env: env ?? '' });
                }}
              />
            </span>
          </Field>
          {idsInput('target_instance', 'instance', form.instanceIds, 'instanceIds')}
          {idsInput('target_service', 'service', form.serviceIds, 'serviceIds')}
          {idsInput('target_host', 'ext_host', form.hostIds, 'hostIds')}
        </div>
      </section>

      <section className="alert-rule-section" data-testid="alert-rule-section-condition">
        <h2 className="alert-rule-section__title">{t('alerts:rules.sections.condition')}</h2>
        <div className="alert-rule-grid">
          <Field
            field="window"
            label={t('alerts:rules.fields.window')}
            hint={t('alerts:rules.hints.window')}
            htmlFor={fieldId('window')}
            {...common}
          >
            <input
              id={fieldId('window')}
              className="alert-rule-input"
              value={form.window}
              data-testid="alert-rule-field-window"
              onChange={(event) => {
                onChange({ window: event.target.value });
              }}
            />
          </Field>
          <Field
            field="for"
            label={t('alerts:rules.fields.for')}
            hint={t('alerts:rules.hints.for')}
            htmlFor={fieldId('for')}
            {...common}
          >
            <input
              id={fieldId('for')}
              className="alert-rule-input"
              value={form.for}
              data-testid="alert-rule-field-for"
              onChange={(event) => {
                onChange({ for: event.target.value });
              }}
            />
          </Field>
          <Field
            field="evaluation_unit"
            label={t('alerts:rules.fields.evaluation_unit')}
            htmlFor={fieldId('evaluation_unit')}
            {...common}
          >
            <select
              id={fieldId('evaluation_unit')}
              className="alert-rule-input"
              value={form.evaluationUnit}
              data-testid="alert-rule-field-evaluation_unit"
              onChange={(event) => {
                onChange({ evaluationUnit: event.target.value as RuleForm['evaluationUnit'] });
              }}
            >
              {EVALUATION_UNITS.map((unit) => (
                <option
                  key={unit}
                  value={unit}
                  disabled={
                    unit === 'instance' &&
                    form.targetType !== 'app' &&
                    form.targetType !== 'instance'
                  }
                >
                  {labels.evaluationUnit(unit)}
                </option>
              ))}
            </select>
          </Field>
          <Field
            field="operator"
            label={t('alerts:rules.fields.operator')}
            htmlFor={fieldId('operator')}
            {...common}
          >
            <select
              id={fieldId('operator')}
              className="alert-rule-input"
              value={form.operator}
              data-testid="alert-rule-field-operator"
              onChange={(event) => {
                onChange({ operator: event.target.value as RuleForm['operator'] });
              }}
            >
              {OPERATORS.map((operator) => (
                <option key={operator} value={operator}>
                  {operator}
                </option>
              ))}
            </select>
          </Field>
          {THRESHOLD_FIELDS.map((field) => (
            <Field
              key={field}
              field={field}
              label={t(`alerts:rules.fields.${field}`)}
              htmlFor={fieldId(field)}
              {...common}
            >
              <span className="alert-rule-unit-input">
                <input
                  id={fieldId(field)}
                  className="alert-rule-input"
                  inputMode="decimal"
                  value={form[field]}
                  data-testid={`alert-rule-field-${field}`}
                  data-unit={info?.unit ?? 'none'}
                  onChange={(event) => {
                    onChange({ [field]: event.target.value });
                  }}
                />
                {unitSuffix === '' ? null : (
                  <span className="alert-rule-unit" data-testid={`alert-rule-unit-${field}`}>
                    {unitSuffix}
                  </span>
                )}
              </span>
            </Field>
          ))}
          <Field
            field="min_samples"
            label={t('alerts:rules.fields.min_samples')}
            hint={
              info !== null && !info.request
                ? t('alerts:rules.hints.minSamplesNotRequest')
                : t('alerts:rules.hints.minSamples')
            }
            htmlFor={fieldId('min_samples')}
            {...common}
          >
            <input
              id={fieldId('min_samples')}
              className="alert-rule-input"
              inputMode="numeric"
              value={form.minSamples}
              disabled={info !== null && !info.request}
              data-testid="alert-rule-field-min_samples"
              onChange={(event) => {
                onChange({ minSamples: event.target.value });
              }}
            />
          </Field>
          <Field
            field="no_data"
            label={t('alerts:rules.fields.no_data')}
            htmlFor={fieldId('no_data')}
            {...common}
          >
            <select
              id={fieldId('no_data')}
              className="alert-rule-input"
              value={form.noData}
              data-testid="alert-rule-field-no_data"
              onChange={(event) => {
                onChange({ noData: event.target.value as RuleForm['noData'] });
              }}
            >
              {NO_DATA_MODES.map((mode) => (
                <option key={mode} value={mode}>
                  {labels.noData(mode)}
                </option>
              ))}
            </select>
          </Field>
          <Field
            field="recover_threshold"
            label={t('alerts:rules.fields.recover_threshold')}
            hint={t('alerts:rules.hints.recoverThreshold')}
            htmlFor={fieldId('recover_threshold')}
            {...common}
          >
            <span className="alert-rule-unit-input">
              <input
                id={fieldId('recover_threshold')}
                className="alert-rule-input"
                inputMode="decimal"
                value={form.recoverThreshold}
                data-testid="alert-rule-field-recover_threshold"
                onChange={(event) => {
                  onChange({ recoverThreshold: event.target.value });
                }}
              />
              {unitSuffix === '' ? null : <span className="alert-rule-unit">{unitSuffix}</span>}
            </span>
          </Field>
          <Field
            field="recover_for"
            label={t('alerts:rules.fields.recover_for')}
            hint={t('alerts:rules.hints.recoverFor')}
            htmlFor={fieldId('recover_for')}
            {...common}
          >
            <input
              id={fieldId('recover_for')}
              className="alert-rule-input"
              value={form.recoverFor}
              data-testid="alert-rule-field-recover_for"
              onChange={(event) => {
                onChange({ recoverFor: event.target.value });
              }}
            />
          </Field>
          <Field
            field="warmup_grace"
            label={t('alerts:rules.fields.warmup_grace')}
            hint={t('alerts:rules.hints.warmupGrace')}
            htmlFor={fieldId('warmup_grace')}
            {...common}
          >
            <input
              id={fieldId('warmup_grace')}
              className="alert-rule-input"
              value={form.warmupGrace}
              data-testid="alert-rule-field-warmup_grace"
              onChange={(event) => {
                onChange({ warmupGrace: event.target.value });
              }}
            />
          </Field>
        </div>
      </section>

      <section className="alert-rule-section" data-testid="alert-rule-section-schedule">
        <h2 className="alert-rule-section__title">{t('alerts:rules.sections.schedule')}</h2>
        <Field
          field="active_time"
          label={t('alerts:rules.fields.active_time')}
          hint={t('alerts:rules.hints.activeTime')}
          {...common}
        >
          <div className="alert-rule-rows" data-testid="alert-rule-field-active_time">
            {form.activeTime.map((slot, index) => (
              <div
                key={`slot-${String(index)}`}
                className="alert-rule-row"
                data-testid="alert-rule-active-time-row"
              >
                <span
                  className="alert-rule-days"
                  role="group"
                  aria-label={t('alerts:rules.fields.days')}
                >
                  {WEEK_DAYS.map((day) => (
                    <label key={day} className="alert-rule-check">
                      <input
                        type="checkbox"
                        checked={slot.days.includes(day)}
                        data-testid={`alert-rule-active-time-${String(index)}-${day}`}
                        onChange={() => {
                          setSlot(index, {
                            days: WEEK_DAYS.filter((d) =>
                              toggle<WeekDay>(slot.days, day).includes(d),
                            ),
                          });
                        }}
                      />
                      {labels.day(day)}
                    </label>
                  ))}
                </span>
                <input
                  className="alert-rule-input alert-rule-input--time"
                  aria-label={t('alerts:rules.fields.from')}
                  value={slot.from}
                  data-testid={`alert-rule-active-time-${String(index)}-from`}
                  onChange={(event) => {
                    setSlot(index, { from: event.target.value });
                  }}
                />
                <input
                  className="alert-rule-input alert-rule-input--time"
                  aria-label={t('alerts:rules.fields.to')}
                  value={slot.to}
                  data-testid={`alert-rule-active-time-${String(index)}-to`}
                  onChange={(event) => {
                    setSlot(index, { to: event.target.value });
                  }}
                />
                <button
                  type="button"
                  className="control-button"
                  data-testid={`alert-rule-active-time-${String(index)}-remove`}
                  onClick={() => {
                    onChange({ activeTime: form.activeTime.filter((_, i) => i !== index) });
                  }}
                >
                  {t('alerts:rules.actions.remove')}
                </button>
              </div>
            ))}
            <button
              type="button"
              className="control-button"
              data-testid="alert-rule-active-time-add"
              disabled={form.activeTime.length >= 14}
              onClick={() => {
                onChange({
                  activeTime: [
                    ...form.activeTime,
                    { days: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'], from: '09:00', to: '18:00' },
                  ],
                });
              }}
            >
              {t('alerts:rules.actions.addActiveTime')}
            </button>
          </div>
        </Field>
        <Field
          field="mute_schedules"
          label={t('alerts:rules.fields.mute_schedules')}
          hint={t('alerts:rules.hints.muteSchedules')}
          {...common}
        >
          <div className="alert-rule-rows" data-testid="alert-rule-field-mute_schedules">
            {form.muteSchedules.map((row, index) => (
              <div
                key={`mute-${String(index)}`}
                className="alert-rule-row"
                data-testid="alert-rule-mute-schedule-row"
              >
                <input
                  className="alert-rule-input alert-rule-input--mono"
                  aria-label={t('alerts:rules.fields.cron')}
                  value={row.cron}
                  data-testid={`alert-rule-mute-schedule-${String(index)}-cron`}
                  onChange={(event) => {
                    setMute(index, { cron: event.target.value });
                  }}
                />
                <input
                  className="alert-rule-input alert-rule-input--short"
                  aria-label={t('alerts:rules.fields.duration')}
                  value={row.duration}
                  data-testid={`alert-rule-mute-schedule-${String(index)}-duration`}
                  onChange={(event) => {
                    setMute(index, { duration: event.target.value });
                  }}
                />
                <input
                  className="alert-rule-input"
                  aria-label={t('alerts:rules.fields.reason')}
                  maxLength={256}
                  value={row.reason}
                  data-testid={`alert-rule-mute-schedule-${String(index)}-reason`}
                  onChange={(event) => {
                    setMute(index, { reason: event.target.value });
                  }}
                />
                <button
                  type="button"
                  className="control-button"
                  data-testid={`alert-rule-mute-schedule-${String(index)}-remove`}
                  onClick={() => {
                    onChange({ muteSchedules: form.muteSchedules.filter((_, i) => i !== index) });
                  }}
                >
                  {t('alerts:rules.actions.remove')}
                </button>
              </div>
            ))}
            <button
              type="button"
              className="control-button"
              data-testid="alert-rule-mute-schedule-add"
              disabled={form.muteSchedules.length >= 14}
              onClick={() => {
                onChange({
                  muteSchedules: [
                    ...form.muteSchedules,
                    { cron: '0 2 * * *', duration: '1h', reason: '' },
                  ],
                });
              }}
            >
              {t('alerts:rules.actions.addMuteSchedule')}
            </button>
          </div>
        </Field>
      </section>

      <section className="alert-rule-section" data-testid="alert-rule-section-notify">
        <h2 className="alert-rule-section__title">{t('alerts:rules.sections.notify')}</h2>
        <div className="alert-rule-grid">
          {CHANNEL_FIELDS.map((field) => {
            const key = CHANNEL_KEYS[field];
            const selected = form[key];
            return (
              <Field
                key={field}
                field={field}
                label={t(`alerts:rules.fields.${field}`)}
                {...common}
              >
                <div className="alert-rule-channels" data-testid={`alert-rule-field-${field}`}>
                  {channelsLoading ? (
                    <span className="alert-rule-preview__muted">
                      {t('alerts:rules.channels.loading')}
                    </span>
                  ) : channels.length === 0 ? (
                    <span
                      className="alert-rule-preview__muted"
                      data-testid="alert-rule-channels-none"
                    >
                      {t('alerts:rules.channels.none')}
                    </span>
                  ) : (
                    channels.map((channel) => (
                      <label key={channel.id} className="alert-rule-check">
                        <input
                          type="checkbox"
                          checked={selected.includes(channel.id)}
                          data-testid={`alert-rule-channel-${CHANNEL_SEVERITY[field]}-${channel.id}`}
                          onChange={() => {
                            onChange({ [key]: toggle(selected, channel.id) });
                          }}
                        />
                        <SafeText value={channel.name} />
                        <span className="alert-rule-preview__muted">
                          {channelTypeLabel(t, channel.type)}
                        </span>
                      </label>
                    ))
                  )}
                  {/* Saved ids of channels that no longer exist stay selected and visible. */}
                  {selected
                    .filter((channelId) => !channels.some((channel) => channel.id === channelId))
                    .map((channelId) =>
                      channelsLoading ? null : (
                        <label key={channelId} className="alert-rule-check">
                          <input
                            type="checkbox"
                            checked
                            onChange={() => {
                              onChange({ [key]: toggle(selected, channelId) });
                            }}
                          />
                          {t('alerts:rules.channels.missing')}
                        </label>
                      ),
                    )}
                </div>
              </Field>
            );
          })}
          <Field
            field="renotify_interval"
            label={t('alerts:rules.fields.renotify_interval')}
            hint={t('alerts:rules.hints.renotify')}
            htmlFor={fieldId('renotify_interval')}
            {...common}
          >
            <input
              id={fieldId('renotify_interval')}
              className="alert-rule-input"
              value={form.renotifyInterval}
              data-testid="alert-rule-field-renotify_interval"
              onChange={(event) => {
                onChange({ renotifyInterval: event.target.value });
              }}
            />
          </Field>
          <Field
            field="runbook_url"
            label={t('alerts:rules.fields.runbook_url')}
            hint={t('alerts:rules.hints.runbook')}
            htmlFor={fieldId('runbook_url')}
            {...common}
          >
            <span className="alert-rule-runbook">
              <input
                id={fieldId('runbook_url')}
                className="alert-rule-input"
                type="url"
                maxLength={1024}
                value={form.runbookUrl}
                data-testid="alert-rule-field-runbook_url"
                onChange={(event) => {
                  onChange({ runbookUrl: event.target.value });
                }}
              />
              {form.runbookUrl.trim() !== '' && safeHref(form.runbookUrl.trim()) !== undefined ? (
                <SafeLink href={form.runbookUrl.trim()} data-testid="alert-rule-runbook-link">
                  {t('alerts:rules.actions.openRunbook')}
                </SafeLink>
              ) : null}
            </span>
          </Field>
        </div>
        <div className="alert-rule-field" data-field="message_template">
          <span id={templateLabelId} className="alert-rule-field__label">
            {t('alerts:rules.fields.message_template')}
            {diffDots.get('message_template') === undefined ? null : (
              <DiffDot field="message_template" />
            )}
          </span>
          <MessageTemplateField
            value={form.messageTemplate}
            labelId={templateLabelId}
            error={
              templateError ??
              (errors.message_template === undefined ? null : labels.error(errors.message_template))
            }
            onChange={(messageTemplate) => {
              onChange({ messageTemplate });
            }}
          />
        </div>
      </section>
    </div>
  );
}
