// SPDX-License-Identifier: Apache-2.0
//
// Instance detail /apps/:appId/instances/:instanceId (docs/15 §9.8, 05 §2.7, F-U08, WO-37): the
// header with every item of 05 §2.7-1 and the four links of 05 §2.7-6, then the tabs of `tab=`
// (InstanceQ): metrics (05 §2.7-2), environment (05 §2.7-3), commands (05 §2.7-4) and thread
// dumps (05 §2.7-5). Commands and thread dumps need the operator role; other roles see a notice.
//
// Data: `GET /instances/{id}` (refreshed every 30 s), the application's deploy/start/stop/config
// events of the selected range (restart count, chart markers), and the tabs' own queries. An
// instance of another application than the URL's replaces the URL with its own application (ids
// belong to an application, docs/15 §3.1); an unknown instance shows what to do next.
import { useCallback, useEffect, useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate, useParams } from 'react-router';

import { errorInfo, isApiError } from '../../api/errors';
import { useInstance, useInstanceEvents } from '../../api/instances';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAppId } from '../../routes/navigation';
import { useCan } from '../../state/permissions';
import { carriedQuery, InstanceQ } from '../../state/urlState';
import { useClock, useTimeRange } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';

import { CommandsTab } from './commands';
import { DumpsTab } from './dumps';
import { EnvTab } from './env/EnvTab';
import { InstanceHeader } from './header/InstanceHeader';
import { instanceMarkers } from './header/model';
import { InstanceTabs, type InstanceTab } from './InstanceTabs';
import { MetricsTab } from './metrics/MetricsTab';
import { useInstanceRange } from './useInstanceRange';

import './instance.css';

const ID_PATTERN = /^[1-9]\d{0,9}$/;

/** The :instanceId of the route, or null when it is not an instance id. */
function parseInstanceId(raw: string | undefined): number | null {
  if (raw === undefined || !ID_PATTERN.test(raw)) {
    return null;
  }
  const id = Number(raw);
  return id <= 2_147_483_647 ? id : null;
}

export function InstancePage() {
  const { t } = useTranslation(['common', 'instance']);
  const appId = useAppId();
  const params = useParams();
  const instanceId = parseInstanceId(params.instanceId);
  const navigate = useNavigate();
  const location = useLocation();
  const { state, set } = useUrlState(InstanceQ);
  const time = useTimeRange(InstanceQ);
  const dispatch = time.dispatch;
  const range = useInstanceRange(state.time);
  const now = useClock();
  const canOperate = useCan('commands.thread-dump');

  const query = useInstance(instanceId);
  const instance = query.data?.data ?? null;
  const events = useInstanceEvents(instance === null ? null : instance.app.app_id, range);
  const eventList = events.data?.data ?? null;
  const markers = useMemo(
    () => (instance === null ? [] : instanceMarkers(eventList, instance.instance_id)),
    [eventList, instance],
  );

  // Ids belong to an application: show the instance under its own application.
  const ownApp = instance?.app.app_id;
  useEffect(() => {
    if (ownApp !== undefined && appId !== null && ownApp !== appId && instanceId !== null) {
      void navigate(`/apps/${String(ownApp)}/instances/${String(instanceId)}${location.search}`, {
        replace: true,
      });
    }
  }, [appId, instanceId, location.search, navigate, ownApp]);

  const onZoom = useCallback(
    (from: number, to: number) => {
      dispatch({ type: 'chartDrag', from, to });
    },
    [dispatch],
  );
  const onWidenRange = useCallback(() => {
    dispatch({ type: 'zoomOut' });
  }, [dispatch]);
  const setTab = useCallback(
    (tab: InstanceTab) => {
      set({ tab }, { replace: false });
    },
    [set],
  );

  const current = {
    time: state.time,
    live: state.live,
    kind: state.kind,
    env: state.env,
    kiosk: state.kiosk,
  };
  const openList = (): void => {
    if (appId === null) {
      return;
    }
    const carried = carriedQuery(current);
    const path = `/apps/${String(appId)}/instances`;
    void navigate(carried === '' ? path : `${path}?${carried}`);
  };

  const notFound =
    instanceId === null || (query.isError && isApiError(query.error) && query.error.status === 404);

  let pageState: 'loading' | 'empty' | 'error' | 'ready';
  let body: ReactNode;
  if (notFound) {
    pageState = 'empty';
    body = (
      <EmptyState
        title={t('instance:page.notFound.title')}
        description={t('instance:page.notFound.description')}
        actions={
          appId === null
            ? []
            : [
                {
                  label: t('instance:page.notFound.list'),
                  onClick: openList,
                  'data-testid': 'instance-not-found-list',
                },
              ]
        }
        data-testid="instance-not-found"
      />
    );
  } else if (query.isError && instance === null) {
    pageState = 'error';
    body = (
      <ErrorState
        error={errorInfo(query.error)}
        onRetry={() => {
          void query.refetch();
        }}
        data-testid="instance-error"
      />
    );
  } else if (instance === null) {
    pageState = 'loading';
    body = (
      <div data-testid="instance-loading">
        <LoadingState variant="tiles" />
        <LoadingState variant="chart" />
      </div>
    );
  } else {
    pageState = 'ready';
    const operatorOnly = (
      <p className="instance-notice" role="note" data-testid="instance-operator-only">
        {t('instance:page.operatorOnly')}
      </p>
    );
    let panel: ReactNode;
    switch (state.tab) {
      case 'metrics':
        panel = (
          <MetricsTab
            appId={instance.app.app_id}
            instanceId={instance.instance_id}
            range={range}
            markers={markers}
            current={current}
            onZoom={onZoom}
            onWidenRange={onWidenRange}
          />
        );
        break;
      case 'env':
        panel = (
          <EnvTab
            appId={instance.app.app_id}
            instance={instance}
            range={range}
            onOpenCommands={() => {
              setTab('commands');
            }}
          />
        );
        break;
      case 'commands':
        panel = canOperate ? (
          <CommandsTab appId={instance.app.app_id} instance={instance} />
        ) : (
          operatorOnly
        );
        break;
      case 'dumps':
        panel = canOperate ? (
          <DumpsTab appId={instance.app.app_id} instance={instance} />
        ) : (
          operatorOnly
        );
        break;
    }
    body = (
      <>
        {query.isError ? (
          <ErrorState
            compact
            error={errorInfo(query.error)}
            onRetry={() => {
              void query.refetch();
            }}
            data-testid="instance-refresh-error"
          />
        ) : null}
        <InstanceHeader
          instance={instance}
          events={eventList}
          eventsTruncated={events.data?.meta.truncated === true}
          now={now}
          current={current}
        />
        <InstanceTabs active={state.tab} onChange={setTab}>
          {panel}
        </InstanceTabs>
      </>
    );
  }

  return (
    <section
      className="instance-page"
      data-testid="instance-page"
      data-state={pageState}
      data-instance-id={instance?.instance_id}
      aria-labelledby={pageState === 'ready' ? 'instance-title' : 'instance-page-title'}
    >
      {pageState === 'ready' ? null : (
        <h1 id="instance-page-title" className="visually-hidden">
          {t('instance:page.title')}
        </h1>
      )}
      {body}
    </section>
  );
}
