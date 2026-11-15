// SPDX-License-Identifier: Apache-2.0
//
// Document title per screen (WCAG 2.4.2 "page titled"): a single-page app keeps one document, so
// every route sets `<screen> · Argos APM` while it is shown and screen readers and the browser's
// tab and history lists tell the screens apart. Routes without a title fall back to the product
// name of index.html.
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Sets the document title to `<screen> · Argos APM` while the calling component is mounted, and
 * again when the language changes (the caller passes a translated label, so it changes with the
 * language). `screen` is a translated UI label only: never collected data such as application,
 * service or user names (they are untrusted, docs/16 §9.1, and the title shows up in history,
 * bookmarks and screen sharing).
 */
export function useDocumentTitle(screen: string): void {
  const { t } = useTranslation(['common', 'shell']);
  useEffect(() => {
    document.title = t('shell:documentTitle', { screen });
    return () => {
      document.title = t('app.name');
    };
  }, [t, screen]);
}
