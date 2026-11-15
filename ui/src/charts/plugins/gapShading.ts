// SPDX-License-Identifier: Apache-2.0
//
// uPlot plugin `gapShading` (docs/15 §6.1): collection outages (docs/14 `outages[]`; the field is
// `outages`, never `gaps`, C-5; D-18) as hatched grey rectangles. The plugin computes the clipped
// rectangles after each draw (chartModel.outageRects); TimeSeriesChart draws them as DOM overlays
// with a CSS hatch (data-testid chart-outage), so no inline style attribute is needed (CSP).
import type uPlot from 'uplot';

import { outageRects, type ChartOutage, type OutageRect, type TimeRange } from '../chartModel';

export interface GapShadingPluginOptions {
  readonly getOutages: () => readonly ChartOutage[];
  readonly getRange: () => TimeRange;
  readonly onLayout: (rects: OutageRect[]) => void;
}

export function gapShadingPlugin(options: GapShadingPluginOptions): uPlot.Plugin {
  return {
    hooks: {
      draw: (u: uPlot) => {
        options.onLayout(
          outageRects(options.getOutages(), options.getRange(), (ts) => u.valToPos(ts, 'x')),
        );
      },
    },
  };
}
