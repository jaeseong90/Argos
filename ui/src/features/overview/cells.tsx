// SPDX-License-Identifier: Apache-2.0
//
// Value cells of an overview row, shared by the table and the cards (05 §2.10 "앱 행·카드 필수
// 항목"). Every cell carries `data-value` with the raw API value ("null" when missing) so tests can
// compare it with the same-question response; missing values show the missing marker, never 0
// (T-UI-007, D-23). Collected strings (version) render through SafeText.
import { useTranslation } from 'react-i18next';

import type { OverviewApp } from '../../api/overview';
import { SafeText } from '../../components/SafeText';
import { StatusBadge } from '../../components/StatusBadge';
import { ValueText } from '../../components/ValueText';
import { useFormat } from '../../i18n/format';
import { MISSING_DASH } from '../../utils/missing';

import { finite, firingCounts } from './model';
import { Sparkline } from './Sparkline';

/** Suffixes of the cell testids (`overview-cell-<field>-<appId>`). */
export type CellField =
  | 'status'
  | 'instances'
  | 'tps'
  | 'p95'
  | 'error-rate'
  | 'active'
  | 'batches'
  | 'firing'
  | 'deploy';

export function cellTestId(field: CellField, appId: number): string {
  return `overview-cell-${field}-${String(appId)}`;
}

/** `data-value` of a raw number: the number, "null" when missing. */
export function rawValue(value: unknown): string {
  const number = finite(value);
  return number === null ? 'null' : String(number);
}

export function StatusCell({ app }: { readonly app: OverviewApp }) {
  return (
    <span data-testid={cellTestId('status', app.app_id)} data-value={app.status}>
      <StatusBadge status={app.status} data-testid={`overview-status-${String(app.app_id)}`} />
    </span>
  );
}

export function InstancesCell({ app }: { readonly app: OverviewApp }) {
  const { t } = useTranslation(['common', 'overview']);
  const { up, total } = app.instances;
  return (
    <span
      className={`overview-instances${up < total ? ' overview-instances--short' : ''}`}
      title={t('overview:instances.title', { up, total })}
      data-testid={cellTestId('instances', app.app_id)}
      data-up={up}
      data-total={total}
    >
      <ValueText value={up} unit="count" />/<ValueText value={total} unit="count" />
    </span>
  );
}

export interface NumberCellProps {
  readonly app: OverviewApp;
  readonly field: 'tps' | 'p95' | 'error-rate' | 'active' | 'batches';
}

/** The API value and unit of a numeric cell. */
export function numberOf(
  app: OverviewApp,
  field: NumberCellProps['field'],
): { value: number | null; unit: 'rate' | 'ms' | 'ratio' | 'count' } {
  switch (field) {
    case 'tps':
      return { value: finite(app.tps), unit: 'rate' };
    case 'p95':
      return { value: finite(app.p95_ms), unit: 'ms' };
    case 'error-rate':
      return { value: finite(app.error_rate), unit: 'ratio' };
    case 'active':
      return { value: finite(app.active), unit: 'count' };
    case 'batches':
      return { value: finite(app.batch_running), unit: 'count' };
  }
}

export function NumberCell({ app, field }: NumberCellProps) {
  const { value, unit } = numberOf(app, field);
  return (
    <span
      className={
        field === 'error-rate' && value !== null && value > 0 ? 'overview-value--error' : undefined
      }
      data-testid={cellTestId(field, app.app_id)}
      data-value={rawValue(value)}
    >
      <ValueText value={value} unit={unit} />
    </span>
  );
}

/**
 * Firing alerts by severity (FIRING and ACKED, the alert engine's `{critical, warn}`, WO-25).
 * `firing` null (a server without the alert engine, ADR-0010 C-212 (2)) says "not available",
 * never 0; zero alerts is the value 0.
 */
export function FiringCell({ app }: { readonly app: OverviewApp }) {
  const { t } = useTranslation(['common', 'overview']);
  const counts = firingCounts(app.firing);
  const testId = cellTestId('firing', app.app_id);
  if (counts === null) {
    const text = t('overview:firing.notAvailable');
    return (
      <span
        className="overview-firing overview-firing--unavailable"
        title={text}
        data-testid={testId}
        data-state="unavailable"
        data-value="null"
      >
        <span aria-hidden="true">{t('overview:firing.notAvailableShort')}</span>
        <span className="visually-hidden">{text}</span>
      </span>
    );
  }
  const total = counts.critical + counts.warn;
  const parts = [
    counts.critical > 0 ? t('overview:firing.critical', { count: counts.critical }) : null,
    counts.warn > 0 ? t('overview:firing.warn', { count: counts.warn }) : null,
  ].filter((part): part is string => part !== null);
  const description = parts.length === 0 ? t('overview:firing.none') : parts.join(', ');
  return (
    <span
      className="overview-firing"
      title={description}
      data-testid={testId}
      data-state={total > 0 ? 'firing' : 'none'}
      data-value={String(total)}
      data-critical={counts.critical}
      data-warn={counts.warn}
    >
      {total === 0 ? (
        <ValueText value={0} unit="count" />
      ) : (
        <>
          {counts.critical > 0 ? (
            <span className="overview-firing__count overview-firing__count--critical">
              <span className="overview-firing__dot" aria-hidden="true" />
              <ValueText value={counts.critical} unit="count" />
            </span>
          ) : null}
          {counts.warn > 0 ? (
            <span className="overview-firing__count overview-firing__count--warn">
              <span className="overview-firing__dot" aria-hidden="true" />
              <ValueText value={counts.warn} unit="count" />
            </span>
          ) : null}
          <span className="visually-hidden">{description}</span>
        </>
      )}
    </span>
  );
}

/** Last deploy: version (collected) and how long ago, the absolute time as tooltip. */
export function DeployCell({ app, now }: { readonly app: OverviewApp; readonly now: number }) {
  const { t } = useTranslation(['common', 'overview']);
  const format = useFormat();
  const deploy = app.last_deploy;
  const testId = cellTestId('deploy', app.app_id);
  const ts = deploy === null ? Number.NaN : Date.parse(deploy.ts);
  if (deploy === null || !Number.isFinite(ts)) {
    return (
      <span
        className="overview-deploy value-text--missing"
        title={t('component.missing.noData')}
        data-testid={testId}
        data-value="null"
      >
        {MISSING_DASH}
      </span>
    );
  }
  const absolute = format.dateTimeWithOffset(ts);
  return (
    <span
      className="overview-deploy"
      title={t('overview:deploy.title', { time: absolute })}
      data-testid={testId}
      data-value={deploy.ts}
      data-version={deploy.version === null ? 'null' : undefined}
    >
      {deploy.version === null ? null : (
        <SafeText
          value={deploy.version}
          showTitle={false}
          className="overview-deploy__version"
          data-testid={`overview-deploy-version-${String(app.app_id)}`}
        />
      )}
      <time className="overview-deploy__time" dateTime={new Date(ts).toISOString()}>
        {format.relative(ts, now)}
      </time>
    </span>
  );
}

/** The three one-hour sparklines (TPS, p95, error rate). */
export function TrendCell({ app }: { readonly app: OverviewApp }) {
  const { t } = useTranslation(['common', 'overview']);
  const id = String(app.app_id);
  return (
    <span className="overview-trend">
      <Sparkline
        values={app.sparkline.tps}
        unit="rate"
        label={t('overview:spark.tps')}
        data-testid={`overview-spark-tps-${id}`}
      />
      <Sparkline
        values={app.sparkline.p95}
        unit="ms"
        label={t('overview:spark.p95')}
        data-testid={`overview-spark-p95-${id}`}
      />
      <Sparkline
        values={app.sparkline.err}
        unit="ratio"
        label={t('overview:spark.err')}
        data-testid={`overview-spark-err-${id}`}
      />
    </span>
  );
}
