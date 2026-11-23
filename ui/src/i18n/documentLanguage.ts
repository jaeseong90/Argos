// SPDX-License-Identifier: Apache-2.0
import type { i18n } from 'i18next';

/** Keeps `<html lang>` in sync with the UI language (screen readers, hyphenation). */
export function bindDocumentLanguage(instance: i18n, doc: Document): () => void {
  const apply = (language: string): void => {
    doc.documentElement.lang = language;
  };
  apply(instance.resolvedLanguage ?? 'en');
  instance.on('languageChanged', apply);
  return () => {
    instance.off('languageChanged', apply);
  };
}
