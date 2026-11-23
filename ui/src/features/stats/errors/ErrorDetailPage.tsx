// SPDX-License-Identifier: Apache-2.0
//
// Error group detail /apps/:appId/stats/errors/:groupId (docs/15 §9.7, 05 §2.5-5, WO-36): the
// class with the "new" badge, count and first / last occurrence (and first seen ever), the masked
// message pattern, the representative stack (one masked sample; the transaction detail's FrameList,
// text nodes only), the occurrence trend with event markers (deploys) and outage shading, the
// distributions by version (each version opens the XLog with the group and that version), by
// instance (raw window) and by service, the last 10 transactions opening their detail with `ts`
// (C-206 (1)), and the XLog with `f.error_group`. Frames of the application's packages
// (`app_packages` of GET /apps/{appId}, ADR-0010 C-207 (3)) are bold; while none are set, none are
// bold and the screen says so: the client never guesses them.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { useAppPackages } from '../../../api/apps';
import { getErrorGroup, type ErrorGroupDetail, type StatsBreakdown } from '../../../api/stats';
import { untrusted } from '../../../api/untrusted';
import type { TimeRange as ChartRange } from '../../../charts/chartModel';
import { SafeText } from '../../../components/SafeText';
import { useAppId } from '../../../routes/navigation';
import { carriedState, CommonQ, type Filter } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { formatDateTime } from '../../../utils/format';
import { FrameList } from '../../transaction/FrameList';
import { BreakdownTable } from '../common/BreakdownTable';
import { statsKey, useEventMarkers, useStatsQuery, useStatsRange } from '../common/data';
import { DetailLinks, RawWindowNote, routeKey, SummaryItem } from '../common/DetailParts';
import { errorsListHref, serviceHref, xlogAtRangeHref, xlogHref } from '../common/links';
import { SourceNote } from '../common/ListControls';
import { xlogFilters } from '../common/model';
import { RelatedTxList } from '../common/RelatedTxList';
import { StatsScreen, type StatsScreenState } from '../common/StatsScreen';
import { TrendChart } from '../common/TrendChart';

import { NewBadge } from './errorColumns';

/** A date-time figure of the summary with its raw ISO value. */
function TimeItem({
  label,
  iso,
  locale,
  'data-testid': testId,
}: {
  readonly label: string;
  readonly iso: string | null;
  readonly locale: string;
  readonly 'data-testid': string;
}) {
  const ms = iso === null ? Number.NaN : Date.parse(iso);
  return (
    <div className="stats-summary__item">
      <dt>{label}</dt>
      <dd>
        {iso === null ? (
          <span data-testid={testId} data-value="" data-missing="true">
            —
          </span>
        ) : (
          <time dateTime={iso} data-testid={testId} data-value={iso}>
            {Number.isFinite(ms) ? formatDateTime(ms, locale) : iso}
          </time>
        )}
      </dd>
    </div>
  );
}

export function ErrorDetailPage() {
  const { t, i18n } = useTranslation(['common', 'stats']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const appId = useAppId();
  const params = useParams();
  const groupId = routeKey(params.groupId);
  const { state } = useUrlState(CommonQ);
  const time = useTimeRange(CommonQ);
  const dispatch = time.dispatch;
  const range = useStatsRange(state.time);
  const kind = state.kind;
  const enabled = appId !== null && groupId !== null;

  const query = useStatsQuery<ErrorGroupDetail>(
    statsKey('error-detail', { appId, range, kind, extra: { groupId } }),
    (signal) => getErrorGroup({ appId: appId ?? 0, range, kind, groupId: groupId ?? '' }, signal),
    enabled,
  );
  const markers = useEventMarkers(appId, range);
  const appPackages = useAppPackages(appId);
  const detail = query.data;
  const id = appId ?? 0;
  const carried = useMemo(() => carriedState(state), [state]);
  const ownFilter = useMemo<Filter[]>(
    () => (groupId === null ? [] : [{ field: 'error_group', op: 'eq', value: untrusted(groupId) }]),
    [groupId],
  );
  const filters = useMemo(() => xlogFilters(ownFilter, state.filters), [ownFilter, state.filters]);
  const env = state.env;
  const xlogAt = useCallback(
    (window: ChartRange) => xlogAtRangeHref(appId ?? 0, window, { kind, env }, filters),
    [appId, kind, env, filters],
  );
  const onZoom = useCallback(
    (from: number, to: number) => {
      dispatch({ type: 'chartDrag', from, to });
    },
    [dispatch],
  );
  const versionHref = useCallback(
    (row: StatsBreakdown) =>
      xlogHref(id, carried, [...filters, { field: 'version', op: 'eq', value: untrusted(row.id) }]),
    [id, carried, filters],
  );
  const frames = useMemo(() => ({ frames: detail?.sample_stack ?? [] }), [detail]);

  let pageState: StatsScreenState;
  if (!enabled) {
    pageState = 'empty';
  } else if (query.status === 'ready' && detail !== null) {
    pageState = detail.group.count === 0 ? 'empty' : 'ready';
  } else {
    pageState = query.status === 'error' ? 'error' : 'loading';
  }

  return (
    <StatsScreen
      testId="stats-error-detail"
      title={
        detail === null ? (
          t('stats:errors.detailTitle')
        ) : (
          <>
            <SafeText value={detail.group.error_class} mono data-testid="stats-error-class" />
            {detail.group.is_new ? (
              <NewBadge label={t('stats:errors.newBadge')} title={t('stats:errors.newTooltip')} />
            ) : null}
          </>
        )
      }
      state={pageState}
      range={range}
      error={query.error}
      onRetry={query.retry}
      onNarrowRange={() => {
        dispatch({ type: 'preset', range: '1h' });
      }}
      refreshing={query.refreshing}
      loadingVariant="chart"
      headerActions={
        <DetailLinks
          backHref={errorsListHref(id, carried)}
          xlogHref={enabled ? xlogHref(id, carried, filters) : null}
        />
      }
      empty={
        enabled
          ? {
              title: t('stats:errors.detailEmptyTitle'),
              description: t('stats:state.emptyDescription'),
              onWidenRange: () => {
                dispatch({ type: 'zoomOut' });
              },
            }
          : {
              title: t('stats:state.invalidKeyTitle'),
              description: t('stats:state.invalidKeyDescription'),
            }
      }
    >
      {detail === null ? null : (
        <>
          <SourceNote meta={query.meta} />
          <dl className="stats-summary" data-testid="stats-error-summary">
            <SummaryItem
              label={t('stats:errors.count')}
              value={detail.group.count}
              unit="count"
              data-testid="stats-error-count"
            />
            <TimeItem
              label={t('stats:columns.first_seen')}
              iso={detail.group.first_seen}
              locale={locale}
              data-testid="stats-error-first-seen"
            />
            <TimeItem
              label={t('stats:columns.last_seen')}
              iso={detail.group.last_seen}
              locale={locale}
              data-testid="stats-error-last-seen"
            />
            <TimeItem
              label={t('stats:errors.firstSeenEver')}
              iso={detail.group.first_seen_ever}
              locale={locale}
              data-testid="stats-error-first-seen-ever"
            />
          </dl>
          <section className="stats-section" aria-labelledby="stats-error-message-title">
            <h2 id="stats-error-message-title" className="stats-section__title">
              {t('stats:errors.message')}
            </h2>
            <p className="stats-note">{t('stats:errors.messageNote')}</p>
            <div className="stats-message" data-testid="stats-error-message">
              <SafeText value={detail.group.message_pattern} mono maxLines={0} />
            </div>
          </section>
          <section className="stats-section stats-stack" aria-labelledby="stats-error-stack-title">
            <h2 id="stats-error-stack-title" className="stats-section__title">
              {t('stats:errors.stack')}
            </h2>
            <p className="stats-note" data-testid="stats-error-app-frames-note">
              {appPackages.length === 0
                ? t('stats:errors.appFramesUnknown')
                : t('stats:errors.appFramesBold')}
            </p>
            <FrameList source={frames} appPackages={appPackages} data-testid="stats-error-stack" />
          </section>
          <TrendChart
            series={detail.series}
            meta={query.meta}
            range={range}
            markers={markers}
            estimated={false}
            volume={['count']}
            latency={[]}
            xlogAt={xlogAt}
            onZoom={onZoom}
          />
          <div className="stats-grid">
            <BreakdownTable
              tableId="stats-by-version"
              title={t('stats:breakdown.byVersion')}
              nameHeader={t('stats:columns.version')}
              rows={detail.by_version}
              metrics={['count', 'share']}
              href={versionHref}
              mono
              emptyText={t('stats:breakdown.empty')}
            />
            <BreakdownTable
              tableId="stats-by-service"
              title={t('stats:errors.byService')}
              nameHeader={t('stats:columns.service')}
              rows={detail.by_service}
              metrics={['count', 'share']}
              href={(row) => serviceHref(id, row.id, carried)}
              mono
              emptyText={t('stats:breakdown.empty')}
            />
          </div>
          <div className="stats-section">
            <RawWindowNote window={detail.raw_window} />
            <BreakdownTable
              tableId="stats-by-instance"
              title={t('stats:breakdown.byInstance')}
              nameHeader={t('stats:columns.instance')}
              rows={detail.by_instance}
              metrics={['count', 'share']}
              emptyText={t('stats:breakdown.rawEmpty')}
            />
          </div>
          <RelatedTxList
            appId={id}
            tableId="stats-recent-tx"
            title={t('stats:errors.recent')}
            rows={detail.recent_tx}
            profiledOnly={false}
            emptyText={t('stats:errors.recentEmpty')}
          />
        </>
      )}
    </StatsScreen>
  );
}
