// SPDX-License-Identifier: Apache-2.0
//
// The pinned top of the alert history (05 §2.9-1): the active FIRING and ACKED alerts of every
// application or of the selected one (`GET /overview/alerts`, filtered here by application), the
// counts by severity, and the channel status (last success and failure, consecutive failures;
// `GET /alerts/channels`). A FIRING row can be acknowledged from here. Rule, application and
// target names and channel names are entered or collected strings (SafeText, SEC-60).
import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import { ruleUnit, type AlertChannel, type AlertEventItem } from '../../../api/alerts';
import type { Enveloped } from '../../../api/client';
import { errorInfo } from '../../../api/errors';
import { AlertStateLabel } from '../../../components/AlertStateLabel';
import { AlertValue } from '../../../components/AlertValue';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';
import { MISSING_DASH } from '../../../utils/missing';
import { channelTypeLabel, targetTypeLabel } from '../common/labels';

import { severityCounts } from './model';

export interface PinnedPanelProps {
  readonly query: UseQueryResult<Enveloped<AlertEventItem[]>>;
  /** The rows to show (pinnedRows of the response). */
  readonly rows: readonly AlertEventItem[];
  /** The selected application (null: every application). */
  readonly allApps: boolean;
  readonly channels: UseQueryResult<Enveloped<AlertChannel[]>>;
  /** The deep-linked event (`alert=`), highlighted and scrolled into view. */
  readonly highlightId: string | null;
  /** Shows the ACK button on FIRING rows (operator and above). */
  readonly canAck: boolean;
  readonly onAck: (item: AlertEventItem) => void;
}

export function PinnedPanel({
  query,
  rows,
  allApps,
  channels,
  highlightId,
  canAck,
  onAck,
}: PinnedPanelProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const counts = severityCounts(rows);
  const listRef = useRef<HTMLUListElement>(null);
  const highlightShown = highlightId !== null && rows.some((row) => row.event_id === highlightId);
  useEffect(() => {
    if (!highlightShown) {
      return;
    }
    const row = Array.from(
      listRef.current?.querySelectorAll<HTMLElement>('[data-event-id]') ?? [],
    ).find((element) => element.dataset.eventId === highlightId);
    if (row !== undefined && typeof row.scrollIntoView === 'function') {
      row.scrollIntoView({ block: 'nearest' });
    }
  }, [highlightShown, highlightId]);

  return (
    <section
      className="alerts-panel alerts-pinned"
      aria-labelledby="alerts-pinned-title"
      data-testid="alerts-pinned"
      data-scope={allApps ? 'all' : 'app'}
    >
      <div className="alerts-pinned__head">
        <h2 id="alerts-pinned-title" className="alerts-panel__title">
          {allApps ? t('alerts:history.pinned.titleAll') : t('alerts:history.pinned.titleApp')}
        </h2>
        <span
          className="alerts-count alerts-count--critical"
          data-testid="alerts-pinned-count-critical"
          data-count={counts.critical}
        >
          {t('alerts:history.pinned.critical', { count: counts.critical })}
        </span>
        <span
          className="alerts-count alerts-count--warn"
          data-testid="alerts-pinned-count-warn"
          data-count={counts.warn}
        >
          {t('alerts:history.pinned.warn', { count: counts.warn })}
        </span>
      </div>
      <div className="alerts-pinned__body">
        <div className="alerts-pinned__active">
          {query.isError ? (
            <ErrorState
              compact
              error={errorInfo(query.error)}
              onRetry={() => {
                void query.refetch();
              }}
              data-testid="alerts-pinned-error"
            />
          ) : query.isPending ? (
            <p className="alerts-muted">{t('alerts:history.pinned.loading')}</p>
          ) : rows.length === 0 ? (
            <p className="alerts-muted" data-testid="alerts-pinned-empty">
              {t('alerts:history.pinned.none')}
            </p>
          ) : (
            <ul ref={listRef} className="alerts-pinned__list">
              {rows.map((row) => (
                <li
                  key={row.event_id}
                  className="alerts-pinned__row"
                  data-testid="alerts-pinned-row"
                  data-event-id={row.event_id}
                  data-state={row.state}
                  data-severity={row.severity}
                  data-highlighted={row.event_id === highlightId ? 'true' : undefined}
                >
                  <AlertStateLabel state={row.state} severity={row.severity} />
                  <SafeText value={row.rule.name} className="alerts-pinned__rule" />
                  {row.app === null ? (
                    <span className="alerts-muted">{t('alerts:common.serverTarget')}</span>
                  ) : (
                    <SafeText
                      value={row.app.name ?? String(row.app.id)}
                      className="alerts-pinned__app"
                    />
                  )}
                  <span className="alerts-muted">{targetTypeLabel(t, row.target.type)}</span>
                  {row.target.name === null ? null : (
                    <SafeText value={row.target.name} className="alerts-pinned__target" />
                  )}
                  <AlertValue
                    value={row.value}
                    threshold={row.threshold}
                    unit={ruleUnit(row.rule)}
                  />
                  <span className="alerts-pinned__since">
                    {t('alerts:history.pinned.since', {
                      time: Number.isNaN(Date.parse(row.ts))
                        ? MISSING_DASH
                        : format.relative(Date.parse(row.ts)),
                    })}
                  </span>
                  {row.state === 'ACKED' && row.acked_by !== null ? (
                    <span className="alerts-pinned__acked">
                      <span className="alerts-muted">{t('alerts:history.pinned.ackedBy')}</span>{' '}
                      <SafeText value={row.acked_by} />
                    </span>
                  ) : null}
                  {canAck && row.state === 'FIRING' ? (
                    <button
                      type="button"
                      className="control-button"
                      onClick={() => {
                        onAck(row);
                      }}
                      data-testid="alerts-pinned-ack"
                    >
                      {t('alerts:history.action.ack')}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {query.data?.meta.truncated === true ? (
            <p className="alerts-muted" data-testid="alerts-pinned-truncated">
              {t('alerts:history.pinned.truncated')}
            </p>
          ) : null}
        </div>
        <ChannelStatus channels={channels} />
      </div>
    </section>
  );
}

function ChannelStatus({
  channels,
}: {
  readonly channels: UseQueryResult<Enveloped<AlertChannel[]>>;
}) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const time = (iso: string | null): string => {
    const ms = iso === null ? Number.NaN : Date.parse(iso);
    return Number.isNaN(ms) ? MISSING_DASH : format.dateTime(ms);
  };
  const list = channels.data?.data ?? [];
  return (
    <div className="alerts-channel-status" data-testid="alerts-channel-status">
      <h3 className="alerts-panel__subtitle">{t('alerts:history.channelStatus.title')}</h3>
      {channels.isError ? (
        <ErrorState
          compact
          error={errorInfo(channels.error)}
          onRetry={() => {
            void channels.refetch();
          }}
        />
      ) : channels.isPending ? (
        <p className="alerts-muted">{t('alerts:history.channelStatus.loading')}</p>
      ) : list.length === 0 ? (
        <p className="alerts-muted" data-testid="alerts-channel-status-empty">
          {t('alerts:history.channelStatus.none')}
        </p>
      ) : (
        <ul className="alerts-channel-status__list">
          {list.map((channel) => (
            <li
              key={channel.channel_id}
              className={`alerts-channel-status__row alerts-channel-status__row--${channel.status}`}
              data-testid="alerts-channel-status-row"
              data-channel-id={channel.channel_id}
              data-status={channel.status}
              data-last-success={channel.last_success_at ?? ''}
              data-last-failure={channel.last_failure_at ?? ''}
            >
              <SafeText value={channel.name} className="alerts-channel-status__name" />
              <span className="alerts-muted">{channelTypeLabel(t, channel.type)}</span>
              <span>
                {t('alerts:history.channelStatus.lastSuccess', {
                  time: time(channel.last_success_at),
                })}
              </span>
              <span>
                {t('alerts:history.channelStatus.lastFailure', {
                  time: time(channel.last_failure_at),
                })}
              </span>
              {channel.consecutive_failures > 0 ? (
                <span className="alerts-delivery__error">
                  {t('alerts:history.channelStatus.failures', {
                    count: channel.consecutive_failures,
                  })}
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
