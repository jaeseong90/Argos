// SPDX-License-Identifier: Apache-2.0
//
// The ten required columns of the alert history (05 §2.9-2): time, application (environment),
// target (type and name), rule, severity, state transition (from → to with the reason, ADR-0010
// C-227 (3)), current value / threshold with the rule's unit (C-227 (4)), duration, deliveries per
// channel (status, attempts, error code; F-S27) and the acknowledging user with the note. Rule,
// target, application, environment, user, note and channel names are entered or collected strings
// and render through SafeText (SEC-60). The row actions are a separate column.
import type { TFunction } from 'i18next';
import type { ReactNode } from 'react';

import { eventReason, ruleUnit, type AlertEvent } from '../../../api/alerts';
import type { Untrusted } from '../../../api/untrusted';
import { AlertStateLabel } from '../../../components/AlertStateLabel';
import { AlertValue } from '../../../components/AlertValue';
import type { DataTableColumn } from '../../../components/DataTable';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import type { Formatter } from '../../../i18n/format';
import { MISSING_DASH } from '../../../utils/missing';
import {
  deliveryErrorLabel,
  deliveryStatusLabel,
  reasonLabel,
  severityLabel,
  targetTypeLabel,
} from '../common/labels';

/** Column ids of the ten required columns, in display order (E2E contract, plan §1.4). */
export const EVENT_COLUMN_IDS = [
  'ts',
  'app',
  'target',
  'rule',
  'severity',
  'transition',
  'value',
  'duration',
  'deliveries',
  'acked_by',
] as const;

export interface EventColumnsContext {
  readonly t: TFunction<['common', 'alerts']>;
  readonly format: Formatter;
  /** Channel id → name (GET /alerts/channels); unknown ids show a short id. */
  readonly channelNames: ReadonlyMap<string, Untrusted>;
  /** The row actions cell. */
  readonly actions: (event: AlertEvent) => ReactNode;
}

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

export function eventColumns(ctx: EventColumnsContext): DataTableColumn<AlertEvent>[] {
  // GET /alerts/events has one order (newest first) and no sort parameter: no sortable header.
  return columnsOf(ctx).map((column) => ({ ...column, sortable: false }));
}

function columnsOf(ctx: EventColumnsContext): DataTableColumn<AlertEvent>[] {
  const { t, format, channelNames } = ctx;
  return [
    {
      id: 'ts',
      header: t('alerts:history.column.ts'),
      value: (row) => Date.parse(row.ts),
      width: 150,
      render: (row) => {
        const ms = Date.parse(row.ts);
        return Number.isNaN(ms) ? (
          MISSING_DASH
        ) : (
          <time dateTime={new Date(ms).toISOString()}>{format.dateTime(ms)}</time>
        );
      },
    },
    {
      id: 'app',
      header: t('alerts:history.column.app'),
      value: (row) => row.app?.name ?? null,
      width: 150,
      render: (row) =>
        row.app === null ? (
          <span className="alerts-muted">{t('alerts:common.serverTarget')}</span>
        ) : (
          <span className="alerts-cell-pair">
            <SafeText value={row.app.name ?? String(row.app.id)} />
            {row.env === null ? null : (
              <span className="alerts-env">
                <SafeText value={row.env} />
              </span>
            )}
          </span>
        ),
    },
    {
      id: 'target',
      header: t('alerts:history.column.target'),
      value: (row) => row.target.name ?? row.target.id,
      width: 160,
      render: (row) => (
        <span className="alerts-cell-pair">
          <span className="alerts-muted">{targetTypeLabel(t, row.target.type)}</span>
          {row.target.name === null ? (
            row.target.id === null ? null : (
              <SafeText value={row.target.id} mono />
            )
          ) : (
            <SafeText value={row.target.name} />
          )}
        </span>
      ),
    },
    {
      id: 'rule',
      header: t('alerts:history.column.rule'),
      value: (row) => row.rule.name,
      width: 190,
      render: (row) => <SafeText value={row.rule.name} data-testid="alerts-rule-name" />,
    },
    {
      id: 'severity',
      header: t('alerts:history.column.severity'),
      value: (row) => row.severity,
      width: 90,
      render: (row) => (
        <span
          className={`alerts-severity alerts-severity--${row.severity ?? 'none'}`}
          data-severity={row.severity ?? 'none'}
        >
          {severityLabel(t, row.severity)}
        </span>
      ),
    },
    {
      id: 'transition',
      header: t('alerts:history.column.transition'),
      value: (row) => row.state,
      width: 250,
      render: (row) => {
        const reason = eventReason(row);
        return (
          <span className="alerts-transition">
            {row.from_state === null ? null : (
              <>
                <AlertStateLabel state={row.from_state} data-testid="alerts-from-state" />
                <span className="alerts-transition__arrow" aria-hidden="true" />
                <span className="visually-hidden">{t('alerts:history.transitionTo')}</span>
              </>
            )}
            <AlertStateLabel state={row.state} data-testid="alerts-to-state" />
            <span
              className="alerts-reason"
              data-testid="alerts-reason"
              data-reason={reason ?? 'none'}
              title={t('alerts:history.reasonTitle')}
            >
              {reason === null ? MISSING_DASH : reasonLabel(t, reason)}
            </span>
          </span>
        );
      },
    },
    {
      id: 'value',
      header: t('alerts:history.column.value'),
      value: (row) => row.value,
      width: 150,
      render: (row) => (
        <AlertValue value={row.value} threshold={row.threshold} unit={ruleUnit(row.rule)} />
      ),
    },
    {
      id: 'duration',
      header: t('alerts:history.column.duration'),
      value: (row) => row.duration_s,
      kind: 'seconds',
      width: 100,
      render: (row) => (
        <ValueText value={row.duration_s} unit="seconds" data-testid="alerts-duration" />
      ),
    },
    {
      id: 'deliveries',
      header: t('alerts:history.column.deliveries'),
      value: (row) => row.deliveries.length,
      width: 220,
      render: (row) =>
        row.deliveries.length === 0 ? (
          <span className="alerts-muted" data-testid="alerts-deliveries-none">
            {t('alerts:history.noDeliveries')}
          </span>
        ) : (
          <span className="alerts-deliveries">
            {row.deliveries.map((delivery) => {
              const name = channelNames.get(delivery.channel_id);
              return (
                <span
                  key={delivery.channel_id}
                  className={`alerts-delivery alerts-delivery--${delivery.status}`}
                  data-testid="alerts-delivery"
                  data-channel-id={delivery.channel_id}
                  data-status={delivery.status}
                  data-attempts={delivery.attempts}
                  data-error={delivery.last_error_code ?? undefined}
                >
                  {name === undefined ? (
                    <span className="alerts-mono">{shortId(delivery.channel_id)}</span>
                  ) : (
                    <SafeText value={name} />
                  )}
                  <span>{deliveryStatusLabel(t, delivery.status)}</span>
                  <span className="alerts-muted">
                    {t('alerts:history.attempts', { count: delivery.attempts })}
                  </span>
                  {delivery.last_error_code === null ? null : (
                    <span className="alerts-delivery__error">
                      {deliveryErrorLabel(t, delivery.last_error_code)}
                    </span>
                  )}
                </span>
              );
            })}
          </span>
        ),
    },
    {
      id: 'acked_by',
      header: t('alerts:history.column.ackedBy'),
      value: (row) => row.acked_by,
      width: 170,
      render: (row) =>
        row.acked_by === null ? (
          MISSING_DASH
        ) : (
          <span className="alerts-cell-pair">
            <SafeText value={row.acked_by} data-testid="alerts-acked-by" />
            {row.note === null ? null : (
              <span className="alerts-note">
                <SafeText value={row.note} data-testid="alerts-ack-note-text" />
              </span>
            )}
          </span>
        ),
    },
    {
      id: 'actions',
      header: t('alerts:history.column.actions'),
      value: () => null,
      width: 290,
      hideable: false,
      render: ctx.actions,
    },
  ];
}
