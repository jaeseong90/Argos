// SPDX-License-Identifier: Apache-2.0
//
// Summary of the `alert=` event above the charts of the dashboard and the XLog (docs/15 §4.3 item
// 4, ADR-0010 C-226 (8)): rule, target, state with the severity, value/threshold with the rule's
// unit and the transition time, plus a link to that event in the alert history. The event comes
// from the same `GET /alerts/events` answer as the chart's alert markers; an event that is not in
// the range says so (the marker cannot be emphasised then). Rule and target names are entered or
// collected strings (SafeText).
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { AlertEvent } from '../api/alertMarkers';
import { useFormat } from '../i18n/format';

import { AlertStateLabel } from './AlertStateLabel';
import { AlertValue } from './AlertValue';
import { SafeText } from './SafeText';

import './alerts.css';

/** The `rule.unit` of an event (an additive field: absent on older servers). */
export function ruleUnitOf(rule: unknown): string | null {
  if (typeof rule !== 'object' || rule === null || !('unit' in rule)) {
    return null;
  }
  const unit = (rule as { unit?: unknown }).unit;
  return typeof unit === 'string' ? unit : null;
}

/** Translated alert target type (app, instance, service, external host, server). */
export function useAlertTargetLabel(): (type: string) => string {
  const { t } = useTranslation();
  return (type) => {
    switch (type) {
      case 'app':
        return t('component.alertTarget.app');
      case 'instance':
        return t('component.alertTarget.instance');
      case 'service':
        return t('component.alertTarget.service');
      case 'ext_host':
        return t('component.alertTarget.extHost');
      case 'server':
        return t('component.alertTarget.server');
      default:
        return type;
    }
  };
}

export interface AlertBannerProps {
  /** The `alert=` key of the URL. */
  readonly eventId: string;
  /** The event, or null when the range's events do not contain it. */
  readonly event: AlertEvent | null;
  /** The events are still loading. */
  readonly loading: boolean;
  /** The events query failed. */
  readonly failed?: boolean;
}

export function AlertBanner({ eventId, event, loading, failed = false }: AlertBannerProps) {
  const { t } = useTranslation();
  const format = useFormat();
  const targetLabel = useAlertTargetLabel();
  const historyHref = `/alerts?alert=${encodeURIComponent(eventId)}`;
  if (event === null) {
    const state = loading ? 'loading' : failed ? 'error' : 'missing';
    return (
      <div className="alert-banner" role="status" data-testid="alert-banner" data-state={state}>
        <p className="alert-banner__note">
          {loading
            ? t('component.alertBanner.loading')
            : failed
              ? t('component.alertBanner.failed')
              : t('component.alertBanner.missing')}
        </p>
        <Link to={historyHref} data-testid="alert-banner-history">
          {t('component.alertBanner.history')}
        </Link>
      </div>
    );
  }
  const ts = Date.parse(event.ts);
  const classes = ['alert-banner'];
  if (event.severity === 'critical') {
    classes.push('alert-banner--critical');
  }
  return (
    <div
      className={classes.join(' ')}
      role="status"
      data-testid="alert-banner"
      data-state="ready"
      data-event-id={event.event_id}
    >
      <AlertStateLabel state={event.state} severity={event.severity} />
      <SafeText
        value={event.rule.name}
        className="alert-banner__rule"
        data-testid="alert-banner-rule"
      />
      <span className="alert-banner__muted">
        {targetLabel(event.target.type)}{' '}
        <SafeText
          value={event.target.name ?? event.app?.name ?? null}
          data-testid="alert-banner-target"
        />
      </span>
      <AlertValue
        value={event.value}
        threshold={event.threshold}
        unit={ruleUnitOf(event.rule)}
        data-testid="alert-banner-value"
      />
      {Number.isFinite(ts) ? (
        <time className="alert-banner__muted" dateTime={new Date(ts).toISOString()}>
          {format.dateTime(ts)}
        </time>
      ) : null}
      <Link to={historyHref} data-testid="alert-banner-history">
        {t('component.alertBanner.history')}
      </Link>
    </div>
  );
}

/** Note under the charts when the range has more alert transitions than one page (500). */
export function AlertMarkersTruncated({ count }: { readonly count: number }) {
  const { t } = useTranslation();
  return (
    <p className="alert-banner__note" data-testid="alert-markers-truncated">
      {t('component.alertBanner.truncated', { count })}
    </p>
  );
}
