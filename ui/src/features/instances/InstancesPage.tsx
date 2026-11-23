// SPDX-License-Identifier: Apache-2.0
//
// Instances of an application /apps/:appId/instances (docs/15 §3.1, WO-37 minimal list; the
// administration table with archive and delete is /admin/instances, WO-39): name (link to the
// detail), state, application version, agent version with the compatibility warning, host,
// uptime, IP capture mode, virtual threads and last heartbeat. `GET /apps/{appId}/instances`,
// refreshed every 30 s. Collected strings render through SafeText (SEC-60); missing values are the
// D-23 dash.
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { errorInfo } from '../../api/errors';
import { useInstances, type Instance } from '../../api/instances';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { SafeLink } from '../../components/SafeLink';
import { SafeText } from '../../components/SafeText';
import { StatusBadge } from '../../components/StatusBadge';
import { useFormat } from '../../i18n/format';
import { useAppId } from '../../routes/navigation';
import { CommonQ } from '../../state/urlState';
import { useClock } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';

import { compatOf, ipModeOf, uptimeMs, virtualThreadMode, warnReasons } from './header/model';
import { instanceUrl } from './links';

import './instance.css';

const EMPTY: readonly Instance[] = [];

export function InstancesPage() {
  const { t } = useTranslation(['common', 'instance']);
  const format = useFormat();
  const navigate = useNavigate();
  const appId = useAppId();
  const { state } = useUrlState(CommonQ);
  const now = useClock();
  const query = useInstances(appId);
  const rows = query.data?.data ?? EMPTY;
  const current = useMemo(
    () => ({
      time: state.time,
      live: state.live,
      kind: state.kind,
      env: state.env,
      kiosk: state.kiosk,
    }),
    [state.time, state.live, state.kind, state.env, state.kiosk],
  );
  const detailUrl = (row: Instance): string =>
    instanceUrl(row.app.app_id, row.instance_id, current);

  const columns: DataTableColumn<Instance>[] = [
    {
      id: 'name',
      header: t('instance:list.name'),
      value: (row) => row.name,
      sortable: true,
      render: (row) => (
        <SafeLink
          href={detailUrl(row)}
          onNavigate={(path) => {
            void navigate(path);
          }}
          data-testid={`instances-link-${String(row.instance_id)}`}
        >
          <SafeText value={row.name} />
        </SafeLink>
      ),
    },
    {
      id: 'state',
      header: t('instance:list.state'),
      value: (row) => row.state,
      sortable: true,
      render: (row) => <StatusBadge status={row.state} warnReasons={warnReasons(row)} />,
    },
    {
      id: 'version',
      header: t('instance:header.version'),
      value: (row) => row.version,
      render: (row) => <SafeText value={row.version} />,
    },
    {
      id: 'agent_version',
      header: t('instance:header.agentVersion'),
      value: (row) => row.agent_version,
      render: (row) => {
        const compat = compatOf(row);
        return (
          <span data-compat={compat ?? undefined}>
            <SafeText value={row.agent_version} />
            {compat === 'old' ? (
              <span className="instance-warning instance-warning--warn">
                {t('instance:list.compatOld')}
              </span>
            ) : compat === 'unsupported' ? (
              <span className="instance-warning instance-warning--error">
                {t('instance:list.compatUnsupported')}
              </span>
            ) : null}
          </span>
        );
      },
    },
    {
      id: 'host',
      header: t('instance:header.host'),
      value: (row) => row.host,
      sortable: true,
      render: (row) => <SafeText value={row.host} />,
    },
    {
      id: 'uptime',
      header: t('instance:header.uptime'),
      value: (row) => uptimeMs(row, now),
      kind: 'ms',
      sortable: true,
      render: (row) => {
        const uptime = uptimeMs(row, now);
        return uptime === null ? <SafeText value={null} /> : <span>{format.duration(uptime)}</span>;
      },
    },
    {
      id: 'ip_mode',
      header: t('instance:list.ipMode'),
      value: (row) => ipModeOf(row),
      render: (row) => {
        switch (ipModeOf(row)) {
          case 'full':
            return t('instance:header.ipMode.full');
          case 'masked':
            return t('instance:header.ipMode.masked');
          case 'off':
            return t('instance:header.ipMode.off');
          case null:
            return <SafeText value={null} />;
        }
      },
    },
    {
      id: 'virtual_threads',
      header: t('instance:list.virtualThreads'),
      value: (row) => virtualThreadMode(row),
      render: (row) => {
        switch (virtualThreadMode(row)) {
          case 'on':
            return t('instance:header.virtualThreads.on');
          case 'off':
            return t('instance:header.virtualThreads.off');
          case null:
            return <SafeText value={null} />;
        }
      },
    },
    {
      id: 'last_heartbeat_at',
      header: t('instance:list.lastHeartbeat'),
      value: (row) => row.last_heartbeat_at,
      sortable: true,
      render: (row) => {
        const at = row.last_heartbeat_at === null ? NaN : Date.parse(row.last_heartbeat_at);
        return Number.isFinite(at) ? (
          <time dateTime={row.last_heartbeat_at ?? undefined} title={format.dateTimeWithOffset(at)}>
            {format.relative(at, now)}
          </time>
        ) : (
          <SafeText value={null} />
        );
      },
    },
  ];

  const pageState = query.isError
    ? 'error'
    : query.data === undefined
      ? 'loading'
      : rows.length === 0
        ? 'empty'
        : 'ready';

  return (
    <section
      className="instance-page"
      data-testid="instances-page"
      data-state={pageState}
      aria-labelledby="instances-title"
    >
      <h1 id="instances-title" className="instance-page__title">
        {t('instance:list.title')}
      </h1>
      {query.isError ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="instances-error"
        />
      ) : (
        <div data-testid="instances-table">
          <DataTable<Instance>
            tableId="instances"
            caption={t('instance:list.title')}
            columns={columns}
            rows={rows}
            rowKey={(row) => String(row.instance_id)}
            dataComplete
            loading={query.data === undefined}
            onRowOpen={(row) => {
              void navigate(detailUrl(row));
            }}
            empty={{
              title: t('instance:list.empty.title'),
              description: t('instance:list.empty.description'),
            }}
            rowAttributes={(row) => ({
              'data-testid': `instances-row-${String(row.instance_id)}`,
              'data-state': row.state,
            })}
          />
        </div>
      )}
    </section>
  );
}
