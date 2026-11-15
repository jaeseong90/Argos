// SPDX-License-Identifier: Apache-2.0
//
// uPlot plugin `markers` (docs/15 §6.1): event markers (deploy, start, stop, configuration change,
// alert transition) as a vertical line on the canvas plus a DOM marker that TimeSeriesChart renders
// over the plot (shape, "+N" group badge, tooltip, data-testid chart-marker). After each draw the
// plugin groups the markers that would overlap (chartModel.groupMarkers) and reports the layout.
import type uPlot from 'uplot';

import { groupMarkers, type ChartMarker, type MarkerGroup } from '../chartModel';

export interface MarkersPluginOptions {
  readonly getMarkers: () => readonly ChartMarker[];
  /** Event id of the `alert=` deep-link key: that marker leads its group and is emphasised. */
  readonly getEmphasizedId: () => string | null | undefined;
  /** Line colour of a marker (severity colour for alert transitions). */
  readonly colorOf: (marker: ChartMarker) => string;
  readonly onLayout: (groups: MarkerGroup[]) => void;
}

/** Width of the plotting area in CSS px. */
export function plotWidth(u: uPlot): number {
  const ratio = typeof devicePixelRatio === 'number' && devicePixelRatio > 0 ? devicePixelRatio : 1;
  return u.bbox.width / ratio;
}

export function markersPlugin(options: MarkersPluginOptions): uPlot.Plugin {
  return {
    hooks: {
      draw: (u: uPlot) => {
        const groups = groupMarkers(
          options.getMarkers(),
          (ts) => u.valToPos(ts, 'x'),
          plotWidth(u),
          options.getEmphasizedId(),
        );
        const ctx = u.ctx as CanvasRenderingContext2D | null;
        if (ctx !== null && typeof ctx.save === 'function') {
          ctx.save();
          ctx.lineWidth = 1;
          ctx.setLineDash([2, 3]);
          for (const group of groups) {
            const x = Math.round(u.valToPos(group.lead.ts, 'x', true)) + 0.5;
            ctx.strokeStyle = options.colorOf(group.lead);
            ctx.globalAlpha = group.emphasized ? 1 : 0.7;
            ctx.beginPath();
            ctx.moveTo(x, u.bbox.top);
            ctx.lineTo(x, u.bbox.top + u.bbox.height);
            ctx.stroke();
          }
          ctx.restore();
        }
        options.onLayout(groups);
      },
    },
  };
}
