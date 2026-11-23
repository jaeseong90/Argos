// SPDX-License-Identifier: Apache-2.0
//
// Overview /overview (docs/15 §9.1, 05 §2.10, F-U17, WO-31): every application of the environment
// and group filter with its status (worst instance), instances up/total, TPS, p95, error rate,
// active and running batches, firing alerts by severity, last deploy and one-hour sparklines; the
// all-application firing panel and the collection server panel on the right. The screen after
// sign-in (route `/`), also the kiosk status board (`?kiosk=1`, F-U21).
//
// Data: `GET /overview` (last 5 minutes and the last hour per minute; the API takes no time range)
// every 10 s while the tab is visible, and in live mode or kiosk the `overview` topic every 5 s on
// top of it (mergeLive, ADR-0010 C-213 (3)), with exactly the parameters of the shell's own live
// subscription (routes/liveStatus.ts), so WsManager shares one server subscription. Abnormal
// applications are pinned on top whatever the sort (sortOverview). A row or card opens the
// application's dashboard with the global keys (time range, live, kind, env, kiosk). Shared state
// is URL state (OverviewQ: env, group, kind, sort); the card/table choice is a browser preference.
import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { listApps } from '../../api/apps';
import { errorInfo } from '../../api/errors';
import { useOverviewQuery } from '../../api/overview';
import { queryKeys } from '../../api/queryKeys';
import { untrusted } from '../../api/untrusted';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import type { PushFrame } from '../../realtime/frames';
import { useTopic } from '../../realtime/hooks';
import { hrefWithState } from '../../routes/navigation';
import { screenById, screenPath } from '../../routes/screens';
import { AlertsQ, carriedState, defaultState, OverviewQ, writeQuery } from '../../state/urlState';
import { useClock } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';

import { FiringPanel } from './FiringPanel';
import { GroupSelect } from './GroupSelect';
import { mergeLive, parseOverviewPush, type OverviewPush } from './mergeLive';
import { OverviewCards } from './OverviewCards';
import { OverviewTable } from './OverviewTable';
import { ServerHealthPanel } from './ServerHealthPanel';
import {
  DEFAULT_OVERVIEW_SORT,
  nextOverviewSort,
  OVERVIEW_SORT_IDS,
  overviewSortFromUrl,
  sortOverview,
  type OverviewSort,
  type OverviewSortId,
} from './sortOverview';
import { readOverviewView, writeOverviewView, type OverviewView } from './viewPreference';

import './overviewPage.css';

/** The two views of the overview, table first (the default). */
const VIEW_OPTIONS = ['table', 'cards'] as const satisfies readonly OverviewView[];

const SORT_ASC_MARK = '▲';
const SORT_DESC_MARK = '▼';

const SORT_LABEL_KEYS = {
  status: 'overview:sort.status',
  error_rate: 'overview:sort.errorRate',
  p95: 'overview:sort.p95',
  firing: 'overview:sort.firing',
} as const satisfies Record<OverviewSortId, string>;

/** Testid suffix of a sort field (error_rate → error-rate). */
export function sortTestId(id: OverviewSortId): string {
  return `overview-sort-${id.replace('_', '-')}`;
}

interface PushState {
  readonly key: string | null;
  readonly push: OverviewPush | null;
}

export function OverviewPage() {
  const { t } = useTranslation(['common', 'overview']);
  const navigate = useNavigate();
  const { state, set } = useUrlState(OverviewQ);
  const env = state.env;
  const group = state.group;
  const kind = state.kind;
  const query = useOverviewQuery({ env, group, kind });
  const now = useClock(30_000);

  // Group options: the distinct groups of the applications (the shell's cached app list).
  const appsQuery = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });
  const groups = useMemo(() => {
    const names = new Set<string>();
    for (const app of appsQuery.data?.data ?? []) {
      if (app.group !== null && (env === undefined || app.env === env)) {
        names.add(app.group);
      }
    }
    if (group !== undefined) {
      names.add(group);
    }
    // Only groups the URL can carry (OverviewQ group pattern); the server takes the same value.
    return [...names].filter((name) => OverviewQ.group.schema.safeParse(name).success);
  }, [appsQuery.data, env, group]);

  // Live: the overview topic with the shell's parameters (shared subscription).
  const realtime = state.live || state.kiosk;
  const pushKey = realtime ? `overview:${env ?? ''}` : null;
  const [pushState, setPushState] = useState<PushState>({ key: null, push: null });
  const push = pushState.key === pushKey ? pushState.push : null;
  const refetch = query.refetch;
  useTopic('overview', realtime ? (env === undefined ? {} : { env }) : null, {
    onData: (frame: PushFrame) => {
      const parsed = parseOverviewPush(frame.t, frame.data);
      if (parsed !== null) {
        setPushState({ key: pushKey, push: parsed });
      }
    },
    onResync: () => {
      void refetch();
    },
  });

  const data = query.data;
  const restAt = data === undefined ? null : Date.parse(data.meta.generated_at);
  const apps = useMemo(
    () =>
      mergeLive(
        data?.data.apps ?? [],
        push,
        restAt === null || Number.isNaN(restAt) ? null : restAt,
      ),
    [data, push, restAt],
  );
  const urlSort = overviewSortFromUrl(state.sort);
  const sort: OverviewSort = urlSort ?? DEFAULT_OVERVIEW_SORT;
  const rows = useMemo(() => sortOverview(apps, urlSort), [apps, urlSort]);

  const [view, setView] = useState<OverviewView>(readOverviewView);
  const chooseView = (next: OverviewView): void => {
    setView(next);
    writeOverviewView(next);
  };

  const applySort = useCallback(
    (next: OverviewSort) => {
      set({ sort: `${next.id}:${next.desc ? 'desc' : 'asc'}` });
    },
    [set],
  );

  // Links carry the global keys (docs/15 §5): time range, live, kind, env, kiosk.
  const hrefOf = useCallback(
    (appId: number) => hrefWithState(screenPath(screenById('dashboard'), appId), state),
    [state],
  );
  const openApp = useCallback(
    (appId: number) => {
      void navigate(hrefOf(appId));
    },
    [hrefOf, navigate],
  );
  const historyHref = useCallback(
    (alertId: string | null, rowState?: string | null) => {
      // An event id the URL cannot carry opens the firing list without the highlight.
      const alert =
        alertId !== null && AlertsQ.alert.schema.safeParse(alertId).success ? alertId : undefined;
      // An acknowledged row opens the acknowledged alerts (ADR-0010 C-226 (8)); every other row
      // and the panel link open the firing ones.
      const filter = rowState?.toUpperCase() === 'ACKED' ? 'acked' : 'firing';
      const search = writeQuery(AlertsQ, {
        ...defaultState(AlertsQ),
        ...carriedState(state),
        state: filter,
        alert,
      });
      return `/alerts?${search}`;
    },
    [state],
  );

  // GroupSelect offers only names of the URL pattern and FiringPanel sends the environment as a
  // query parameter only: both are passed as plain strings (like the top bar's EnvSelect).
  const groupValue: string | null = group ?? null;
  const envParam: string | undefined = env;

  const filtered = env !== undefined || group !== undefined;
  const clearFilters = (): void => {
    set({ env: undefined, group: undefined }, { replace: false });
  };

  const toolbar = (
    <div className="overview-toolbar">
      <GroupSelect
        groups={groups}
        value={groupValue}
        onChange={(next) => {
          set({ group: next === null ? undefined : untrusted(next) }, { replace: false });
        }}
      />
      <div className="overview-toolbar__group" role="group" aria-label={t('overview:sort.label')}>
        <span className="overview-toolbar__label" aria-hidden="true">
          {t('overview:sort.label')}
        </span>
        <span className="segmented" title={t('overview:sort.pinnedHint')}>
          {OVERVIEW_SORT_IDS.map((id) => {
            const active = sort.id === id;
            return (
              <button
                key={id}
                type="button"
                className={`segmented__option${active ? ' segmented__option--checked' : ''}`}
                aria-pressed={active}
                onClick={() => {
                  applySort(nextOverviewSort(sort, id));
                }}
                data-testid={sortTestId(id)}
                data-desc={active ? String(sort.desc) : undefined}
              >
                {t(SORT_LABEL_KEYS[id])}
                {active ? (
                  <span className="overview-toolbar__mark" aria-hidden="true">
                    {sort.desc ? SORT_DESC_MARK : SORT_ASC_MARK}
                  </span>
                ) : null}
              </button>
            );
          })}
        </span>
      </div>
      <div className="segmented" role="group" aria-label={t('overview:view.label')}>
        {VIEW_OPTIONS.map((option) => (
          <button
            key={option}
            type="button"
            className={`segmented__option${view === option ? ' segmented__option--checked' : ''}`}
            aria-pressed={view === option}
            onClick={() => {
              chooseView(option);
            }}
            data-testid={`overview-view-${option}`}
          >
            {option === 'table' ? t('overview:view.table') : t('overview:view.cards')}
          </button>
        ))}
      </div>
    </div>
  );

  let body: ReactNode;
  let pageState: 'loading' | 'error' | 'empty' | 'ready';
  if (data === undefined) {
    pageState = query.isError ? 'error' : 'loading';
    body = query.isError ? (
      <ErrorState
        error={errorInfo(query.error)}
        onRetry={() => {
          void refetch();
        }}
        onClearFilters={filtered ? clearFilters : undefined}
        data-testid="overview-error"
      />
    ) : (
      <LoadingState variant="table" rows={12} slowHint="wait" data-testid="overview-loading" />
    );
  } else if (rows.length === 0) {
    pageState = 'empty';
    body = (
      <>
        {toolbar}
        <EmptyState
          title={t('overview:page.empty.title')}
          description={
            filtered ? t('overview:page.empty.description') : t('overview:page.empty.noFilter')
          }
          onClearFilters={filtered ? clearFilters : undefined}
          data-testid="overview-empty"
        />
      </>
    );
  } else {
    pageState = 'ready';
    body =
      view === 'table' ? (
        <OverviewTable
          rows={rows}
          sort={sort}
          onSort={applySort}
          hrefOf={hrefOf}
          onOpen={openApp}
          toolbar={toolbar}
          now={now}
        />
      ) : (
        <>
          <div className="overview-page__cards-toolbar">{toolbar}</div>
          <OverviewCards rows={rows} hrefOf={hrefOf} now={now} />
        </>
      );
  }

  return (
    <section
      className="overview-page"
      data-testid="overview-page"
      data-state={pageState}
      data-view={view}
      data-live={push === null ? undefined : new Date(push.t).toISOString()}
      aria-labelledby="overview-title"
    >
      <h1 id="overview-title" className="visually-hidden">
        {t('overview:page.title')}
      </h1>
      <div className="overview-page__main">
        {data !== undefined && query.isError ? (
          <ErrorState
            compact
            error={errorInfo(query.error)}
            onRetry={() => {
              void refetch();
            }}
            data-testid="overview-refresh-error"
          />
        ) : null}
        {data?.meta.truncated === true ? (
          <p className="overview-page__notice" data-testid="overview-truncated">
            {t('overview:page.truncated', { count: data.data.apps.length })}
          </p>
        ) : null}
        {body}
      </div>
      <aside className="overview-page__side">
        <p
          className="overview-page__basis"
          title={t('overview:page.basisTitle')}
          data-testid="overview-basis"
        >
          {t('overview:page.basis')}
        </p>
        {data === undefined ? null : (
          <>
            <FiringPanel
              total={data.data.alerts_firing_total}
              env={envParam}
              historyHref={historyHref}
            />
            <ServerHealthPanel server={data.data.server} />
          </>
        )}
      </aside>
    </section>
  );
}
