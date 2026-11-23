// SPDX-License-Identifier: Apache-2.0
//
// Test harness of the rule screens: the rule list and editor under a memory data router with their
// URL schemas (route handles, as screens.ts declares them), the real query client, i18n and the
// generated client over the API stub (test/apiStub.ts; fixtures in fixtures.ts). Test-only module: nothing in the application imports it.
import { QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';

import { createQueryClient } from '../../../../api/queryClient';
import { installDomStubs } from '../../../../components/testing/dom';
import { createI18n, type SupportedLanguage } from '../../../../i18n';
import { AlertRuleQ, AlertRulesQ } from '../../../../state/urlState';
import { AlertRuleEditorPage } from '../AlertRuleEditorPage';
import { AlertRulesPage } from '../AlertRulesPage';

/**
 * The rule screens at `path` (pathname + search): the element to render and its router. The test
 * renders the element itself (this module stays free of test-library imports).
 */
export function rulesApp(
  path: string,
  options: { language?: SupportedLanguage; state?: unknown } = {},
) {
  installDomStubs();
  const router = createMemoryRouter(
    [
      { path: '/alerts/rules', element: <AlertRulesPage />, handle: { urlSchema: AlertRulesQ } },
      {
        path: '/alerts/rules/new',
        element: <AlertRuleEditorPage />,
        handle: { urlSchema: AlertRuleQ },
      },
      {
        path: '/alerts/rules/:ruleId',
        element: <AlertRuleEditorPage />,
        handle: { urlSchema: AlertRuleQ },
      },
      { path: '/alerts', element: <p data-testid="alerts-history-stub" /> },
    ],
    {
      initialEntries: [
        {
          pathname: path.split('?')[0] ?? path,
          search: path.includes('?') ? `?${path.split('?')[1] ?? ''}` : '',
          state: options.state,
        },
      ],
    },
  );
  const element = (
    <I18nextProvider i18n={createI18n(options.language ?? 'en')}>
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>
  );
  return {
    element,
    router,
    url: () => `${router.state.location.pathname}${router.state.location.search}`,
  };
}
