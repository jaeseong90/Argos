// SPDX-License-Identifier: Apache-2.0
//
// Transient UI state (docs/15 §4.1 "일시 상태", DSN-15-01): notices, the "resume live" offer,
// the side menu and the shortcut help. Nothing here is shared through the URL, and nothing here
// is a token or user data (SEC-40).
import { create } from 'zustand';

import { readSidebarCollapsed, writeSidebarCollapsed } from './preferences';
import type { UrlIssue } from './urlState';

export interface UiState {
  /** Items of the last opened link that fell back to defaults (url-restore-notice), or null. */
  readonly restoreNotice: readonly UrlIssue[] | null;
  /** Path of the link the notice is about: the notice goes away on another screen. */
  readonly restorePath: string | null;
  /** The writer refused a URL longer than 2 KB (docs/14 §6). */
  readonly urlTooLong: boolean;
  /** A past selection turned live off; the picker offers "resume live" (docs/15 §5). */
  readonly resumeAvailable: boolean;
  readonly sidebarCollapsed: boolean;
  readonly shortcutHelpOpen: boolean;
  readonly showRestoreNotice: (issues: readonly UrlIssue[], pathname?: string) => void;
  readonly dismissRestoreNotice: () => void;
  readonly setUrlTooLong: (value: boolean) => void;
  readonly setResumeAvailable: (value: boolean) => void;
  readonly setSidebarCollapsed: (value: boolean) => void;
  readonly setShortcutHelpOpen: (value: boolean) => void;
}

export const useUiStore = create<UiState>()((set) => ({
  restoreNotice: null,
  restorePath: null,
  urlTooLong: false,
  resumeAvailable: false,
  sidebarCollapsed: readSidebarCollapsed(),
  shortcutHelpOpen: false,
  showRestoreNotice: (issues, pathname) => {
    if (issues.length > 0) {
      set({ restoreNotice: issues, restorePath: pathname ?? null });
    }
  },
  dismissRestoreNotice: () => {
    set({ restoreNotice: null, restorePath: null });
  },
  setUrlTooLong: (value) => {
    set({ urlTooLong: value });
  },
  setResumeAvailable: (value) => {
    set({ resumeAvailable: value });
  },
  setSidebarCollapsed: (value) => {
    writeSidebarCollapsed(value);
    set({ sidebarCollapsed: value });
  },
  setShortcutHelpOpen: (value) => {
    set({ shortcutHelpOpen: value });
  },
}));
