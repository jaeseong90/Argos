// SPDX-License-Identifier: Apache-2.0
//
// Root of the UI (docs/15 §2.2 app/): i18n, the TanStack Query cache and the data router. The Ant
// Design ConfigProvider (with the CSP nonce) sits inside the router (app/AntdRoot.tsx) so that the
// WO-04 PoC route stays without it.
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import type { i18n as I18n } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import type { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

export interface AppProps {
  readonly i18n: I18n;
  readonly queryClient: QueryClient;
  readonly router: ReturnType<typeof createBrowserRouter>;
}

export function App({ i18n, queryClient, router }: AppProps) {
  return (
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>
  );
}
