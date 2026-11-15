// SPDX-License-Identifier: Apache-2.0
//
// Data router (docs/15 §2.1: React Router, createBrowserRouter). The route tree of docs/15 §3.1
// lives in routes/routeTree.tsx; screens are lazy routes (route-level code splitting) with
// URL-validating loaders (docs/15 §4.2).
import { createBrowserRouter, type RouteObject } from 'react-router';

import { createRouteTree } from '../routes/routeTree';

export function createRoutes(version: string): RouteObject[] {
  return createRouteTree({ version });
}

export function createAppRouter(version: string): ReturnType<typeof createBrowserRouter> {
  return createBrowserRouter(createRoutes(version));
}
