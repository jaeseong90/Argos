// SPDX-License-Identifier: Apache-2.0
//
// Alert rules list /alerts/rules (docs/15 §9.9, 05 §2.4, WO-38): every rule (`GET /alerts/rules`,
// application and enabled filters as server parameters) with its application and environment,
// metric, warning and critical thresholds in the metric's unit, evaluation unit, channels (by
// name), enabled state and last change; actions: new rule, create from template (opens the editor
// with the template drawer), clone to another application and environment, disable / enable
// (distinct from delete: a disabled rule stays and keeps its history) and delete with
// confirmation. Rule, application and channel names are entered strings (SafeText). URL state:
// AlertRulesQ (`app`, `enabled`).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import {
  alertRuleKeys,
  deleteAlertRule,
  listAlertChannelChoices,
  listAlertRules,
  setAlertRuleEnabled,
  type AlertRule,
} from '../../../api/alertRules';
import { listApps } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { useAlertValueFormat } from '../../../components/AlertValue';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';
import { AppSelect } from '../../../routes/Shell';
import { AlertRulesQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { AlertDialog } from '../common/AlertDialog';

import { CloneRuleDialog } from './CloneRuleDialog';
import { useMetricLabel, useRuleLabels } from './labels';
import { metricInfo } from './metricCatalog';

import './rules.css';

export function AlertRulesPage() {
  const { t } = useTranslation(['common', 'alerts']);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const format = useFormat();
  const formatValue = useAlertValueFormat();
  const metricLabel = useMetricLabel();
  const labels = useRuleLabels();
  const { state, set } = useUrlState(AlertRulesQ);
  const [cloning, setCloning] = useState<AlertRule | null>(null);
  const [deleting, setDeleting] = useState<AlertRule | null>(null);

  const filter = {
    app: state.app,
    env: state.env,
    enabled: state.enabled === 'all' ? undefined : state.enabled === 'enabled',
  };
  const rules = useQuery({
    queryKey: alertRuleKeys.list(filter),
    queryFn: ({ signal }) => listAlertRules(filter, signal),
  });
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
  const appList = useMemo(() => apps.data?.data ?? [], [apps.data]);
  const appsById = useMemo(() => new Map(appList.map((app) => [app.app_id, app])), [appList]);
  const channelsById = useMemo(
    () => new Map((channels.data ?? []).map((channel) => [channel.id, channel])),
    [channels.data],
  );

  const invalidate = (): void => {
    void queryClient.invalidateQueries({ queryKey: alertRuleKeys.all() });
  };
  const toggle = useMutation({
    mutationFn: (rule: AlertRule) => setAlertRuleEnabled(rule.rule_id, !rule.enabled),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (rule: AlertRule) => deleteAlertRule(rule.rule_id),
    onSuccess: () => {
      setDeleting(null);
      invalidate();
    },
  });

  const editHref = (rule: AlertRule): string => `/alerts/rules/${encodeURIComponent(rule.rule_id)}`;
  const newHref =
    state.app === undefined ? '/alerts/rules/new' : `/alerts/rules/new?app=${String(state.app)}`;

  const columns: DataTableColumn<AlertRule>[] = [
    {
      id: 'name',
      header: t('alerts:rules.columns.name'),
      value: (rule) => rule.name,
      sortable: true,
      width: 240,
      render: (rule) => (
        <Link to={editHref(rule)} className="alert-rule-link" data-testid="alert-rule-name">
          <SafeText value={rule.name} />
        </Link>
      ),
    },
    {
      id: 'app',
      header: t('alerts:rules.columns.app'),
      value: (rule) => rule.target.app_id,
      width: 180,
      render: (rule) => {
        if (rule.target.type === 'server') {
          return <span>{labels.targetType('server')}</span>;
        }
        const app = rule.target.app_id === null ? undefined : appsById.get(rule.target.app_id);
        return (
          <span className="alert-rule-app">
            {app === undefined ? (
              <span>{t('alerts:rules.columns.appId', { id: rule.target.app_id ?? '—' })}</span>
            ) : (
              <SafeText value={app.name} />
            )}
            <span className="alert-rule-preview__muted">
              {rule.target.env === null ? (
                t('alerts:rules.columns.allEnvs')
              ) : (
                <SafeText value={rule.target.env} />
              )}
            </span>
          </span>
        );
      },
    },
    {
      id: 'metric',
      header: t('alerts:rules.columns.metric'),
      value: (rule) => metricLabel(rule.metric),
      sortable: true,
      width: 200,
    },
    {
      id: 'thresholds',
      header: t('alerts:rules.columns.thresholds'),
      value: (rule) => rule.condition.warn ?? rule.condition.critical,
      width: 170,
      render: (rule) => {
        const unit = metricInfo(rule.metric)?.unit ?? null;
        return (
          <span className="alert-rule-thresholds" data-testid="alert-rule-thresholds">
            <span title={t('component.severity.warn')}>
              {rule.condition.operator} {formatValue(rule.condition.warn, unit)}
            </span>
            <span className="alert-rule-preview__muted"> / </span>
            <span title={t('component.severity.critical')}>
              {formatValue(rule.condition.critical, unit)}
            </span>
          </span>
        );
      },
    },
    {
      id: 'evaluation_unit',
      header: t('alerts:rules.columns.evaluationUnit'),
      value: (rule) => labels.evaluationUnit(rule.evaluation_unit),
      width: 130,
    },
    {
      id: 'channels',
      header: t('alerts:rules.columns.channels'),
      value: (rule) => rule.channels.warn.length + rule.channels.critical.length,
      width: 180,
      render: (rule) => {
        const ids = [...new Set([...rule.channels.warn, ...rule.channels.critical])];
        if (ids.length === 0) {
          return (
            <span className="alert-rule-preview__muted">
              {t('alerts:rules.columns.noChannels')}
            </span>
          );
        }
        return (
          <span className="alert-rule-channel-names">
            {ids.map((id) => {
              const channel = channelsById.get(id);
              return channel === undefined ? (
                <span key={id} className="alert-rule-preview__muted">
                  {t('alerts:rules.channels.missing')}
                </span>
              ) : (
                <SafeText key={id} value={channel.name} />
              );
            })}
          </span>
        );
      },
    },
    {
      id: 'enabled',
      header: t('alerts:rules.columns.enabled'),
      value: (rule) => (rule.enabled ? 1 : 0),
      sortable: true,
      width: 110,
      render: (rule) => (
        <span
          className={`alert-rule-enabled alert-rule-enabled--${rule.enabled ? 'on' : 'off'}`}
          data-testid="alert-rule-enabled"
          data-enabled={String(rule.enabled)}
        >
          {rule.enabled ? t('alerts:rules.enabled.on') : t('alerts:rules.enabled.off')}
        </span>
      ),
    },
    {
      id: 'updated_at',
      header: t('alerts:rules.columns.updatedAt'),
      value: (rule) => Date.parse(rule.updated_at),
      sortable: true,
      width: 170,
      render: (rule) => {
        const at = Date.parse(rule.updated_at);
        return Number.isFinite(at) ? (
          <time dateTime={rule.updated_at}>{format.dateTime(at)}</time>
        ) : (
          '—'
        );
      },
    },
    {
      id: 'actions',
      header: t('alerts:rules.columns.actions'),
      value: () => null,
      width: 230,
      hideable: false,
      render: (rule) => (
        <span className="alert-rule-actions">
          {rule.target.type === 'server' ? null : (
            <button
              type="button"
              className="control-button"
              data-testid="alert-rule-clone"
              onClick={() => {
                setCloning(rule);
              }}
            >
              {t('alerts:rules.actions.clone')}
            </button>
          )}
          <button
            type="button"
            className="control-button"
            data-testid="alert-rule-toggle"
            data-enabled={String(rule.enabled)}
            disabled={toggle.isPending}
            onClick={() => {
              toggle.mutate(rule);
            }}
          >
            {rule.enabled ? t('alerts:rules.actions.disable') : t('alerts:rules.actions.enable')}
          </button>
          <button
            type="button"
            className="control-button alert-rule-danger"
            data-testid="alert-rule-delete"
            onClick={() => {
              setDeleting(rule);
            }}
          >
            {t('alerts:rules.actions.delete')}
          </button>
        </span>
      ),
    },
  ];

  const pageState = rules.isPending
    ? 'loading'
    : rules.isError
      ? 'error'
      : rules.data.length === 0
        ? 'empty'
        : 'ready';

  return (
    <section
      className="alert-rules"
      aria-labelledby="alert-rules-title"
      data-testid="alert-rules"
      data-state={pageState}
    >
      <header className="alert-rules__header">
        <h1 id="alert-rules-title" className="alert-rules__title">
          {t('alerts:rules.list.title')}
        </h1>
        <div className="alert-rules__actions">
          <button
            type="button"
            className="control-button"
            data-testid="alert-rule-from-template"
            onClick={() => {
              void navigate(newHref, { state: { templates: true } });
            }}
          >
            {t('alerts:rules.actions.fromTemplate')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            data-testid="alert-rule-new"
            onClick={() => {
              void navigate(newHref);
            }}
          >
            {t('alerts:rules.actions.new')}
          </button>
        </div>
      </header>
      <div
        className="alert-rules__filters"
        role="group"
        aria-label={t('alerts:rules.list.filters')}
      >
        <span className="alert-rule-dialog__label">{t('alerts:rules.list.app')}</span>
        <span className="alert-rule-select" data-testid="alert-rules-filter-app">
          <AppSelect
            apps={appList}
            value={state.app ?? null}
            loading={apps.isPending}
            onChange={(app) => {
              set({ app });
            }}
          />
        </span>
        {state.app === undefined ? null : (
          <button
            type="button"
            className="control-button"
            data-testid="alert-rules-filter-app-clear"
            onClick={() => {
              set({ app: undefined });
            }}
          >
            {t('alerts:rules.list.allApps')}
          </button>
        )}
        <label className="alert-rule-dialog__label" htmlFor="alert-rules-filter-enabled">
          {t('alerts:rules.list.enabled')}
        </label>
        <select
          id="alert-rules-filter-enabled"
          className="alert-rule-input alert-rule-input--short"
          value={state.enabled}
          data-testid="alert-rules-filter-enabled"
          onChange={(event) => {
            set({ enabled: event.target.value as typeof state.enabled });
          }}
        >
          <option value="all">{t('alerts:rules.list.enabledAll')}</option>
          <option value="enabled">{t('alerts:rules.list.enabledOnly')}</option>
          <option value="disabled">{t('alerts:rules.list.disabledOnly')}</option>
        </select>
      </div>
      {toggle.isError ? (
        <ErrorState
          compact
          error={errorInfo(toggle.error)}
          data-testid="alert-rules-action-error"
        />
      ) : null}
      <DataTable
        tableId="alert-rules"
        caption={t('alerts:rules.list.caption')}
        columns={columns}
        rows={rules.data ?? []}
        rowKey={(rule) => rule.rule_id}
        dataComplete
        loading={rules.isPending}
        error={rules.isError ? errorInfo(rules.error) : null}
        onRetry={() => {
          void rules.refetch();
        }}
        empty={{
          title: t('alerts:rules.list.emptyTitle'),
          description: t('alerts:rules.list.emptyDescription'),
          actions: [
            {
              label: t('alerts:rules.actions.fromTemplate'),
              onClick: () => {
                void navigate(newHref, { state: { templates: true } });
              },
              'data-testid': 'alert-rules-empty-template',
            },
          ],
          ...(state.app !== undefined || state.enabled !== 'all'
            ? {
                onClearFilters: () => {
                  set({ app: undefined, enabled: 'all' });
                },
              }
            : {}),
        }}
        onRowOpen={(rule) => {
          void navigate(editHref(rule));
        }}
        rowAttributes={(rule) => ({ 'data-enabled': String(rule.enabled) })}
      />
      <CloneRuleDialog
        rule={cloning}
        apps={appList}
        onClose={() => {
          setCloning(null);
        }}
        onCloned={() => {
          setCloning(null);
        }}
      />
      <AlertDialog
        open={deleting !== null}
        size="narrow"
        title={t('alerts:rules.delete.title')}
        onClose={() => {
          setDeleting(null);
          remove.reset();
        }}
        data-testid="alert-rule-delete-confirm"
        footer={
          <>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setDeleting(null);
                remove.reset();
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
                if (deleting !== null) {
                  remove.mutate(deleting);
                }
              }}
            >
              {t('alerts:rules.actions.delete')}
            </button>
          </>
        }
      >
        {deleting === null ? null : (
          <>
            <p>
              <SafeText value={deleting.name} className="alert-rule-dialog__name" />
            </p>
            <p>{t('alerts:rules.delete.description')}</p>
            {remove.isError ? <ErrorState compact error={errorInfo(remove.error)} /> : null}
          </>
        )}
      </AlertDialog>
    </section>
  );
}
