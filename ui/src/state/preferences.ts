// SPDX-License-Identifier: Apache-2.0
//
// Per-browser UI preferences (docs/15 §4.1 "사용자 선호"): localStorage under argos.ui.* keys
// only, every access in try/catch (private windows, blocked storage). Preferences are never
// shared and never hold tokens or user data (SEC-40, argos/no-token-storage). The keys are
// literal on purpose: the lint rule only accepts literal argos.ui.* keys.

/** Last live state the user chose (docs/15 §5 "now" restores it). */
export function readLivePreference(): boolean {
  try {
    return localStorage.getItem('argos.ui.live') === '1';
  } catch {
    return false;
  }
}

export function writeLivePreference(live: boolean): void {
  try {
    localStorage.setItem('argos.ui.live', live ? '1' : '0');
  } catch {
    // Storage unavailable: the preference is simply not remembered.
  }
}

/** Collapsed side menu (docs/15 §3.3). */
export function readSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem('argos.ui.sidebar') === 'collapsed';
  } catch {
    return false;
  }
}

export function writeSidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem('argos.ui.sidebar', collapsed ? 'collapsed' : 'expanded');
  } catch {
    // Storage unavailable: the menu state is not remembered.
  }
}

/** UI language chosen in the account menu (docs/15 §12): 'en', 'ko' or null if never chosen. */
export function readLanguagePreference(): string | null {
  try {
    return localStorage.getItem('argos.ui.lang');
  } catch {
    return null;
  }
}

export function writeLanguagePreference(language: string): void {
  try {
    localStorage.setItem('argos.ui.lang', language);
  } catch {
    // Storage unavailable: the browser language decides next time.
  }
}

/**
 * When this browser last refreshed the session (epoch ms; not a token). A hint for tabs that get
 * the cross-tab refresh lock: newer than their failed request means another tab may have
 * refreshed, so they retry the request before refreshing (api/transport.ts). It never skips a
 * refresh on its own: localStorage reaches other tabs asynchronously, and the server has no reuse
 * grace (16 §7.2, WO-24 D-9).
 */
export function readLastRefresh(): number {
  try {
    const value = Number(localStorage.getItem('argos.ui.refreshAt'));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

export function writeLastRefresh(epochMs: number): void {
  try {
    localStorage.setItem('argos.ui.refreshAt', String(epochMs));
  } catch {
    // Storage unavailable: tabs fall back to the in-tab single flight.
  }
}

/** Most recent applications first (ids only, at most 5; docs/15 §6.5 "최근 사용 상단"). */
export const RECENT_APPS_MAX = 5;

export function readRecentApps(): number[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem('argos.ui.recentApps') ?? '[]');
    return Array.isArray(raw)
      ? raw
          .filter((id): id is number => Number.isInteger(id) && id > 0 && id < 2_147_483_648)
          .slice(0, RECENT_APPS_MAX)
      : [];
  } catch {
    return [];
  }
}

export function rememberRecentApp(appId: number): number[] {
  const next = [appId, ...readRecentApps().filter((id) => id !== appId)].slice(0, RECENT_APPS_MAX);
  try {
    localStorage.setItem('argos.ui.recentApps', JSON.stringify(next));
  } catch {
    // Storage unavailable: the order is not remembered.
  }
  return next;
}
