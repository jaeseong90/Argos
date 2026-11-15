// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { createQueryClient } from './api/queryClient';
import { App } from './app/App';
import { logCspViolation, watchCspViolations } from './app/cspViolations';
import { createAppRouter } from './app/router';
import { installSessionHandlers } from './features/auth/session';
import { bindDocumentLanguage, createI18n, resolveInitialLanguage } from './i18n';
import { readLanguagePreference } from './state/preferences';
import './styles/global.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Missing #root element in index.html');
}

const i18n = createI18n(resolveInitialLanguage(navigator.languages, readLanguagePreference()));
bindDocumentLanguage(i18n, document);
// Session expiry from the API transport (failed refresh) and the WebSocket (4401, reauth).
installSessionHandlers();
// The browser reports violations to the server (report-uri); the page logs them too (SEC-46).
watchCspViolations(document, logCspViolation);
const queryClient = createQueryClient();
const router = createAppRouter(__ARGOS_VERSION__);

createRoot(container).render(
  <StrictMode>
    <App i18n={i18n} queryClient={queryClient} router={router} />
  </StrictMode>,
);
