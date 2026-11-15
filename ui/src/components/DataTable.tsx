// SPDX-License-Identifier: Apache-2.0
//
// Shared data table (docs/15 §6.3, DSN-15-05, F-U16, T-UI-004): TanStack Table for the row model
// (client sorting, column visibility) and TanStack Virtual above 100 rows. Server sorting is the
// default (onSortChange); the table sorts by itself only when told the rows are complete. Column
// filters, value include/exclude, the column chooser and CSV export only emit events: the shell
// keeps filters, sort and columns in the URL and calls the server export (DSN-15-06, the table
// never builds CSV). Every collected string renders through SafeText or SafeLink (SEC-60).
//
// Scrolling (docs/15 §13, §18 #6): up to 100 rows the table is part of the page and the document
// is the only vertical scroll container. Above 100 rows the body is virtualized inside its own
// bounded viewport (`maxHeight`, default 560 px) with a sticky header, so the page still has a
// single scrollbar and the table never grows past the viewport.
//
// Keyboard (docs/15 §6.3, §13, 03 §3.3): rows use a roving tabindex. Up/Down/Home/End move the row
// focus, Enter opens the row, / opens the first column filter, Esc clears the focus. The focused
// row has a focus cell among its value-filterable cells (first one by default): Left/Right move
// it, and Shift+F10 or the context menu key opens its include/exclude menu (05 §2.0-4), which
// gives the focus back to the row when an item is chosen or Esc closes it.
//
// Value include/exclude uses the column's filterValue (a short key such as an id) when the column
// defines one, and only that: a row whose key is null/undefined offers no include/exclude (the
// shown value is never sent under the key's filterField). Columns without filterValue use the
// shown value, as is. A value over the server's 1,024 UTF-8 byte limit (SEC-64) is refused, not
// cut: its buttons and menu items are disabled and say why, on every path (pointer and keyboard).
import { FilterOutlined, MinusCircleOutlined, PlusCircleOutlined } from '@ant-design/icons';
import {
  columnVisibilityFeature,
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  tableFeatures,
  useTable,
} from '@tanstack/react-table';
import {
  elementScroll,
  observeElementOffset,
  Virtualizer,
  type VirtualizerOptions,
} from '@tanstack/react-virtual';
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  type SubmitEvent,
} from 'react';
import { useTranslation } from 'react-i18next';

import { shapeTokens } from '../styles/tokens';
import type { ValueUnit } from '../utils/format';

import {
  clampColumnWidth,
  loadColumnWidths,
  saveColumnWidths,
  type ColumnWidths,
} from './columnWidths';
import { DeltaValue, type GoodDirection } from './DeltaValue';
import { EmptyState, type EmptyStateProps } from './EmptyState';
import { ErrorState, type ApiErrorInfo } from './ErrorState';
import {
  FILTER_VALUE_MAX_LENGTH,
  fitsFilterValueLimit,
  type CellFilter,
  type FilterSpec,
} from './filters';
import { LoadingState } from './LoadingState';
import { Popup } from './Popup';
import { SafeLink } from './SafeLink';
import { SafeText } from './SafeText';
import { StatusBadge } from './StatusBadge';
import { ValueText } from './ValueText';

import './dataTable.css';

/** Rows above which the body is virtualized (docs/15 §6.3). */
export const VIRTUALIZE_THRESHOLD = 100;
/** Default height of the virtualized viewport in px. */
export const DEFAULT_TABLE_MAX_HEIGHT = 560;
/** Row heights of docs/15 §13 (--row-h, --row-h-dense); dataTable.css uses the same tokens. */
const ROW_HEIGHT = { normal: shapeTokens['row-h'], dense: shapeTokens['row-h-dense'] } as const;
const OVERSCAN = 8;
/** Height of the header row in px (dataTable.css .data-table__row--head). */
const HEADER_HEIGHT = 34;
const RESIZE_STEP = 16;
const SORT_ASC_MARK = '▲';
const SORT_DESC_MARK = '▼';

/** Cell kinds: strings render with SafeText, numbers with their unit, links with SafeLink. */
export type CellKind = 'text' | 'link' | 'status' | ValueUnit;

export type ColumnFilterType =
  | { readonly type: 'text' }
  | { readonly type: 'range' }
  | {
      readonly type: 'select';
      readonly options: readonly { readonly value: string; readonly label: string }[];
    };

/** A column of the shared table. Screens keep their definitions in features/<screen>/columns.ts. */
export interface DataTableColumn<Row> {
  /** Column id: sort key, URL `cols=` entry and default filter field. */
  readonly id: string;
  /** Translated header text. */
  readonly header: string;
  /** Cell value; null/undefined render the missing marker (T-UI-007). */
  readonly value: (row: Row) => string | number | null | undefined;
  readonly kind?: CellKind;
  /** Link target of a 'link' column (rendered through SafeLink). */
  readonly href?: (row: Row) => string | null | undefined;
  /** false when the server could not measure the value (shows "Not measurable"). */
  readonly measured?: (row: Row) => boolean | null | undefined;
  /** Offers "Filter by this value" / "Exclude this value" on cells (05 §2.0-4). */
  readonly filterable?: boolean;
  /** Filter field name when it differs from the id (e.g. column `service`, field `service_id`). */
  readonly filterField?: string;
  /**
   * Value used for include/exclude when it differs from the shown value (ids for names). When it
   * is defined, only its value is used: a row for which it returns null/undefined offers no
   * include/exclude on this column (the shown value never stands in for the key). Give a short
   * key for long collected strings: include/exclude is disabled for a value over 1,024 UTF-8
   * bytes (SEC-64).
   */
  readonly filterValue?: (row: Row) => string | null | undefined;
  /** Column filter UI in the header (text, range or select). */
  readonly filter?: ColumnFilterType;
  readonly sortable?: boolean;
  readonly hideable?: boolean;
  readonly defaultHidden?: boolean;
  /** Initial width in px (user widths in localStorage override it). */
  readonly width?: number;
  /** Change column next to the value (05 §2.5): relative ratio and its good direction. */
  readonly delta?: {
    readonly value: (row: Row) => number | null | undefined;
    readonly goodWhen: GoodDirection;
  };
  readonly maxLines?: number;
  readonly mono?: boolean;
  /**
   * Custom cell content for cases the kinds do not cover. Collected strings inside it must still
   * go through SafeText/SafeLink (the lint enforces this in the caller).
   */
  readonly render?: (row: Row) => ReactNode;
}

export interface SortSpec {
  readonly id: string;
  readonly desc: boolean;
}

export interface DataTableProps<Row> {
  /** Stable table id for stored column widths (`argos.ui.cols`). */
  readonly tableId: string;
  /** Accessible name of the table. */
  readonly caption: string;
  readonly columns: readonly DataTableColumn<Row>[];
  readonly rows: readonly Row[];
  readonly rowKey: (row: Row) => string;
  /** Current sort (controlled). Uncontrolled when undefined. */
  readonly sort?: SortSpec | null;
  /** Sort change request: the caller re-queries the server (default) or just stores it. */
  readonly onSortChange?: (sort: SortSpec | null) => void;
  /** The rows are the complete result: the table sorts them itself (no server request). */
  readonly dataComplete?: boolean;
  /** Applied filters, to prefill the column filter forms and mark filtered columns. */
  readonly filters?: readonly FilterSpec[];
  /** New filter list after a column filter was applied or cleared. */
  readonly onFiltersChange?: (filters: FilterSpec[]) => void;
  /** Value include (eq) / exclude (ne) from a cell. */
  readonly onCellFilter?: (filter: CellFilter) => void;
  /** Visible column ids (controlled, URL `cols=`); uncontrolled when undefined. */
  readonly visibleColumns?: readonly string[];
  readonly onVisibleColumnsChange?: (ids: string[]) => void;
  /** Enter on a focused row (and click when `openOnClick`): open the detail. */
  readonly onRowOpen?: (row: Row) => void;
  readonly openOnClick?: boolean;
  /** Shows the CSV button; the caller runs the server export (T-UI-004, DSN-15-06). */
  readonly onExportCsv?: () => void;
  readonly csvBusy?: boolean;
  readonly loading?: boolean;
  readonly error?: ApiErrorInfo | null;
  readonly onRetry?: () => void;
  /** Props of the empty state shown for zero rows. */
  readonly empty?: EmptyStateProps;
  /** Height of the virtualized viewport in px (above 100 rows). Default 560. */
  readonly maxHeight?: number;
  readonly density?: 'normal' | 'dense';
  /** Extra toolbar content (left side). */
  readonly toolbar?: ReactNode;
}

const features = tableFeatures({
  rowSortingFeature,
  sortedRowModel: createSortedRowModel(),
  columnVisibilityFeature,
});

const NUMERIC_KINDS: ReadonlySet<CellKind> = new Set<CellKind>([
  'count',
  'ms',
  'bytes',
  'percent',
  'ratio',
  'rate',
  'seconds',
  'cores',
  'none',
]);

function isNumericKind(kind: CellKind | undefined): kind is ValueUnit {
  return kind !== undefined && NUMERIC_KINDS.has(kind);
}

/** Ascending comparison of cell values: numbers numerically, strings by locale. */
export function compareCellValues(a: unknown, b: unknown): number {
  if (typeof a === 'number' && typeof b === 'number') {
    return a < b ? -1 : a > b ? 1 : 0;
  }
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });
}

/**
 * Raw value of a cell include/exclude, as a string, used as is (never cut). A column with
 * filterValue uses only that value: null/undefined from it means the cell cannot be filtered,
 * never a fallback to the shown value (which would pair the key's filterField with a display
 * string). A column without filterValue uses the shown value; null when the cell has no value.
 */
function cellFilterRaw<Row>(column: DataTableColumn<Row>, row: Row): string | null {
  const raw = column.filterValue !== undefined ? column.filterValue(row) : column.value(row);
  return raw === null || raw === undefined ? null : String(raw);
}

function defaultWidth<Row>(column: DataTableColumn<Row>): number {
  return column.width ?? (isNumericKind(column.kind) ? 112 : 220);
}

/** Next sort after clicking a header: numbers start descending, text ascending, then toggle. */
export function nextSort(
  current: SortSpec | null | undefined,
  id: string,
  numeric: boolean,
): SortSpec {
  if (current?.id === id) {
    return { id, desc: !current.desc };
  }
  return { id, desc: numeric };
}

/** Filters of one field replaced by the result of a column filter form. */
export function replaceFieldFilters(
  filters: readonly FilterSpec[],
  field: string,
  ops: readonly FilterSpec['op'][],
  next: readonly FilterSpec[],
): FilterSpec[] {
  return [
    ...filters.filter((filter) => !(filter.field === field && ops.includes(filter.op))),
    ...next,
  ];
}

interface BodyVirtualizerOptions {
  readonly count: number;
  readonly getScrollElement: () => HTMLDivElement | null;
  readonly rowHeight: number;
  readonly overscan: number;
  readonly enabled: boolean;
  readonly getItemKey: (index: number) => string;
  /**
   * Height of the scroll viewport, which this component sets itself (no measuring needed). The
   * viewport element is keyed by it, so a new height arrives with a new scroll element.
   */
  readonly viewportHeight: number;
}

/**
 * TanStack Virtual (DSN-15-05) for the table body: the virtual-core Virtualizer driven the way
 * @tanstack/react-virtual's useVirtualizer drives it, specialised for fixed-height rows in a
 * viewport of known height. Only the width is read from the element, so the row window is the same
 * in a browser and in jsdom (no layout there). Using the core class directly also keeps the
 * React Compiler lint quiet (it flags useVirtualizer as an incompatible library).
 */
function useBodyVirtualizer(options: BodyVirtualizerOptions): Virtualizer<HTMLDivElement, Element> {
  const [, rerender] = useReducer((tick: number) => tick + 1, 0);
  const resolved: VirtualizerOptions<HTMLDivElement, Element> = {
    count: options.count,
    getScrollElement: options.getScrollElement,
    estimateSize: () => options.rowHeight,
    overscan: options.overscan,
    enabled: options.enabled,
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

interface ColumnFilterFormProps {
  readonly field: string;
  readonly header: string;
  readonly filter: ColumnFilterType;
  readonly filters: readonly FilterSpec[];
  readonly onApply: (next: FilterSpec[]) => void;
  readonly onCancel: () => void;
}

const TEXT_OPS: readonly FilterSpec['op'][] = ['contains'];
const RANGE_OPS: readonly FilterSpec['op'][] = ['gte', 'lte'];
const SELECT_OPS: readonly FilterSpec['op'][] = ['eq'];

function ColumnFilterForm({
  field,
  header,
  filter,
  filters,
  onApply,
  onCancel,
}: ColumnFilterFormProps) {
  const { t } = useTranslation();
  const current = (op: FilterSpec['op']): string =>
    filters.find((candidate) => candidate.field === field && candidate.op === op)?.value ?? '';
  const [text, setText] = useState(() => current('contains'));
  const [min, setMin] = useState(() => current('gte'));
  const [max, setMax] = useState(() => current('lte'));
  const [selected, setSelected] = useState(() => current('eq'));
  const ops = filter.type === 'text' ? TEXT_OPS : filter.type === 'range' ? RANGE_OPS : SELECT_OPS;
  const rangeInvalid =
    filter.type === 'range' &&
    ((min !== '' && Number.isNaN(Number(min))) ||
      (max !== '' && Number.isNaN(Number(max))) ||
      (min !== '' && max !== '' && Number(min) > Number(max)));
  // The server counts 1,024 UTF-8 bytes (SEC-64): a longer text is refused, not cut.
  const textTooLong = filter.type === 'text' && !fitsFilterValueLimit(text);

  const submit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (rangeInvalid || textTooLong) {
      return;
    }
    const next: FilterSpec[] = [];
    if (filter.type === 'text' && text.trim() !== '') {
      next.push({ field, op: 'contains', value: text });
    } else if (filter.type === 'range') {
      if (min.trim() !== '') {
        next.push({ field, op: 'gte', value: String(Number(min)) });
      }
      if (max.trim() !== '') {
        next.push({ field, op: 'lte', value: String(Number(max)) });
      }
    } else if (filter.type === 'select' && selected !== '') {
      next.push({ field, op: 'eq', value: selected });
    }
    onApply(replaceFieldFilters(filters, field, ops, next));
  };

  return (
    <form
      className="popup__form"
      onSubmit={submit}
      data-testid="data-table-filter"
      data-field={field}
    >
      <p className="popup__heading">{header}</p>
      {filter.type === 'text' ? (
        <label className="popup__field">
          <span>{t('component.dataTable.filter.contains')}</span>
          <input
            type="text"
            value={text}
            maxLength={FILTER_VALUE_MAX_LENGTH}
            aria-invalid={textTooLong || undefined}
            onChange={(event) => {
              setText(event.target.value);
            }}
            data-testid="data-table-filter-text"
          />
        </label>
      ) : null}
      {textTooLong ? (
        <p className="popup__error" role="alert" data-testid="data-table-filter-too-long">
          {t('component.dataTable.filter.tooLong')}
        </p>
      ) : null}
      {filter.type === 'range' ? (
        <>
          <label className="popup__field">
            <span>{t('component.dataTable.filter.min')}</span>
            <input
              type="number"
              value={min}
              onChange={(event) => {
                setMin(event.target.value);
              }}
              data-testid="data-table-filter-min"
            />
          </label>
          <label className="popup__field">
            <span>{t('component.dataTable.filter.max')}</span>
            <input
              type="number"
              value={max}
              onChange={(event) => {
                setMax(event.target.value);
              }}
              data-testid="data-table-filter-max"
            />
          </label>
          {rangeInvalid ? (
            <p className="popup__error" role="alert">
              {t('component.dataTable.filter.rangeInvalid')}
            </p>
          ) : null}
        </>
      ) : null}
      {filter.type === 'select' ? (
        <label className="popup__field">
          <span>{t('component.dataTable.filter.value')}</span>
          <select
            value={selected}
            onChange={(event) => {
              setSelected(event.target.value);
            }}
            data-testid="data-table-filter-select"
          >
            <option value="">{t('component.dataTable.filter.any')}</option>
            {filter.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      <div className="popup__actions">
        <button
          type="button"
          className="control-button"
          onClick={() => {
            onApply(replaceFieldFilters(filters, field, ops, []));
          }}
          data-testid="data-table-filter-clear"
        >
          {t('component.dataTable.filter.clear')}
        </button>
        <button type="button" className="control-button" onClick={onCancel}>
          {t('component.common.cancel')}
        </button>
        <button
          type="submit"
          className="control-button control-button--primary"
          disabled={rangeInvalid || textTooLong}
          data-testid="data-table-filter-apply"
        >
          {t('component.common.apply')}
        </button>
      </div>
    </form>
  );
}

interface ColumnChooserProps<Row> {
  readonly columns: readonly DataTableColumn<Row>[];
  readonly visible: readonly string[];
  readonly onChange: (ids: string[]) => void;
}

function ColumnChooser<Row>({ columns, visible, onChange }: ColumnChooserProps<Row>) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <span className="data-table__anchor">
      <button
        ref={triggerRef}
        type="button"
        className="control-button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => {
          setOpen((current) => !current);
        }}
        data-testid="column-chooser"
      >
        {t('component.dataTable.columns')}
      </button>
      <Popup
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        label={t('component.dataTable.columns')}
        triggerRef={triggerRef}
        align="end"
      >
        <ul className="popup__menu" data-testid="column-chooser-list">
          {columns.map((column) => {
            const checked = visible.includes(column.id);
            const locked = column.hideable === false;
            return (
              <li key={column.id}>
                <label className="popup__menu-item">
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={locked}
                    onChange={() => {
                      const next = checked
                        ? visible.filter((id) => id !== column.id)
                        : columns
                            .map((candidate) => candidate.id)
                            .filter((id) => id === column.id || visible.includes(id));
                      onChange(next);
                    }}
                    data-testid={`column-chooser-${column.id}`}
                  />
                  {column.header}
                </label>
              </li>
            );
          })}
        </ul>
      </Popup>
    </span>
  );
}

interface HeaderFilterProps {
  readonly field: string;
  readonly header: string;
  readonly filter: ColumnFilterType;
  readonly filters: readonly FilterSpec[];
  readonly open: boolean;
  readonly align: 'start' | 'end';
  readonly onOpenChange: (open: boolean) => void;
  readonly onFiltersChange: (filters: FilterSpec[]) => void;
}

function HeaderFilter({
  field,
  header,
  filter,
  filters,
  open,
  align,
  onOpenChange,
  onFiltersChange,
}: HeaderFilterProps) {
  const { t } = useTranslation();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const filtered = filters.some((candidate) => candidate.field === field);
  const label = t('component.dataTable.filterColumn', { column: header });
  return (
    <span className="data-table__anchor">
      <button
        ref={triggerRef}
        type="button"
        className={`data-table__filter-button${filtered ? ' data-table__filter-button--active' : ''}`}
        aria-label={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-pressed={filtered}
        onClick={() => {
          onOpenChange(!open);
        }}
        data-testid={`data-table-filter-button-${field}`}
      >
        <FilterOutlined aria-hidden="true" />
      </button>
      <Popup
        open={open}
        onClose={() => {
          onOpenChange(false);
        }}
        label={label}
        triggerRef={triggerRef}
        align={align}
      >
        <ColumnFilterForm
          field={field}
          header={header}
          filter={filter}
          filters={filters}
          onCancel={() => {
            onOpenChange(false);
            triggerRef.current?.focus();
          }}
          onApply={(next) => {
            onOpenChange(false);
            onFiltersChange(next);
          }}
        />
      </Popup>
    </span>
  );
}

interface CellMenuState {
  readonly rowKey: string;
  readonly columnId: string;
}

/** Scrolls the table viewport horizontally so that the cell of `columnId` in `row` is visible. */
function revealCell(
  scroller: HTMLElement | null,
  row: HTMLTableRowElement,
  columnId: string,
): void {
  const cell = Array.from(row.cells).find((td) => td.getAttribute('data-column') === columnId);
  if (scroller === null || cell === undefined) {
    return;
  }
  const view = scroller.getBoundingClientRect();
  const box = cell.getBoundingClientRect();
  if (box.left < view.left) {
    scroller.scrollLeft -= view.left - box.left;
  } else if (box.right > view.right) {
    scroller.scrollLeft += box.right - view.right;
  }
}

/** The shared data table. */
export function DataTable<Row extends object>({
  tableId,
  caption,
  columns,
  rows,
  rowKey,
  sort,
  onSortChange,
  dataComplete = false,
  filters = [],
  onFiltersChange,
  onCellFilter,
  visibleColumns,
  onVisibleColumnsChange,
  onRowOpen,
  openOnClick = true,
  onExportCsv,
  csvBusy = false,
  loading = false,
  error,
  onRetry,
  empty,
  maxHeight = DEFAULT_TABLE_MAX_HEIGHT,
  density = 'normal',
  toolbar,
}: DataTableProps<Row>) {
  const { t } = useTranslation();
  const scrollRef = useRef<HTMLDivElement>(null);
  const tableRef = useRef<HTMLTableElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  // Sort: controlled by `sort` when given, otherwise local.
  const [localSort, setLocalSort] = useState<SortSpec | null>(null);
  const activeSort = sort === undefined ? localSort : sort;
  const applySort = (next: SortSpec | null): void => {
    if (sort === undefined) {
      setLocalSort(next);
    }
    onSortChange?.(next);
  };

  // Visible columns: controlled by `visibleColumns` when given, otherwise local.
  const defaultVisible = useMemo(
    () => columns.filter((column) => column.defaultHidden !== true).map((column) => column.id),
    [columns],
  );
  const [localVisible, setLocalVisible] = useState<readonly string[]>(defaultVisible);
  const visible = useMemo(() => {
    const requested = visibleColumns ?? localVisible;
    // Columns that cannot be hidden are always shown.
    return columns
      .filter((column) => requested.includes(column.id) || column.hideable === false)
      .map((column) => column.id);
  }, [columns, visibleColumns, localVisible]);
  const changeVisible = (ids: string[]): void => {
    if (visibleColumns === undefined) {
      setLocalVisible(ids);
    }
    onVisibleColumnsChange?.(ids);
  };

  // Widths: user preference in localStorage (try/catch inside).
  const [widths, setWidths] = useState<ColumnWidths>(() => loadColumnWidths(tableId));
  const widthOf = (column: DataTableColumn<Row>): number =>
    widths[column.id] ?? defaultWidth(column);
  const setWidth = (id: string, width: number, persist: boolean): void => {
    setWidths((current) => {
      const next = { ...current, [id]: clampColumnWidth(width) };
      if (persist) {
        saveColumnWidths(tableId, next);
      }
      return next;
    });
  };

  const [filterOpen, setFilterOpen] = useState<string | null>(null);
  const [cellMenu, setCellMenu] = useState<CellMenuState | null>(null);
  const [focusedIndex, setFocusedIndex] = useState<number | null>(null);
  // Focus cell of the focused row (column id); null or a column the row cannot filter on means
  // the row's first value-filterable cell.
  const [focusColumn, setFocusColumn] = useState<string | null>(null);
  const focusHintId = useId();
  const tooLongHintId = useId();
  const tooLongText = t('component.dataTable.valueTooLongForFilter');

  // TanStack Table: row model, client sorting and visibility.
  const columnDefs = useMemo(() => {
    const helper = createColumnHelper<typeof features, Row>();
    return helper.columns(
      columns.map((column) =>
        helper.accessor((row: Row): unknown => column.value(row) ?? undefined, {
          id: column.id,
          enableSorting: column.sortable !== false,
          enableHiding: column.hideable !== false,
          sortUndefined: 'last',
          sortFn: (a, b, id) => compareCellValues(a.getValue(id), b.getValue(id)),
        }),
      ),
    );
  }, [columns]);
  const data = useMemo(() => [...rows], [rows]);
  const sorting = useMemo(
    () => (activeSort === null ? [] : [{ id: activeSort.id, desc: activeSort.desc }]),
    [activeSort],
  );
  const columnVisibility = useMemo(
    () => Object.fromEntries(columns.map((column) => [column.id, visible.includes(column.id)])),
    [columns, visible],
  );
  const table = useTable({
    features,
    columns: columnDefs,
    data,
    getRowId: (row: Row) => rowKey(row),
    state: { sorting, columnVisibility },
    onSortingChange: () => undefined,
    onColumnVisibilityChange: () => undefined,
    manualSorting: !dataComplete,
    enableSortingRemoval: false,
  });
  const modelRows = table.getRowModel().rows;
  const shownColumns = columns.filter((column) => visible.includes(column.id));

  // Value include/exclude (05 §2.0-4): offered on filterable columns for present values, and
  // enabled only for a value within the filter limit (SEC-64; refused, never cut).
  const canFilterCell = (column: DataTableColumn<Row>, row: Row): boolean =>
    column.filterable === true && onCellFilter !== undefined && cellFilterRaw(column, row) !== null;
  const cellFilterTooLong = (column: DataTableColumn<Row>, row: Row): boolean => {
    const raw = cellFilterRaw(column, row);
    return raw !== null && !fitsFilterValueLimit(raw);
  };
  const filterableColumnsOf = (row: Row): DataTableColumn<Row>[] =>
    shownColumns.filter((column) => canFilterCell(column, row));
  const focusColumnOf = (row: Row): DataTableColumn<Row> | undefined => {
    const candidates = filterableColumnsOf(row);
    return candidates.find((column) => column.id === focusColumn) ?? candidates[0];
  };
  const focusedRow = focusedIndex === null ? undefined : modelRows[focusedIndex]?.original;
  const focusTarget = focusedRow === undefined ? undefined : focusColumnOf(focusedRow);
  const focusTargetTooLong =
    focusedRow !== undefined &&
    focusTarget !== undefined &&
    cellFilterTooLong(focusTarget, focusedRow);

  // Virtualization above 100 rows, in a bounded viewport of known height.
  const virtual = modelRows.length > VIRTUALIZE_THRESHOLD;
  const rowHeight = ROW_HEIGHT[density];
  const viewportHeight = Math.min(maxHeight, Math.max(rowHeight, modelRows.length * rowHeight));
  const virtualizer = useBodyVirtualizer({
    count: modelRows.length,
    getScrollElement: () => scrollRef.current,
    rowHeight,
    overscan: OVERSCAN,
    enabled: virtual,
    getItemKey: (index) => modelRows[index]?.id ?? String(index),
    viewportHeight,
  });
  const items = virtual
    ? virtualizer.getVirtualItems().map((item) => ({ index: item.index, start: item.start }))
    : modelRows.map((_, index) => ({ index, start: index * rowHeight }));

  // Keyboard focus follows focusedIndex (roving tabindex on rows).
  useEffect(() => {
    if (focusedIndex === null) {
      return;
    }
    if (virtual) {
      virtualizer.scrollToIndex(focusedIndex, { align: 'auto' });
    }
    const find = (): HTMLElement | null | undefined =>
      tableRef.current?.querySelector<HTMLElement>(`[data-row-index="${String(focusedIndex)}"]`);
    const element = find();
    if (element !== null && element !== undefined) {
      element.focus();
      return;
    }
    // A virtualized row appears after the scroll; focus it on the next frame.
    const frame = requestAnimationFrame(() => {
      find()?.focus();
    });
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [focusedIndex, virtual, virtualizer]);

  const firstFilterable = shownColumns.find((column) => column.filter !== undefined);
  // Grid keys (docs/15 §6.3, §13): Up/Down/Home/End move the row focus, / opens the first column
  // filter, Esc clears the row focus and closes menus. Enter is handled on the row itself.
  const onGridKeyDown = (event: KeyboardEvent): void => {
    const target = event.target as HTMLElement | null;
    if (
      target === null ||
      tableRef.current?.contains(target) !== true ||
      target.closest('.popup') !== null ||
      target.tagName === 'INPUT'
    ) {
      return;
    }
    const last = modelRows.length - 1;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (last >= 0) {
          setFocusedIndex((index) => (index === null ? 0 : Math.min(last, index + 1)));
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (last >= 0) {
          setFocusedIndex((index) => (index === null ? 0 : Math.max(0, index - 1)));
        }
        break;
      case 'Home':
        if (last >= 0) {
          event.preventDefault();
          setFocusedIndex(0);
        }
        break;
      case 'End':
        if (last >= 0) {
          event.preventDefault();
          setFocusedIndex(last);
        }
        break;
      case '/':
        if (firstFilterable !== undefined) {
          event.preventDefault();
          setFilterOpen(firstFilterable.id);
        }
        break;
      case 'Escape':
        setCellMenu(null);
        setFilterOpen(null);
        if (focusedIndex !== null) {
          event.preventDefault();
          setFocusedIndex(null);
          (document.activeElement as HTMLElement | null)?.blur();
        }
        break;
      default:
        break;
    }
  };

  const gridKeyHandler = useRef(onGridKeyDown);
  useEffect(() => {
    gridKeyHandler.current = onGridKeyDown;
  });
  useEffect(() => {
    const root = rootRef.current;
    if (root === null) {
      return undefined;
    }
    const listener = (event: KeyboardEvent): void => {
      gridKeyHandler.current(event);
    };
    root.addEventListener('keydown', listener);
    return () => {
      root.removeEventListener('keydown', listener);
    };
  }, []);

  const startResize = (
    event: ReactPointerEvent<HTMLButtonElement>,
    column: DataTableColumn<Row>,
  ): void => {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const startWidth = widthOf(column);
    let latest = startWidth;
    const move = (moveEvent: PointerEvent): void => {
      latest = startWidth + moveEvent.clientX - startX;
      setWidth(column.id, latest, false);
    };
    const up = (): void => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      setWidth(column.id, latest, true);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  const cellFilter = useCallback(
    (column: DataTableColumn<Row>, row: Row, op: 'eq' | 'ne'): void => {
      const raw = cellFilterRaw(column, row);
      // Over the limit the value is refused (the actions are disabled), never cut (SEC-64).
      if (raw === null || !fitsFilterValueLimit(raw) || onCellFilter === undefined) {
        return;
      }
      onCellFilter({ field: column.filterField ?? column.id, op, value: raw });
      setCellMenu(null);
    },
    [onCellFilter],
  );

  const renderValue = (column: DataTableColumn<Row>, row: Row): ReactNode => {
    if (column.render !== undefined) {
      return column.render(row);
    }
    const value = column.value(row);
    const measured = column.measured?.(row);
    if (column.kind === 'link') {
      const text = value === null || value === undefined ? null : String(value);
      return (
        <SafeLink href={column.href?.(row)}>
          <SafeText value={text} maxLines={column.maxLines ?? 1} mono={column.mono} />
        </SafeLink>
      );
    }
    if (column.kind === 'status') {
      return <StatusBadge status={value === null || value === undefined ? null : String(value)} />;
    }
    if (isNumericKind(column.kind)) {
      return (
        <ValueText
          value={typeof value === 'number' ? value : null}
          unit={column.kind}
          measured={measured}
        />
      );
    }
    return (
      <SafeText
        value={value === null || value === undefined ? null : String(value)}
        maxLines={column.maxLines ?? 1}
        mono={column.mono}
      />
    );
  };

  const gridTemplate = shownColumns
    .map((column, index) =>
      index === 0 ? `minmax(${String(widthOf(column))}px, 1fr)` : `${String(widthOf(column))}px`,
    )
    .join(' ');
  const tableStyle = { '--dt-cols': gridTemplate } as CSSProperties;

  const toolbarNode = (
    <div className="data-table__toolbar">
      <div className="data-table__toolbar-start">{toolbar}</div>
      <span className="data-table__count" data-testid="data-table-count">
        {t('component.dataTable.rowCount', { count: rows.length })}
      </span>
      <ColumnChooser columns={columns} visible={visible} onChange={changeVisible} />
      {onExportCsv === undefined ? null : (
        <button
          type="button"
          className="control-button"
          onClick={onExportCsv}
          disabled={csvBusy}
          aria-busy={csvBusy || undefined}
          data-testid="csv-export"
        >
          {csvBusy ? t('component.dataTable.csvBusy') : t('component.dataTable.csv')}
        </button>
      )}
    </div>
  );

  let body: ReactNode;
  if (error !== undefined && error !== null) {
    body = <ErrorState error={error} onRetry={onRetry} compact />;
  } else if (loading && rows.length === 0) {
    body = <LoadingState variant="table" />;
  } else if (rows.length === 0) {
    body = <EmptyState compact {...empty} />;
  } else {
    body = (
      <div
        key={virtual ? viewportHeight : 'flow'}
        ref={scrollRef}
        className={`data-table__scroll${virtual ? ' data-table__scroll--virtual' : ''}`}
        style={virtual ? { height: viewportHeight + HEADER_HEIGHT } : undefined}
        data-virtual={virtual ? 'true' : undefined}
      >
        <table
          ref={tableRef}
          className={`data-table__grid data-table__grid--${density}`}
          aria-label={caption}
          aria-rowcount={modelRows.length + 1}
          aria-busy={loading || undefined}
          style={tableStyle}
        >
          <thead className="data-table__head">
            <tr className="data-table__row data-table__row--head" aria-rowindex={1}>
              {shownColumns.map((column) => {
                const sorted = activeSort?.id === column.id ? activeSort : null;
                const numeric = isNumericKind(column.kind);
                return (
                  <th
                    key={column.id}
                    className={`data-table__th${numeric ? ' data-table__th--num' : ''}`}
                    aria-sort={
                      sorted === null ? undefined : sorted.desc ? 'descending' : 'ascending'
                    }
                    scope="col"
                    data-column={column.id}
                  >
                    {column.sortable === false ? (
                      <span className="data-table__header-text">{column.header}</span>
                    ) : (
                      <button
                        type="button"
                        className="data-table__sort"
                        onClick={() => {
                          applySort(nextSort(activeSort, column.id, numeric));
                        }}
                        data-testid={`data-table-sort-${column.id}`}
                      >
                        <span className="data-table__header-text">{column.header}</span>
                        <span className="data-table__sort-indicator" aria-hidden="true">
                          {sorted === null ? null : sorted.desc ? SORT_DESC_MARK : SORT_ASC_MARK}
                        </span>
                      </button>
                    )}
                    {column.filter === undefined || onFiltersChange === undefined ? null : (
                      <HeaderFilter
                        field={column.filterField ?? column.id}
                        header={column.header}
                        filter={column.filter}
                        filters={filters}
                        open={filterOpen === column.id}
                        align={numeric ? 'end' : 'start'}
                        onOpenChange={(open) => {
                          setFilterOpen(open ? column.id : null);
                        }}
                        onFiltersChange={onFiltersChange}
                      />
                    )}
                    <button
                      type="button"
                      className="data-table__resize"
                      aria-label={t('component.dataTable.resize', { column: column.header })}
                      onPointerDown={(event) => {
                        startResize(event, column);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                          event.preventDefault();
                          event.stopPropagation();
                          setWidth(
                            column.id,
                            widthOf(column) +
                              (event.key === 'ArrowRight' ? RESIZE_STEP : -RESIZE_STEP),
                            true,
                          );
                        }
                      }}
                      data-testid={`data-table-resize-${column.id}`}
                    />
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody
            className="data-table__body"
            style={virtual ? { height: virtualizer.getTotalSize() } : undefined}
          >
            {items.map(({ index, start }) => {
              const modelRow = modelRows[index];
              if (modelRow === undefined) {
                return null;
              }
              const row = modelRow.original;
              const key = modelRow.id;
              const focused = focusedIndex === index;
              const focusCellId = focused ? focusTarget?.id : undefined;
              // Opens the value menu of the focus cell (Shift+F10, context menu key).
              const openFocusCellMenu = (): boolean => {
                const target = focusColumnOf(row);
                if (target === undefined) {
                  return false;
                }
                setFocusColumn(target.id);
                setCellMenu({ rowKey: key, columnId: target.id });
                return true;
              };
              return (
                <tr
                  key={key}
                  className={`data-table__row${focused ? ' data-table__row--focused' : ''}`}
                  aria-rowindex={index + 2}
                  aria-selected={focused}
                  aria-describedby={
                    focusCellId === undefined
                      ? undefined
                      : focusTargetTooLong
                        ? `${focusHintId} ${tooLongHintId}`
                        : focusHintId
                  }
                  tabIndex={focused || (focusedIndex === null && index === 0) ? 0 : -1}
                  data-row-index={index}
                  data-row-key={key}
                  style={virtual ? { transform: `translateY(${String(start)}px)` } : undefined}
                  onClick={(event) => {
                    if ((event.target as HTMLElement).closest('button, a, .popup') !== null) {
                      return;
                    }
                    setFocusedIndex(index);
                    if (openOnClick && onRowOpen !== undefined) {
                      onRowOpen(row);
                    }
                  }}
                  onKeyDown={(event) => {
                    if (event.target !== event.currentTarget) {
                      return;
                    }
                    if (event.key === 'Enter') {
                      if (onRowOpen !== undefined) {
                        event.preventDefault();
                        onRowOpen(row);
                      }
                      return;
                    }
                    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                      const candidates = filterableColumnsOf(row);
                      const current = focusColumnOf(row);
                      if (current === undefined) {
                        return;
                      }
                      event.preventDefault();
                      const at = candidates.indexOf(current);
                      const next =
                        candidates[
                          event.key === 'ArrowRight'
                            ? Math.min(candidates.length - 1, at + 1)
                            : Math.max(0, at - 1)
                        ] ?? current;
                      setFocusColumn(next.id);
                      revealCell(scrollRef.current, event.currentTarget, next.id);
                      return;
                    }
                    if (
                      (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) &&
                      openFocusCellMenu()
                    ) {
                      event.preventDefault();
                    }
                  }}
                  onContextMenu={(event) => {
                    // The context menu key and Shift+F10 also fire contextmenu on the focused
                    // row; a pointer right-click lands on a cell (handled there).
                    if (event.target === event.currentTarget && openFocusCellMenu()) {
                      event.preventDefault();
                    }
                  }}
                  onFocus={(event) => {
                    if (event.target === event.currentTarget) {
                      setFocusedIndex(index);
                    }
                  }}
                  data-testid="data-table-row"
                >
                  {shownColumns.map((column) => {
                    const canFilter = canFilterCell(column, row);
                    // Over the limit: shown but disabled, with the reason (SEC-64).
                    const tooLong = canFilter && cellFilterTooLong(column, row);
                    const tooLongDescription = tooLong ? tooLongHintId : undefined;
                    const menuOpen = cellMenu?.rowKey === key && cellMenu.columnId === column.id;
                    const focusCell = focusCellId === column.id;
                    return (
                      <td
                        key={column.id}
                        className={`data-table__cell${isNumericKind(column.kind) ? ' data-table__cell--num' : ''}${menuOpen ? ' data-table__cell--menu' : ''}${focusCell ? ' data-table__cell--focus' : ''}`}
                        aria-current={focusCell ? 'true' : undefined}
                        onContextMenu={
                          canFilter
                            ? (event) => {
                                event.preventDefault();
                                setFocusColumn(column.id);
                                setCellMenu({ rowKey: key, columnId: column.id });
                              }
                            : undefined
                        }
                        data-column={column.id}
                        data-testid="data-table-cell"
                      >
                        <span className="data-table__value">{renderValue(column, row)}</span>
                        {column.delta === undefined ? null : (
                          <DeltaValue
                            value={column.delta.value(row)}
                            goodWhen={column.delta.goodWhen}
                          />
                        )}
                        {canFilter ? (
                          <span className="data-table__cell-actions">
                            <button
                              type="button"
                              className="data-table__cell-action"
                              aria-label={t('component.dataTable.include')}
                              title={tooLong ? tooLongText : t('component.dataTable.include')}
                              disabled={tooLong}
                              aria-disabled={tooLong || undefined}
                              aria-describedby={tooLongDescription}
                              tabIndex={-1}
                              onClick={() => {
                                cellFilter(column, row, 'eq');
                              }}
                              data-testid="cell-filter-include"
                            >
                              <PlusCircleOutlined aria-hidden="true" />
                            </button>
                            <button
                              type="button"
                              className="data-table__cell-action"
                              aria-label={t('component.dataTable.exclude')}
                              title={tooLong ? tooLongText : t('component.dataTable.exclude')}
                              disabled={tooLong}
                              aria-disabled={tooLong || undefined}
                              aria-describedby={tooLongDescription}
                              tabIndex={-1}
                              onClick={() => {
                                cellFilter(column, row, 'ne');
                              }}
                              data-testid="cell-filter-exclude"
                            >
                              <MinusCircleOutlined aria-hidden="true" />
                            </button>
                          </span>
                        ) : null}
                        {menuOpen ? (
                          <Popup
                            open
                            role="menu"
                            onClose={() => {
                              setCellMenu(null);
                            }}
                            label={t('component.dataTable.cellMenu')}
                            className="data-table__cell-menu"
                            data-testid="data-table-cell-menu"
                          >
                            <button
                              type="button"
                              role="menuitem"
                              className="popup__menu-item"
                              title={tooLong ? tooLongText : undefined}
                              disabled={tooLong}
                              aria-disabled={tooLong || undefined}
                              aria-describedby={tooLongDescription}
                              onClick={(event) => {
                                const rowElement = event.currentTarget.closest('tr');
                                cellFilter(column, row, 'eq');
                                rowElement?.focus();
                              }}
                            >
                              <PlusCircleOutlined aria-hidden="true" />
                              {t('component.dataTable.include')}
                            </button>
                            <button
                              type="button"
                              role="menuitem"
                              className="popup__menu-item"
                              title={tooLong ? tooLongText : undefined}
                              disabled={tooLong}
                              aria-disabled={tooLong || undefined}
                              aria-describedby={tooLongDescription}
                              onClick={(event) => {
                                const rowElement = event.currentTarget.closest('tr');
                                cellFilter(column, row, 'ne');
                                rowElement?.focus();
                              }}
                            >
                              <MinusCircleOutlined aria-hidden="true" />
                              {t('component.dataTable.exclude')}
                            </button>
                            {tooLong ? (
                              <span
                                className="popup__note data-table__cell-menu-note"
                                data-testid="data-table-cell-too-long"
                              >
                                {tooLongText}
                              </span>
                            ) : null}
                          </Popup>
                        ) : null}
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <div ref={rootRef} className="data-table" data-testid="data-table" data-table-id={tableId}>
      {toolbarNode}
      {body}
      {focusTarget === undefined ? null : (
        <span id={focusHintId} className="visually-hidden">
          {t('component.dataTable.cellFocus', { column: focusTarget.header })}
        </span>
      )}
      {onCellFilter === undefined ? null : (
        // Description of disabled include/exclude actions (aria-describedby reads hidden text).
        <span id={tooLongHintId} hidden>
          {tooLongText}
        </span>
      )}
    </div>
  );
}
