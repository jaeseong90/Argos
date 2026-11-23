// SPDX-License-Identifier: Apache-2.0
//
// The shared alert row (ADR-0010 C-227 (4)): the rule name keeps its own line with the full name as
// the tooltip (no 'E…' cut by long application and target names), state and severity, the optional
// value slot, the panels' own test ids around the names, and collected names as text (T-UI-005).
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import { untrusted } from '../api/untrusted';

import { AlertSummaryRow, type AlertSummary } from './AlertSummaryRow';
import { AlertValue } from './AlertValue';
import { withI18n } from './testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from './testing/xssPayloads';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const I18n = withI18n();

function renderRow(
  alert: AlertSummary,
  extra: Partial<Parameters<typeof AlertSummaryRow>[0]> = {},
) {
  return render(
    <I18n>
      <MemoryRouter>
        <AlertSummaryRow href="/alerts?alert=e-1&state=acked" alert={alert} {...extra} />
      </MemoryRouter>
    </I18n>,
  );
}

const LONG_RULE = 'E2E seed error rate on the checkout service of the order application';

describe('AlertSummaryRow (C-227 (4))', () => {
  it('gives the rule name its own line with the full name as the tooltip', () => {
    renderRow({
      state: 'FIRING',
      severity: 'critical',
      rule: untrusted(LONG_RULE),
      app: untrusted('order-api-with-a-rather-long-application-name'),
      target: untrusted('order-api-7f9c8d6b5a-xk2lp.prod.internal.example'),
      since: Date.parse('2026-11-20T03:00:00Z'),
    });
    const row = screen.getByTestId('alert-summary-row');
    const rule = within(row).getByTestId('alert-row-rule');
    expect(rule).toHaveTextContent(LONG_RULE);
    expect(rule).toHaveAttribute('title', LONG_RULE);
    // The rule shares its line only with the state and the time; app and target go below.
    const head = rule.closest('.alert-summary-row__head');
    expect(head).not.toBeNull();
    expect(head).not.toHaveTextContent('order-api-with');
    expect(row.querySelector('.alert-summary-row__meta')).toHaveTextContent('order-api-with');
    expect(within(row).getByTestId('alert-state')).toHaveAttribute('data-state', 'FIRING');
    expect(row).toHaveAttribute('href', '/alerts?alert=e-1&state=acked');
  });

  it('wraps the names in the panel test ids and shows the value slot', () => {
    renderRow(
      {
        state: 'ACKED',
        severity: 'warn',
        rule: untrusted('Error rate'),
        target: untrusted('order-1'),
        since: null,
      },
      {
        ruleTestId: 'dashboard-firing-rule',
        targetTestId: 'dashboard-firing-target',
        value: <AlertValue value={0.071} threshold={0.05} unit="ratio" />,
      },
    );
    expect(screen.getByTestId('dashboard-firing-rule')).toHaveTextContent('Error rate');
    expect(screen.getByTestId('dashboard-firing-target')).toHaveTextContent('order-1');
    expect(screen.getByTestId('alert-value')).toHaveTextContent('7.1% / 5%');
  });

  it('T-UI-005 renders rule, application and target names as text', () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror=window.__xss=1>';
    const { container } = renderRow({
      state: 'FIRING',
      severity: 'warn',
      rule: untrusted(payload),
      app: untrusted(payload),
      target: untrusted(payload),
      since: null,
    });
    expect(screen.getByTestId('alert-row-rule')).toHaveTextContent(payload);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
