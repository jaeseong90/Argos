// SPDX-License-Identifier: Apache-2.0
//
// DOM virtual list of the timeline, call tree and trace view (DSN-15-11, docs/15 §8.4, §14
// "1,000행 60fps"): 1,000 rows put a bounded number of rows in the DOM and scrolling moves the
// window; treegrid semantics (aria-level, aria-expanded, roving tabindex); keys Up/Down, Home/End,
// PageUp/PageDown, Right/Left (expand, collapse, child, parent), Enter and click activate; the
// tooltip slot points to the hovered row.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'N-14' }
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { SafeText } from '../SafeText';
import { withI18n } from '../testing/withI18n';

import { WATERFALL_ROW_HEIGHT } from './scale';
import {
  parentIndex,
  VIRTUAL_ROWS_OVERSCAN,
  VirtualRows,
  type VirtualRowMeta,
} from './VirtualRows';
import { WaterfallRow } from './WaterfallRow';

const wrapper = withI18n('en');

interface Item {
  readonly id: string;
  readonly level: number;
  readonly expanded?: boolean;
}

function flat(count: number): Item[] {
  return Array.from({ length: count }, (_, i) => ({ id: `s${String(i)}`, level: 1 }));
}

function rowIndices(): number[] {
  return screen
    .getAllByTestId('virtual-row')
    .map((row) => Number(row.getAttribute('data-row-index')));
}

function renderList(
  items: readonly Item[],
  props: {
    onActivate?: (item: Item, index: number) => void;
    onExpandedChange?: (item: Item, index: number, expanded: boolean) => void;
    tooltip?: boolean;
    getMeta?: (item: Item) => VirtualRowMeta;
  } = {},
) {
  return render(
    <VirtualRows
      items={items}
      label="Timeline"
      getKey={(item) => item.id}
      getMeta={props.getMeta ?? ((item) => ({ level: item.level, expanded: item.expanded }))}
      onActivate={props.onActivate}
      onExpandedChange={props.onExpandedChange}
      renderTooltip={
        props.tooltip === true ? (item) => <SafeText value={untrusted(item.id)} /> : undefined
      }
      renderRow={(item, index) => (
        <WaterfallRow
          depth={item.level - 1}
          name={<SafeText value={untrusted(item.id)} />}
          startMs={index}
          elapsedMs={1}
          totalMs={items.length}
          tone="method"
        />
      )}
    />,
    { wrapper },
  );
}

beforeEach(() => {
  // jsdom has no scrolling: scrollTo moves scrollTop and fires the scroll event, as a browser does.
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value(this: HTMLElement, options: ScrollToOptions) {
      this.scrollTop = options.top ?? 0;
      this.dispatchEvent(new Event('scroll'));
    },
  });
});

afterEach(() => {
  Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo');
});

describe('VirtualRows virtualization (DSN-15-11, docs/15 §14 1,000 rows)', () => {
  it('renders a bounded number of DOM rows for 1,000 steps and moves the window on scroll', () => {
    renderList(flat(1000));
    // Viewport 480 px / 24 px = 20 visible rows (0..19) + 10 overscan rows below.
    expect(rowIndices()).toEqual(Array.from({ length: 30 }, (_, i) => i));
    const grid = screen.getByRole('treegrid', { name: 'Timeline' });
    expect(grid).toHaveAttribute('aria-rowcount', '1000');
    expect(grid.style.height).toBe(`${String(1000 * WATERFALL_ROW_HEIGHT)}px`);
    const viewport = grid.parentElement as HTMLElement;
    expect(viewport.style.height).toBe('480px');
    act(() => {
      viewport.scrollTop = 500 * WATERFALL_ROW_HEIGHT;
      viewport.dispatchEvent(new Event('scroll'));
    });
    const indices = rowIndices();
    expect(indices[0]).toBe(500 - VIRTUAL_ROWS_OVERSCAN);
    expect(indices.at(-1)).toBe(519 + VIRTUAL_ROWS_OVERSCAN);
    expect(indices.length).toBeLessThanOrEqual(20 + 2 * VIRTUAL_ROWS_OVERSCAN);
    const first = screen.getAllByTestId('virtual-row')[0];
    expect(first?.style.transform).toBe(`translateY(${String(490 * WATERFALL_ROW_HEIGHT)}px)`);
  });

  it('sizes a short list to its rows', () => {
    renderList(flat(3));
    expect(rowIndices()).toEqual([0, 1, 2]);
    const viewport = screen.getByRole('treegrid').parentElement as HTMLElement;
    expect(viewport.style.height).toBe(`${String(3 * WATERFALL_ROW_HEIGHT)}px`);
  });

  it('renders an empty list without rows', () => {
    renderList([]);
    expect(screen.queryAllByTestId('virtual-row')).toHaveLength(0);
    expect(screen.getByRole('treegrid')).toHaveAttribute('aria-rowcount', '0');
  });
});

describe('VirtualRows keyboard and treegrid (WAI-ARIA treegrid, docs/15 §13)', () => {
  it('keeps one tab stop and moves the focus with Up/Down, Home/End and PageUp/PageDown', async () => {
    const user = userEvent.setup();
    renderList(flat(1000));
    // jsdom has no layout: give the viewport the scroll extent a browser computes.
    const viewport = screen.getByRole('treegrid').parentElement as HTMLElement;
    Object.defineProperty(viewport, 'scrollHeight', { value: 1000 * WATERFALL_ROW_HEIGHT });
    Object.defineProperty(viewport, 'clientHeight', { value: 480 });
    const rows = screen.getAllByTestId('virtual-row');
    expect(rows.filter((row) => row.tabIndex === 0)).toHaveLength(1);
    expect(rows[0]).toHaveAttribute('tabindex', '0');
    await user.tab();
    expect(document.activeElement).toBe(rows[0]);
    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '2');
    await user.keyboard('{ArrowUp}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '1');
    await user.keyboard('{PageDown}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '20');
    await user.keyboard('{PageUp}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '1');
    // End scrolls the last row into the window, then focuses it.
    await user.keyboard('{End}');
    await waitFor(() => {
      expect(document.activeElement).toHaveAttribute('data-row-index', '999');
    });
    expect(screen.getAllByTestId('virtual-row').filter((row) => row.tabIndex === 0)).toHaveLength(
      1,
    );
    await user.keyboard('{Home}');
    await waitFor(() => {
      expect(document.activeElement).toHaveAttribute('data-row-index', '0');
    });
    await user.keyboard('{ArrowUp}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '0');
  });

  it('expands, collapses and walks the tree with Right/Left; exposes aria-level and aria-expanded', async () => {
    const user = userEvent.setup();
    const onExpandedChange = vi.fn();
    const items: Item[] = [
      { id: 'root-a', level: 1, expanded: true },
      { id: 'child-a1', level: 2 },
      { id: 'child-a2', level: 2, expanded: false },
      { id: 'root-b', level: 1 },
    ];
    renderList(items, { onExpandedChange });
    const rows = screen.getAllByTestId('virtual-row');
    expect(rows.map((row) => row.getAttribute('aria-level'))).toEqual(['1', '2', '2', '1']);
    expect(rows[0]).toHaveAttribute('aria-expanded', 'true');
    expect(rows[1]).not.toHaveAttribute('aria-expanded');
    expect(rows[2]).toHaveAttribute('aria-expanded', 'false');
    await user.tab();
    // Right on an expanded row moves to its first child.
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '1');
    // Left on a leaf moves to its parent.
    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toHaveAttribute('data-row-index', '0');
    // Left on an expanded row collapses it.
    await user.keyboard('{ArrowLeft}');
    expect(onExpandedChange).toHaveBeenLastCalledWith(items[0], 0, false);
    // Right on a collapsed row expands it.
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowRight}');
    expect(onExpandedChange).toHaveBeenLastCalledWith(items[2], 2, true);
    expect(onExpandedChange).toHaveBeenCalledTimes(2);
  });

  it('activates a row with Enter and with a click (focus follows the click)', async () => {
    const user = userEvent.setup();
    const onActivate = vi.fn();
    renderList(flat(5), { onActivate });
    await user.click(screen.getAllByTestId('virtual-row')[3] as HTMLElement);
    expect(onActivate).toHaveBeenLastCalledWith({ id: 's3', level: 1 }, 3);
    expect(screen.getAllByTestId('virtual-row')[3]).toHaveAttribute('tabindex', '0');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onActivate).toHaveBeenLastCalledWith({ id: 's4', level: 1 }, 4);
    expect(onActivate).toHaveBeenCalledTimes(2);
  });

  it('marks the selected row with aria-selected', () => {
    renderList(flat(3), { getMeta: (item) => ({ level: 1, selected: item.id === 's1' }) });
    const rows = screen.getAllByTestId('virtual-row');
    expect(rows.map((row) => row.getAttribute('aria-selected'))).toEqual([
      'false',
      'true',
      'false',
    ]);
    expect(rows[1]).toHaveClass('virtual-rows__row--selected');
  });

  it('parentIndex finds the nearest row above with a lower level', () => {
    expect(parentIndex([1, 2, 3, 2, 1], 2)).toBe(1);
    expect(parentIndex([1, 2, 3, 2, 1], 3)).toBe(0);
    expect(parentIndex([1, 2, 3, 2, 1], 4)).toBe(-1);
    expect(parentIndex([1], 0)).toBe(-1);
  });
});

describe('VirtualRows tooltip slot', () => {
  it('shows the tooltip of the hovered row and of the focused row, described by aria', async () => {
    const user = userEvent.setup();
    renderList(flat(5), { tooltip: true });
    expect(screen.queryByTestId('waterfall-tooltip')).not.toBeInTheDocument();
    const rows = screen.getAllByTestId('virtual-row');
    fireEvent.mouseEnter(rows[2] as HTMLElement);
    const tooltip = screen.getByRole('tooltip');
    expect(tooltip).toHaveTextContent('s2');
    expect(tooltip.style.top).toBe(`${String(3 * WATERFALL_ROW_HEIGHT)}px`);
    expect(rows[2]).toHaveAttribute('aria-describedby', tooltip.id);
    fireEvent.mouseLeave(rows[2] as HTMLElement);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    await user.tab();
    expect(screen.getByRole('tooltip')).toHaveTextContent('s0');
  });
});
