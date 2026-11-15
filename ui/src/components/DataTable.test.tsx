// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DataTable,
  nextSort,
  replaceFieldFilters,
  VIRTUALIZE_THRESHOLD,
  type DataTableColumn,
} from './DataTable';
import type { FilterSpec } from './filters';
import { withI18n } from './testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from './testing/xssPayloads';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U16' };

interface Row {
  readonly id: string;
  readonly service: string;
  readonly instance: string;
  readonly instanceId: string;
  readonly p95: number | null;
  readonly count: number;
  readonly delta: number | null;
  readonly url: string;
}

const COLUMNS: readonly DataTableColumn<Row>[] = [
  {
    id: 'service',
    header: 'Service',
    value: (row) => row.service,
    filterable: true,
    filter: { type: 'text' },
    mono: true,
  },
  {
    id: 'instance',
    header: 'Instance',
    value: (row) => row.instance,
    filterable: true,
    filterField: 'instance_id',
    filterValue: (row) => row.instanceId,
    filter: {
      type: 'select',
      options: [
        { value: '7', label: 'api-1' },
        { value: '8', label: 'api-2' },
      ],
    },
  },
  {
    id: 'p95',
    header: 'p95',
    value: (row) => row.p95,
    kind: 'ms',
    filter: { type: 'range' },
    delta: { value: (row) => row.delta, goodWhen: 'down' },
  },
  { id: 'count', header: 'Count', value: (row) => row.count, kind: 'count', hideable: true },
  {
    id: 'link',
    header: 'Link',
    kind: 'link',
    value: (row) => row.url,
    href: (row) => row.url,
    defaultHidden: true,
  },
];

const ROWS: readonly Row[] = [
  {
    id: 'a',
    service: 'GET /a',
    instance: 'api-1',
    instanceId: '7',
    p95: 120,
    count: 10,
    delta: 0.1,
    url: '/apps/1/a',
  },
  {
    id: 'b',
    service: 'GET /b',
    instance: 'api-2',
    instanceId: '8',
    p95: null,
    count: 30,
    delta: null,
    url: '/apps/1/b',
  },
  {
    id: 'c',
    service: 'GET /c',
    instance: 'api-1',
    instanceId: '7',
    p95: 900,
    count: 20,
    delta: -0.2,
    url: '/apps/1/c',
  },
];

const wrapper = withI18n('en');
const rowKey = (row: Row): string => row.id;

function rowKeys(): (string | null)[] {
  return screen.getAllByTestId('data-table-row').map((row) => row.getAttribute('data-row-key'));
}

function makeRows(count: number): Row[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `r${String(index)}`,
    service: `GET /s/${String(index)}`,
    instance: 'api-1',
    instanceId: '7',
    p95: index,
    count: index,
    delta: null,
    url: '/x',
  }));
}

afterEach(() => {
  localStorage.clear();
  Reflect.deleteProperty(window, '__xss');
  vi.restoreAllMocks();
});

describe('DataTable sorting (docs/15 §6.3)', () => {
  it('T-UI-004 asks the server to sort (onSortChange) and keeps the server order', async () => {
    const user = userEvent.setup();
    const onSortChange = vi.fn();
    render(
      <DataTable
        tableId="t"
        caption="Services"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onSortChange={onSortChange}
      />,
      {
        wrapper,
      },
    );
    await user.click(screen.getByTestId('data-table-sort-p95'));
    expect(onSortChange).toHaveBeenLastCalledWith({ id: 'p95', desc: true });
    expect(rowKeys()).toEqual(['a', 'b', 'c']);
    expect(screen.getByTestId('data-table-sort-p95').closest('th')).toHaveAttribute(
      'aria-sort',
      'descending',
    );
    await user.click(screen.getByTestId('data-table-sort-p95'));
    expect(onSortChange).toHaveBeenLastCalledWith({ id: 'p95', desc: false });
    await user.click(screen.getByTestId('data-table-sort-service'));
    expect(onSortChange).toHaveBeenLastCalledWith({ id: 'service', desc: false });
  });

  it('T-UI-004 sorts by itself only when the rows are complete, missing values last', async () => {
    const user = userEvent.setup();
    render(
      <DataTable
        tableId="t"
        caption="Services"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        dataComplete
      />,
      { wrapper },
    );
    await user.click(screen.getByTestId('data-table-sort-p95'));
    expect(rowKeys()).toEqual(['c', 'a', 'b']);
    await user.click(screen.getByTestId('data-table-sort-p95'));
    expect(rowKeys()).toEqual(['a', 'c', 'b']);
  });

  it('follows a controlled sort and computes the next sort', () => {
    render(
      <DataTable
        tableId="t"
        caption="Services"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        sort={{ id: 'count', desc: true }}
        dataComplete
      />,
      { wrapper },
    );
    expect(rowKeys()).toEqual(['b', 'c', 'a']);
    expect(nextSort(null, 'p95', true)).toEqual({ id: 'p95', desc: true });
    expect(nextSort({ id: 'p95', desc: true }, 'p95', true)).toEqual({ id: 'p95', desc: false });
    expect(nextSort({ id: 'p95', desc: true }, 'service', false)).toEqual({
      id: 'service',
      desc: false,
    });
  });
});

describe('DataTable filters (05 §2.0-4)', () => {
  it('T-UI-004 emits text, range and select column filters', async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    const filters: FilterSpec[] = [{ field: 'kind', op: 'eq', value: 'HTTP' }];
    render(
      <DataTable
        tableId="t"
        caption="Services"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        filters={filters}
        onFiltersChange={onFiltersChange}
      />,
      { wrapper },
    );
    await user.click(screen.getByTestId('data-table-filter-button-service'));
    await user.type(screen.getByTestId('data-table-filter-text'), 'orders');
    await user.click(screen.getByTestId('data-table-filter-apply'));
    expect(onFiltersChange).toHaveBeenLastCalledWith([
      ...filters,
      { field: 'service', op: 'contains', value: 'orders' },
    ]);

    await user.click(screen.getByTestId('data-table-filter-button-p95'));
    await user.type(screen.getByTestId('data-table-filter-min'), '500');
    await user.type(screen.getByTestId('data-table-filter-max'), '100');
    expect(screen.getByTestId('data-table-filter-apply')).toBeDisabled();
    await user.clear(screen.getByTestId('data-table-filter-max'));
    await user.type(screen.getByTestId('data-table-filter-max'), '2000');
    await user.click(screen.getByTestId('data-table-filter-apply'));
    expect(onFiltersChange).toHaveBeenLastCalledWith([
      ...filters,
      { field: 'p95', op: 'gte', value: '500' },
      { field: 'p95', op: 'lte', value: '2000' },
    ]);

    await user.click(screen.getByTestId('data-table-filter-button-instance_id'));
    await user.selectOptions(screen.getByTestId('data-table-filter-select'), '8');
    await user.click(screen.getByTestId('data-table-filter-apply'));
    expect(onFiltersChange).toHaveBeenLastCalledWith([
      ...filters,
      { field: 'instance_id', op: 'eq', value: '8' },
    ]);
  });

  it('prefills the form from applied filters and clears only that field', async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    const filters: FilterSpec[] = [
      { field: 'service', op: 'contains', value: 'api' },
      { field: 'service', op: 'ne', value: 'GET /a' },
    ];
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        filters={filters}
        onFiltersChange={onFiltersChange}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('data-table-filter-button-service')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await user.click(screen.getByTestId('data-table-filter-button-service'));
    expect(screen.getByTestId('data-table-filter-text')).toHaveValue('api');
    await user.click(screen.getByTestId('data-table-filter-clear'));
    expect(onFiltersChange).toHaveBeenLastCalledWith([
      { field: 'service', op: 'ne', value: 'GET /a' },
    ]);
    expect(replaceFieldFilters(filters, 'service', ['ne'], [])).toEqual([filters[0]]);
  });

  it('T-UI-004 emits include/exclude from the hover buttons and the context menu', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onCellFilter={onCellFilter}
      />,
      {
        wrapper,
      },
    );
    const firstRow = screen.getAllByTestId('data-table-row')[0] as HTMLElement;
    const instanceCell = within(firstRow).getAllByTestId('data-table-cell')[1] as HTMLElement;
    await user.click(within(instanceCell).getByTestId('cell-filter-include'));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'instance_id', op: 'eq', value: '7' });
    const serviceCell = within(firstRow).getAllByTestId('data-table-cell')[0] as HTMLElement;
    await user.click(within(serviceCell).getByTestId('cell-filter-exclude'));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'service', op: 'ne', value: 'GET /a' });

    fireEvent.contextMenu(serviceCell);
    const menu = screen.getByTestId('data-table-cell-menu');
    expect(menu).toHaveAttribute('role', 'menu');
    await user.click(within(menu).getByRole('menuitem', { name: 'Filter by this value' }));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'service', op: 'eq', value: 'GET /a' });
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    // Columns without filterable and missing values offer no actions.
    const p95Cell = within(
      screen.getAllByTestId('data-table-row')[1] as HTMLElement,
    ).getAllByTestId('data-table-cell')[2] as HTMLElement;
    expect(within(p95Cell).queryByTestId('cell-filter-include')).toBeNull();
  });
});

describe('DataTable columns and widths', () => {
  it('T-UI-004 emits the chosen columns and hides unchosen ones', async () => {
    const user = userEvent.setup();
    const onVisibleColumnsChange = vi.fn();
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onVisibleColumnsChange={onVisibleColumnsChange}
      />,
      { wrapper },
    );
    expect(screen.queryByTestId('data-table-sort-link')).toBeNull();
    await user.click(screen.getByTestId('column-chooser'));
    await user.click(screen.getByTestId('column-chooser-count'));
    expect(onVisibleColumnsChange).toHaveBeenLastCalledWith(['service', 'instance', 'p95']);
    expect(screen.queryByTestId('data-table-sort-count')).toBeNull();
    await user.click(screen.getByTestId('column-chooser-link'));
    expect(onVisibleColumnsChange).toHaveBeenLastCalledWith(['service', 'instance', 'p95', 'link']);
    expect(screen.getByTestId('data-table-sort-link')).toBeInTheDocument();
  });

  it('shows exactly the controlled columns (URL cols=)', () => {
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        visibleColumns={['service', 'link']}
      />,
      {
        wrapper,
      },
    );
    const headers = screen.getAllByRole('columnheader').map((th) => th.getAttribute('data-column'));
    expect(headers).toEqual(['service', 'link']);
  });

  it('keeps column widths in localStorage argos.ui.cols', () => {
    const { unmount } = render(
      <DataTable tableId="services" caption="S" columns={COLUMNS} rows={ROWS} rowKey={rowKey} />,
      {
        wrapper,
      },
    );
    fireEvent.keyDown(screen.getByTestId('data-table-resize-service'), { key: 'ArrowRight' });
    expect(JSON.parse(localStorage.getItem('argos.ui.cols') ?? '{}')).toEqual({
      services: { service: 236 },
    });
    unmount();
    render(
      <DataTable tableId="services" caption="S" columns={COLUMNS} rows={ROWS} rowKey={rowKey} />,
      { wrapper },
    );
    const grid = screen.getByRole('table');
    expect(grid.style.getPropertyValue('--dt-cols')).toMatch(/^minmax\(236px, 1fr\)/);
  });
});

describe('DataTable keyboard (docs/15 §6.3, §13)', () => {
  it('T-UI-004 moves the row focus with Up/Down, opens with Enter, / focuses a filter, Esc clears', async () => {
    const user = userEvent.setup();
    const onRowOpen = vi.fn();
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onRowOpen={onRowOpen}
        filters={[]}
        onFiltersChange={vi.fn()}
      />,
      { wrapper },
    );
    const rows = screen.getAllByTestId('data-table-row');
    expect(rows[0]).toHaveAttribute('tabindex', '0');
    (rows[0] as HTMLElement).focus();
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getAllByTestId('data-table-row')[1]);
    expect(screen.getAllByTestId('data-table-row')[1]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(onRowOpen).toHaveBeenCalledWith(ROWS[1]);
    await user.keyboard('{ArrowUp}');
    expect(document.activeElement).toBe(screen.getAllByTestId('data-table-row')[0]);
    await user.keyboard('{Escape}');
    expect(
      screen
        .getAllByTestId('data-table-row')
        .filter((row) => row.getAttribute('aria-selected') === 'true'),
    ).toHaveLength(0);
    (screen.getAllByTestId('data-table-row')[0] as HTMLElement).focus();
    await user.keyboard('/');
    expect(document.activeElement).toBe(screen.getByTestId('data-table-filter-text'));
  });

  const firstRow = (): HTMLElement => screen.getAllByTestId('data-table-row')[0] as HTMLElement;
  const focusCellColumn = (row: HTMLElement): (string | null)[] =>
    within(row)
      .getAllByTestId('data-table-cell')
      .filter((cell) => cell.getAttribute('aria-current') === 'true')
      .map((cell) => cell.getAttribute('data-column'));
  const renderFilterable = (onCellFilter: (filter: unknown) => void) =>
    render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onCellFilter={onCellFilter}
      />,
      { wrapper },
    );

  it('T-UI-004 keyboard include/exclude: Right, Shift+F10, Down, Enter excludes the focus cell value', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderFilterable(onCellFilter);
    act(() => {
      firstRow().focus();
    });
    // The focus cell starts on the first value-filterable column and is announced on the row.
    expect(focusCellColumn(firstRow())).toEqual(['service']);
    expect(firstRow()).toHaveAccessibleDescription(
      'Value column: Service. Left and Right arrows change it, Shift+F10 opens the value actions.',
    );
    await user.keyboard('{ArrowRight}');
    expect(focusCellColumn(firstRow())).toEqual(['instance']);
    expect(firstRow()).toHaveAccessibleDescription(/^Value column: Instance\./);
    // Right stops at the last filterable cell (p95 and count are not filterable).
    await user.keyboard('{ArrowRight}');
    expect(focusCellColumn(firstRow())).toEqual(['instance']);
    expect(document.activeElement).toBe(firstRow());

    await user.keyboard('{Shift>}{F10}{/Shift}');
    const menu = screen.getByTestId('data-table-cell-menu');
    expect(menu).toHaveAttribute('role', 'menu');
    expect(menu.closest('[data-testid="data-table-cell"]')).toHaveAttribute(
      'data-column',
      'instance',
    );
    expect(document.activeElement).toBe(
      within(menu).getByRole('menuitem', { name: 'Filter by this value' }),
    );
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(
      within(menu).getByRole('menuitem', { name: 'Exclude this value' }),
    );
    await user.keyboard('{Enter}');
    expect(onCellFilter).toHaveBeenCalledTimes(1);
    expect(onCellFilter).toHaveBeenCalledWith({ field: 'instance_id', op: 'ne', value: '7' });
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    expect(document.activeElement).toBe(firstRow());
    expect(firstRow()).toHaveAttribute('aria-selected', 'true');
  });

  it('T-UI-004 keyboard include/exclude: the context menu key opens the focus cell menu, Enter includes', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderFilterable(onCellFilter);
    act(() => {
      firstRow().focus();
    });
    await user.keyboard('{ArrowRight}{ArrowLeft}');
    expect(focusCellColumn(firstRow())).toEqual(['service']);
    await user.keyboard('{ContextMenu}');
    const menu = screen.getByTestId('data-table-cell-menu');
    expect(menu.closest('[data-testid="data-table-cell"]')).toHaveAttribute(
      'data-column',
      'service',
    );
    expect(document.activeElement).toBe(
      within(menu).getByRole('menuitem', { name: 'Filter by this value' }),
    );
    await user.keyboard('{Enter}');
    expect(onCellFilter).toHaveBeenCalledTimes(1);
    expect(onCellFilter).toHaveBeenCalledWith({ field: 'service', op: 'eq', value: 'GET /a' });
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    expect(document.activeElement).toBe(firstRow());

    // The focus cell follows the row focus: Down keeps the column on the next row.
    await user.keyboard('{ArrowRight}{ArrowDown}');
    const secondRow = screen.getAllByTestId('data-table-row')[1] as HTMLElement;
    expect(document.activeElement).toBe(secondRow);
    expect(focusCellColumn(secondRow)).toEqual(['instance']);
    expect(focusCellColumn(firstRow())).toEqual([]);
    // The contextmenu event the browser fires on the focused row for these keys opens the same menu.
    fireEvent.contextMenu(secondRow);
    expect(
      screen.getByTestId('data-table-cell-menu').closest('[data-testid="data-table-cell"]'),
    ).toHaveAttribute('data-column', 'instance');
  });

  it('T-UI-004 keyboard include/exclude: Esc closes the cell menu and gives the focus back to the row', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderFilterable(onCellFilter);
    act(() => {
      firstRow().focus();
    });
    await user.keyboard('{Shift>}{F10}{/Shift}');
    expect(screen.getByTestId('data-table-cell-menu')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    expect(document.activeElement).toBe(firstRow());
    // Esc closed only the menu: the row keeps its focus and selection.
    expect(firstRow()).toHaveAttribute('aria-selected', 'true');
    expect(onCellFilter).not.toHaveBeenCalled();
    // Without a value-filterable cell the keys do nothing.
    const { unmount } = render(
      <DataTable tableId="t2" caption="S2" columns={COLUMNS} rows={ROWS} rowKey={rowKey} />,
      { wrapper },
    );
    const plainRow = within(screen.getByRole('table', { name: 'S2' })).getAllByTestId(
      'data-table-row',
    )[0] as HTMLElement;
    act(() => {
      plainRow.focus();
    });
    await user.keyboard('{Shift>}{F10}{/Shift}{ContextMenu}');
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    expect(plainRow).not.toHaveAttribute('aria-describedby');
    unmount();
  });
});

describe('DataTable virtualization (docs/15 §6.3, §18 #6)', () => {
  it('T-UI-004 renders every row up to 100 and a window of rows above', () => {
    const { unmount } = render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={makeRows(VIRTUALIZE_THRESHOLD)}
        rowKey={rowKey}
      />,
      { wrapper },
    );
    expect(screen.getAllByTestId('data-table-row')).toHaveLength(100);
    expect(screen.getByRole('table').parentElement).not.toHaveAttribute('data-virtual');
    unmount();

    render(
      <DataTable tableId="t" caption="S" columns={COLUMNS} rows={makeRows(1000)} rowKey={rowKey} />,
      { wrapper },
    );
    const scroll = screen.getByRole('table').parentElement as HTMLElement;
    expect(scroll).toHaveAttribute('data-virtual', 'true');
    // Viewport 560 px / 28 px rows (--row-h) = 20 visible rows (0..19) + 8 overscan rows below.
    expect(screen.getAllByTestId('data-table-row')).toHaveLength(28);
    expect(screen.getByTestId('data-table-count')).toHaveTextContent('1,000 rows');
    expect(screen.getByRole('table')).toHaveAttribute('aria-rowcount', '1001');
    const body = scroll.querySelector('tbody') as HTMLElement;
    expect(body.style.height).toBe('28000px');
    expect(scroll.style.height).toBe(`${String(560 + 34)}px`);
    // Scrolling the table's own viewport moves the window: rows 492..527 around row 500.
    act(() => {
      scroll.scrollTop = 500 * 28;
      scroll.dispatchEvent(new Event('scroll'));
    });
    const indices = screen
      .getAllByTestId('data-table-row')
      .map((row) => Number(row.getAttribute('data-row-index')));
    expect(indices[0]).toBe(492);
    expect(indices[indices.length - 1]).toBe(527);
    expect(indices).toHaveLength(36);
  });
});

describe('DataTable CSV (DSN-15-06)', () => {
  it('T-UI-004 only calls onExportCsv: the table never builds a CSV file', async () => {
    const user = userEvent.setup();
    const onExportCsv = vi.fn();
    const createObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true });
    const { rerender } = render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onExportCsv={onExportCsv}
      />,
      { wrapper },
    );
    await user.click(screen.getByTestId('csv-export'));
    expect(onExportCsv).toHaveBeenCalledTimes(1);
    expect(createObjectURL).not.toHaveBeenCalled();
    expect(document.querySelector('a[download]')).toBeNull();
    rerender(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={rowKey}
        onExportCsv={onExportCsv}
        csvBusy
      />,
    );
    expect(screen.getByTestId('csv-export')).toBeDisabled();
    rerender(<DataTable tableId="t" caption="S" columns={COLUMNS} rows={ROWS} rowKey={rowKey} />);
    expect(screen.queryByTestId('csv-export')).toBeNull();
  });
});

describe('DataTable cells and states', () => {
  it('T-UI-005 renders collected strings in cells and links as text only', () => {
    const rows: Row[] = XSS_PAYLOADS.map((payload, index) => ({
      id: String(index),
      service: payload,
      instance: payload,
      instanceId: payload,
      p95: index,
      count: 1,
      delta: null,
      url: payload,
    }));
    const { container } = render(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={rows}
        rowKey={rowKey}
        visibleColumns={['service', 'instance', 'link']}
        onCellFilter={vi.fn()}
      />,
      { wrapper },
    );
    for (const payload of XSS_PAYLOADS) {
      expect(screen.getAllByTitle(payload).length).toBeGreaterThanOrEqual(3);
    }
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-007 shows missing values as the dash and deltas with arrows', () => {
    render(<DataTable tableId="t" caption="S" columns={COLUMNS} rows={ROWS} rowKey={rowKey} />, {
      wrapper,
    });
    const cells = (index: number) =>
      within(screen.getAllByTestId('data-table-row')[index] as HTMLElement).getAllByTestId(
        'data-table-cell',
      );
    expect(cells(1)[2]).toHaveTextContent('—');
    expect(cells(0)[2]).toHaveTextContent('120ms▲+10%');
    expect(within(cells(0)[2] as HTMLElement).getByTestId('delta')).toHaveAttribute(
      'data-tone',
      'bad',
    );
    expect(within(cells(2)[2] as HTMLElement).getByTestId('delta')).toHaveAttribute(
      'data-tone',
      'good',
    );
  });

  it('shows the loading, error and empty states', () => {
    const { rerender } = render(
      <DataTable tableId="t" caption="S" columns={COLUMNS} rows={[]} rowKey={rowKey} loading />,
      { wrapper },
    );
    expect(screen.getByTestId('loading-state')).toBeInTheDocument();
    rerender(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={[]}
        rowKey={rowKey}
        error={{ status: 504, code: 'query_timeout', requestId: 'req-1' }}
      />,
    );
    expect(screen.getByTestId('error-state')).toHaveTextContent('Narrow the time range');
    expect(screen.getByTestId('error-state-request-id')).toHaveTextContent('req-1');
    rerender(
      <DataTable
        tableId="t"
        caption="S"
        columns={COLUMNS}
        rows={[]}
        rowKey={rowKey}
        empty={{ title: 'No services.' }}
      />,
    );
    expect(screen.getByTestId('empty-state')).toHaveTextContent('No services.');
  });
});
