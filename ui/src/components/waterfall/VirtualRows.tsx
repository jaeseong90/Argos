// SPDX-License-Identifier: Apache-2.0
//
// DOM virtual list of the transaction timeline, call tree and trace view (DSN-15-11, docs/15 §8.4,
// §14 "1,000행 60fps"): fixed 24 px rows in a viewport of known height, only the rows in view plus
// an overscan are in the DOM (TanStack Virtual, the virtual-core Virtualizer driven as DataTable
// does, so the row window is the same in a browser and in jsdom). No SVG or canvas: rows are text,
// selectable and readable by assistive technology.
//
// Accessibility (WAI-ARIA treegrid, docs/15 §13): the list is role=treegrid (or grid), rows are
// role=row with aria-level / aria-expanded / aria-selected from getMeta, and a roving tabindex
// keeps one row in the tab order. Keys on a row: Up/Down, Home/End, PageUp/PageDown move the focus;
// Right expands a collapsed row or moves to its first child; Left collapses an expanded row or moves
// to its parent; Enter activates (onActivate, also on click). The tooltip slot (renderTooltip)
// shows one tooltip for the hovered or focused row, outside the scroll viewport so it is never
// clipped; the row points to it with aria-describedby.
import {
  elementScroll,
  observeElementOffset,
  Virtualizer,
  type VirtualizerOptions,
} from '@tanstack/react-virtual';
import {
  useEffect,
  useId,
  useLayoutEffect,
  useReducer,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
} from 'react';

import { WATERFALL_ROW_HEIGHT } from './scale';

import './waterfall.css';

/** Default viewport height in px (20 rows of 24 px). */
export const DEFAULT_VIRTUAL_ROWS_HEIGHT = 480;
/** Rows rendered beyond the viewport (each side). */
export const VIRTUAL_ROWS_OVERSCAN = 10;
/** Default width of the label column in px (the track takes the rest). */
export const DEFAULT_LABEL_WIDTH = 360;

/** Tree state of a row for assistive technology and the arrow keys. */
export interface VirtualRowMeta {
  /** Tree level, 1 = top (aria-level). */
  readonly level?: number;
  /** true/false for a row with children (aria-expanded), undefined for a leaf. */
  readonly expanded?: boolean;
  /** The row is the selected one (aria-selected), e.g. the step shown in the side panel. */
  readonly selected?: boolean;
}

export interface VirtualRowsProps<T> {
  readonly items: readonly T[];
  readonly getKey: (item: T, index: number) => string;
  /** The row's cells (role=gridcell elements, e.g. WaterfallRow). */
  readonly renderRow: (item: T, index: number, focused: boolean) => ReactNode;
  readonly getMeta?: (item: T, index: number) => VirtualRowMeta;
  /** Enter or click on a row (open the side panel, open the transaction). */
  readonly onActivate?: (item: T, index: number) => void;
  /** Right/Left on a row with children (getMeta().expanded defined). */
  readonly onExpandedChange?: (item: T, index: number, expanded: boolean) => void;
  /** The tooltip of the hovered or focused row (null for none). */
  readonly renderTooltip?: (item: T, index: number) => ReactNode;
  /** Content above the rows, aligned to the same columns (TimeScale). */
  readonly header?: ReactNode;
  /** Accessible name of the list (translated by the caller). */
  readonly label: string;
  readonly role?: 'treegrid' | 'grid';
  /** Largest viewport height in px; a shorter list is as tall as its rows. */
  readonly maxHeight?: number;
  readonly rowHeight?: number;
  readonly overscan?: number;
  /** Width of the label column in px (shared with the header). */
  readonly labelWidth?: number;
  /** Controlled focus; without it the list keeps its own. */
  readonly focusedIndex?: number | null;
  readonly onFocusedIndexChange?: (index: number) => void;
  /** data-testid of each row element (default `virtual-row`). */
  readonly rowTestId?: string;
  readonly 'data-testid'?: string;
}

interface RowVirtualizerOptions {
  readonly count: number;
  readonly getScrollElement: () => HTMLDivElement | null;
  readonly rowHeight: number;
  readonly overscan: number;
  readonly viewportHeight: number;
  readonly getItemKey: (index: number) => string;
}

/** The virtual-core Virtualizer for fixed rows in a viewport of known height (as DataTable). */
function useRowVirtualizer(options: RowVirtualizerOptions): Virtualizer<HTMLDivElement, Element> {
  const [, rerender] = useReducer((tick: number) => tick + 1, 0);
  const resolved: VirtualizerOptions<HTMLDivElement, Element> = {
    count: options.count,
    getScrollElement: options.getScrollElement,
    estimateSize: () => options.rowHeight,
    overscan: options.overscan,
    getItemKey: options.getItemKey,
    initialRect: { width: 0, height: options.viewportHeight },
    scrollToFn: elementScroll,
    observeElementOffset,
    observeElementRect: (instance, callback) => {
      callback({ width: instance.scrollElement?.clientWidth ?? 0, height: options.viewportHeight });
      return undefined;
    },
    onChange: () => {
      rerender();
    },
  };
  const [instance] = useState(() => new Virtualizer<HTMLDivElement, Element>(resolved));
  instance.setOptions(resolved);
  useLayoutEffect(() => instance._didMount(), [instance]);
  useLayoutEffect(() => {
    instance._willUpdate();
  });
  return instance;
}

/** Class names of a row element. */
function rowClassName(focused: boolean, selected: boolean): string {
  const classes = ['virtual-rows__row'];
  if (focused) {
    classes.push('virtual-rows__row--focused');
  }
  if (selected) {
    classes.push('virtual-rows__row--selected');
  }
  return classes.join(' ');
}

/** Index of the parent row: the nearest row above with a lower level, or -1. */
export function parentIndex(levels: readonly number[], index: number): number {
  const level = levels[index] ?? 1;
  for (let i = index - 1; i >= 0; i -= 1) {
    if ((levels[i] ?? 1) < level) {
      return i;
    }
  }
  return -1;
}

export function VirtualRows<T>({
  items,
  getKey,
  renderRow,
  getMeta,
  onActivate,
  onExpandedChange,
  renderTooltip,
  header,
  label,
  role = 'treegrid',
  maxHeight = DEFAULT_VIRTUAL_ROWS_HEIGHT,
  rowHeight = WATERFALL_ROW_HEIGHT,
  overscan = VIRTUAL_ROWS_OVERSCAN,
  labelWidth = DEFAULT_LABEL_WIDTH,
  focusedIndex: controlledFocus,
  onFocusedIndexChange,
  rowTestId = 'virtual-row',
  'data-testid': testId = 'virtual-rows',
}: VirtualRowsProps<T>) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const tooltipId = useId();
  const [ownFocus, setOwnFocus] = useState<number | null>(null);
  const [hovered, setHovered] = useState<number | null>(null);
  const [focusWithin, setFocusWithin] = useState(false);
  // Set when the focus moves by key: only then the row element takes the DOM focus.
  const focusRequest = useRef(false);
  const count = items.length;
  const focused = controlledFocus === undefined ? ownFocus : controlledFocus;
  const focusedIndex = focused !== null && focused >= 0 && focused < count ? focused : null;
  const viewportHeight = Math.min(maxHeight, Math.max(rowHeight, count * rowHeight));

  const virtualizer = useRowVirtualizer({
    count,
    getScrollElement: () => scrollRef.current,
    rowHeight,
    overscan,
    viewportHeight,
    getItemKey: (index) => {
      const item = items[index];
      return item === undefined ? String(index) : getKey(item, index);
    },
  });

  const setFocus = (index: number, byKey: boolean): void => {
    if (count === 0) {
      return;
    }
    const next = Math.min(count - 1, Math.max(0, index));
    focusRequest.current = byKey;
    if (controlledFocus === undefined) {
      setOwnFocus(next);
    }
    onFocusedIndexChange?.(next);
  };

  // Keyboard focus follows the focused index: scroll it into view, then focus its element (a row
  // that was outside the window appears after the scroll; focus it on the next frame).
  useEffect(() => {
    if (focusedIndex === null || !focusRequest.current) {
      return;
    }
    focusRequest.current = false;
    virtualizer.scrollToIndex(focusedIndex, { align: 'auto' });
    const find = (): HTMLElement | null | undefined =>
      listRef.current?.querySelector<HTMLElement>(`[data-row-index="${String(focusedIndex)}"]`);
    const element = find();
    if (element !== null && element !== undefined) {
      element.focus();
      return;
    }
    const frame = requestAnimationFrame(() => {
      find()?.focus();
    });
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [focusedIndex, virtualizer]);

  const metas = items.map((item, index) => getMeta?.(item, index) ?? {});
  const levels = metas.map((meta) => meta.level ?? 1);
  const pageRows = Math.max(1, Math.floor(viewportHeight / rowHeight) - 1);

  const onRowKeyDown = (event: KeyboardEvent<HTMLDivElement>, index: number): void => {
    if (event.target !== event.currentTarget) {
      return;
    }
    const item = items[index];
    if (item === undefined) {
      return;
    }
    const meta = metas[index] ?? {};
    const move = (to: number): void => {
      event.preventDefault();
      setFocus(to, true);
    };
    switch (event.key) {
      case 'ArrowDown':
        move(index + 1);
        break;
      case 'ArrowUp':
        move(index - 1);
        break;
      case 'Home':
        move(0);
        break;
      case 'End':
        move(count - 1);
        break;
      case 'PageDown':
        move(index + pageRows);
        break;
      case 'PageUp':
        move(index - pageRows);
        break;
      case 'ArrowRight':
        if (meta.expanded === false && onExpandedChange !== undefined) {
          event.preventDefault();
          onExpandedChange(item, index, true);
        } else if (meta.expanded === true && (levels[index + 1] ?? 0) > (levels[index] ?? 1)) {
          move(index + 1);
        }
        break;
      case 'ArrowLeft':
        if (meta.expanded === true && onExpandedChange !== undefined) {
          event.preventDefault();
          onExpandedChange(item, index, false);
        } else {
          const parent = parentIndex(levels, index);
          if (parent >= 0) {
            move(parent);
          }
        }
        break;
      case 'Enter':
        if (onActivate !== undefined) {
          event.preventDefault();
          onActivate(item, index);
        }
        break;
      default:
        break;
    }
  };

  const tipIndex = hovered ?? (focusWithin ? focusedIndex : null);
  const tipItem = tipIndex === null ? undefined : items[tipIndex];
  const tooltip =
    renderTooltip !== undefined && tipIndex !== null && tipItem !== undefined
      ? renderTooltip(tipItem, tipIndex)
      : null;
  // Below the row, in the coordinates of the body (the viewport's scroll offset subtracted).
  const tipTop =
    tipIndex === null ? 0 : (tipIndex + 1) * rowHeight - (virtualizer.scrollOffset ?? 0);
  // The focused row is the tab stop; with none yet, the first row is.
  const tabStop = focusedIndex ?? 0;
  const rootStyle = { '--waterfall-label-w': `${String(labelWidth)}px` } as CSSProperties;

  return (
    <div className="virtual-rows" style={rootStyle} data-testid={testId}>
      {header}
      <div className="virtual-rows__body">
        <div
          ref={scrollRef}
          className="virtual-rows__viewport"
          style={{ height: `${String(viewportHeight)}px` }}
          data-virtual-count={count}
        >
          <div
            ref={listRef}
            role={role}
            aria-label={label}
            aria-rowcount={count}
            className="virtual-rows__list"
            style={{ height: `${String(virtualizer.getTotalSize())}px` }}
          >
            {virtualizer.getVirtualItems().map((virtualItem) => {
              const index = virtualItem.index;
              const item = items[index];
              if (item === undefined) {
                return null;
              }
              const meta = metas[index] ?? {};
              const isFocused = focusedIndex === index;
              return (
                <div
                  key={virtualItem.key}
                  role="row"
                  className={rowClassName(isFocused, meta.selected === true)}
                  aria-rowindex={index + 1}
                  aria-level={role === 'treegrid' ? (meta.level ?? 1) : undefined}
                  aria-expanded={meta.expanded}
                  aria-selected={meta.selected ?? false}
                  aria-describedby={tooltip !== null && tipIndex === index ? tooltipId : undefined}
                  tabIndex={index === tabStop ? 0 : -1}
                  data-row-index={index}
                  data-testid={rowTestId}
                  style={{
                    height: `${String(rowHeight)}px`,
                    transform: `translateY(${String(virtualItem.start)}px)`,
                  }}
                  onClick={() => {
                    setFocus(index, false);
                    onActivate?.(item, index);
                  }}
                  onKeyDown={(event) => {
                    onRowKeyDown(event, index);
                  }}
                  onFocus={() => {
                    setFocusWithin(true);
                    if (focusedIndex !== index) {
                      setFocus(index, false);
                    }
                  }}
                  onBlur={(event) => {
                    // Focus leaving the list (not moving to another row) hides the tooltip.
                    const next = event.relatedTarget;
                    if (!(next instanceof Node) || listRef.current?.contains(next) !== true) {
                      setFocusWithin(false);
                    }
                  }}
                  onMouseEnter={() => {
                    setHovered(index);
                  }}
                  onMouseLeave={() => {
                    setHovered((current) => (current === index ? null : current));
                  }}
                >
                  {renderRow(item, index, isFocused)}
                </div>
              );
            })}
          </div>
        </div>
        {tooltip === null ? null : (
          <div
            id={tooltipId}
            role="tooltip"
            className="virtual-rows__tooltip"
            style={{ top: `${String(tipTop)}px` }}
            data-testid="waterfall-tooltip"
          >
            {tooltip}
          </div>
        )}
      </div>
    </div>
  );
}
