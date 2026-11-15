// SPDX-License-Identifier: Apache-2.0
import type { SupportedLanguage } from './resources';

const KOREAN = /^ko(?:-|$)/i;

/**
 * Picks the initial UI language: the language chosen in the account menu if one was stored
 * ('argos.ui.lang'), otherwise the browser's first preferred language (navigator.languages):
 * Korean when it is `ko` or `ko-*`, English otherwise (O-A05, docs/15 §12).
 */
export function resolveInitialLanguage(
  preferred: readonly string[],
  stored: string | null = null,
): SupportedLanguage {
  if (stored === 'en' || stored === 'ko') {
    return stored;
  }
  const first = preferred[0];
  return first !== undefined && KOREAN.test(first) ? 'ko' : 'en';
}

/** True for the UI languages. */
export function isSupportedLanguage(value: string | null | undefined): value is SupportedLanguage {
  return value === 'en' || value === 'ko';
}
