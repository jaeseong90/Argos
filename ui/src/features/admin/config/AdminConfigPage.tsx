// SPDX-License-Identifier: Apache-2.0
//
// Configuration changes `/admin/config` (docs/15 §9.10, 05 §2.11-5 and §2.11-6, F-S29, T-ACPT-18,
// WO-39): choose an application (URL `app=`), see its current configuration and drift, change
// the keys every instance allows remotely with a reason, preview the diff, apply, follow the
// per-instance results (URL `change=`), the history and the revert; and the kill switch. For
// administrators (`config.push`); the server enforces the role on every call (docs/16 §7.10).
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { configKeys, getAppConfig, type ConfigChange } from '../../../api/admin/config';
import { listApps } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { AdminConfigQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { AdminAppPicker } from '../common/AdminAppPicker';

import { ChangeHistory } from './ChangeHistory';
import { ChangeResults } from './ChangeResults';
import { ConfigEditor } from './ConfigEditor';
import { KillSwitchSection } from './KillSwitchSection';
import { instanceNames } from './model';

import '../common/admin.css';
import './config.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

export function AdminConfigPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const { state, set } = useUrlState(AdminConfigQ);
  const appId = state.app ?? null;

  const apps = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });
  const config = useQuery({
    queryKey: configKeys.app(appId ?? 0),
    queryFn: ({ signal }) => getAppConfig(appId ?? 0, signal),
    enabled: appId !== null,
  });
  const names = useMemo(() => instanceNames(config.data), [config.data]);

  const openChange = useCallback(
    (changeId: string) => {
      set({ change: changeId });
    },
    [set],
  );
  const refresh = useCallback(() => {
    if (appId !== null) {
      void queryClient.invalidateQueries({ queryKey: configKeys.app(appId) });
      void queryClient.invalidateQueries({ queryKey: configKeys.history(appId) });
    }
  }, [appId, queryClient]);
  const applied = useCallback(
    (change: ConfigChange) => {
      queryClient.setQueryData(configKeys.change(change.change_id), change);
      refresh();
      openChange(change.change_id);
    },
    [openChange, queryClient, refresh],
  );

  let screenState: ScreenState;
  if (apps.isPending || (appId !== null && config.isPending)) {
    screenState = 'loading';
  } else if (apps.isError || config.isError) {
    screenState = 'error';
  } else if (appId === null || apps.data.data.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-config-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-config"
      data-state={screenState}
    >
      <header className="admin-page__header">
        <h1 id="admin-config-title" className="admin-page__title">
          {t('admin:config.title')}
        </h1>
        <AdminAppPicker
          apps={apps.data?.data ?? []}
          value={appId}
          onChange={(next) => {
            set({ app: next, change: undefined });
          }}
          disabled={apps.isPending}
        />
      </header>
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-config-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(apps.isError ? apps.error : config.error)}
          onRetry={() => {
            void (apps.isError ? apps.refetch() : config.refetch());
          }}
          data-testid="admin-config-error-state"
        />
      ) : screenState === 'empty' || config.data === undefined || appId === null ? (
        <EmptyState
          title={
            (apps.data?.data.length ?? 0) === 0
              ? t('admin:config.empty.noAppsTitle')
              : t('admin:config.empty.chooseTitle')
          }
          description={
            (apps.data?.data.length ?? 0) === 0
              ? t('admin:config.empty.noAppsDescription')
              : t('admin:config.empty.chooseDescription')
          }
          data-testid="admin-config-empty"
        />
      ) : config.data.remote_allowed_by_instance.length === 0 ? (
        <EmptyState
          title={t('admin:config.empty.noInstancesTitle')}
          description={t('admin:config.empty.noInstancesDescription')}
          data-testid="admin-config-no-instances"
        />
      ) : (
        <>
          <ConfigEditor
            key={appId}
            appId={appId}
            config={config.data}
            names={names}
            onApplied={applied}
          />
          {state.change === undefined ? null : (
            <ChangeResults
              changeId={state.change}
              names={names}
              onOpenChange={openChange}
              onSettled={refresh}
            />
          )}
          <KillSwitchSection
            key={`ks-${String(appId)}`}
            appId={appId}
            config={config.data}
            onChanged={applied}
          />
          <ChangeHistory appId={appId} selected={state.change} onOpen={openChange} />
        </>
      )}
    </section>
  );
}
