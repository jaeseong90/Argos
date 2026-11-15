// SPDX-License-Identifier: Apache-2.0
//
// CSP violations seen by the page (docs/15 §11 CSP row, DSN-15-09, SEC-46). The browser already
// sends each report to the server itself: the server's policy names `report-uri
// /api/v1/csp-report` and `report-to csp` (WO-24 CspPolicy), so the UI does not post them a second
// time. This listener counts them and writes one console line with the directive and the blocked
// origin (no page content), which makes a violation visible during development; the E2E global
// hooks assert that there are none.

/** The parts of a securitypolicyviolation event that are logged. */
export interface CspViolation {
  readonly directive: string;
  readonly blocked: string;
  readonly disposition: string;
}

interface ViolationEventLike {
  readonly effectiveDirective?: string;
  readonly violatedDirective?: string;
  readonly blockedURI?: string;
  readonly disposition?: string;
}

export interface EventSourceLike {
  addEventListener(type: 'securitypolicyviolation', listener: (event: Event) => void): void;
  removeEventListener(type: 'securitypolicyviolation', listener: (event: Event) => void): void;
}

/** Origin only (or the keyword such as `inline`/`eval`): never a full URL with its query. */
export function blockedOrigin(blockedUri: string): string {
  if (blockedUri === '' || !blockedUri.includes(':')) {
    return blockedUri.slice(0, 32);
  }
  try {
    return new URL(blockedUri).origin;
  } catch {
    return blockedUri.slice(0, blockedUri.indexOf(':')).slice(0, 32);
  }
}

let count = 0;

/** Violations counted since the page loaded. */
export function cspViolationCount(): number {
  return count;
}

/** Listens for violations on `target`; returns the function that stops listening. */
export function watchCspViolations(
  target: EventSourceLike,
  report: (violation: CspViolation) => void,
): () => void {
  const listener = (event: Event): void => {
    const e = event as Event & ViolationEventLike;
    count += 1;
    report({
      directive: e.effectiveDirective ?? e.violatedDirective ?? 'unknown',
      blocked: blockedOrigin(e.blockedURI ?? ''),
      disposition: e.disposition ?? 'enforce',
    });
  };
  target.addEventListener('securitypolicyviolation', listener);
  return () => {
    target.removeEventListener('securitypolicyviolation', listener);
  };
}

/** The production reporter: one console line per violation. */
export function logCspViolation(violation: CspViolation): void {
  console.warn(
    `Content Security Policy violation: ${violation.directive} blocked ${violation.blocked} (${violation.disposition})`,
  );
}
