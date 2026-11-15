// SPDX-License-Identifier: Apache-2.0
//
// Comparison baseline (docs/15 §6.1, F-U19): the same series shifted by 1 or 7 days, drawn as a
// dashed line (dash [4, 4]) at 60 % opacity in the colour of its series. The legend names it with
// the suffix "1 day ago" / "7 days ago".
import type uPlot from 'uplot';

/** Dash pattern and opacity of baseline lines (docs/15 §6.1). */
export const BASELINE_DASH: readonly number[] = [4, 4];
export const BASELINE_ALPHA = 0.6;

/** uPlot series options of a baseline line. */
export function baselineSeriesOptions(label: string, color: string, scale: string): uPlot.Series {
  return {
    label,
    scale,
    stroke: color,
    width: 1.5,
    dash: [...BASELINE_DASH],
    alpha: BASELINE_ALPHA,
    spanGaps: false,
    points: { show: false },
  };
}
