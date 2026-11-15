// SPDX-License-Identifier: Apache-2.0
//
// Table sort (DataTable `sort` / `onSortChange`, docs/15 §6.1) ↔ URL `sort=field:asc|desc` (docs/14
// §2.3; the API's `sort` parameter takes the same string). Both directions use the grammar of the
// URL field (urlState.ts sortField), so a sort the table emits is exactly what the loader accepts.
// The table's SortSpec is matched structurally, so src/state does not import src/components.
import { sortField } from './urlState';

/** A table sort (structurally components/DataTable.tsx SortSpec). */
export interface TableSort {
  readonly id: string;
  readonly desc: boolean;
}

const SORT = sortField();

/**
 * The URL (and API) form of a table sort: `field:asc|desc`, or undefined for no sort or a column
 * id the grammar does not allow (`[a-z_][a-z0-9_.]{0,63}`).
 */
export function sortSpecToUrl(sort: TableSort | null | undefined): string | undefined {
  if (sort === null || sort === undefined) {
    return undefined;
  }
  const value = `${sort.id}:${sort.desc ? 'desc' : 'asc'}`;
  return SORT.schema.safeParse(value).success ? value : undefined;
}

/**
 * The table sort of a URL `sort` value, or null. Reads what the URL field reads: the docs/14
 * form `field:asc|desc` and the docs/15 form `-field` / `field`; anything else is null.
 */
export function urlToSortSpec(value: string | undefined): TableSort | null {
  if (value === undefined) {
    return null;
  }
  const parsed = SORT.parse(new URLSearchParams([['sort', value]]));
  if (parsed.issues.length > 0 || parsed.value === undefined) {
    return null;
  }
  const index = parsed.value.lastIndexOf(':');
  return { id: parsed.value.slice(0, index), desc: parsed.value.slice(index + 1) === 'desc' };
}
