// SPDX-License-Identifier: Apache-2.0
//
// Top bar of the shell (docs/15 §3.2): application and environment selectors, the global time
// range picker with live toggle, the kind filter, global search (Ctrl/Cmd+K), the firing-alerts
// badge and the account menu. The controls are the shared components (src/components); this file
// connects them to the URL state (useUrlState, useTimeRange).
import { useQuery } from '@tanstack/react-query';
import { useMemo, useRef, useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import { countFiringAlerts, listApps, type App } from '../api/apps';
import { isApiError } from '../api/errors';
import { queryKeys } from '../api/queryKeys';
import { untrusted } from '../api/untrusted';
import { AppSelect } from '../components/AppSelect';
import { EnvSelect } from '../components/EnvSelect';
import { KindFilter } from '../components/KindFilter';
import { Popup } from '../components/Popup';
import { TimeRangePicker, type TimeRangeValue } from '../components/TimeRangePicker';
import { useLogout } from '../features/auth/session';
import { supportedLanguages } from '../i18n';
import { useLanguage } from '../i18n/useLanguage';
import { useStaleSeconds } from '../realtime/hooks';
import { useAuthStore } from '../state/authStore';
import { readRecentApps, rememberRecentApp } from '../state/preferences';
import {
  carriedState,
  defaultState,
  PRESETS,
  SearchQ,
  writeQuery,
  type CommonState,
  type Kind,
  type RelativeRange,
} from '../state/urlState';
import { useTimeRange } from '../state/useTimeRange';
import { useUrlState } from '../state/useUrlState';

import { screenForAppSwitch, useAppId, useCarriedNavigation, useCurrentScreen } from './navigation';
import { screenById } from './screens';

/** id of the search input; Ctrl/Cmd+K focuses it (docs/15 §13). */
export const GLOBAL_SEARCH_ID = 'argos-global-search';

const ROLE_KEYS = {
  viewer: 'shell:role.viewer',
  operator: 'shell:role.operator',
  admin: 'shell:role.admin',
} as const;
const LANGUAGE_LABEL_KEYS = { en: 'language.en', ko: 'language.ko' } as const;

/** Applications for the selectors (cached for a minute). */
export function useApps(): { apps: readonly App[]; loading: boolean } {
  const query = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });
  return { apps: query.data?.data ?? [], loading: query.isPending };
}

function isPreset(range: string): range is RelativeRange {
  return (PRESETS as readonly string[]).includes(range);
}

/** The global time range picker (docs/15 §5, §6.5). */
export function GlobalTimeRange({ liveAvailable }: { readonly liveAvailable: boolean }) {
  const time = useTimeRange();
  // The picker shows presets and absolute ranges; a relative length that is not a preset (after
  // zoom + "now") is shown with its current bounds.
  const value: TimeRangeValue =
    time.range.kind === 'absolute'
      ? time.range
      : isPreset(time.range.range)
        ? { kind: 'relative', range: time.range.range }
        : { kind: 'absolute', from: time.resolved.from, to: time.resolved.to };
  return (
    <TimeRangePicker
      value={value}
      resolved={time.resolved}
      live={time.live}
      liveAvailable={liveAvailable}
      showLiveResume={time.resumeAvailable}
      onPreset={(range) => {
        time.dispatch({ type: 'preset', range });
      }}
      onAbsolute={(from, to) => {
        time.dispatch({ type: 'absolute', from, to });
      }}
      onShift={(direction) => {
        time.dispatch({ type: direction < 0 ? 'prev' : 'next' });
      }}
      onZoom={(direction) => {
        time.dispatch({ type: direction === 'in' ? 'zoomIn' : 'zoomOut' });
      }}
      onNow={() => {
        time.dispatch({ type: 'now' });
      }}
      onLiveChange={(live) => {
        time.dispatch({ type: 'setLive', live });
      }}
      onLiveResume={() => {
        time.dispatch({ type: 'resume' });
      }}
    />
  );
}

/**
 * "No updates for N s" while realtime is silent for more than 10 s (docs/15 §5, §10). The visible
 * counter changes every second, so it is hidden from assistive technology; the always-present
 * live region next to it holds one fixed sentence while the badge is shown (announced once when
 * updates stop) and is empty otherwise.
 */
export function StaleBadge() {
  const { t } = useTranslation(['common', 'shell']);
  const seconds = useStaleSeconds();
  return (
    <>
      <span className="visually-hidden" role="status">
        {seconds !== null && t('shell:realtime.staleAnnounce')}
      </span>
      {seconds !== null && (
        <span
          className="stale-badge"
          title={t('shell:realtime.staleTitle', { count: seconds })}
          data-testid="ws-stale-badge"
        >
          <span aria-hidden="true">{t('shell:realtime.stale', { count: seconds })}</span>
        </span>
      )}
    </>
  );
}

function AppAndEnv() {
  const { t } = useTranslation(['common', 'shell']);
  const navigate = useNavigate();
  const appId = useAppId();
  const screen = useCurrentScreen();
  const { apps, loading } = useApps();
  const { state, set } = useUrlState();
  const { hrefFor } = useCarriedNavigation();
  const [recent, setRecent] = useState<number[]>(readRecentApps);
  const current = apps.find((app) => app.app_id === appId) ?? null;

  const switchApp = (target: number): void => {
    setRecent(rememberRecentApp(target));
    void navigate(hrefFor(screenForAppSwitch(screen), target));
  };

  const envs: readonly string[] = useMemo(() => {
    if (current === null) {
      return [...new Set(apps.map((app) => app.env))];
    }
    // On an app route the environment selector moves to the same-named app of another env.
    return apps.filter((app) => app.name === current.name).map((app) => app.env);
  }, [apps, current]);

  const changeEnv = (env: string | null): void => {
    if (current === null) {
      set({ env: env === null ? undefined : untrusted(env) }, { replace: false });
      return;
    }
    const target = apps.find((app) => app.name === current.name && app.env === env);
    if (target !== undefined && target.app_id !== current.app_id) {
      switchApp(target.app_id);
    }
  };

  // EnvSelect renders every name through SafeText; the names are passed as plain strings.
  const envValue: string | null = current !== null ? current.env : (state.env ?? null);
  return (
    <>
      {/*
        Intentional difference from docs/15 §3.2 (the application selector is active on
        application routes only): it stays enabled on global routes because /overview is still a
        placeholder (WO-31) and the side menu hides the application screens there, so this is the
        only way into an application in this slice. Open PM decision: disable it on global routes
        once WO-31 builds the overview's application list.
      */}
      <AppSelect
        apps={apps}
        value={appId}
        onChange={switchApp}
        recentAppIds={recent}
        loading={loading}
      />
      <EnvSelect
        envs={envs}
        value={envValue}
        onChange={changeEnv}
        allowAll={current === null}
        disabled={current !== null && envs.length < 2}
      />
      {appId !== null && current === null && !loading && (
        <span className="visually-hidden">{t('shell:topbar.app.globalHint')}</span>
      )}
    </>
  );
}

/** `/search?...&q=...` in canonical form, carrying the global keys (docs/15 §9.12). */
export function searchHref(text: string, state: Partial<CommonState>): string {
  const query = writeQuery(SearchQ, {
    ...defaultState(SearchQ),
    ...carriedState(state),
    q: untrusted(text),
  });
  return query === '' ? '/search' : `/search?${query}`;
}

function GlobalSearch() {
  const { t } = useTranslation(['common', 'shell']);
  const navigate = useNavigate();
  const { state } = useUrlState();
  const [text, setText] = useState('');
  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const q = text.trim();
    if (q === '') {
      return;
    }
    void navigate(searchHref(q, state));
  };
  return (
    <form role="search" className="global-search" onSubmit={submit}>
      <label htmlFor={GLOBAL_SEARCH_ID} className="visually-hidden">
        {t('shell:topbar.search.label')}
      </label>
      <input
        id={GLOBAL_SEARCH_ID}
        type="search"
        className="global-search__input"
        placeholder={t('shell:topbar.search.placeholder')}
        title={t('shell:topbar.search.hint')}
        aria-keyshortcuts="Control+K Meta+K"
        maxLength={1024}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
        }}
        data-testid="global-search"
      />
      <kbd className="global-search__kbd" aria-hidden="true">
        {t('shell:topbar.search.shortcut')}
      </kbd>
    </form>
  );
}

function AlertsBadge() {
  const { t } = useTranslation(['common', 'shell']);
  const { state } = useUrlState();
  const env = state.env ?? null;
  const query = useQuery({
    queryKey: queryKeys.alerts.firingCount(env),
    queryFn: () => countFiringAlerts(env ?? undefined),
    refetchInterval: 30_000,
    staleTime: 10_000,
  });
  // The badge is hidden when the API is missing (404) or not allowed (docs/15 §3.2).
  if (
    query.isError &&
    isApiError(query.error) &&
    (query.error.status === 404 || query.error.status === 403)
  ) {
    return null;
  }
  const count = query.data ?? 0;
  const label = t('shell:topbar.alerts.count', { count });
  return (
    <Link
      to="/alerts?state=firing"
      className="alerts-badge"
      aria-label={label}
      title={label}
      data-testid="alerts-badge"
      data-count={count}
    >
      <span className="alerts-badge__icon" aria-hidden="true">
        &#x1F514;
      </span>
      {count > 0 && <span className="alerts-badge__count">{count}</span>}
    </Link>
  );
}

function AccountMenu() {
  const { t } = useTranslation(['common', 'shell']);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const role = useAuthStore((s) => s.role);
  const { language, setLanguage } = useLanguage();
  const logout = useLogout();
  const { hrefFor } = useCarriedNavigation();
  const navigate = useNavigate();
  const roleText = role === null ? t('shell:role.none') : t(ROLE_KEYS[role]);
  return (
    <span className="account-menu">
      <button
        ref={trigger}
        type="button"
        className="account-menu__trigger"
        // The panel mixes the role text, a link, the language select and logout: a dialog, not
        // a menu (the popup role and aria-haspopup must agree).
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={t('shell:topbar.account.label')}
        onClick={() => {
          setOpen((v) => !v);
        }}
        data-testid="account-menu"
      >
        <span aria-hidden="true">&#x1F464;</span>
      </button>
      <Popup
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        label={t('shell:topbar.account.label')}
        triggerRef={trigger}
        align="end"
        role="dialog"
        className="account-menu__panel"
      >
        <p className="account-menu__role" data-testid="account-menu-role">
          {t('shell:topbar.account.role', { role: roleText })}
        </p>
        <button
          type="button"
          className="account-menu__item"
          onClick={() => {
            setOpen(false);
            void navigate(hrefFor(screenById('account')));
          }}
          data-testid="account-menu-page"
        >
          {t('shell:topbar.account.page')}
        </button>
        <label className="account-menu__language">
          <span>{t('shell:topbar.account.language')}</span>
          <select
            value={language}
            onChange={(e) => {
              const next = e.target.value;
              if (next === 'en' || next === 'ko') {
                setLanguage(next);
              }
            }}
            data-testid="language-select"
          >
            {supportedLanguages.map((code) => (
              <option key={code} value={code} lang={code}>
                {t(LANGUAGE_LABEL_KEYS[code])}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="account-menu__item"
          onClick={() => {
            setOpen(false);
            void logout();
          }}
          data-testid="logout"
        >
          {t('shell:topbar.account.logout')}
        </button>
      </Popup>
    </span>
  );
}

export function TopBar() {
  const { t } = useTranslation(['common', 'shell']);
  const screen = useCurrentScreen();
  const { state, set } = useUrlState();
  const { hrefFor } = useCarriedNavigation();
  return (
    <header
      className="argos-shell__topbar topbar"
      data-testid="topbar"
      aria-label={t('shell:topbar.label')}
    >
      <Link
        className="topbar__brand"
        to={hrefFor(screenById('overview'))}
        aria-label={t('shell:topbar.home')}
      >
        {t('app.name')}
      </Link>
      <AppAndEnv />
      <GlobalTimeRange liveAvailable={screen?.live === true} />
      <StaleBadge />
      <KindFilter
        value={state.kind}
        onChange={(kind: Kind) => {
          set({ kind }, { replace: false });
        }}
      />
      <GlobalSearch />
      <AlertsBadge />
      <AccountMenu />
    </header>
  );
}
