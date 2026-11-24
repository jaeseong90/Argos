// SPDX-License-Identifier: Apache-2.0
//
// Test harness of the administration screens of WO-39 lane A: each screen under a memory data
// router with its URL schema (route handles, as screens.ts declares them), the real query client,
// i18n (the admin catalog registered eagerly by createI18n) and the generated client over the API
// stub (test/apiStub.ts). The signed-in role is set on the auth store. Test-only module: nothing in
// the application imports it; it stays free of test-library imports (the test renders `element`).
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';

import { createQueryClient } from '../../../api/queryClient';
import { installDomStubs } from '../../../components/testing/dom';
import { createI18n, type SupportedLanguage } from '../../../i18n';
import { useAuthStore, type Role } from '../../../state/authStore';
import {
  AdminAppsQ,
  AdminInstancesQ,
  AdminTokensQ,
  AdminUsersQ,
  CommonQ,
} from '../../../state/urlState';
import { AboutPage } from '../about/AboutPage';
import { LicensesPage } from '../about/LicensesPage';
import { AdminAppsPage } from '../apps/AdminAppsPage';
import { AdminInstancesPage } from '../instances/AdminInstancesPage';
import { AdminPendingPage } from '../pending/AdminPendingPage';
import { AdminTokensPage } from '../tokens/AdminTokensPage';
import { AdminUsersPage } from '../users/AdminUsersPage';

/** The id of the signed-in test user (the users screen disables deleting oneself). */
export const ME_ID = '7f000000-0000-4000-8000-000000000001';

export interface AdminAppOptions {
  readonly role?: Role;
  readonly language?: SupportedLanguage;
}

export function adminApp(path: string, options: AdminAppOptions = {}) {
  installDomStubs();
  useAuthStore.setState({
    status: 'authenticated',
    role: options.role ?? 'admin',
    permissions: [],
    user: { id: ME_ID, username: 'me-admin' },
  });
  const router = createMemoryRouter(
    [
      { path: '/admin/apps', element: <AdminAppsPage />, handle: { urlSchema: AdminAppsQ } },
      { path: '/admin/pending', element: <AdminPendingPage />, handle: { urlSchema: CommonQ } },
      {
        path: '/admin/instances',
        element: <AdminInstancesPage />,
        handle: { urlSchema: AdminInstancesQ },
      },
      { path: '/admin/tokens', element: <AdminTokensPage />, handle: { urlSchema: AdminTokensQ } },
      { path: '/admin/users', element: <AdminUsersPage />, handle: { urlSchema: AdminUsersQ } },
      { path: '/admin/about', element: <AboutPage />, handle: { urlSchema: CommonQ } },
      { path: '/admin/licenses', element: <LicensesPage />, handle: { urlSchema: CommonQ } },
      {
        path: '/apps/:appId/instances/:instanceId',
        element: <p data-testid="instance-detail-stub" />,
      },
    ],
    { initialEntries: [path] },
  );
  const queryClient: QueryClient = createQueryClient();
  const element = (
    <I18nextProvider i18n={createI18n(options.language ?? 'en')}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>
  );
  return {
    element,
    router,
    queryClient,
    url: () => `${router.state.location.pathname}${router.state.location.search}`,
  };
}
