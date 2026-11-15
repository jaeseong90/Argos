// SPDX-License-Identifier: Apache-2.0
//
// The per-request CSP nonce (docs/16 §9.9): the server's IndexHtmlTemplate fills
// <meta name="csp-nonce" content="..."> in index.html for every request (WO-24). A static preview
// (vite preview, E2E of the PoC) leaves it empty: then there is no nonce.

const NONCE_PATTERN = /^[A-Za-z0-9+/_=-]{8,128}$/;

/** The nonce of the page, or undefined when there is none (or it is malformed). */
export function readCspNonce(doc: Document): string | undefined {
  const content = doc.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') ?? '';
  return NONCE_PATTERN.test(content) ? content : undefined;
}
