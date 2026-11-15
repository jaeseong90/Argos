// SPDX-License-Identifier: Apache-2.0
//
// Column widths of DataTable (docs/15 §4.1 "사용자 선호", §6.3): per-browser preference in
// localStorage, never shared and never needed for correctness. All tables share one key,
// `argos.ui.cols`, holding `{ <tableId>: { <columnId>: width } }`: the storage lint
// (argos/no-token-storage) only accepts literal `argos.ui.*` keys, which rules out one key per
// table (`argos.ui.cols.<table>`). Reads and writes never throw: storage may be disabled or full.

/** Narrowest and widest column a user can drag to, in px. */
export const MIN_COLUMN_WIDTH = 48;
export const MAX_COLUMN_WIDTH = 1200;
/** Bounds of the stored map, so a long-lived browser profile cannot grow it without limit. */
const MAX_TABLES = 50;
const MAX_COLUMNS = 64;
const ID_PATTERN = /^[A-Za-z0-9_.:-]{1,64}$/;

export type ColumnWidths = Readonly<Record<string, number>>;

function clampWidth(width: number): number {
  return Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, Math.round(width)));
}

function readAll(): Record<string, Record<string, number>> {
  let raw: string | null;
  try {
    raw = localStorage.getItem('argos.ui.cols');
  } catch {
    return {};
  }
  if (raw === null) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    const result: Record<string, Record<string, number>> = {};
    for (const [tableId, widths] of Object.entries(parsed).slice(0, MAX_TABLES)) {
      if (!ID_PATTERN.test(tableId) || typeof widths !== 'object' || widths === null) {
        continue;
      }
      const table: Record<string, number> = {};
      for (const [columnId, width] of Object.entries(widths as Record<string, unknown>).slice(
        0,
        MAX_COLUMNS,
      )) {
        if (ID_PATTERN.test(columnId) && typeof width === 'number' && Number.isFinite(width)) {
          table[columnId] = clampWidth(width);
        }
      }
      result[tableId] = table;
    }
    return result;
  } catch {
    return {};
  }
}

/** Stored widths of one table (empty when none or when storage is unavailable). */
export function loadColumnWidths(tableId: string): ColumnWidths {
  return readAll()[tableId] ?? {};
}

/**
 * Stores the widths of one table. The most recently saved table moves to the end; the oldest
 * tables are dropped beyond the bound. Returns false when storage refused the write.
 */
export function saveColumnWidths(tableId: string, widths: ColumnWidths): boolean {
  if (!ID_PATTERN.test(tableId)) {
    return false;
  }
  const all = Object.fromEntries(Object.entries(readAll()).filter(([id]) => id !== tableId));
  const table: Record<string, number> = {};
  for (const [columnId, width] of Object.entries(widths).slice(0, MAX_COLUMNS)) {
    if (ID_PATTERN.test(columnId) && Number.isFinite(width)) {
      table[columnId] = clampWidth(width);
    }
  }
  all[tableId] = table;
  const entries = Object.entries(all);
  const kept = Object.fromEntries(entries.slice(Math.max(0, entries.length - MAX_TABLES)));
  try {
    localStorage.setItem('argos.ui.cols', JSON.stringify(kept));
    return true;
  } catch {
    return false;
  }
}

export { clampWidth as clampColumnWidth };
