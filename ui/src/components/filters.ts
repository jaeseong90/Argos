// SPDX-License-Identifier: Apache-2.0
//
// Filter value shared by FilterChipBar, DataTable and the shell's URL state (docs/15 §4.2 `f=`,
// docs/14 §6 `f.<field>=value|!value`, C-166). Components only emit and render filters; the shell
// parses and serialises them (one serializer, src/state).
import { utf8ByteLength } from '../utils/utf8';

/** Operators of docs/15 §4.2; docs/14 §6 expresses eq and ne (`!value`). */
export const FILTER_OPS = ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'contains', 'in'] as const;
export type FilterOp = (typeof FILTER_OPS)[number];

/**
 * Free-text filter values are capped at 1 KB = 1,024 UTF-8 bytes, the server's limit (SEC-64,
 * docs/14 §6, §7.2; the same limit as FILTER_VALUE_MAX of src/state/urlState.ts). As an input
 * `maxLength` (UTF-16 code units) it is only an upper bound: check with fitsFilterValueLimit().
 */
export const FILTER_VALUE_MAX_LENGTH = 1024;

/**
 * True when a filter value fits the limit, counted in UTF-8 bytes like the server. A value that
 * does not fit is refused, never cut: a cut value would filter on a different condition.
 */
export function fitsFilterValueLimit(value: string): boolean {
  return utf8ByteLength(value) <= FILTER_VALUE_MAX_LENGTH;
}

export interface FilterSpec {
  /** Field name (`^[a-z_][a-z0-9_]{0,31}$`, docs/15 §4.2). */
  readonly field: string;
  readonly op: FilterOp;
  /** The value as typed or taken from a cell; may be a collected (untrusted) string. */
  readonly value: string;
}

/** Filter emitted by the value include/exclude actions of a table cell (05 §2.0-4). */
export interface CellFilter extends FilterSpec {
  readonly op: 'eq' | 'ne';
}

/** True when two filters are the same condition. */
export function sameFilter(a: FilterSpec, b: FilterSpec): boolean {
  return a.field === b.field && a.op === b.op && a.value === b.value;
}

/**
 * Adds a cell filter to a list: an existing identical filter is kept, and the opposite condition
 * on the same value (eq ↔ ne) is replaced, so "include" after "exclude" flips it.
 */
export function addCellFilter(filters: readonly FilterSpec[], filter: CellFilter): FilterSpec[] {
  if (filters.some((existing) => sameFilter(existing, filter))) {
    return [...filters];
  }
  const opposite: FilterOp = filter.op === 'eq' ? 'ne' : 'eq';
  return [
    ...filters.filter(
      (existing) =>
        !(
          existing.field === filter.field &&
          existing.op === opposite &&
          existing.value === filter.value
        ),
    ),
    filter,
  ];
}
