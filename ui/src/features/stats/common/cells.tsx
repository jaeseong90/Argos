// SPDX-License-Identifier: Apache-2.0
//
// Cells of the statistics tables (docs/15 §6.3, 05 §2.5): a number keeps its raw API value in
// `data-value` ('' plus `data-missing` when null, never 0: T-UI-007, D-23) for tests and tooling
// (`stats-value`); a change against the previous range of the same length (`stats-delta-<field>`)
// follows ADR-0010 C-211 (3): no comparison value → "No comparison data", beyond ±999 % → ">+999%"
// with both raw values in the tooltip; count and p95 are relative, the error rate is an absolute
// difference in percentage points. The estimated badge marks bucket-estimated percentiles (C-14).
import { useTranslation } from 'react-i18next';

import { DeltaValue, type GoodDirection } from '../../../components/DeltaValue';
import { ValueText } from '../../../components/ValueText';
import { formatNumber, type ValueUnit } from '../../../utils/format';
import { formatTileRaw, relativeTileDelta } from '../../dashboard/tiles/tileDelta';

import { finite, rawValue } from './model';

export interface StatsValueProps {
  readonly value: number | null | undefined;
  readonly unit: ValueUnit;
  /** false when the server reports the value as not measured (shows "Not measurable"). */
  readonly measured?: boolean | null;
  readonly 'data-testid'?: string;
}

/** A number with its unit and its raw API value (`data-value`). */
export function StatsValue({
  value,
  unit,
  measured,
  'data-testid': testId = 'stats-value',
}: StatsValueProps) {
  const number = finite(value);
  return (
    <span
      className="stats-value"
      data-testid={testId}
      data-value={rawValue(number)}
      data-missing={number === null ? 'true' : undefined}
    >
      <ValueText value={number} unit={unit} measured={measured} />
    </span>
  );
}

/** Relative change (ratio) or absolute change in percentage points (error rate). */
export type DeltaMode = 'relative' | 'points';

export interface StatsDeltaProps {
  /** API field of the change (`count`, `p95_ms`, `error_rate`): `stats-delta-<field>`. */
  readonly field: string;
  /** The server's change: a ratio (relative) or a ratio difference (points); null = no basis. */
  readonly value: number | null | undefined;
  /** The current value (recovers the previous one for the tooltip of a clamped change). */
  readonly current: number | null | undefined;
  readonly unit: ValueUnit;
  readonly mode: DeltaMode;
  readonly goodWhen: GoodDirection;
}

const MINUS = '−';

/** "+1.2 pp" text of a ratio difference (0.012). */
export function formatPoints(difference: number, locale: string): string {
  const points = difference * 100;
  const text = formatNumber(Math.abs(points), locale, 2);
  return `${points > 0 ? '+' : points < 0 ? MINUS : ''}${text}`;
}

/** The change of a statistics row against the previous range (C-211 (3)). */
export function StatsDelta({ field, value, current, unit, mode, goodWhen }: StatsDeltaProps) {
  const { t, i18n } = useTranslation(['common', 'stats']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const change = finite(value);
  const testId = `stats-delta-${field}`;
  const attributes = {
    className: 'stats-delta',
    'data-testid': testId,
    'data-value': rawValue(change),
    'data-missing': change === null ? 'true' : undefined,
  };
  if (change === null) {
    return (
      <span {...attributes}>
        <DeltaValue value={null} goodWhen={goodWhen} noBasisText data-testid={`${testId}-text`} />
      </span>
    );
  }
  if (mode === 'points') {
    return (
      <span {...attributes}>
        <DeltaValue
          value={change}
          goodWhen={goodWhen}
          mode="absolute"
          text={t('stats:delta.points', { value: formatPoints(change, locale) })}
          data-testid={`${testId}-text`}
        />
      </span>
    );
  }
  const display = relativeTileDelta(change, finite(current), locale);
  if (display.kind !== 'value' || !display.clamped || display.text === null) {
    return (
      <span {...attributes}>
        <DeltaValue value={change} goodWhen={goodWhen} data-testid={`${testId}-text`} />
      </span>
    );
  }
  const shown = (raw: number | null): string =>
    raw === null || !Number.isFinite(raw) ? '—' : formatTileRaw(raw, unit, locale);
  return (
    <span {...attributes} data-clamped="true">
      <DeltaValue
        value={change}
        goodWhen={goodWhen}
        text={display.text}
        detail={t('stats:delta.detail', {
          current: shown(finite(current)),
          previous: shown(display.previous),
        })}
        data-testid={`${testId}-text`}
      />
    </span>
  );
}

/** "Estimated" mark of bucket-estimated percentiles (C-14); render it only when estimated. */
export function EstimatedBadge({ className }: { readonly className?: string }) {
  const { t } = useTranslation(['common', 'stats']);
  return (
    <span
      className={`stats-badge stats-badge--estimated${className === undefined ? '' : ` ${className}`}`}
      title={t('stats:estimated.tooltip')}
      data-testid="estimated-badge"
    >
      {t('stats:estimated.label')}
    </span>
  );
}
