// SPDX-License-Identifier: Apache-2.0
import type { SupportedLanguage } from './resources';

const KOREAN = /^ko(?:-|$)/i;

/**
 * Picks the initial UI language from the browser's preferred languages (navigator.languages):
 * Korean when the first preference is `ko` or `ko-*`, English otherwise (O-A05, docs/15 §12).
 */
export function resolveInitialLanguage(preferred: readonly string[]): SupportedLanguage {
  const first = preferred[0];
  return first !== undefined && KOREAN.test(first) ? 'ko' : 'en';
}
