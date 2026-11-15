// SPDX-License-Identifier: Apache-2.0
//
// Navigation helpers of the shell (docs/15 §3, §5): the current screen and application from the
// matched routes, and links that carry the global state (time range, live, kind, environment,
// kiosk) while screen keys (sel, tab, color, filters) start fresh.
import { useCallback } from 'react';
import { useMatches, useNavigate } from 'react-router';

import { carriedQuery, type CommonState } from '../state/urlState';
import { useUrlState } from '../state/useUrlState';

import { screenById, screenPath, type ScreenDef } from './screens';

/** Route handle of the screen routes (routeTree.tsx). */
export interface ScreenHandle {
  readonly screen?: ScreenDef;
}

/** The deepest matched screen, or null (login, setup, not found). */
export function useCurrentScreen(): ScreenDef | null {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const handle = matches[i]?.handle as ScreenHandle | undefined;
    if (handle?.screen !== undefined) {
      return handle.screen;
    }
  }
  return null;
}

/** The :appId of the current route (app screens), or null. */
export function useAppId(): number | null {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const raw = matches[i]?.params.appId;
    if (raw !== undefined) {
      return /^[1-9]\d{0,9}$/.test(raw) ? Number(raw) : null;
    }
  }
  return null;
}

/** Detail screens fall back to their list when the application changes (ids belong to an app). */
const LIST_OF: Readonly<Record<string, string>> = {
  transaction: 'xlog',
  service: 'services',
  'sql-detail': 'sql',
  'external-detail': 'external',
  'error-detail': 'errors',
  instance: 'instances',
};

/** The screen to open in another application: the same one, or the list of a detail screen. */
export function screenForAppSwitch(screen: ScreenDef | null): ScreenDef {
  if (screen === null || screen.scope !== 'app') {
    return screenById('dashboard');
  }
  return screenById(LIST_OF[screen.id] ?? screen.id);
}

/** `path?carried-query` for a screen. */
export function hrefWithState(path: string, state: Partial<CommonState>): string {
  const query = carriedQuery(state);
  return query === '' ? path : `${path}?${query}`;
}

/** Navigation that carries the global keys (docs/15 §5 "앱·화면 전환"). */
export function useCarriedNavigation(): {
  readonly state: CommonState;
  readonly hrefFor: (screen: ScreenDef, appId?: number | null) => string;
  readonly goToScreen: (screen: ScreenDef, appId?: number | null) => void;
  readonly goToPath: (path: string) => void;
} {
  const { state } = useUrlState();
  const navigate = useNavigate();
  const common: CommonState = state;
  const hrefFor = useCallback(
    (screen: ScreenDef, appId?: number | null) => hrefWithState(screenPath(screen, appId), common),
    [common],
  );
  const goToScreen = useCallback(
    (screen: ScreenDef, appId?: number | null) => {
      void navigate(hrefFor(screen, appId));
    },
    [hrefFor, navigate],
  );
  const goToPath = useCallback(
    (path: string) => {
      void navigate(hrefWithState(path, common));
    },
    [common, navigate],
  );
  return { state: common, hrefFor, goToScreen, goToPath };
}
