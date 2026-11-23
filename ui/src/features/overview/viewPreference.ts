// SPDX-License-Identifier: Apache-2.0
//
// Card/table choice of the overview (docs/15 §9.1 "카드/표 전환은 localStorage"): a per-browser
// preference under argos.ui.* (docs/15 §4.1). Reads and writes never throw: storage may be
// disabled, full or missing (private windows, previews); the table is the default.

export type OverviewView = 'table' | 'cards';

/** The remembered view, or the table when nothing (valid) is stored or storage fails. */
export function readOverviewView(): OverviewView {
  try {
    return localStorage.getItem('argos.ui.overviewView') === 'cards' ? 'cards' : 'table';
  } catch {
    return 'table';
  }
}

/** Remembers the view; a storage failure only means it is not remembered. */
export function writeOverviewView(view: OverviewView): void {
  try {
    localStorage.setItem('argos.ui.overviewView', view);
  } catch {
    // Not remembered: the choice still applies to this page.
  }
}
