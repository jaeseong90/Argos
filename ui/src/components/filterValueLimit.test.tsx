// SPDX-License-Identifier: Apache-2.0
//
// Filter values are limited to 1,024 UTF-8 bytes, the server's limit (SEC-64, docs/14 §7.2,
// QueryLimits.MAX_TEXT_FILTER_BYTES): the chip editor, the column text filter and the cell value
// include/exclude refuse a longer value instead of writing a URL the server would reject with
// limit_exceeded. A value is never cut: a cut value would be a different eq/ne condition.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { DataTable, type DataTableColumn } from './DataTable';
import { FilterChipBar } from './FilterChipBar';
import { fitsFilterValueLimit } from './filters';
import { installDomStubs } from './testing/dom';
import { withI18n } from './testing/withI18n';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-64' };

installDomStubs();

const wrapper = withI18n('en');

/** 341 Hangul syllables = 1,023 bytes; 342 = 1,026 bytes. */
const FITS = '가'.repeat(341);
const TOO_LONG = '가'.repeat(342);
/** ASCII at the limit (1,024 bytes) and one byte over it. */
const ASCII_FITS = 'x'.repeat(1024);
const ASCII_TOO_LONG = 'x'.repeat(1025);
const TOO_LONG_HINT =
  'This value is longer than 1,024 bytes (UTF-8) and cannot be used as a filter.';

describe('filter value limit in UTF-8 bytes (SEC-64)', () => {
  it('counts bytes, not characters', () => {
    expect(fitsFilterValueLimit(FITS)).toBe(true);
    expect(fitsFilterValueLimit(TOO_LONG)).toBe(false);
    expect(fitsFilterValueLimit('😀'.repeat(256))).toBe(true);
    expect(fitsFilterValueLimit('😀'.repeat(257))).toBe(false);
    expect(fitsFilterValueLimit(ASCII_FITS)).toBe(true);
    expect(fitsFilterValueLimit(ASCII_TOO_LONG)).toBe(false);
  });

  it('T-UI-003 the chip editor refuses a value over 1,024 bytes and accepts one that fits', async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    render(
      <FilterChipBar
        filters={[{ field: 'user', op: 'eq', value: 'abc' }]}
        fieldLabel={() => 'User'}
        onRemove={vi.fn()}
        onClear={vi.fn()}
        onEdit={onEdit}
      />,
      { wrapper },
    );
    const chip = screen.getByTestId('filter-chip');
    await user.click(within(chip).getByRole('button', { name: /^User/ }));
    const input = screen.getByTestId('filter-chip-value');
    fireEvent.change(input, { target: { value: TOO_LONG } });
    expect(screen.getByTestId('filter-chip-save')).toBeDisabled();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('filter-chip-too-long')).toHaveTextContent(
      'Filter values can have at most 1,024 bytes',
    );
    fireEvent.submit(screen.getByTestId('filter-chip-editor'));
    expect(onEdit).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: FITS } });
    expect(screen.queryByTestId('filter-chip-too-long')).toBeNull();
    expect(input).not.toHaveAttribute('aria-invalid');
    await user.click(screen.getByTestId('filter-chip-save'));
    expect(onEdit).toHaveBeenCalledWith(0, { field: 'user', op: 'eq', value: FITS });
  });

  it('T-UI-003 the column text filter refuses a value over 1,024 bytes and accepts one that fits', async () => {
    interface Row {
      readonly id: string;
      readonly service: string;
    }
    const columns: readonly DataTableColumn<Row>[] = [
      {
        id: 'service',
        header: 'Service',
        value: (row) => row.service,
        filterable: true,
        filter: { type: 'text' },
      },
    ];
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    render(
      <DataTable
        tableId="limit"
        caption="Services"
        columns={columns}
        rows={[{ id: 'a', service: 'GET /a' }]}
        rowKey={(row) => row.id}
        filters={[]}
        onFiltersChange={onFiltersChange}
      />,
      { wrapper },
    );
    await user.click(screen.getByTestId('data-table-filter-button-service'));
    const input = screen.getByTestId('data-table-filter-text');
    fireEvent.change(input, { target: { value: '😀'.repeat(257) } });
    expect(screen.getByTestId('data-table-filter-apply')).toBeDisabled();
    expect(screen.getByTestId('data-table-filter-too-long')).toBeInTheDocument();
    fireEvent.submit(screen.getByTestId('data-table-filter'));
    expect(onFiltersChange).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: '😀'.repeat(256) } });
    expect(screen.queryByTestId('data-table-filter-too-long')).toBeNull();
    await user.click(screen.getByTestId('data-table-filter-apply'));
    expect(onFiltersChange).toHaveBeenLastCalledWith([
      { field: 'service', op: 'contains', value: '😀'.repeat(256) },
    ]);
  });
});

describe('cell value include/exclude and the filter limit (SEC-64, 05 §2.0-4)', () => {
  interface LongRow {
    readonly id: string;
    readonly text: string | null;
  }
  /** Include/exclude on the shown value. */
  const SHOWN_VALUE: readonly DataTableColumn<LongRow>[] = [
    { id: 'message', header: 'Message', value: (row) => row.text, filterable: true },
  ];
  /** Include/exclude on a short key (filterValue) while the shown value is long. */
  const SHORT_KEY: readonly DataTableColumn<LongRow>[] = [
    {
      id: 'message',
      header: 'Message',
      value: (row) => row.text,
      filterable: true,
      filterField: 'message_id',
      filterValue: (row) => row.id,
    },
  ];

  const renderTable = (
    columns: readonly DataTableColumn<LongRow>[],
    rows: readonly LongRow[],
    onCellFilter: (filter: unknown) => void,
  ) =>
    render(
      <DataTable
        tableId="long"
        caption="Messages"
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        onCellFilter={onCellFilter}
      />,
      { wrapper },
    );
  const cell = (index: number): HTMLElement =>
    screen.getAllByTestId('data-table-cell')[index] as HTMLElement;
  const row = (index: number): HTMLElement =>
    screen.getAllByTestId('data-table-row')[index] as HTMLElement;

  it('T-UI-003 passes a value of up to 1,024 bytes to onCellFilter as is', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderTable(
      SHOWN_VALUE,
      [
        { id: 'a', text: ASCII_FITS },
        { id: 'b', text: FITS },
      ],
      onCellFilter,
    );
    for (const index of [0, 1]) {
      for (const testId of ['cell-filter-include', 'cell-filter-exclude']) {
        const button = within(cell(index)).getByTestId(testId);
        expect(button).toBeEnabled();
        expect(button).not.toHaveAttribute('aria-disabled');
      }
    }
    await user.click(within(cell(0)).getByTestId('cell-filter-include'));
    expect(onCellFilter).toHaveBeenLastCalledWith({
      field: 'message',
      op: 'eq',
      value: ASCII_FITS,
    });
    await user.click(within(cell(1)).getByTestId('cell-filter-exclude'));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'message', op: 'ne', value: FITS });

    fireEvent.contextMenu(cell(1));
    const menu = screen.getByTestId('data-table-cell-menu');
    expect(screen.queryByTestId('data-table-cell-too-long')).toBeNull();
    await user.click(within(menu).getByRole('menuitem', { name: 'Filter by this value' }));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'message', op: 'eq', value: FITS });
    expect(onCellFilter).toHaveBeenCalledTimes(3);
  });

  it('T-UI-003 disables include/exclude for a value over 1,024 bytes and says why', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderTable(
      SHOWN_VALUE,
      [
        { id: 'a', text: ASCII_TOO_LONG },
        { id: 'b', text: TOO_LONG },
      ],
      onCellFilter,
    );
    for (const index of [0, 1]) {
      for (const testId of ['cell-filter-include', 'cell-filter-exclude']) {
        const button = within(cell(index)).getByTestId(testId);
        expect(button).toBeDisabled();
        expect(button).toHaveAttribute('aria-disabled', 'true');
        expect(button).toHaveAttribute('title', TOO_LONG_HINT);
        expect(button).toHaveAccessibleDescription(TOO_LONG_HINT);
        await user.click(button);
        fireEvent.click(button);
      }
      fireEvent.contextMenu(cell(index));
      const menu = screen.getByTestId('data-table-cell-menu');
      const items = within(menu).getAllByRole('menuitem');
      expect(items.map((item) => item.textContent)).toEqual([
        'Filter by this value',
        'Exclude this value',
      ]);
      for (const item of items) {
        expect(item).toBeDisabled();
        expect(item).toHaveAttribute('aria-disabled', 'true');
        expect(item).toHaveAccessibleDescription(TOO_LONG_HINT);
        await user.click(item);
        fireEvent.click(item);
      }
      expect(within(menu).getByTestId('data-table-cell-too-long')).toHaveTextContent(TOO_LONG_HINT);
      expect(within(menu).getByTestId('data-table-cell-too-long')).toBeVisible();
      await user.keyboard('{Escape}');
      expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    }
    expect(onCellFilter).not.toHaveBeenCalled();
  });

  it('T-UI-003 keyboard path: Shift+F10 on a value over the limit opens disabled items, Esc returns to the row', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderTable(SHOWN_VALUE, [{ id: 'b', text: TOO_LONG }], onCellFilter);
    act(() => {
      row(0).focus();
    });
    // The row announces the focus cell and why its value actions are unavailable.
    expect(row(0)).toHaveAccessibleDescription(
      `Value column: Message. Left and Right arrows change it, Shift+F10 opens the value actions. ${TOO_LONG_HINT}`,
    );
    for (const openKeys of ['{Shift>}{F10}{/Shift}', '{ContextMenu}']) {
      await user.keyboard(openKeys);
      const menu = screen.getByTestId('data-table-cell-menu');
      for (const item of within(menu).getAllByRole('menuitem')) {
        expect(item).toBeDisabled();
        expect(item).toHaveAttribute('aria-disabled', 'true');
      }
      expect(within(menu).getByTestId('data-table-cell-too-long')).toHaveTextContent(TOO_LONG_HINT);
      // No item can take the focus: the menu panel holds it, so the keys stay inside the menu.
      expect(document.activeElement).toBe(menu);
      await user.keyboard('{ArrowDown}{Enter}[Space]');
      expect(onCellFilter).not.toHaveBeenCalled();
      expect(screen.getByTestId('data-table-cell-menu')).toBe(menu);
      await user.keyboard('{Escape}');
      expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
      expect(document.activeElement).toBe(row(0));
      expect(row(0)).toHaveAttribute('aria-selected', 'true');
    }
    expect(onCellFilter).not.toHaveBeenCalled();
  });

  it('T-UI-003 a short filterValue key keeps include/exclude enabled for a long shown value', async () => {
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    renderTable(
      SHORT_KEY,
      [
        { id: 'k1', text: TOO_LONG },
        { id: 'k2', text: null },
      ],
      onCellFilter,
    );
    const include = within(cell(0)).getByTestId('cell-filter-include');
    expect(include).toBeEnabled();
    expect(include).toHaveAttribute('title', 'Filter by this value');
    expect(include).not.toHaveAccessibleDescription(TOO_LONG_HINT);
    await user.click(include);
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'message_id', op: 'eq', value: 'k1' });
    // The key decides, not the shown value: a row with a key but no shown value can be filtered.
    await user.click(within(cell(1)).getByTestId('cell-filter-exclude'));
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'message_id', op: 'ne', value: 'k2' });

    act(() => {
      row(0).focus();
    });
    expect(row(0)).not.toHaveAccessibleDescription(/cannot be used as a filter/);
    await user.keyboard('{Shift>}{F10}{/Shift}');
    const menu = screen.getByTestId('data-table-cell-menu');
    expect(screen.queryByTestId('data-table-cell-too-long')).toBeNull();
    expect(document.activeElement).toBe(
      within(menu).getByRole('menuitem', { name: 'Filter by this value' }),
    );
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onCellFilter).toHaveBeenLastCalledWith({ field: 'message_id', op: 'ne', value: 'k1' });
    expect(onCellFilter).toHaveBeenCalledTimes(3);
  });

  it('T-UI-003 a filterValue key that is null makes the cell not filterable; the shown value never stands in', async () => {
    interface KeyedRow {
      readonly id: string;
      readonly key: string | null;
      readonly text: string | null;
    }
    const columns: readonly DataTableColumn<KeyedRow>[] = [
      {
        id: 'message',
        header: 'Message',
        value: (keyed) => keyed.text,
        filterable: true,
        filterField: 'message_id',
        filterValue: (keyed) => keyed.key,
      },
    ];
    const user = userEvent.setup();
    const onCellFilter = vi.fn();
    render(
      <DataTable
        tableId="keyed"
        caption="Messages"
        columns={columns}
        rows={[
          { id: 'r1', key: null, text: 'GET /a' },
          { id: 'r2', key: 'k2', text: 'GET /b' },
        ]}
        rowKey={(keyed) => keyed.id}
        onCellFilter={onCellFilter}
      />,
      { wrapper },
    );
    // Row 0: the key is null, so there is no include/exclude although a value is shown.
    expect(within(cell(0)).getByText('GET /a')).toBeInTheDocument();
    expect(within(cell(0)).queryByTestId('cell-filter-include')).toBeNull();
    expect(within(cell(0)).queryByTestId('cell-filter-exclude')).toBeNull();
    expect(cell(0)).not.toHaveAttribute('aria-current');
    fireEvent.contextMenu(cell(0));
    expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
    act(() => {
      row(0).focus();
    });
    for (const openKeys of ['{Shift>}{F10}{/Shift}', '{ContextMenu}']) {
      await user.keyboard(openKeys);
      expect(screen.queryByTestId('data-table-cell-menu')).toBeNull();
      expect(document.activeElement).toBe(row(0));
    }
    expect(onCellFilter).not.toHaveBeenCalled();

    // Row 1: the key is used exactly, with the key's field.
    await user.click(within(cell(1)).getByTestId('cell-filter-include'));
    expect(onCellFilter).toHaveBeenCalledTimes(1);
    expect(onCellFilter).toHaveBeenCalledWith({ field: 'message_id', op: 'eq', value: 'k2' });
  });
});
