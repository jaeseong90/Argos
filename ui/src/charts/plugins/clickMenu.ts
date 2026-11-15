// SPDX-License-Identifier: Apache-2.0
//
// uPlot plugin `clickMenu` (docs/15 §6.1, 05 §2.0-5): a click on the plot (not a drag; uPlot
// swallows the click that ends a drag selection) reports the nearest data index and the cursor
// position. TimeSeriesChart opens the menu "Open XLog at this time" (± range/60, min 1 minute,
// chartModel.clickMenuRange) and "Related list" there.
import type uPlot from 'uplot';

export interface ClickMenuPluginOptions {
  readonly onPointClick: (index: number, left: number, top: number) => void;
}

export function clickMenuPlugin(options: ClickMenuPluginOptions): uPlot.Plugin {
  let listener: ((event: MouseEvent) => void) | null = null;
  return {
    hooks: {
      ready: (u: uPlot) => {
        listener = (event: MouseEvent) => {
          const left = u.cursor.left ?? event.offsetX;
          const top = u.cursor.top ?? event.offsetY;
          const index = u.cursor.idx ?? u.posToIdx(left);
          if (index < 0 || left < 0) {
            return;
          }
          options.onPointClick(index, left, top);
        };
        u.over.addEventListener('click', listener);
      },
      destroy: (u: uPlot) => {
        if (listener !== null) {
          u.over.removeEventListener('click', listener);
          listener = null;
        }
      },
    },
  };
}
