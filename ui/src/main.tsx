// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { I18nextProvider } from 'react-i18next';
import { RouterProvider } from 'react-router/dom';

import { createAppRouter } from './app/router';
import { bindDocumentLanguage, createI18n, resolveInitialLanguage } from './i18n';
import './styles/global.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Missing #root element in index.html');
}

const i18n = createI18n(resolveInitialLanguage(navigator.languages));
bindDocumentLanguage(i18n, document);
const router = createAppRouter(__ARGOS_VERSION__);

createRoot(container).render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <RouterProvider router={router} />
    </I18nextProvider>
  </StrictMode>,
);
