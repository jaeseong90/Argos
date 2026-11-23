// SPDX-License-Identifier: Apache-2.0
//
// Alert history `/alerts` (docs/15 §9.9, 05 §2.9, WO-38): the pinned active alerts with severity
// counts and channel status, the event table with the ten required columns, the filters (global
// range and environment, application, severity, state, rule, failed deliveries only), four row
// actions (deep link, edit rule, ACK with a note, mute this target), the active mutes and the
// per-rule statistics of 7 or 30 days. Without `app` it shows every application (05 §2.9-6, the
// overview's entry). `alert=` highlights that event in the table and the pinned panel (and turns
// live off, urlState). In live mode the `alert` topic refreshes the pinned panel, the first page
// and the top bar badge; otherwise the pinned panel polls every 10 s.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { type AlertEvent, type AlertEventItem, type AlertEventsRequest } from '../../../api/alerts';
import { listApps } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import type { Untrusted } from '../../../api/untrusted';
import { DataTable, type RowAttributes } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { PartialBanner } from '../../../components/PartialBanner';
import { SafeLink } from '../../../components/SafeLink';
import { useFormat } from '../../../i18n/format';
import { usePollingFallback, useTopic } from '../../../realtime/hooks';
import { hrefWithState } from '../../../routes/navigation';
import { useCan } from '../../../state/permissions';
import { AlertsQ, type AlertsState } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';

import { AckDialog, type AckTarget } from './AckDialog';
import {
  useAlertEventsQuery,
  useAlertStatsQuery,
  useChannelsQuery,
  usePinnedQuery,
  useRuleOptionsQuery,
  useStableRange,
} from './data';
import { eventColumns } from './eventColumns';
import { HistoryFilters, type RuleOption } from './HistoryFilters';
import { deepLinkTarget, muteTargetOf, pinnedRows } from './model';
import { MuteDialog, type MuteDialogTarget } from './MuteDialog';
import { MutesList } from './MutesList';
import { PinnedPanel } from './PinnedPanel';
import { StatsTab } from './StatsTab';

import '../common/alerts.css';

export type HistoryState = 'loading' | 'empty' | 'error' | 'ready';

function dedupe(rows: readonly AlertEvent[]): AlertEvent[] {
  const seen = new Set<string>();
  const out: AlertEvent[] = [];
  for (const row of rows) {
    if (!seen.has(row.event_id)) {
      seen.add(row.event_id);
      out.push(row);
    }
  }
  return out;
}

export function AlertHistoryPage() {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { state, set } = useUrlState(AlertsQ);
  const time = useTimeRange(AlertsQ);
  const canAck = useCan('alerts.ack');
  const canEditRule = useCan('alerts.rules.edit');
  const polling = usePollingFallback();
  const env = state.env ?? null;
  const appId = state.app ?? null;
  const highlightId = state.alert ?? null;
  const realtime = time.live || state.kiosk;

  const stable = useStableRange(time.range, time.queryRange, time.live);
  const request: AlertEventsRequest = {
    from: stable.range.from,
    to: stable.range.to,
    appId,
    env,
    severity: state.severity,
    state: state.state,
    ruleId: state.rule ?? null,
    failedOnly: state.failedOnly,
  };
  const events = useAlertEventsQuery(request);
  const pinned = usePinnedQuery(env, !realtime || polling);
  const channels = useChannelsQuery();
  const ruleOptions = useRuleOptionsQuery(appId);
  const stats = useAlertStatsQuery(state.days === '30' ? 30 : 7, appId, state.tab === 'stats');
  const appsQuery = useQuery({
    queryKey: queryKeys.apps.list(),
    queryFn: () => listApps(),
    staleTime: 60_000,
  });

  // Realtime (docs/14 §5.3 `alert`, immediate): a transition or delivery result refreshes the
  // active alerts, the badge and the event list of a live range.
  useTopic('alert', realtime ? (env === null ? {} : { env }) : null, {
    onData: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.pinned(env) });
      void queryClient.invalidateQueries({ queryKey: ['alerts', 'firing-count'] });
      void queryClient.invalidateQueries({ queryKey: ['alerts', 'events'] });
    },
    onResync: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.all() });
    },
  });

  const [ackTarget, setAckTarget] = useState<AckTarget | null>(null);
  const [muteSubject, setMuteSubject] = useState<MuteDialogTarget | null>(null);

  const rows = useMemo(
    () => dedupe(events.data?.pages.flatMap((page) => page.data) ?? []),
    [events.data],
  );
  const pinnedList = useMemo(
    () => pinnedRows(pinned.data?.data ?? [], appId),
    [pinned.data, appId],
  );
  // ACK targets the FIRING event of a current firing (docs/14 §4.13); while the active list is
  // not known, any FIRING row may try (a 409 then says it is no longer firing).
  const firingIds = useMemo(
    () =>
      pinned.data === undefined
        ? null
        : new Set(
            pinned.data.data.filter((item) => item.state === 'FIRING').map((item) => item.event_id),
          ),
    [pinned.data],
  );
  const channelNames = useMemo(() => {
    const map = new Map<string, Untrusted>();
    for (const channel of channels.data?.data ?? []) {
      map.set(channel.channel_id, channel.name);
    }
    return map;
  }, [channels.data]);
  const ruleList: RuleOption[] = useMemo(
    () => (ruleOptions.data?.data ?? []).map((rule) => ({ id: rule.rule_id, name: rule.name })),
    [ruleOptions.data],
  );
  const ruleNames = useMemo(() => {
    const map = new Map<string, Untrusted>();
    for (const rule of ruleOptions.data?.data ?? []) {
      map.set(rule.rule_id, rule.name);
    }
    return map;
  }, [ruleOptions.data]);
  const apps = appsQuery.data?.data ?? [];
  const appNames = useMemo(() => {
    const map = new Map<number, Untrusted>();
    for (const app of appsQuery.data?.data ?? []) {
      map.set(app.app_id, app.name);
    }
    return map;
  }, [appsQuery.data]);

  const origin = typeof window === 'undefined' ? '' : window.location.origin;

  const openMute = (row: AlertEvent | AlertEventItem): void => {
    setMuteSubject({
      key: row.event_id,
      target: muteTargetOf(row),
      appName: row.app?.name ?? null,
      instanceName: row.target.type === 'instance' ? row.target.name : null,
      ruleName: row.rule.name,
    });
  };

  const onNavigate = (path: string): void => {
    void navigate(path);
  };

  const actions = (row: AlertEvent) => {
    const link = deepLinkTarget(row.deeplink, origin);
    const ackable =
      canAck && row.state === 'FIRING' && (firingIds === null || firingIds.has(row.event_id));
    return (
      <span className="alerts-actions">
        {link === null ? (
          <button
            type="button"
            className="control-button"
            disabled
            data-testid="alerts-action-deeplink"
          >
            {t('alerts:history.action.deeplink')}
          </button>
        ) : (
          <SafeLink
            href={link.href}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="alerts-action-deeplink"
          >
            {t('alerts:history.action.deeplink')}
          </SafeLink>
        )}
        {canEditRule ? (
          <SafeLink
            href={hrefWithState(`/alerts/rules/${encodeURIComponent(row.rule.id)}`, state)}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="alerts-action-edit-rule"
          >
            {t('alerts:history.action.editRule')}
          </SafeLink>
        ) : null}
        {ackable ? (
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setAckTarget({ eventId: row.event_id, rule: row.rule.name });
            }}
            data-testid="alerts-action-ack"
          >
            {t('alerts:history.action.ack')}
          </button>
        ) : null}
        {canAck ? (
          <button
            type="button"
            className="control-button"
            onClick={() => {
              openMute(row);
            }}
            data-testid="alerts-action-mute"
          >
            {t('alerts:history.action.mute')}
          </button>
        ) : null}
      </span>
    );
  };

  const columns = eventColumns({ t, format, channelNames, actions });

  let historyState: HistoryState;
  if (events.isPending) {
    historyState = 'loading';
  } else if (events.isError && rows.length === 0) {
    historyState = 'error';
  } else if (rows.length === 0) {
    historyState = 'empty';
  } else {
    historyState = 'ready';
  }

  // alert=: scroll the highlighted event row into view once it is shown.
  const tableRef = useRef<HTMLDivElement>(null);
  const highlightInTable = highlightId !== null && rows.some((row) => row.event_id === highlightId);
  useEffect(() => {
    if (!highlightInTable) {
      return;
    }
    const row = Array.from(
      tableRef.current?.querySelectorAll<HTMLElement>('[data-row-key]') ?? [],
    ).find((element) => element.dataset.rowKey === highlightId);
    if (row !== undefined && typeof row.scrollIntoView === 'function') {
      row.scrollIntoView({ block: 'center' });
    }
  }, [highlightInTable, highlightId]);

  const rowAttributes = (row: AlertEvent): RowAttributes => ({
    'data-event-id': row.event_id,
    'data-state': row.state,
    'data-severity': row.severity ?? undefined,
    'data-highlighted': row.event_id === highlightId ? 'true' : undefined,
  });

  const setFilters = (partial: Partial<AlertsState>): void => {
    set(partial);
  };
  const clearFilters = (): void => {
    set({ severity: 'all', state: 'all', rule: undefined, failedOnly: false });
  };
  const filtered =
    state.severity !== 'all' ||
    state.state !== 'all' ||
    state.rule !== undefined ||
    state.failedOnly;
  const lastMeta = events.data?.pages.at(-1)?.meta ?? null;

  const rootState: HistoryState =
    state.tab === 'stats'
      ? stats.isPending
        ? 'loading'
        : stats.isError
          ? 'error'
          : stats.data.data.length === 0
            ? 'empty'
            : 'ready'
      : historyState;

  return (
    <section
      className="alerts-page"
      aria-labelledby="alerts-history-title"
      aria-busy={rootState === 'loading' || undefined}
      data-testid="alerts-history"
      data-state={rootState}
      data-tab={state.tab}
      data-scope={appId === null ? 'all' : 'app'}
      data-range-from={stable.range.from}
      data-range-to={stable.range.to}
    >
      <header className="alerts-page__header">
        <h1 id="alerts-history-title" className="alerts-page__title">
          {t('alerts:history.title')}
        </h1>
        <div className="alerts-tabs" role="tablist" aria-label={t('alerts:history.tabs')}>
          <button
            type="button"
            role="tab"
            id="alerts-tab-history"
            aria-selected={state.tab === 'history'}
            aria-controls="alerts-tabpanel"
            className={`alerts-tab${state.tab === 'history' ? ' alerts-tab--on' : ''}`}
            onClick={() => {
              set({ tab: 'history' });
            }}
            data-testid="alerts-tab-history"
          >
            {t('alerts:history.tab.history')}
          </button>
          <button
            type="button"
            role="tab"
            id="alerts-tab-stats"
            aria-selected={state.tab === 'stats'}
            aria-controls="alerts-tabpanel"
            className={`alerts-tab${state.tab === 'stats' ? ' alerts-tab--on' : ''}`}
            onClick={() => {
              set({ tab: 'stats' });
            }}
            data-testid="alerts-tab-stats"
          >
            {t('alerts:history.tab.stats')}
          </button>
        </div>
      </header>
      <HistoryFilters
        state={state}
        apps={apps}
        appsLoading={appsQuery.isPending}
        rules={ruleList}
        set={setFilters}
      />
      <div
        id="alerts-tabpanel"
        role="tabpanel"
        aria-labelledby={state.tab === 'stats' ? 'alerts-tab-stats' : 'alerts-tab-history'}
        className="alerts-page__panel"
      >
        {state.tab === 'stats' ? (
          <StatsTab
            days={state.days}
            onDaysChange={(days) => {
              set({ days });
            }}
            query={stats}
            allApps={appId === null}
          />
        ) : (
          <>
            <PinnedPanel
              query={pinned}
              rows={pinnedList}
              allApps={appId === null}
              channels={channels}
              highlightId={highlightId}
              canAck={canAck}
              onAck={(item) => {
                setAckTarget({ eventId: item.event_id, rule: item.rule.name });
              }}
            />
            <section
              className="alerts-panel alerts-events"
              aria-labelledby="alerts-events-title"
              data-testid="alerts-events"
            >
              <div className="alerts-toolbar">
                <h2 id="alerts-events-title" className="alerts-panel__title">
                  {t('alerts:history.events.title')}
                </h2>
                {time.live ? null : (
                  <button
                    type="button"
                    className="control-button"
                    onClick={() => {
                      stable.refresh();
                      void events.refetch();
                    }}
                    data-testid="alerts-refresh"
                  >
                    {t('alerts:history.events.refresh')}
                  </button>
                )}
              </div>
              {highlightId !== null && historyState === 'ready' && !highlightInTable ? (
                <p className="alerts-muted" role="note" data-testid="alerts-highlight-missing">
                  {t('alerts:history.events.highlightMissing')}
                </p>
              ) : null}
              <PartialBanner meta={lastMeta} data-testid="alerts-events-partial" />
              {historyState === 'loading' ? (
                <LoadingState variant="table" data-testid="alerts-history-loading" />
              ) : historyState === 'error' ? (
                <ErrorState
                  error={errorInfo(events.error)}
                  onRetry={() => {
                    void events.refetch();
                  }}
                  onClearFilters={filtered ? clearFilters : undefined}
                  data-testid="alerts-history-error"
                />
              ) : historyState === 'empty' ? (
                <EmptyState
                  title={
                    filtered
                      ? t('alerts:history.empty.filteredTitle')
                      : t('alerts:history.empty.title')
                  }
                  description={t('alerts:history.empty.description')}
                  onWidenRange={() => {
                    time.dispatch({ type: 'zoomOut' });
                  }}
                  onClearFilters={filtered ? clearFilters : undefined}
                  data-testid="alerts-history-empty"
                />
              ) : (
                <div ref={tableRef}>
                  <DataTable
                    tableId="alerts-events"
                    caption={t('alerts:history.events.caption')}
                    columns={columns}
                    rows={rows}
                    rowKey={(row) => row.event_id}
                    dataComplete={false}
                    rowAttributes={rowAttributes}
                  />
                  {events.isError ? (
                    <ErrorState
                      compact
                      error={errorInfo(events.error)}
                      onRetry={() => {
                        void events.refetch();
                      }}
                      data-testid="alerts-history-page-error"
                    />
                  ) : null}
                  {events.hasNextPage ? (
                    <button
                      type="button"
                      className="control-button alerts-load-more"
                      disabled={events.isFetchingNextPage}
                      onClick={() => {
                        void events.fetchNextPage();
                      }}
                      data-testid="alerts-load-more"
                    >
                      {events.isFetchingNextPage
                        ? t('alerts:history.events.loadingMore')
                        : t('alerts:history.events.loadMore')}
                    </button>
                  ) : null}
                </div>
              )}
            </section>
            {canAck ? <MutesList appNames={appNames} ruleNames={ruleNames} /> : null}
          </>
        )}
      </div>
      <AckDialog
        target={ackTarget}
        onClose={() => {
          setAckTarget(null);
        }}
      />
      <MuteDialog
        subject={muteSubject}
        onClose={() => {
          setMuteSubject(null);
        }}
      />
    </section>
  );
}
