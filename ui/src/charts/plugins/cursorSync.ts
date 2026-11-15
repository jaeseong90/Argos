// SPDX-License-Identifier: Apache-2.0
//
// Cursor synchronisation (docs/15 §6.1): charts of one screen pass the same sync key, and uPlot
// shares cursor position and hover through `uPlot.sync(key)`. Series visibility is not shared
// (each chart has its own legend state). Type-only import: uPlot reads matchMedia when imported.
import type uPlot from 'uplot';

/** Cursor options that join the sync group `key`, or undefined for no synchronisation. */
export function cursorSyncOptions(key: string | null | undefined): uPlot.Cursor.Sync | undefined {
  if (key === null || key === undefined || key === '') {
    return undefined;
  }
  return { key, setSeries: false };
}
