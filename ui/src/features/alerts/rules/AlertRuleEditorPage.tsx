// SPDX-License-Identifier: Apache-2.0
//
// Alert rule editor /alerts/rules/new and /alerts/rules/:ruleId (docs/15 §9.9, 05 §2.4, WO-38):
// every rule field (RuleEditorForm), the 7-day preview of the rule as edited (RulePreview, 500 ms
// debounce), "create from template" (TemplateDrawer: the form is filled and the fields that differ
// from the template get a dot; a saved rule's server `template_diff` marks them until the templates
// are loaded), clone to another application and environment, enable / disable (distinct from
// delete), delete with confirmation and the revision history. Channels are chosen by name; the
// screen never sees a channel URL. The form keeps the user's edits; a save sends the whole rule
// (POST or PUT) and maps the server's 400 details and 422 template_variable_forbidden back to the
// fields. URL state: AlertRuleQ (`app` = default application of a new rule, `template` = the chosen
// template key).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useParams } from 'react-router';

import {
  alertRuleKeys,
  createAlertRule,
  deleteAlertRule,
  getAlertRule,
  listAlertChannelChoices,
  listAlertTemplates,
  setAlertRuleEnabled,
  updateAlertRule,
  type AlertRule,
  type AlertTemplate,
  type AlertTemplatePart,
} from '../../../api/alertRules';
import { listApps } from '../../../api/apps';
import { errorInfo, isApiError } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { AlertRuleQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { AlertDialog } from '../common/AlertDialog';

import { CloneRuleDialog } from './CloneRuleDialog';
import { useMetricLabel } from './labels';
import { metricInfo } from './metricCatalog';
import { RuleEditorForm } from './RuleEditorForm';
import {
  diffFieldsOf,
  diffFromTemplate,
  dotFieldOf,
  emptyForm,
  errorOfReason,
  fieldOfDetail,
  formFromRule,
  previewable,
  toPreviewRequest,
  toRequest,
  validateForm,
  type DiffField,
  type FieldErrors,
  type RuleField,
  type RuleForm,
} from './ruleForm';
import { RuleHistory } from './RuleHistory';
import { RulePreview } from './RulePreview';
import { TemplateDrawer } from './TemplateDrawer';

import './rules.css';

/** Form keys → the field whose error a change clears and whose client error it reveals. */
const FIELD_OF_KEY: Readonly<Partial<Record<keyof RuleForm, RuleField>>> = {
  name: 'name',
  metric: 'metric',
  kind: 'kind',
  targetType: 'target_type',
  appId: 'target_app',
  env: 'target_env',
  instanceIds: 'target_instance',
  serviceIds: 'target_service',
  hostIds: 'target_host',
  window: 'window',
  for: 'for',
  evaluationUnit: 'evaluation_unit',
  operator: 'operator',
  warn: 'warn',
  critical: 'critical',
  minSamples: 'min_samples',
  noData: 'no_data',
  recoverThreshold: 'recover_threshold',
  recoverFor: 'recover_for',
  warmupGrace: 'warmup_grace',
  activeTime: 'active_time',
  muteSchedules: 'mute_schedules',
  channelsWarn: 'channels_warn',
  channelsCritical: 'channels_critical',
  renotifyInterval: 'renotify_interval',
  runbookUrl: 'runbook_url',
  messageTemplate: 'message_template',
};

/** Fields whose client error shows while typing, before any save (05 §2.4-1 template variables). */
const ALWAYS_SHOWN: ReadonlySet<RuleField> = new Set<RuleField>(['message_template']);

/** The template part a rule was made from: the chosen index, else the part of its metric. */
function templatePart(
  templates: readonly AlertTemplate[] | undefined,
  key: string | null,
  index: number | null,
  metric: string,
): AlertTemplatePart | null {
  if (templates === undefined || key === null) {
    return null;
  }
  const template = templates.find((item) => item.key === key);
  if (template === undefined) {
    return null;
  }
  if (index !== null) {
    return template.defaults[index] ?? null;
  }
  return (
    template.defaults.find((part) => part.rule.metric === metric) ?? template.defaults[0] ?? null
  );
}

/**
 * The form of a template part. The key and version come from the template itself (the server
 * also fills AlertRuleDefaults.template_key and template_version with the same values, C-228
 * (6)): the key marks the changed fields and is saved with the rule (F-S24).
 */
function formFromTemplate(
  template: Pick<AlertTemplate, 'key' | 'version'>,
  part: AlertTemplatePart,
  appId: number | null,
): RuleForm {
  return {
    ...formFromRule(part.rule, appId),
    templateKey: template.key,
    templateVersion: template.version,
  };
}

/** Server errors of a failed save, by form field. */
function serverErrorsOf(error: unknown): FieldErrors {
  if (!isApiError(error)) {
    return {};
  }
  if (error.code === 'template_variable_forbidden') {
    return { message_template: 'templateForbidden' };
  }
  const out: FieldErrors = {};
  for (const detail of error.details) {
    const field = fieldOfDetail(detail.field);
    if (field !== null && out[field] === undefined) {
      out[field] = errorOfReason(detail.reason);
    }
  }
  return out;
}

export function AlertRuleEditorPage() {
  const params = useParams();
  const ruleId = params.ruleId;
  // A new editor per rule: the edited form never leaks into another rule.
  return <RuleEditor key={ruleId ?? 'new'} ruleId={ruleId ?? null} />;
}

function RuleEditor({ ruleId }: { readonly ruleId: string | null }) {
  const { t } = useTranslation(['common', 'alerts']);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const metricLabel = useMetricLabel();
  const { state: url, set } = useUrlState(AlertRuleQ);
  const isNew = ruleId === null;

  const [edited, setEdited] = useState<RuleForm | null>(null);
  const [touched, setTouched] = useState<ReadonlySet<RuleField>>(new Set());
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [partIndex, setPartIndex] = useState<number | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(
    () => isNew && (location.state as { templates?: unknown } | null)?.templates === true,
  );
  const [cloneOpen, setCloneOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [saved, setSaved] = useState(false);

  const apps = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });
  const channels = useQuery({
    queryKey: alertRuleKeys.channels(),
    queryFn: ({ signal }) => listAlertChannelChoices(signal),
    staleTime: 60_000,
  });
  const detail = useQuery({
    queryKey: alertRuleKeys.detail(ruleId ?? ''),
    queryFn: ({ signal }) => getAlertRule(ruleId ?? '', signal),
    enabled: !isNew,
  });
  const needTemplates =
    drawerOpen ||
    (isNew && url.template !== undefined) ||
    edited?.templateKey != null ||
    detail.data?.rule.template_key != null;
  const templates = useQuery({
    queryKey: alertRuleKeys.templates(),
    queryFn: ({ signal }) => listAlertTemplates(signal),
    staleTime: 5 * 60_000,
    enabled: needTemplates,
  });

  // The form before the first edit: the saved rule, the URL's template, or an empty rule.
  const base = useMemo((): RuleForm | null => {
    if (!isNew) {
      return detail.data === undefined ? null : formFromRule(detail.data.rule);
    }
    if (url.template !== undefined) {
      if (templates.isPending && templates.fetchStatus !== 'idle') {
        return null;
      }
      const template = templates.data?.find((item) => item.key === url.template);
      const part = templatePart(templates.data, url.template, 0, '');
      if (template !== undefined && part !== null) {
        return formFromTemplate(template, part, part.scope === 'server' ? null : (url.app ?? null));
      }
    }
    return emptyForm(url.app ?? null);
  }, [
    isNew,
    detail.data,
    url.template,
    url.app,
    templates.isPending,
    templates.fetchStatus,
    templates.data,
  ]);
  const form = edited ?? base;

  const clientErrors = useMemo(() => (form === null ? {} : validateForm(form)), [form]);
  const shownErrors = useMemo((): FieldErrors => {
    const out: FieldErrors = {};
    for (const [field, error] of Object.entries(clientErrors) as [
      RuleField,
      FieldErrors[RuleField],
    ][]) {
      if (submitted || touched.has(field) || ALWAYS_SHOWN.has(field)) {
        out[field] = error;
      }
    }
    return { ...out, ...serverErrors };
  }, [clientErrors, serverErrors, submitted, touched]);

  // Fields that differ from the template part: compared on the client once the templates are
  // loaded, the saved rule's server diff until then.
  const diffDots = useMemo((): ReadonlyMap<RuleField, string> => {
    const dots = new Map<RuleField, string>();
    if (form === null || form.templateKey === null) {
      return dots;
    }
    const part = templatePart(templates.data, form.templateKey, partIndex, form.metric);
    const fields: DiffField[] =
      part !== null
        ? diffFromTemplate(form, part.rule)
        : diffFieldsOf(detail.data?.template_diff ?? null);
    for (const field of fields) {
      dots.set(dotFieldOf(field), field);
    }
    return dots;
  }, [form, templates.data, partIndex, detail.data]);

  const onChange = useCallback(
    (patch: Partial<RuleForm>) => {
      if (form === null) {
        return;
      }
      setEdited({ ...form, ...patch });
      setSaved(false);
      const fields = (Object.keys(patch) as (keyof RuleForm)[])
        .map((key) => FIELD_OF_KEY[key])
        .filter((field): field is RuleField => field !== undefined);
      if (fields.length > 0) {
        setTouched((current) => new Set([...current, ...fields]));
        setServerErrors((current) =>
          Object.fromEntries(
            Object.entries(current).filter(([field]) => !fields.includes(field as RuleField)),
          ),
        );
      }
    },
    [form],
  );

  const chooseTemplate = (
    template: AlertTemplate,
    part: AlertTemplatePart,
    index: number,
  ): void => {
    const appId = part.scope === 'server' ? null : (form?.appId ?? url.app ?? null);
    setEdited(formFromTemplate(template, part, appId));
    setPartIndex(index);
    setDrawerOpen(false);
    setServerErrors({});
    setTouched(new Set());
    if (isNew) {
      set({ template: template.key }, { replace: true });
    }
  };

  const save = useMutation({
    mutationFn: (body: ReturnType<typeof toRequest>) =>
      ruleId === null ? createAlertRule(body) : updateAlertRule(ruleId, body),
    onSuccess: (rule: AlertRule) => {
      void queryClient.invalidateQueries({ queryKey: alertRuleKeys.all() });
      setServerErrors({});
      setSaved(true);
      if (isNew) {
        void navigate(`/alerts/rules/${encodeURIComponent(rule.rule_id)}`, { replace: true });
      } else {
        setEdited(formFromRule(rule));
      }
    },
    onError: (error) => {
      setServerErrors(serverErrorsOf(error));
    },
  });
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => setAlertRuleEnabled(ruleId ?? '', enabled),
    onSuccess: (_, enabled) => {
      void queryClient.invalidateQueries({ queryKey: alertRuleKeys.all() });
      if (form !== null) {
        setEdited({ ...form, enabled });
      }
    },
  });
  const remove = useMutation({
    mutationFn: () => deleteAlertRule(ruleId ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: alertRuleKeys.all() });
      void navigate('/alerts/rules');
    },
  });

  const submit = (): void => {
    setSubmitted(true);
    if (form === null || Object.keys(clientErrors).length > 0) {
      return;
    }
    save.mutate(toRequest(form));
  };

  const info = metricInfo(form?.metric);
  const previewRequest =
    form === null || !previewable(clientErrors)
      ? null
      : toPreviewRequest(form, t('alerts:rules.preview.placeholderName'));

  const pageState = !isNew && detail.isError ? 'error' : form === null ? 'loading' : 'ready';
  const savedRule = detail.data?.rule ?? null;
  const errorCount = Object.keys(shownErrors).length;

  return (
    <section
      className="alert-rule-editor"
      aria-labelledby="alert-rule-editor-title"
      data-testid="alert-rule-editor"
      data-state={pageState}
      data-rule-id={ruleId ?? undefined}
    >
      <header className="alert-rule-editor__header">
        <div className="alert-rule-editor__heading">
          <Link
            to="/alerts/rules"
            className="alert-rule-editor__back"
            data-testid="alert-rule-back"
          >
            {t('alerts:rules.editor.back')}
          </Link>
          <h1 id="alert-rule-editor-title" className="alert-rule-editor__title">
            {isNew ? (
              t('alerts:rules.editor.newTitle')
            ) : savedRule === null ? (
              t('alerts:rules.editor.title')
            ) : (
              <SafeText value={savedRule.name} data-testid="alert-rule-editor-name" />
            )}
          </h1>
        </div>
        <div className="alert-rule-editor__actions">
          <button
            type="button"
            className="control-button"
            data-testid="alert-rule-from-template"
            onClick={() => {
              setDrawerOpen(true);
            }}
          >
            {t('alerts:rules.actions.fromTemplate')}
          </button>
          {savedRule === null ? null : (
            <>
              {savedRule.target.type === 'server' ? null : (
                <button
                  type="button"
                  className="control-button"
                  data-testid="alert-rule-clone"
                  onClick={() => {
                    setCloneOpen(true);
                  }}
                >
                  {t('alerts:rules.actions.clone')}
                </button>
              )}
              <button
                type="button"
                className="control-button"
                data-testid="alert-rule-toggle"
                data-enabled={String(form?.enabled ?? savedRule.enabled)}
                disabled={toggle.isPending}
                onClick={() => {
                  toggle.mutate(!(form?.enabled ?? savedRule.enabled));
                }}
              >
                {(form?.enabled ?? savedRule.enabled)
                  ? t('alerts:rules.actions.disable')
                  : t('alerts:rules.actions.enable')}
              </button>
              <button
                type="button"
                className="control-button alert-rule-danger"
                data-testid="alert-rule-delete"
                onClick={() => {
                  setDeleteOpen(true);
                }}
              >
                {t('alerts:rules.actions.delete')}
              </button>
            </>
          )}
          <button
            type="button"
            className="control-button control-button--primary"
            data-testid="alert-rule-save"
            disabled={form === null || save.isPending}
            onClick={submit}
          >
            {save.isPending ? t('alerts:rules.actions.saving') : t('alerts:rules.actions.save')}
          </button>
        </div>
      </header>

      {saved ? (
        <p className="alert-rule-editor__notice" role="status" data-testid="alert-rule-saved">
          {t('alerts:rules.editor.saved')}
        </p>
      ) : null}
      {submitted && errorCount > 0 ? (
        <p className="alert-rule-editor__error" role="alert" data-testid="alert-rule-invalid">
          {t('alerts:rules.editor.invalid', { count: errorCount })}
        </p>
      ) : null}
      {save.isError && Object.keys(serverErrors).length === 0 ? (
        <ErrorState compact error={errorInfo(save.error)} data-testid="alert-rule-save-error" />
      ) : null}
      {toggle.isError || remove.isError ? (
        <ErrorState
          compact
          error={errorInfo(toggle.error ?? remove.error)}
          data-testid="alert-rule-action-error"
        />
      ) : null}
      {form !== null && form.templateKey !== null ? (
        <p
          className="alert-rule-editor__template"
          data-testid="alert-rule-template-key"
          data-key={form.templateKey}
        >
          {t('alerts:rules.editor.fromTemplate', {
            name:
              templates.data?.find((template) => template.key === form.templateKey)?.name ??
              form.templateKey,
          })}
          {diffDots.size > 0
            ? ` ${t('alerts:rules.editor.changedCount', { count: diffDots.size })}`
            : ''}
        </p>
      ) : null}

      {pageState === 'error' ? (
        <ErrorState
          error={errorInfo(detail.error)}
          onRetry={() => {
            void detail.refetch();
          }}
          data-testid="alert-rule-editor-error"
        />
      ) : form === null ? (
        <LoadingState variant="block" data-testid="alert-rule-editor-loading" />
      ) : (
        <div className="alert-rule-editor__body">
          <div className="alert-rule-editor__main">
            <RuleEditorForm
              form={form}
              onChange={onChange}
              errors={shownErrors}
              diffDots={diffDots}
              apps={apps.data?.data ?? []}
              channels={channels.data ?? []}
              channelsLoading={channels.isPending}
              templateError={null}
            />
            {detail.data === undefined ? null : <RuleHistory history={detail.data.history} />}
          </div>
          <aside className="alert-rule-editor__side" data-testid="alert-rule-section-preview">
            <h2 className="alert-rule-section__title">{t('alerts:rules.sections.preview')}</h2>
            <p className="alert-rule-preview__muted">{t('alerts:rules.preview.description')}</p>
            <RulePreview
              request={previewRequest}
              unsupported={info !== null && !info.preview}
              unit={info?.unit ?? null}
              metricLabel={metricLabel(form.metric)}
            />
          </aside>
        </div>
      )}

      <TemplateDrawer
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
        }}
        onChoose={chooseTemplate}
      />
      <CloneRuleDialog
        rule={cloneOpen ? savedRule : null}
        apps={apps.data?.data ?? []}
        onClose={() => {
          setCloneOpen(false);
        }}
        onCloned={(rule) => {
          setCloneOpen(false);
          void navigate(`/alerts/rules/${encodeURIComponent(rule.rule_id)}`);
        }}
      />
      <AlertDialog
        open={deleteOpen}
        size="narrow"
        title={t('alerts:rules.delete.title')}
        onClose={() => {
          setDeleteOpen(false);
        }}
        data-testid="alert-rule-delete-confirm"
        footer={
          <>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setDeleteOpen(false);
              }}
            >
              {t('alerts:rules.actions.cancel')}
            </button>
            <button
              type="button"
              className="control-button alert-rule-danger"
              data-testid="alert-rule-delete-confirm-ok"
              disabled={remove.isPending}
              onClick={() => {
                remove.mutate();
              }}
            >
              {t('alerts:rules.actions.delete')}
            </button>
          </>
        }
      >
        <p>{t('alerts:rules.delete.description')}</p>
      </AlertDialog>
    </section>
  );
}
