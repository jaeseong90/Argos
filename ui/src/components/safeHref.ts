// SPDX-License-Identifier: Apache-2.0
//
// Link target sanitiser (docs/16 §9.3, SEC-61, T-SEC-61). The ESLint rule argos/no-raw-href
// accepts only literal internal paths or the result of this function in `href`.

/** Longest link target that is kept (the deep-link URL cap is 2 KB, C-166; external URLs 8 KB). */
const MAX_HREF_LENGTH = 8192;
const INTERNAL_PATH = /^\/(?!\/)/;
const HTTP_URL = /^https?:\/\//i;

/**
 * Returns `value` when it is safe as a link target, otherwise undefined (the link is disabled):
 *
 *   - `http:` / `https:` URLs (returned normalised by the URL parser),
 *   - internal paths starting with exactly one `/` (`/apps/1/xlog?from=…`).
 *
 * Everything else is rejected: `javascript:`, `data:`, `vbscript:` and every other scheme,
 * protocol-relative `//host`, relative paths, values with control characters, whitespace or
 * backslashes, and values longer than 8 KB.
 */
export function safeHref(value: string | null | undefined): string | undefined {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_HREF_LENGTH) {
    return undefined;
  }
  if (hasForbiddenCharacter(value)) {
    return undefined;
  }
  if (INTERNAL_PATH.test(value)) {
    return value;
  }
  if (!HTTP_URL.test(value)) {
    return undefined;
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    return undefined;
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.hostname === '') {
    return undefined;
  }
  return parsed.href;
}

/**
 * ASCII control characters, space, DEL and backslash. Browsers strip tabs and newlines from URLs
 * and treat '\' like '/', so '/\tevil' or '/\evil' would turn into the protocol-relative '//evil'
 * (another origin).
 */
function hasForbiddenCharacter(value: string): boolean {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (code <= 0x20 || code === 0x7f || code === 0x5c) {
      return true;
    }
  }
  return false;
}

/** True when a safe link target leaves the UI (an http(s) URL rather than an internal path). */
export function isExternalHref(href: string): boolean {
  return HTTP_URL.test(href);
}
