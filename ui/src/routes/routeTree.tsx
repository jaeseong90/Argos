// SPDX-License-Identifier: Apache-2.0
//
// The route tree of docs/15 §3.1 (ADR-0010 C-166: app-scoped routes are canonical, /tx/:txId is
// a deep-link alias). Every screen route validates its URL in a loader (docs/15 §4.2, SEC-64)
// and loads its element lazily (docs/15 §14). Screens of later work orders render a placeholder
// (route-pending). /xlog-poc stays exactly as WO-04 left it: outside the shell, the Ant Design root
// and the authentication.
import { redirect, type RouteObject } from 'react-router';

import { RouteLoading } from '../app/RouteStatus';
import { useUiStore } from '../state/uiStore';
import { CommonQ, parseUrl, TxDetailQ, writeQuery } from '../state/urlState';
import { urlLoader, type RouteHandle } from '../state/useUrlState';

import type { ScreenHandle } from './navigation';
import { SCREENS, type ScreenDef } from './screens';

/** `/` and `/apps/:appId` redirect, keeping the global keys of the query (validated). */
function redirectWithQuery(request: Request, pathname: string): Response {
  const url = new URL(request.url);
  const parsed = parseUrl(CommonQ, url.search);
  if (parsed.issues.length > 0) {
    useUiStore.getState().showRestoreNotice(parsed.issues, pathname);
  }
  const query = writeQuery(CommonQ, parsed.state);
  return redirect(query === '' ? pathname : `${pathname}?${query}`);
}

function screenRoute(screen: ScreenDef): RouteObject {
  const handle: RouteHandle & ScreenHandle = {
    urlSchema: screen.schema,
    scope: screen.scope,
    screen,
  };
  return {
    id: screen.id,
    path: screen.path,
    handle,
    loader: urlLoader(screen.schema),
    lazy: async () => {
      const [{ ScreenRoute }, Body] = await Promise.all([
        import('./ScreenRoute'),
        screen.load === undefined ? Promise.resolve(null) : screen.load(),
      ]);
      return { element: <ScreenRoute body={Body} /> };
    },
  };
}

export interface RouteTreeOptions {
  /** Build version shown on the login and setup pages. */
  readonly version: string;
}

/** The full route tree (data router). */
export function createRouteTree({ version }: RouteTreeOptions): RouteObject[] {
  return [
    {
      HydrateFallback: RouteLoading,
      children: [
        {
          index: true,
          loader: ({ request }) => redirectWithQuery(request, '/overview'),
        },
        {
          // WO-04 PoC: unchanged (no shell, no Ant Design, no session).
          path: 'xlog-poc',
          lazy: async () => {
            const { XLogPocPage } = await import('../features/xlog-poc/XLogPocPage');
            return { Component: XLogPocPage };
          },
        },
        {
          lazy: async () => {
            const { AntdRoot } = await import('../app/AntdRoot');
            return { Component: AntdRoot };
          },
          children: [
            {
              path: 'login',
              lazy: async () => {
                const { LoginPage } = await import('../features/auth/LoginPage');
                return { element: <LoginPage version={version} /> };
              },
            },
            {
              path: 'setup',
              lazy: async () => {
                const { SetupPage } = await import('../features/auth/SetupPage');
                return { element: <SetupPage version={version} /> };
              },
            },
            {
              lazy: async () => {
                const { AuthGate } = await import('./AuthGate');
                return { Component: AuthGate };
              },
              children: [
                {
                  lazy: async () => {
                    const { Shell } = await import('./Shell');
                    return { Component: Shell };
                  },
                  children: [
                    {
                      lazy: async () => {
                        const { GlobalLayout } = await import('./Shell');
                        return { Component: GlobalLayout };
                      },
                      children: [
                        ...SCREENS.filter((s) => s.scope === 'global').map(screenRoute),
                        {
                          id: 'tx-alias',
                          path: 'tx/:txId',
                          handle: { urlSchema: TxDetailQ, scope: 'global' } satisfies RouteHandle,
                          loader: urlLoader(TxDetailQ),
                          lazy: async () => {
                            const { TransactionAlias } = await import('./TransactionAlias');
                            return { Component: TransactionAlias };
                          },
                        },
                      ],
                    },
                    {
                      path: 'apps/:appId',
                      lazy: async () => {
                        const { AppLayout } = await import('./Shell');
                        return { Component: AppLayout };
                      },
                      children: [
                        {
                          index: true,
                          loader: ({ request }) => {
                            const url = new URL(request.url);
                            return redirectWithQuery(
                              request,
                              `${url.pathname.replace(/\/$/, '')}/dashboard`,
                            );
                          },
                        },
                        ...SCREENS.filter((s) => s.scope === 'app').map(screenRoute),
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
        {
          path: '*',
          lazy: async () => {
            const { NotFound } = await import('../app/RouteStatus');
            return { Component: NotFound };
          },
        },
      ],
    },
  ];
}
