// SPDX-License-Identifier: Apache-2.0
//
// Minimal data router (docs/15 §2.1: React Router, createBrowserRouter). Only the start page and
// the XLog canvas PoC exist so far; the route tree, layouts and URL-schema loaders of docs/15 §3–§4
// come with the UI skeleton (WO-30). Screens are lazy routes (route-level code splitting).
import { createBrowserRouter, type RouteObject } from 'react-router';

import { App } from './App';
import { NotFound, RouteLoading } from './RouteStatus';

export function createRoutes(version: string): RouteObject[] {
  return [
    {
      HydrateFallback: RouteLoading,
      children: [
        { index: true, element: <App version={version} /> },
        {
          path: 'xlog-poc',
          lazy: async () => {
            const { XLogPocPage } = await import('../features/xlog-poc/XLogPocPage');
            return { Component: XLogPocPage };
          },
        },
        { path: '*', Component: NotFound },
      ],
    },
  ];
}

export function createAppRouter(version: string): ReturnType<typeof createBrowserRouter> {
  return createBrowserRouter(createRoutes(version));
}
