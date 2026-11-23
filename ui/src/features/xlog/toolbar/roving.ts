// SPDX-License-Identifier: Apache-2.0
//
// WAI-ARIA toolbar keyboard pattern (docs/15 §13, WO-04 C-12: the chart options are a real
// role="toolbar"): one tab stop for the whole toolbar, Left/Right move between its items (wrapping),
// Home/End go to the first/last item. Pure index arithmetic; the component moves the focus.

export type ToolbarKey = 'ArrowLeft' | 'ArrowRight' | 'Home' | 'End';

/** The item to focus after `key` from `current` among `count` items, or null for other keys. */
export function nextToolbarIndex(key: string, current: number, count: number): number | null {
  if (count <= 0) {
    return null;
  }
  const from = current >= 0 && current < count ? current : 0;
  switch (key) {
    case 'ArrowRight':
      return (from + 1) % count;
    case 'ArrowLeft':
      return (from - 1 + count) % count;
    case 'Home':
      return 0;
    case 'End':
      return count - 1;
    default:
      return null;
  }
}
