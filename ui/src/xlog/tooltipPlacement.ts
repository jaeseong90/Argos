// SPDX-License-Identifier: Apache-2.0
//
// Placement of the XLog hover tooltip. The canvas container clips its content (overflow: hidden),
// so the tooltip must stay inside it: by default it sits right of and below the hovered point; it
// flips to the left and/or above when it would cross the container edge, and as a last resort it
// is clamped to the container. Most points sit in the bottom band of the plot (long-tail response
// times), so the vertical flip is the common case there.

/** Gap between the hovered point and the tooltip, CSS px. */
export const TOOLTIP_OFFSET = 12;
/** Minimum distance between the tooltip and the container edge, CSS px. */
export const TOOLTIP_EDGE = 4;

export interface TooltipPlacementInput {
  /** Hovered point relative to the container's padding box, CSS px. */
  readonly x: number;
  readonly y: number;
  /** Tooltip size (border box), CSS px. */
  readonly width: number;
  readonly height: number;
  /** Size of the container's padding box, CSS px; 0 or less = unknown (no constraint). */
  readonly containerWidth: number;
  readonly containerHeight: number;
}

export interface TooltipPosition {
  /** Tooltip's top-left corner relative to the container's padding box, CSS px. */
  readonly left: number;
  readonly top: number;
}

/** One axis: after the anchor, else before it, else clamped into [edge, extent - edge - size]. */
function placeOnAxis(anchor: number, size: number, extent: number): number {
  const after = anchor + TOOLTIP_OFFSET;
  if (!(extent > 0)) {
    return after;
  }
  if (after + size <= extent - TOOLTIP_EDGE) {
    return after;
  }
  const before = anchor - TOOLTIP_OFFSET - size;
  if (before >= TOOLTIP_EDGE) {
    return before;
  }
  const last = extent - TOOLTIP_EDGE - size;
  if (last < TOOLTIP_EDGE) {
    // Larger than the container: keep the start edge visible.
    return Math.max(0, Math.min(TOOLTIP_EDGE, extent - size));
  }
  return Math.min(Math.max(after, TOOLTIP_EDGE), last);
}

export function placeTooltip(input: TooltipPlacementInput): TooltipPosition {
  return {
    left: placeOnAxis(input.x, Math.max(0, input.width), input.containerWidth),
    top: placeOnAxis(input.y, Math.max(0, input.height), input.containerHeight),
  };
}
