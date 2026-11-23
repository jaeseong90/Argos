// SPDX-License-Identifier: Apache-2.0
//
// Waterfall primitives shared by the transaction detail (timeline, call tree) and the trace view
// (WO-34, docs/15 §8.4, §9.5, DSN-15-11): time scale, row, DOM virtual list.
export {
  barGeometry,
  DEFAULT_MAX_TICKS,
  formatTick,
  MIN_BAR_WIDTH_PX,
  niceStep,
  shareOf,
  timelineTotal,
  timeScale,
  UNIT_MS,
  unitFor,
  WATERFALL_ROW_HEIGHT,
  type BarGeometry,
  type ScaleTick,
  type TimeInterval,
  type TimeScaleModel,
  type TimeUnit,
} from './scale';
export { TimeScale, type TimeScaleProps } from './TimeScale';
export { isDashedTone, stepTone, TONE_TOKENS, WATERFALL_TONES, type WaterfallTone } from './tones';
export {
  DEFAULT_LABEL_WIDTH,
  DEFAULT_VIRTUAL_ROWS_HEIGHT,
  parentIndex,
  VIRTUAL_ROWS_OVERSCAN,
  VirtualRows,
  type VirtualRowMeta,
  type VirtualRowsProps,
} from './VirtualRows';
export {
  WATERFALL_INDENT_PX,
  WaterfallRow,
  type WaterfallMarker,
  type WaterfallMarkerKind,
  type WaterfallRowProps,
  type WaterfallSegment,
} from './WaterfallRow';
