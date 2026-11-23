// SPDX-License-Identifier: Apache-2.0
//
// The time axis above a waterfall (docs/15 §8.4 "상단 눈금(0 ~ total ms)"): ticks of timeScale()
// placed at their percentage of the track, labelled in the unit that fits the length (ms / s /
// min). It shares the label column width of the rows below (--waterfall-label-w, set by
// VirtualRows or WaterfallFrame), so tick 0 sits exactly above the start of every bar.
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { DEFAULT_MAX_TICKS, formatTick, timeScale } from './scale';

import './waterfall.css';

export interface TimeScaleProps {
  /** Length of the scale in ms (timelineTotal of the rows). */
  readonly totalMs: number;
  /** Ticks aimed at (default 6). */
  readonly maxTicks?: number;
  /** Content of the label column (e.g. a column title); empty by default. */
  readonly label?: ReactNode;
  readonly 'data-testid'?: string;
}

export function TimeScale({
  totalMs,
  maxTicks = DEFAULT_MAX_TICKS,
  label,
  'data-testid': testId = 'waterfall-scale',
}: TimeScaleProps) {
  const { i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  const model = timeScale(totalMs, maxTicks);
  return (
    <div
      className="waterfall-scale"
      data-testid={testId}
      data-unit={model.unit}
      data-step-ms={model.stepMs}
      aria-hidden="true"
    >
      <div className="waterfall-scale__label">{label}</div>
      <div className="waterfall-scale__track">
        {model.ticks.map((tick, index) => (
          <span
            key={tick.ms}
            className={`waterfall-scale__tick${index === model.ticks.length - 1 && tick.leftPct > 92 ? ' waterfall-scale__tick--end' : ''}`}
            style={{ left: `${String(tick.leftPct)}%` }}
            data-testid="waterfall-scale-tick"
            data-ms={tick.ms}
          >
            {formatTick(tick.ms, model.unit, locale)}
          </span>
        ))}
      </div>
    </div>
  );
}
