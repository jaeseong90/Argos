// SPDX-License-Identifier: Apache-2.0
//
// About this installation `/admin/about` (docs/15 §9.10, WO-39): server version and commit, the UI
// build version (the UI is served by the server), the agent protocol version, the agent versions
// of the connected (non-archived) instances with their counts, the server uptime and the support
// policy document (a path of the source distribution; no outbound call). Every user can see it.
import { useTranslation } from 'react-i18next';

import { useAbout, type About } from '../../../api/admin/about';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';

import '../common/admin.css';

type AgentVersionRow = About['agent_versions'][number];

/** The UI build version (Vite define, the same value the login page shows). */
export const UI_VERSION: string = __ARGOS_VERSION__;

export function AboutPage() {
  const { t } = useTranslation(['common', 'admin']);
  const format = useFormat();
  const query = useAbout();
  const about = query.data?.data ?? null;

  const columns: DataTableColumn<AgentVersionRow>[] = [
    {
      id: 'version',
      header: t('admin:about.agentVersion'),
      value: (row) => row.version,
      width: 220,
      render: (row) =>
        row.version === null ? (
          <span className="admin-muted">{t('admin:about.unknownVersion')}</span>
        ) : (
          <SafeText value={row.version} mono />
        ),
    },
    {
      id: 'count',
      header: t('admin:about.instances'),
      value: (row) => row.count,
      kind: 'count',
      width: 120,
    },
  ];

  const state = query.isPending ? 'loading' : query.isError ? 'error' : 'ready';
  return (
    <section
      className="admin-page"
      aria-labelledby="admin-about-title"
      aria-busy={state === 'loading' || undefined}
      data-testid="admin-about"
      data-state={state}
    >
      <header className="admin-page__header">
        <h1 id="admin-about-title" className="admin-page__title">
          {t('admin:about.title')}
        </h1>
      </header>
      {state === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-about-loading" />
      ) : state === 'error' || about === null ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-about-error"
        />
      ) : (
        <>
          <dl className="admin-dl admin-panel">
            <dt>{t('admin:about.serverVersion')}</dt>
            <dd data-testid="admin-about-version">
              <SafeText value={about.version} />
            </dd>
            <dt>{t('admin:about.commit')}</dt>
            <dd data-testid="admin-about-commit">
              <SafeText value={about.commit} mono />
            </dd>
            <dt>{t('admin:about.uiVersion')}</dt>
            <dd data-testid="admin-about-ui_version">{UI_VERSION}</dd>
            <dt>{t('admin:about.protocol')}</dt>
            <dd data-testid="admin-about-protocol_version">{about.protocol_version}</dd>
            <dt>{t('admin:about.uptime')}</dt>
            <dd data-testid="admin-about-uptime_s" data-value={String(about.uptime_s)}>
              {format.duration(about.uptime_s * 1000)}
            </dd>
            <dt>{t('admin:about.supportPolicy')}</dt>
            <dd data-testid="admin-about-support_policy_url">
              {about.support_policy_url === null ? (
                <span className="admin-muted">{t('admin:about.noSupportPolicy')}</span>
              ) : (
                <span>
                  {t('admin:about.supportPolicyAt')}{' '}
                  <SafeText value={about.support_policy_url} mono />
                </span>
              )}
            </dd>
          </dl>
          {UI_VERSION !== about.version ? (
            <p className="admin-warning" data-testid="admin-about-mismatch">
              {t('admin:about.versionMismatch')}
            </p>
          ) : null}
          <h2 className="admin-panel__title">{t('admin:about.agentVersions')}</h2>
          <DataTable
            tableId="admin-about-agents"
            caption={t('admin:about.agentVersions')}
            columns={columns}
            rows={about.agent_versions}
            rowKey={(row) => (row.version === null ? '' : String(row.version))}
            dataComplete
            empty={{
              title: t('admin:about.noAgents.title'),
              description: t('admin:about.noAgents.description'),
            }}
          />
        </>
      )}
    </section>
  );
}
