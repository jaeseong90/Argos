// SPDX-License-Identifier: Apache-2.0
//
// Names of the XLog screen's f= keys for the shell's filter chips (docs/15 §6.5, routes/
// filterLabels.tsx; WO-33 review qa-ux-sec-5): the f= values are keys (docs/14 §6), so a chip
// would show a 20-digit service key or an instance id. The points' local dictionary names the
// services and instances; the server summary names the error classes (ADR-0010 C-202). A key that
// is not known shows as itself. Names are collected strings (Untrusted, SafeText only).
import { untrusted, type Untrusted } from '../../api/untrusted';
import type { FilterValueLabel } from '../../components/FilterChipBar';

import type { SummaryDictionary } from './summary/summaryModel';

/** Most error class names remembered from server summaries (bounded). */
export const ERROR_CLASS_NAMES_MAX = 1000;

/** Error class names by error_class_id (decimal string, C-175). */
export type ErrorClassNames = ReadonlyMap<string, Untrusted>;

/**
 * `known` with the names of `next` added (the newest wins); at most ERROR_CLASS_NAMES_MAX entries,
 * the oldest dropped. Returns `known` itself when nothing changes (no re-render).
 */
export function mergeErrorClassNames(
  known: ErrorClassNames,
  next: readonly { readonly id: string | null; readonly name: string | null }[],
): ErrorClassNames {
  let merged: Map<string, Untrusted> | null = null;
  for (const { id, name } of next) {
    if (id === null || name === null || known.get(id) === name) {
      continue;
    }
    merged ??= new Map(known);
    merged.delete(id);
    merged.set(id, untrusted(name));
  }
  if (merged === null) {
    return known;
  }
  for (const key of merged.keys()) {
    if (merged.size <= ERROR_CLASS_NAMES_MAX) {
      break;
    }
    merged.delete(key);
  }
  return merged;
}

/** The name at `index` of `names`, or null (missing name, index -1). */
function nameAt(names: readonly string[], index: number): Untrusted | null {
  const name = index < 0 ? undefined : names[index];
  return typeof name === 'string' ? untrusted(name) : null;
}

/**
 * The chip resolver of the XLog screen: service, instance and error class keys to names. It reads
 * the dictionary on each call (at most 20 chips), so names a realtime push adds in place are found.
 */
export function xlogFilterValueLabel(
  dictionary: SummaryDictionary,
  errorClasses: ErrorClassNames,
): FilterValueLabel {
  return (filter) => {
    switch (filter.field) {
      case 'service':
        // Keys are opaque decimal strings (C-175): compared as text, never as numbers.
        return nameAt(dictionary.services, dictionary.serviceIds.indexOf(filter.value));
      case 'instance':
        return nameAt(
          dictionary.instances,
          dictionary.instanceIds.findIndex((id) => String(id) === filter.value),
        );
      case 'error_class':
        return errorClasses.get(filter.value) ?? null;
      default:
        return null;
    }
  };
}
