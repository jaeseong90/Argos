// SPDX-License-Identifier: Apache-2.0
//
// Component filters (FilterChipBar, DataTable column filters and cell include/exclude, 05 §2.0-4)
// as URL state (docs/15 §4.2 `f=`, docs/14 §6 DSN-14-17, ADR-0010 C-166). The components emit
// filters; screens convert them here and write them with useUrlState().set (the one serializer).
// The component type is matched structurally (components/filters.ts FilterSpec), so src/state does
// not import src/components.
import { untrusted } from '../api/untrusted';

import {
  FIELD_PATTERN,
  FILTER_MAX,
  fitsFilterValue,
  splitInList,
  type Filter,
  type FilterOp,
} from './urlState';

/** A filter as the shared components emit it (structurally components/filters.ts FilterSpec). */
export interface ComponentFilter {
  readonly field: string;
  /**
   * The URL operators plus `in` (docs/15 §4.2, value `a,b`), which the URL grammar writes as one
   * `eq` per value (toUrlFilters).
   */
  readonly op: FilterOp | 'in';
  readonly value: string;
}

/**
 * A component filter as one URL filter, or null if it cannot be one: a field name outside the
 * docs/15 §4.2 pattern or a value over 1,024 UTF-8 bytes (fitsFilterValue, the server's limit,
 * SEC-64). Single filters only: `in` is not part of the DSN-14-17 grammar, so an `in` list with one
 * value becomes `eq`, and an `in` list with several values or an empty item (splitInList) gives
 * null. It never writes the literal list `a,b` as one eq value; use toUrlFilters for `in`. The
 * value stays Untrusted (render it through SafeText).
 */
export function toUrlFilter(spec: ComponentFilter): Filter | null {
  if (!FIELD_PATTERN.test(spec.field) || !fitsFilterValue(spec.value)) {
    return null;
  }
  if (spec.op !== 'in') {
    return { field: spec.field, op: spec.op, value: untrusted(spec.value) };
  }
  const values = splitInList(spec.value);
  const only = values?.length === 1 ? values[0] : undefined;
  if (only === undefined) {
    return null;
  }
  return { field: spec.field, op: 'eq', value: untrusted(only) };
}

/**
 * A component filter as URL filters, or null if it cannot be URL state. `in` (`a,b`) follows the
 * URL reader's rule (splitInList, as `f=<field>:in:a,b` is read): one `eq` filter per value
 * (several eq filters on one field mean "any of them", DSN-14-17); null if the list has an empty
 * item, a value over 1,024 UTF-8 bytes (checked per value, SEC-64) or more than FILTER_MAX (20)
 * values, since such a list can never be URL state. Other operators give `[toUrlFilter(spec)]`
 * (null when that is null). The field must match the docs/15 §4.2 pattern.
 *
 * FILTER_MAX applies to the whole URL: the caller that adds the result to the current filters
 * checks that the total stays at or under FILTER_MAX (the URL reader keeps the first 20 and
 * reports the rest).
 */
export function toUrlFilters(spec: ComponentFilter): Filter[] | null {
  if (!FIELD_PATTERN.test(spec.field)) {
    return null;
  }
  if (spec.op !== 'in') {
    const filter = toUrlFilter(spec);
    return filter === null ? null : [filter];
  }
  const values = splitInList(spec.value);
  if (
    values === null ||
    values.length > FILTER_MAX ||
    !values.every((value) => fitsFilterValue(value))
  ) {
    return null;
  }
  return values.map((value): Filter => ({ field: spec.field, op: 'eq', value: untrusted(value) }));
}
