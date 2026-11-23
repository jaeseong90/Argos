// SPDX-License-Identifier: Apache-2.0
//
// One-hour sparkline of an overview row (05 §2.10 "1시간 스파크라인(TPS·p95·에러율)"): an inline
// SVG polyline of the 60 per-minute values of `GET /overview`. Numbers only (no collected text, no
// chart library: the overview chunk stays small, docs/15 §14). Minutes without a value (null)
// break the line instead of drawing a fake 0 (T-UI-007); the accessible name gives the lowest,
// highest and latest value.
import { useTranslation } from 'react-i18next';

import { useFormattedValue } from '../../components/ValueText';
import type { ValueUnit } from '../../utils/format';

import { finite } from './model';

export const SPARK_WIDTH = 36;
export const SPARK_HEIGHT = 14;
/** Inset so the stroke is not clipped at the top and bottom edges. */
const PAD = 1.5;

/** The polyline segments of the values (runs of non-null values), in SVG coordinates. */
export function sparkSegments(
  values: readonly (number | null)[],
  width = SPARK_WIDTH,
  height = SPARK_HEIGHT,
): string[] {
  const numbers = values.map(finite);
  const present = numbers.filter((v): v is number => v !== null);
  if (present.length === 0) {
    return [];
  }
  const min = Math.min(...present);
  const max = Math.max(...present);
  const span = max - min;
  const stepX = numbers.length > 1 ? width / (numbers.length - 1) : 0;
  const y = (v: number): number =>
    // A flat series sits in the middle.
    span === 0 ? height / 2 : PAD + (1 - (v - min) / span) * (height - 2 * PAD);
  const segments: string[] = [];
  let current: string[] = [];
  numbers.forEach((v, i) => {
    if (v === null) {
      if (current.length > 0) {
        segments.push(current.join(' '));
      }
      current = [];
      return;
    }
    current.push(`${(i * stepX).toFixed(1)},${y(v).toFixed(1)}`);
  });
  if (current.length > 0) {
    segments.push(current.join(' '));
  }
  return segments;
}

export interface SparklineProps {
  readonly values: readonly (number | null)[];
  readonly unit: ValueUnit;
  /** Translated name of the series ("TPS, last hour"). */
  readonly label: string;
  readonly 'data-testid'?: string;
}

export function Sparkline({ values, unit, label, 'data-testid': testId }: SparklineProps) {
  const { t } = useTranslation(['common', 'overview']);
  const present = values.map(finite).filter((v): v is number => v !== null);
  const min = present.length === 0 ? null : Math.min(...present);
  const max = present.length === 0 ? null : Math.max(...present);
  const last =
    [...values]
      .reverse()
      .map(finite)
      .find((v) => v !== null) ?? null;
  const minText = useFormattedValue(min, unit).text;
  const maxText = useFormattedValue(max, unit).text;
  const lastText = useFormattedValue(last, unit).text;
  const name =
    present.length === 0
      ? t('overview:spark.empty', { label })
      : t('overview:spark.summary', { label, min: minText, max: maxText, last: lastText });
  const segments = sparkSegments(values);
  return (
    <svg
      className="overview-spark"
      width={SPARK_WIDTH}
      height={SPARK_HEIGHT}
      viewBox={`0 0 ${String(SPARK_WIDTH)} ${String(SPARK_HEIGHT)}`}
      role="img"
      aria-label={name}
      data-points={present.length}
      data-last={last === null ? 'null' : String(last)}
      data-testid={testId}
    >
      <title>{name}</title>
      {segments.map((points, index) =>
        points.includes(' ') ? (
          <polyline key={index} className="overview-spark__line" points={points} />
        ) : (
          // A lone minute between gaps: a dot, so it is not invisible.
          <circle
            key={index}
            className="overview-spark__dot"
            cx={Number(points.split(',')[0])}
            cy={Number(points.split(',')[1])}
            r={1.2}
          />
        ),
      )}
    </svg>
  );
}
