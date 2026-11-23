// SPDX-License-Identifier: Apache-2.0
//
// Shell of every authenticated screen (docs/15 §3): top bar, side menu, notices, filter chips and
// the screen. With ?kiosk=1 the kiosk layout replaces it on the screens that support it (overview,
// dashboard, active; docs/15 §3.4). AppLayout and GlobalLayout below it add the application
// context (docs/15 §3.1).
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';

import { getApp, type App } from '../api/apps';
import { isApiError } from '../api/errors';
import { queryKeys } from '../api/queryKeys';
import { GlobalShortcuts } from '../app/GlobalShortcuts';
import { useDocumentTitle } from '../app/useDocumentTitle';
import { EmptyState } from '../components/EmptyState';
import { SafeText } from '../components/SafeText';
import { useSecondsSinceMessage } from '../realtime/hooks';
import { useAuthStore } from '../state/authStore';
import { rememberRecentApp } from '../state/preferences';
import { useUiStore } from '../state/uiStore';
import { useClock } from '../state/useTimeRange';
import { useUrlState } from '../state/useUrlState';

import { FilterLabelProvider } from './filterLabels';
import { shellLiveSubscription, useShellLiveSubscription } from './liveStatus';
import { useAppId, useCarriedNavigation, useCurrentScreen } from './navigation';
import { UrlFilterChips, UrlNotices } from './Notices';
import { kioskApplies, screenById } from './screens';
import { SideMenu } from './SideMenu';
import { TopBar, useApps } from './TopBar';
import './shell.css';

/**
 * The top bar's application and environment selects, re-exported for the screens that use them
 * too (alert history, rules and the rule editor). Importing them through this module keeps them,
 * and the Ant Design Select they bring, in the shell chunk: imported straight from components/
 * by a lazy screen they would be split into a chunk of their own on the initial path
 * (ADR-0010 C-228 (1), docs/15 §14).
 */
export { AppSelect } from '../components/AppSelect';
export { EnvSelect } from '../components/EnvSelect';

/**
 * Kiosk badge: environment and application, "live, updated N s ago" and the session expiry
 * warning (docs/15 §3.4, §18 #7).
 */
export const KIOSK_EXPIRY_WARNING_MS = 10 * 60_000;
const KIOSK_EXPIRY_WARNING_MINUTES = KIOSK_EXPIRY_WARNING_MS / 60_000;

/** Minutes until the session expires when within 10 minutes, else null. */
export function expiryWarningMinutes(expiresAt: number | null, now: number): number | null {
  if (expiresAt === null) {
    return null;
  }
  const left = expiresAt - now;
  return left > 0 && left <= KIOSK_EXPIRY_WARNING_MS ? Math.max(1, Math.ceil(left / 60_000)) : null;
}

function KioskBadge() {
  const { t } = useTranslation(['common', 'shell']);
  const seconds = useSecondsSinceMessage();
  const expiresAt = useAuthStore((s) => s.sessionExpiresAt);
  const now = useClock(30_000);
  const minutes = expiryWarningMinutes(expiresAt, now);
  const { state } = useUrlState();
  const appId = useAppId();
  const { apps } = useApps();
  const app = appId === null ? undefined : apps.find((a) => a.app_id === appId);
  // Environment and application names only (docs/15 §3.4); collected names render as text.
  const env = app?.env ?? state.env;
  // No live region around the badge: its "updated N s ago" counter changes every second and a
  // polite region would be read out on every tick. The counter is hidden from assistive
  // technology; only the expiry warning is announced, once, with a fixed text when it appears.
  return (
    <div className="kiosk-badge" data-testid="kiosk-badge">
      {env !== undefined && (
        <span className="kiosk-badge__context" data-testid="kiosk-env">
          <SafeText value={env} />
        </span>
      )}
      {app !== undefined && (
        <span className="kiosk-badge__context" data-testid="kiosk-app">
          <SafeText value={app.name} />
        </span>
      )}
      <span aria-hidden="true">
        {seconds === null
          ? t('shell:kiosk.badgeWaiting')
          : t('shell:kiosk.badge', { count: seconds })}
      </span>
      {minutes !== null && (
        <span className="kiosk-badge__expiry" data-testid="kiosk-expiry">
          {t('shell:kiosk.expiring', { count: minutes })}
        </span>
      )}
      <span className="visually-hidden" role="status">
        {minutes !== null &&
          t('shell:kiosk.expiringAnnounce', { count: KIOSK_EXPIRY_WARNING_MINUTES })}
      </span>
    </div>
  );
}

/** Kiosk layout (F-U21, docs/15 §3.4): no top bar or menu, the badge and the screen only. */
export function KioskLayout() {
  return (
    <div className="argos-kiosk" data-testid="app-shell" data-kiosk="1">
      <KioskBadge />
      <main id="argos-main" className="argos-kiosk__content">
        <Outlet />
      </main>
    </div>
  );
}

export function Shell() {
  const { t } = useTranslation(['common', 'shell']);
  const { state } = useUrlState();
  const collapsed = useUiStore((s) => s.sidebarCollapsed);
  const screen = useCurrentScreen();
  // ?kiosk=1 on a screen without kiosk support (statistics, administration, ...) is ignored: the
  // normal shell keeps the navigation (docs/15 §3.4).
  const kiosk = kioskApplies(state.kiosk, screen);
  const appId = useAppId();
  useShellLiveSubscription(
    shellLiveSubscription({ screen, appId, live: state.live, kiosk, env: state.env }),
  );
  if (kiosk) {
    return <KioskLayout />;
  }
  return (
    <div
      className={collapsed ? 'argos-shell argos-shell--menu-collapsed' : 'argos-shell'}
      data-testid="app-shell"
    >
      <a href="#argos-main" className="skip-link">
        {t('shell:skipToContent')}
      </a>
      <TopBar />
      <SideMenu />
      <main id="argos-main" className="argos-shell__content" tabIndex={-1}>
        <UrlNotices />
        {/* The screen may name the keys of its filter values for the chips (filterLabels.tsx). */}
        <FilterLabelProvider>
          <UrlFilterChips />
          <Outlet />
        </FilterLabelProvider>
      </main>
      <GlobalShortcuts />
    </div>
  );
}

/** Screens without an application (docs/15 §3.1). */
export function GlobalLayout() {
  return <Outlet />;
}

export interface CurrentApp {
  readonly appId: number;
  /** The application once loaded (null while loading or on error). */
  readonly app: App | null;
}

const CurrentAppContext = createContext<CurrentApp | null>(null);

/** The application of the current app route (AppLayout); null on global routes. */
export function useCurrentApp(): CurrentApp | null {
  return useContext(CurrentAppContext);
}

/** Application screens: loads the application of :appId (docs/15 §3.1 AppLayout). */
export function AppLayout() {
  const appId = useAppId();
  const { goToScreen } = useCarriedNavigation();
  const query = useQuery({
    queryKey: queryKeys.apps.detail(appId ?? 0),
    queryFn: () => getApp(appId ?? 0),
    enabled: appId !== null,
    staleTime: 60_000,
    retry: false,
  });
  useEffect(() => {
    if (appId !== null && query.isSuccess) {
      rememberRecentApp(appId);
    }
  }, [appId, query.isSuccess]);

  const missing =
    appId === null || (query.isError && isApiError(query.error) && query.error.status === 404);
  if (missing) {
    return (
      <AppNotFound
        onOverview={() => {
          goToScreen(screenById('overview'));
        }}
      />
    );
  }
  return <AppContextProvider appId={appId} app={query.data ?? null} />;
}

/** An unknown :appId: the screen route is not rendered, so this state titles the document. */
function AppNotFound({ onOverview }: { readonly onOverview: () => void }) {
  const { t } = useTranslation(['common', 'shell']);
  const title = t('route.notFound.title');
  useDocumentTitle(title);
  return (
    <EmptyState
      data-testid="app-not-found"
      title={title}
      description={t('route.notFound.description')}
      actions={[{ label: t('shell:pending.overview'), onClick: onOverview }]}
    />
  );
}

function AppContextProvider({ appId, app }: CurrentApp) {
  const value = useMemo(() => ({ appId, app }), [appId, app]);
  return (
    <CurrentAppContext.Provider value={value}>
      <Outlet />
    </CurrentAppContext.Provider>
  );
}
