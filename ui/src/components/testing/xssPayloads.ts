// SPDX-License-Identifier: Apache-2.0
//
// XSS seed payloads for component tests (T-UI-005, T-SEC-61). ADR-0004 C-45: the seed set is the
// union of docs/16 §9.4 (10 payloads, one of them for CSV) and docs/18 §4.3 (4 XSS payloads),
// without duplicates: two of the docs/18 payloads equal docs/16 ones, so the union has 12 distinct
// strings. Each would set window.__xss if it were ever parsed as HTML or followed as a link.
// Test-only module: nothing in the application imports it.

// Concatenated so this file does not trip no-script-url.
const JS = 'javascript';
// U+202E RIGHT-TO-LEFT OVERRIDE and U+0000, built from code points so the source has no bidi control.
const RLO_NUL = String.fromCharCode(0x202e, 0);

export interface XssPayload {
  readonly id: string;
  readonly source: '16 §9.4' | '18 §4.3';
  readonly value: string;
}

/** The 14 payloads as listed in the two documents. */
export const LISTED_XSS_PAYLOADS: readonly XssPayload[] = [
  { id: 'img-onerror', source: '16 §9.4', value: '<img src=x onerror=window.__xss=1>' },
  { id: 'svg-onload', source: '16 §9.4', value: '"><svg/onload=window.__xss=1>' },
  { id: 'js-url', source: '16 §9.4', value: `${JS}:window.__xss=1` },
  { id: 'script-breakout', source: '16 §9.4', value: `'"><script>window.__xss=1</script>` },
  { id: 'anchor-js', source: '16 §9.4', value: `<a href="${JS}:window.__xss=1">x</a>` },
  { id: 'template', source: '16 §9.4', value: '{{7*7}} ${7*7} <%= 7*7 %>' },
  { id: 'csv-formula', source: '16 §9.4', value: '=HYPERLINK("http://evil","x")' },
  { id: 'bidi-nul', source: '16 §9.4', value: `${RLO_NUL}<b>bidi</b>` },
  {
    id: 'iframe-srcdoc',
    source: '16 §9.4',
    value: '<iframe srcdoc="<script>parent.__xss=1</script>">',
  },
  { id: 'double-encoded', source: '16 §9.4', value: '%3Cscript%3Ewindow.__xss%3D1%3C/script%3E' },
  { id: 'img-onerror-quoted', source: '18 §4.3', value: '<img src=x onerror="window.__xss=1">' },
  { id: 'svg-onload-18', source: '18 §4.3', value: '"><svg/onload=window.__xss=1>' },
  { id: 'js-url-18', source: '18 §4.3', value: `${JS}:window.__xss=1` },
  { id: 'script', source: '18 §4.3', value: '<script>window.__xss=1</script>' },
];

/** The C-45 union: the listed payloads without duplicates (12 strings). */
export const XSS_PAYLOADS: readonly string[] = [
  ...new Set(LISTED_XSS_PAYLOADS.map((payload) => payload.value)),
];

const INJECTED_ELEMENTS =
  'script, iframe, img, b, object, embed, form, input[type="image"], svg[onload]';

/**
 * Markup that a payload could have produced inside `root`: elements the components never create
 * (script, iframe, img, b, ...), any `on*` event attribute, `srcdoc`, and links whose target is not
 * http(s) or an internal path. Returns a description of each finding; empty means inert.
 */
export function findInjectedMarkup(root: ParentNode): string[] {
  const findings: string[] = [];
  for (const element of root.querySelectorAll(INJECTED_ELEMENTS)) {
    findings.push(`element <${element.tagName.toLowerCase()}>`);
  }
  for (const element of root.querySelectorAll('*')) {
    for (const attribute of element.attributes) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith('on') || name === 'srcdoc') {
        findings.push(`attribute ${name} on <${element.tagName.toLowerCase()}>`);
      }
    }
  }
  for (const link of root.querySelectorAll('a[href], area[href]')) {
    const href = link.getAttribute('href') ?? '';
    if (!/^(https?:\/\/|\/(?!\/))/i.test(href)) {
      findings.push(`link href ${JSON.stringify(href)}`);
    }
  }
  return findings;
}

/** True when the payload test hook ran (it never may). */
export function xssFired(): boolean {
  return Reflect.get(window, '__xss') !== undefined;
}
