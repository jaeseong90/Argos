// SPDX-License-Identifier: Apache-2.0
//
// Privacy `/admin/privacy` (docs/15 §9.10, 05 §2.11-9, docs/16 §6, WO-39): the capture modes each
// instance of an application actually runs (IP, user id, query string, SQL parameters; the agent's
// local setting wins over the server default, so the instance's own report is shown), the
// server's default IP mode, deletion requests with the state of their jobs (followed by job id,
// URL `job=`; the subject is never in the URL, SEC-33) and the data retention panel. For
// administrators (`privacy.delete`); the server enforces the role (docs/16 §7.10).
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  getPrivacyJob,
  getPrivacySettings,
  IP_MODES,
  isJobActive,
  PRIVACY_JOB_POLL_MS,
  privacyKeys,
  updatePrivacySettings,
  type DeletionAccepted,
  type IpMode,
  type PrivacyJob,
} from '../../../api/admin/privacy';
import { listApps } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { instanceKeys, listInstances, type Instance } from '../../../api/instances';
import { queryKeys } from '../../../api/queryKeys';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { AdminPrivacyQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { MISSING_DASH } from '../../../utils/missing';
import { AdminAppPicker } from '../common/AdminAppPicker';
import { TimeCell } from '../common/cells';

import { DeletionForm } from './DeletionForm';
import { jobStatusLabel, modeLabel, noticeLabel } from './labels';
import { RetentionPanel } from './RetentionPanel';

import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

/** Most jobs followed on the page at once (the newest kept). */
const JOBS_SHOWN_MAX = 10;

export function AdminPrivacyPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const { state, set } = useUrlState(AdminPrivacyQ);
  const appId = state.app ?? null;

  const apps = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });
  const instances = useQuery({
    queryKey: instanceKeys.list(appId),
    queryFn: ({ signal }) => listInstances(appId ?? 0, signal),
    enabled: appId !== null,
  });
  const settings = useQuery({
    queryKey: privacyKeys.settings(),
    queryFn: ({ signal }) => getPrivacySettings(signal),
  });

  // Jobs requested on this page (newest first) plus the one in the URL (a reload keeps it).
  const [requested, setRequested] = useState<readonly string[]>([]);
  const [notices, setNotices] = useState<readonly string[]>([]);
  const jobIds = useMemo(() => {
    const ids = [...requested];
    if (state.job !== undefined && !ids.includes(state.job)) {
      ids.push(state.job);
    }
    return ids.slice(0, JOBS_SHOWN_MAX);
  }, [requested, state.job]);
  const jobs = useQueries({
    queries: jobIds.map((jobId) => ({
      queryKey: privacyKeys.job(jobId),
      queryFn: ({ signal }: { signal: AbortSignal }) => getPrivacyJob(jobId, signal),
      refetchInterval: (query: { state: { data: PrivacyJob | undefined } }) =>
        isJobActive(query.state.data) ? PRIVACY_JOB_POLL_MS : false,
      refetchIntervalInBackground: false,
    })),
  });
  const jobRows = jobs.flatMap((job) => (job.data === undefined ? [] : [job.data]));
  const jobError = jobs.find((job) => job.isError)?.error ?? null;

  const queued = useCallback(
    (accepted: DeletionAccepted) => {
      setRequested((previous) => [
        accepted.job_id,
        ...previous.filter((id) => id !== accepted.job_id),
      ]);
      setNotices(accepted.notices);
      set({ job: accepted.job_id });
    },
    [set],
  );

  const [modeDraft, setModeDraft] = useState<IpMode | null>(null);
  const [modePending, setModePending] = useState(false);
  const [modeError, setModeError] = useState<unknown>(null);
  const [modeSaved, setModeSaved] = useState(false);
  const saveMode = async (mode: IpMode): Promise<void> => {
    setModePending(true);
    setModeError(null);
    try {
      const saved = await updatePrivacySettings(mode);
      queryClient.setQueryData(privacyKeys.settings(), saved);
      setModeDraft(null);
      setModeSaved(true);
    } catch (caught) {
      setModeError(caught);
    } finally {
      setModePending(false);
    }
  };

  const mode = (value: string | null) =>
    value === null ? (
      <span className="admin-muted">{MISSING_DASH}</span>
    ) : (
      <span data-mode={value}>{modeLabel(t, value)}</span>
    );
  const modeColumns: DataTableColumn<Instance>[] = [
    {
      id: 'name',
      header: t('admin:privacy.modes.column.instance'),
      value: (row) => row.name,
      width: 200,
      render: (row) => <SafeText value={row.name} data-testid="admin-privacy-instance" />,
    },
    {
      id: 'ip_mode',
      header: t('admin:privacy.modes.column.ip'),
      value: (row) => row.ip_mode,
      width: 120,
      render: (row) => mode(row.ip_mode),
    },
    {
      id: 'user_id_mode',
      header: t('admin:privacy.modes.column.userId'),
      value: (row) => row.user_id_mode,
      width: 120,
      render: (row) => mode(row.user_id_mode),
    },
    {
      id: 'query_string_mode',
      header: t('admin:privacy.modes.column.queryString'),
      value: (row) => row.query_string_mode,
      width: 140,
      render: (row) => mode(row.query_string_mode),
    },
    {
      id: 'sql_param_mode',
      header: t('admin:privacy.modes.column.sqlParam'),
      value: (row) => row.sql_param_mode,
      width: 140,
      render: (row) => mode(row.sql_param_mode),
    },
  ];

  const jobColumns: DataTableColumn<PrivacyJob>[] = [
    {
      id: 'created_at',
      header: t('admin:privacy.jobs.column.createdAt'),
      value: (row) => row.created_at,
      width: 170,
      render: (row) => <TimeCell iso={row.created_at} />,
    },
    {
      id: 'subject_kind',
      header: t('admin:privacy.jobs.column.kind'),
      value: (row) => row.subject_kind,
      width: 110,
      render: (row) =>
        row.subject_kind === 'ip'
          ? t('admin:privacy.delete.kindIp')
          : t('admin:privacy.delete.kindUserId'),
    },
    {
      id: 'status',
      header: t('admin:privacy.jobs.column.status'),
      value: (row) => row.status,
      width: 110,
      render: (row) => (
        <span data-testid="admin-privacy-job-status" data-status={row.status}>
          {jobStatusLabel(t, row.status)}
        </span>
      ),
    },
    {
      id: 'tables',
      header: t('admin:privacy.jobs.column.tables'),
      value: (row) =>
        row.tables.length === 0
          ? null
          : `${String(row.tables.filter((table) => table.done).length)} / ${String(row.tables.length)}`,
      width: 110,
    },
    {
      id: 'rows_affected',
      header: t('admin:privacy.jobs.column.rows'),
      value: (row) => row.rows_affected,
      kind: 'count',
      width: 110,
    },
    {
      id: 'warnings',
      header: t('admin:privacy.jobs.column.warnings'),
      value: (row) =>
        row.warnings.length === 0 && row.error === null
          ? null
          : [...row.warnings, ...(row.error === null ? [] : [row.error])]
              .map((code) => noticeLabel(t, code))
              .join('; '),
      width: 240,
    },
    {
      id: 'finished_at',
      header: t('admin:privacy.jobs.column.finishedAt'),
      value: (row) => row.finished_at,
      width: 170,
      render: (row) => <TimeCell iso={row.finished_at} />,
    },
  ];

  const appList = apps.data?.data ?? [];
  let screenState: ScreenState;
  if (apps.isPending || settings.isPending) {
    screenState = 'loading';
  } else if (apps.isError || settings.isError) {
    screenState = 'error';
  } else if (appList.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }
  const currentMode = modeDraft ?? settings.data?.default_ip_mode ?? 'masked';

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-privacy-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-privacy"
      data-state={screenState}
    >
      <header className="admin-page__header">
        <h1 id="admin-privacy-title" className="admin-page__title">
          {t('admin:privacy.title')}
        </h1>
        <AdminAppPicker
          apps={appList}
          value={appId}
          onChange={(next) => {
            set({ app: next });
          }}
          disabled={apps.isPending}
        />
      </header>
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-privacy-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(apps.isError ? apps.error : settings.error)}
          onRetry={() => {
            void apps.refetch();
            void settings.refetch();
          }}
          data-testid="admin-privacy-error"
        />
      ) : (
        <>
          <section className="admin-panel" aria-labelledby="admin-privacy-modes-title">
            <h2 id="admin-privacy-modes-title" className="admin-panel__title">
              {t('admin:privacy.modes.title')}
            </h2>
            <p className="admin-note">{t('admin:privacy.modes.help')}</p>
            {appId === null ? (
              <p className="admin-muted" data-testid="admin-privacy-modes-choose">
                {t('admin:privacy.modes.chooseApp')}
              </p>
            ) : (
              <DataTable
                tableId="admin-privacy-modes"
                caption={t('admin:privacy.modes.caption')}
                columns={modeColumns}
                rows={instances.data?.data ?? []}
                rowKey={(row) => String(row.instance_id)}
                dataComplete
                density="dense"
                loading={instances.isPending}
                error={instances.isError ? errorInfo(instances.error) : null}
                onRetry={() => {
                  void instances.refetch();
                }}
                empty={{
                  title: t('admin:privacy.modes.emptyTitle'),
                  description: t('admin:privacy.modes.emptyDescription'),
                }}
                rowAttributes={(row) => ({ 'data-instance-id': String(row.instance_id) })}
              />
            )}
          </section>

          <section
            className="admin-panel"
            aria-labelledby="admin-privacy-settings-title"
            data-testid="admin-privacy-settings"
            data-mode={settings.data?.default_ip_mode}
            data-source={settings.data?.default_ip_mode_source}
          >
            <h2 id="admin-privacy-settings-title" className="admin-panel__title">
              {t('admin:privacy.settings.title')}
            </h2>
            <div className="admin-toolbar">
              <label className="admin-field admin-field--inline">
                <span className="admin-field__label">{t('admin:privacy.settings.ipMode')}</span>
                <select
                  className="admin-select"
                  value={currentMode}
                  onChange={(event) => {
                    const next = IP_MODES.find((m) => m === event.target.value);
                    if (next !== undefined) {
                      setModeSaved(false);
                      setModeDraft(next);
                    }
                  }}
                  data-testid="admin-privacy-ip-mode"
                >
                  {IP_MODES.map((m) => (
                    <option key={m} value={m}>
                      {modeLabel(t, m)}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="control-button control-button--primary"
                disabled={
                  modePending || modeDraft === null || modeDraft === settings.data?.default_ip_mode
                }
                onClick={() => {
                  if (modeDraft !== null) {
                    void saveMode(modeDraft);
                  }
                }}
                data-testid="admin-privacy-settings-save"
              >
                {t('admin:privacy.settings.save')}
              </button>
              <span className="admin-muted">
                {settings.data?.default_ip_mode_source === 'settings'
                  ? t('admin:privacy.settings.sourceSettings')
                  : t('admin:privacy.settings.sourceConfig')}
              </span>
            </div>
            <p className="admin-field__help">{t('admin:privacy.settings.help')}</p>
            {modeSaved ? (
              <p className="admin-note" role="status" data-testid="admin-privacy-settings-saved">
                {t('admin:privacy.settings.saved')}
              </p>
            ) : null}
            {modeError === null ? null : (
              <ErrorState
                compact
                error={errorInfo(modeError)}
                data-testid="admin-privacy-settings-error"
              />
            )}
          </section>

          <DeletionForm apps={appList} defaultAppId={appId} onQueued={queued} />

          <section className="admin-panel" aria-labelledby="admin-privacy-jobs-title">
            <h2 id="admin-privacy-jobs-title" className="admin-panel__title">
              {t('admin:privacy.jobs.title')}
            </h2>
            {notices.length === 0 ? null : (
              <ul className="admin-warning" data-testid="admin-privacy-notices">
                {notices.map((code) => (
                  <li key={code}>{noticeLabel(t, code)}</li>
                ))}
              </ul>
            )}
            {jobError === null ? null : (
              <ErrorState
                compact
                error={errorInfo(jobError)}
                data-testid="admin-privacy-jobs-error"
              />
            )}
            <DataTable
              tableId="admin-privacy-jobs"
              caption={t('admin:privacy.jobs.caption')}
              columns={jobColumns}
              rows={jobRows}
              rowKey={(row) => row.job_id}
              dataComplete
              density="dense"
              loading={jobIds.length > 0 && jobRows.length === 0 && jobError === null}
              empty={{
                title: t('admin:privacy.jobs.emptyTitle'),
                description: t('admin:privacy.jobs.emptyDescription'),
              }}
              rowAttributes={(row) => ({ 'data-job-id': row.job_id, 'data-status': row.status })}
            />
          </section>

          <RetentionPanel />
        </>
      )}
    </section>
  );
}
