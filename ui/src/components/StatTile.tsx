// SPDX-License-Identifier: Apache-2.0
//
// Summary metric tile ("요약 지표", docs/15 §6.2, docs/01 appendix B): value, unit, change against
// the previous period or 7 days ago (global tileRef), colour per metric direction, optional
// sparkline. A missing value shows the dash with the tooltip "No data" (T-UI-007).
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';

import type { ValueUnit } from '../utils/format';
import { isMissing } from '../utils/missing';

import { DeltaValue, type GoodDirection } from './DeltaValue';
import { ValueText } from './ValueText';

import './badges.css';

export interface StatTileDelta {
  /** Relative change (0.12 = +12 %) or absolute difference when mode is 'absolute'. */
  readonly value: number | null | undefined;
  /** Comparison basis (docs/14 dashboard `delta.basis`, global tileRef). */
  readonly basis: 'prev' | '7d';
  readonly mode?: 'relative' | 'absolute';
  /** Without a value, say "No comparison data" instead of the dash (ADR-0010 C-211 (3)). */
  readonly noBasisText?: boolean;
  /** The change as formatted text (a clamped ">+999%"). */
  readonly text?: string;
  /** Tooltip of the change (both raw values of a clamped change). */
  readonly detail?: string;
}

export interface StatTileProps {
  readonly label: string;
  readonly value: number | null | undefined;
  readonly unit: ValueUnit;
  readonly measured?: boolean | null;
  readonly delta?: StatTileDelta;
  /** Improvement direction for the delta colour: error rate and p95 are 'down'. Default 'up'. */
  readonly goodWhen?: GoodDirection;
  /** Values for the sparkline (null breaks the line). */
  readonly sparkline?: readonly (number | null)[];
  /** Makes the tile a button (scroll to the related chart). */
  readonly onClick?: () => void;
  readonly 'data-testid'?: string;
}

const SPARK_WIDTH = 100;
const SPARK_HEIGHT = 24;

/** SVG path of a sparkline; null values break the line (D-09, D-16). Empty when < 1 point. */
export function sparklinePath(values: readonly (number | null)[]): string {
  const finite = values.filter((v): v is number => v !== null && Number.isFinite(v));
  if (finite.length === 0) {
    return '';
  }
  const min = Math.min(...finite);
  const max = Math.max(...finite);
  const span = max - min || 1;
  const step = values.length > 1 ? SPARK_WIDTH / (values.length - 1) : 0;
  let path = '';
  let penDown = false;
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) {
      penDown = false;
      return;
    }
    const x = (index * step).toFixed(2);
    const y = (SPARK_HEIGHT - 1 - ((value - min) / span) * (SPARK_HEIGHT - 2)).toFixed(2);
    path += `${penDown ? 'L' : 'M'}${x} ${y}`;
    penDown = true;
  });
  return path;
}

/** One summary metric. */
export function StatTile({
  label,
  value,
  unit,
  measured,
  delta,
  goodWhen = 'up',
  sparkline,
  onClick,
  'data-testid': testId,
}: StatTileProps) {
  const { t } = useTranslation();
  const missing = isMissing(value, measured);
  const valueNode = <ValueText value={value} unit={unit} measured={measured} />;
  const body = (
    <>
      <span className="stat-tile__label">{label}</span>
      <span className="stat-tile__value">
        {missing && measured !== false ? (
          <Tooltip title={t('component.missing.noData')}>
            <span className="stat-tile__missing">{valueNode}</span>
          </Tooltip>
        ) : (
          valueNode
        )}
      </span>
      <span className="stat-tile__footer">
        {delta === undefined ? null : (
          <>
            <DeltaValue
              value={delta.value}
              goodWhen={goodWhen}
              mode={delta.mode}
              unit={unit}
              noBasisText={delta.noBasisText}
              text={delta.text}
              detail={delta.detail}
            />
            <span className="stat-tile__basis">
              {delta.basis === '7d'
                ? t('component.statTile.basis.sevenDays')
                : t('component.statTile.basis.prev')}
            </span>
          </>
        )}
      </span>
      {sparkline === undefined || sparkline.length === 0 ? null : (
        <svg
          className="stat-tile__sparkline"
          viewBox={`0 0 ${String(SPARK_WIDTH)} ${String(SPARK_HEIGHT)}`}
          preserveAspectRatio="none"
          aria-hidden="true"
          data-testid="stat-tile-sparkline"
        >
          <path d={sparklinePath(sparkline)} vectorEffect="non-scaling-stroke" />
        </svg>
      )}
    </>
  );
  if (onClick !== undefined) {
    return (
      <button
        type="button"
        className="stat-tile"
        onClick={onClick}
        data-testid={testId ?? 'stat-tile'}
        data-missing={missing ? 'true' : undefined}
      >
        {body}
      </button>
    );
  }
  return (
    <div
      className="stat-tile"
      data-testid={testId ?? 'stat-tile'}
      data-missing={missing ? 'true' : undefined}
    >
      {body}
    </div>
  );
}
