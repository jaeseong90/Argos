// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { TOOLTIP_EDGE, TOOLTIP_OFFSET, placeTooltip } from './tooltipPlacement';

// The /xlog-poc container: 1254 × 420 CSS px; a tooltip of about 220 × 117 px.
const container = { containerWidth: 1254, containerHeight: 420 };
const size = { width: 220, height: 117 };

function inside(position: { left: number; top: number }): boolean {
  return (
    position.left >= 0 &&
    position.top >= 0 &&
    position.left + size.width <= container.containerWidth &&
    position.top + size.height <= container.containerHeight
  );
}

describe('placeTooltip (hover tooltip inside the canvas container)', () => {
  it('places the tooltip right of and below the point when it fits', () => {
    const position = placeTooltip({ x: 300, y: 100, ...size, ...container });
    expect(position).toEqual({ left: 300 + TOOLTIP_OFFSET, top: 100 + TOOLTIP_OFFSET });
  });

  it('flips above the point near the bottom edge (the dense band of the plot)', () => {
    // A point 8 px above the x axis: y = 420 - 26 (bottom margin) - 8.
    const y = 386;
    const position = placeTooltip({ x: 300, y, ...size, ...container });
    expect(position).toEqual({ left: 300 + TOOLTIP_OFFSET, top: y - TOOLTIP_OFFSET - size.height });
    expect(inside(position)).toBe(true);
  });

  it('flips left of the point near the right edge', () => {
    const x = 1250;
    const position = placeTooltip({ x, y: 100, ...size, ...container });
    expect(position).toEqual({ left: x - TOOLTIP_OFFSET - size.width, top: 100 + TOOLTIP_OFFSET });
    expect(inside(position)).toBe(true);
  });

  it('flips both ways in the bottom-right corner', () => {
    const position = placeTooltip({ x: 1250, y: 410, ...size, ...container });
    expect(position).toEqual({
      left: 1250 - TOOLTIP_OFFSET - size.width,
      top: 410 - TOOLTIP_OFFSET - size.height,
    });
    expect(inside(position)).toBe(true);
  });

  it('keeps the tooltip inside in the top-left corner', () => {
    const position = placeTooltip({ x: 0, y: 0, ...size, ...container });
    expect(position).toEqual({ left: TOOLTIP_OFFSET, top: TOOLTIP_OFFSET });
    expect(inside(position)).toBe(true);
  });

  it('clamps to the container when neither side has room', () => {
    // A 160 px tall container: 117 px fits neither below nor above a point in the middle.
    const small = { containerWidth: 300, containerHeight: 160 };
    const position = placeTooltip({ x: 150, y: 80, ...size, ...small });
    expect(position.top).toBe(small.containerHeight - TOOLTIP_EDGE - size.height);
    expect(position.left).toBe(small.containerWidth - TOOLTIP_EDGE - size.width);
    expect(position.top).toBeGreaterThanOrEqual(TOOLTIP_EDGE);
    expect(position.left).toBeGreaterThanOrEqual(TOOLTIP_EDGE);
  });

  it('keeps the start edge visible when the tooltip is larger than the container', () => {
    const position = placeTooltip({
      x: 50,
      y: 50,
      width: 400,
      height: 300,
      containerWidth: 200,
      containerHeight: 100,
    });
    expect(position).toEqual({ left: 0, top: 0 });
  });

  it('does not constrain an axis whose container size is unknown', () => {
    expect(
      placeTooltip({ x: 900, y: 900, ...size, containerWidth: 0, containerHeight: 0 }),
    ).toEqual({ left: 900 + TOOLTIP_OFFSET, top: 900 + TOOLTIP_OFFSET });
  });
});
