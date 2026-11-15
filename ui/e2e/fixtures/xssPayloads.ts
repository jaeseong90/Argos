// SPDX-License-Identifier: Apache-2.0
//
// The XSS seed set of the E2E suite: the union of docs/16 §9.4 (nine markup payloads; the CSV
// formula line is not markup) and docs/18 §4.3 (four payloads with the ARGOS_SEED sentinel), without
// duplicates (ADR-0004 C-45). Every payload that runs sets window.__xss, which the page hook of
// every test checks; a rendered payload must appear as exactly this text.

/** docs/16 §9.4 (markup payloads). */
export const XSS_16: readonly string[] = [
  '<img src=x onerror=window.__xss=1>',
  '"><svg/onload=window.__xss=1>',
  // eslint-disable-next-line no-script-url -- an XSS seed payload: data that must render as text
  'javascript:window.__xss=1',
  `'"><script>window.__xss=1</script>`,
  '<a href="javascript:window.__xss=1">x</a>',
  '{{7*7}} ${7*7} <%= 7*7 %>',
  '‮\u0000<b>bidi</b>',
  '<iframe srcdoc="<script>parent.__xss=1</script>">',
  '%3Cscript%3Ewindow.__xss%3D1%3C/script%3E',
];

/** docs/18 §4.3 (sample-apps/common Seeds.XSS). */
export const XSS_18: readonly string[] = [
  '<img src=x onerror="window.__xss=1">ARGOS_SEED',
  '"><svg/onload=window.__xss=1>ARGOS_SEED',
  // eslint-disable-next-line no-script-url -- an XSS seed payload: data that must render as text
  "javascript:window.__xss='ARGOS_SEED'",
  '<script>window.__xss=1</script>ARGOS_SEED',
];

/** The C-45 union. */
export const XSS_PAYLOADS: readonly string[] = [...new Set([...XSS_16, ...XSS_18])];
