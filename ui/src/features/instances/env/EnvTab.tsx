// SPDX-License-Identifier: Apache-2.0
//
// The environment tab of the instance detail (05 §2.7-3, docs/15 §9.8):
//
// 1. JVM options, environment variables and system properties: the latest environment dump
//    (administrators only, SEC-21; values arrive masked from the agent and are shown as they are).
//    Other roles see "requires the administrator role" (plan assumption: JVM options come only
//    from the environment dump, PM question 2).
// 2. Effective configuration with each key's source: the server's view merged with the latest self
//    diagnosis (env/model.ts); operator and above (`config.view-effective`), viewers see a notice.
// 3. Keys the agent allows to change remotely (`remote-config.allow`, SEC-23).
// 4. Instrumentation module status (active, disabled by the agent with its reason, error counts).
// 5. The public self-diagnosis summary of the agent's own series (every role) and the latest self
//    diagnosis result itself (requester and administrators read results, SEC-50).
//
// The latest self diagnosis is read only when the user presses "Show latest self-diagnosis"
// (ADR-0010 C-234 (3)): reading a result is audited (COMMAND_RESULT_VIEWED), so opening the tab
// reads none. One press feeds the configuration sources, the module states and the result panel.
//
// Every collected string (keys, values, sources, module names) renders through SafeText.
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { errorInfo } from '../../../api/errors';
import {
  useEffectiveConfig,
  useMetricNames,
  useMetricSeries,
  type Instance,
  type InstanceRange,
} from '../../../api/instances';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import { useFormat } from '../../../i18n/format';
import { useCan } from '../../../state/permissions';
import { LatestResultPanel, useLatestSelfDiagnosis } from '../commands';
import { agentNames } from '../metrics/panels';

import {
  agentSummary,
  mergeEffectiveConfig,
  moduleRows,
  type ConfigRow,
  type ModuleRow,
} from './model';

export interface EnvTabProps {
  readonly appId: number;
  readonly instance: Instance;
  readonly range: InstanceRange;
  /** Opens the commands tab (where a self diagnosis is requested). */
  readonly onOpenCommands: () => void;
}

function Notice({ testId, children }: { readonly testId: string; readonly children: string }) {
  return (
    <p className="instance-notice" role="note" data-testid={testId}>
      {children}
    </p>
  );
}

export function EnvTab({ appId, instance, range, onOpenCommands }: EnvTabProps) {
  const { t } = useTranslation(['common', 'instance']);
  const format = useFormat();
  const instanceId = instance.instance_id;
  const isAdmin = useCan('commands.env-dump');
  const canViewConfig = useCan('config.view-effective');
  const canSelfDiagnose = useCan('commands.self-diagnosis');

  const effective = useEffectiveConfig(instanceId, canViewConfig);
  const [diagnosisShown, setDiagnosisShown] = useState(false);
  const showDiagnosis = () => {
    setDiagnosisShown(true);
  };
  const latest = useLatestSelfDiagnosis(instanceId, diagnosisShown);
  const report = latest.report;

  const namesQuery = useMetricNames(instanceId);
  const reported = namesQuery.data?.data;
  const agent = useMemo(() => agentNames(reported ?? []), [reported]);
  const agentQuery = useMetricSeries(instanceId, agent, range);
  const agentData = agentQuery.data?.data;
  const summary = useMemo(() => agentSummary(agentData), [agentData]);

  const configRows = useMemo(
    () => mergeEffectiveConfig(effective.data?.data, report?.effectiveConfig),
    [effective.data, report],
  );
  const modules = useMemo(
    () => moduleRows(instance, agentData, report?.modules),
    [instance, agentData, report],
  );

  const sourceText = (row: ConfigRow) => {
    if (row.source === null) {
      return t('instance:env.config.sourceUnknown');
    }
    switch (row.source.toLowerCase()) {
      case 'file':
        return t('instance:env.config.source.file');
      case 'env':
      case 'environment':
        return t('instance:env.config.source.env');
      case 'server':
      case 'remote':
        return t('instance:env.config.source.server');
      case 'default':
        return t('instance:env.config.source.default');
      case 'system_property':
      case 'system-property':
      case 'sysprop':
        return t('instance:env.config.source.systemProperty');
      default:
        return null;
    }
  };

  const configColumns: DataTableColumn<ConfigRow>[] = [
    {
      id: 'key',
      header: t('instance:env.config.key'),
      value: (row) => row.key,
      mono: true,
      sortable: true,
      render: (row) => <SafeText value={row.key} mono />,
    },
    {
      id: 'value',
      header: t('instance:env.config.value'),
      value: (row) => row.value,
      render: (row) => <SafeText value={row.value} mono maxLines={2} />,
    },
    {
      id: 'source',
      header: t('instance:env.config.sourceHeader'),
      value: (row) => row.source,
      render: (row) => {
        const known = sourceText(row);
        return known === null ? <SafeText value={row.source} /> : <span>{known}</span>;
      },
    },
    {
      id: 'server_value',
      header: t('instance:env.config.serverValue'),
      value: (row) => row.serverValue,
      render: (row) => <SafeText value={row.serverValue} mono maxLines={2} />,
    },
    {
      id: 'server_status',
      header: t('instance:env.config.serverStatus'),
      value: (row) => row.serverStatus,
      render: (row) => {
        switch (row.serverStatus) {
          case 'applied':
            return t('instance:env.config.status.applied');
          case 'pending':
            return t('instance:env.config.status.pending');
          case 'rejected':
            return t('instance:env.config.status.rejected');
          case 'not_allowed':
            return t('instance:env.config.status.notAllowed');
          default:
            return <SafeText value={row.serverStatus} />;
        }
      },
    },
    {
      id: 'remote_allowed',
      header: t('instance:env.config.remoteAllowed'),
      value: (row) => (row.remoteAllowed === null ? null : row.remoteAllowed ? 1 : 0),
      render: (row) =>
        row.remoteAllowed === null ? (
          <SafeText value={null} />
        ) : row.remoteAllowed ? (
          t('instance:env.config.yes')
        ) : (
          t('instance:env.config.no')
        ),
    },
  ];

  const moduleColumns: DataTableColumn<ModuleRow>[] = [
    {
      id: 'name',
      header: t('instance:env.modules.name'),
      value: (row) => row.name,
      sortable: true,
      render: (row) => <SafeText value={row.name} mono />,
    },
    {
      id: 'state',
      header: t('instance:env.modules.state'),
      value: (row) => row.state,
      render: (row) => (
        <span data-state={row.state}>
          {row.state === 'active'
            ? t('instance:env.modules.active')
            : row.state === 'disabled'
              ? t('instance:env.modules.disabled')
              : t('instance:env.modules.unknown')}
          {row.reportedState === null ? null : (
            <>
              {' '}
              (<SafeText value={row.reportedState} />)
            </>
          )}
        </span>
      ),
    },
    {
      id: 'errors_in_range',
      header: t('instance:env.modules.errorsInRange'),
      value: (row) => row.errorsInRange,
      kind: 'count',
      sortable: true,
    },
    {
      id: 'errors_reported',
      header: t('instance:env.modules.errorsReported'),
      value: (row) => row.errorsReported,
      kind: 'count',
    },
    {
      id: 'reason',
      header: t('instance:env.modules.reason'),
      value: (row) => row.reason,
      render: (row) => <SafeText value={row.reason} />,
    },
  ];

  const allow = instance.remote_config_allow;

  return (
    <div className="instance-env" data-testid="instance-env">
      <section className="instance-section" aria-labelledby="instance-env-dump-title">
        <h2 id="instance-env-dump-title" className="instance-section__title">
          {t('instance:env.dump.title')}
        </h2>
        {isAdmin ? (
          <LatestResultPanel appId={appId} instanceId={instanceId} type="env_dump" />
        ) : (
          <Notice testId="instance-env-dump-denied">{t('instance:env.dump.adminOnly')}</Notice>
        )}
      </section>

      <section className="instance-section" aria-labelledby="instance-env-config-title">
        <h2 id="instance-env-config-title" className="instance-section__title">
          {t('instance:env.config.title')}
        </h2>
        <p className="instance-section__meta" data-testid="instance-config-hash">
          {t('instance:env.config.hash')} <SafeText value={instance.config_hash} mono />
          {instance.config_drift === true ? (
            <span
              className="instance-warning instance-warning--warn"
              role="note"
              data-testid="instance-config-drift"
            >
              {t('instance:env.config.drift')}
            </span>
          ) : null}
        </p>
        {!canViewConfig ? (
          <Notice testId="instance-config-denied">{t('instance:env.config.operatorOnly')}</Notice>
        ) : effective.isError ? (
          <ErrorState
            compact
            error={errorInfo(effective.error)}
            onRetry={() => {
              void effective.refetch();
            }}
            data-testid="instance-config-error"
          />
        ) : effective.data === undefined ? (
          <LoadingState variant="table" rows={4} data-testid="instance-config-loading" />
        ) : (
          <>
            {report === null ? (
              <p
                className="instance-notice"
                role="note"
                data-testid="instance-config-source-unknown"
              >
                {t('instance:env.config.sourceUnknownNotice')}{' '}
                {!canSelfDiagnose ? null : latest.available && !diagnosisShown ? (
                  <button
                    type="button"
                    className="instance-link-button"
                    onClick={showDiagnosis}
                    data-testid="instance-config-show-self-diagnosis"
                  >
                    {t('instance:env.config.showSelfDiagnosis')}
                  </button>
                ) : latest.loading ? null : (
                  <button
                    type="button"
                    className="instance-link-button"
                    onClick={onOpenCommands}
                    data-testid="instance-config-run-self-diagnosis"
                  >
                    {t('instance:env.config.runSelfDiagnosis')}
                  </button>
                )}
              </p>
            ) : (
              <p className="instance-section__meta" data-testid="instance-config-reported-at">
                {t('instance:env.config.reportedAt', {
                  at: latest.at === null ? '' : format.dateTime(Date.parse(latest.at)),
                })}
              </p>
            )}
            <div data-testid="instance-config-table">
              <DataTable<ConfigRow>
                tableId="instance-config"
                caption={t('instance:env.config.title')}
                columns={configColumns}
                rows={configRows}
                rowKey={(row) => row.key}
                dataComplete
                density="dense"
                rowAttributes={(row) => ({ 'data-origin': row.origin })}
                empty={{
                  title: t('instance:env.config.empty.title'),
                  description: t('instance:env.config.empty.description'),
                }}
              />
            </div>
          </>
        )}
      </section>

      <section className="instance-section" aria-labelledby="instance-env-allow-title">
        <h2 id="instance-env-allow-title" className="instance-section__title">
          {t('instance:env.allow.title')}
        </h2>
        {allow === null ? (
          <p data-testid="instance-remote-allow" data-missing="true">
            <SafeText value={null} />
          </p>
        ) : allow.length === 0 ? (
          <p data-testid="instance-remote-allow" data-count="0">
            {t('instance:env.allow.none')}
          </p>
        ) : (
          <ul
            className="instance-chips"
            data-testid="instance-remote-allow"
            data-count={allow.length}
          >
            {allow.map((pattern) => (
              <li key={pattern} className="instance-chip">
                <SafeText value={pattern} mono />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="instance-section" aria-labelledby="instance-env-modules-title">
        <h2 id="instance-env-modules-title" className="instance-section__title">
          {t('instance:env.modules.title')}
        </h2>
        {report === null ? (
          <p className="instance-section__meta">{t('instance:env.modules.partial')}</p>
        ) : null}
        <div data-testid="instance-modules-table">
          <DataTable<ModuleRow>
            tableId="instance-modules"
            caption={t('instance:env.modules.title')}
            columns={moduleColumns}
            rows={modules}
            rowKey={(row) => row.name}
            dataComplete
            density="dense"
            loading={agentQuery.isLoading}
            rowAttributes={(row) => ({ 'data-module-state': row.state })}
            empty={{
              title: t('instance:env.modules.empty.title'),
              description: t('instance:env.modules.empty.description'),
            }}
          />
        </div>
      </section>

      <section className="instance-section" aria-labelledby="instance-env-diag-title">
        <h2 id="instance-env-diag-title" className="instance-section__title">
          {t('instance:env.diagnosis.title')}
        </h2>
        <p className="instance-section__meta">{t('instance:env.diagnosis.publicHint')}</p>
        {agentQuery.isError ? (
          <ErrorState
            compact
            error={errorInfo(agentQuery.error)}
            onRetry={() => {
              void agentQuery.refetch();
            }}
            data-testid="instance-diagnosis-error"
          />
        ) : (
          <dl className="instance-summary" data-testid="instance-diagnosis-summary">
            <div data-testid="instance-diagnosis-dropped" data-value={summary.dropped ?? undefined}>
              <dt>{t('instance:env.diagnosis.dropped')}</dt>
              <dd>
                <ValueText value={summary.dropped} unit="count" />
              </dd>
            </div>
            <div data-testid="instance-diagnosis-cpu" data-value={summary.cpuSeconds ?? undefined}>
              <dt>{t('instance:env.diagnosis.cpu')}</dt>
              <dd>
                <ValueText value={summary.cpuSeconds} unit="seconds" />
              </dd>
            </div>
            <div data-testid="instance-diagnosis-heap" data-value={summary.heapBytes ?? undefined}>
              <dt>{t('instance:env.diagnosis.heap')}</dt>
              <dd>
                <ValueText value={summary.heapBytes} unit="bytes" />
              </dd>
            </div>
            <div
              data-testid="instance-diagnosis-stack-capture"
              data-value={summary.stackCaptureSeconds ?? undefined}
            >
              <dt>{t('instance:env.diagnosis.stackCapture')}</dt>
              <dd>
                <ValueText value={summary.stackCaptureSeconds} unit="seconds" />
              </dd>
            </div>
            <div
              data-testid="instance-diagnosis-clock-offset"
              data-value={summary.clockOffsetMs ?? undefined}
            >
              <dt>{t('instance:env.diagnosis.clockOffset')}</dt>
              <dd>
                <ValueText value={summary.clockOffsetMs} unit="ms" />
              </dd>
            </div>
            <div
              data-testid="instance-diagnosis-dictionary-overflow"
              data-value={summary.dictionaryOverflow ?? undefined}
            >
              <dt>{t('instance:env.diagnosis.dictionaryOverflow')}</dt>
              <dd>
                <ValueText value={summary.dictionaryOverflow} unit="count" />
              </dd>
            </div>
          </dl>
        )}
        {canSelfDiagnose ? (
          <LatestResultPanel
            appId={appId}
            instanceId={instanceId}
            type="self_diagnosis"
            reveal={{ revealed: diagnosisShown, onReveal: showDiagnosis }}
          />
        ) : (
          <Notice testId="instance-diagnosis-denied">
            {t('instance:env.diagnosis.operatorOnly')}
          </Notice>
        )}
      </section>
    </div>
  );
}
