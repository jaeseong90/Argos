// SPDX-License-Identifier: Apache-2.0
//
// Change against a comparison basis (docs/15 §6.2 StatTile delta, §6.3 delta columns, 05 §2.5).
// An arrow and a signed number carry the direction; the colour (good/bad per metric) only repeats
// it, and screen readers get "better"/"worse" as text (never colour alone, docs/15 §13).
import { useTranslation } from 'react-i18next';

import { formatSignedPercent, formatValue, type ValueUnit } from '../utils/format';
import { MISSING_DASH } from '../utils/missing';

import './badges.css';

/** Which direction is an improvement: error rate and p95 get worse when they go up. */
export type GoodDirection = 'up' | 'down' | 'neutral';

const ARROW_UP = '▲';
const ARROW_DOWN = '▼';

export interface DeltaValueProps {
  /**
   * The change: a relative ratio (0.12 = +12 %) by default, or an absolute difference in `unit`
   * when `mode` is 'absolute'. null/undefined render the dash.
   */
  readonly value: number | null | undefined;
  readonly goodWhen: GoodDirection;
  readonly mode?: 'relative' | 'absolute';
  readonly unit?: ValueUnit;
  readonly 'data-testid'?: string;
}

/** Classifies a change as good, bad or neutral for a metric's good direction. */
export function deltaTone(value: number, goodWhen: GoodDirection): 'good' | 'bad' | 'neutral' {
  if (value === 0 || goodWhen === 'neutral' || Number.isNaN(value)) {
    return 'neutral';
  }
  const up = value > 0;
  return up === (goodWhen === 'up') ? 'good' : 'bad';
}

/** Arrow, signed change and tone colour. */
export function DeltaValue({
  value,
  goodWhen,
  mode = 'relative',
  unit = 'none',
  'data-testid': testId,
}: DeltaValueProps) {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  if (value === null || value === undefined || Number.isNaN(value)) {
    return (
      <span
        className="delta delta--neutral"
        title={t('component.delta.noBasis')}
        data-testid={testId ?? 'delta'}
      >
        {MISSING_DASH}
      </span>
    );
  }
  const tone = deltaTone(value, goodWhen);
  const text =
    mode === 'relative'
      ? formatSignedPercent(value, locale)
      : `${value > 0 ? '+' : value < 0 ? '−' : ''}${formatValue(Math.abs(value), unit, locale)}`;
  const arrow = value > 0 ? ARROW_UP : value < 0 ? ARROW_DOWN : null;
  const toneText =
    tone === 'good'
      ? t('component.delta.better')
      : tone === 'bad'
        ? t('component.delta.worse')
        : value === 0
          ? t('component.delta.unchanged')
          : '';
  return (
    <span className={`delta delta--${tone}`} data-tone={tone} data-testid={testId ?? 'delta'}>
      {arrow === null ? null : <span aria-hidden="true">{arrow}</span>}
      <span>{text}</span>
      {toneText === '' ? null : <span className="visually-hidden">{toneText}</span>}
    </span>
  );
}
