// SPDX-License-Identifier: Apache-2.0
//
// The `alert=` banner above the charts (docs/15 §4.3 item 4, ADR-0010 C-226 (8)): rule, target,
// state with severity, value/threshold in the rule's unit and the time, the link to that event in
// the history, the loading, failed and "not in this range" states, the truncation note and
// collected names as text (T-UI-005).
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import type { AlertEvent } from '../api/alertMarkers';
import { untrusted } from '../api/untrusted';

import { AlertBanner, AlertMarkersTruncated } from './AlertBanner';
import { withI18n } from './testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from './testing/xssPayloads';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const I18n = withI18n();

function alertEvent(overrides: Partial<AlertEvent> = {}): AlertEvent {
  return {
    event_id: 'ev-1',
    ts: '2026-11-20T03:04:00Z',
    app: { id: 1, name: untrusted('order-api') },
    env: untrusted('prod'),
    target: { type: 'instance', id: '12', name: untrusted('order-1') },
    rule: {
      id: 'r-1',
      name: untrusted('Error rate'),
      metric: 'error_rate',
      unit: 'ratio',
    },
    severity: 'critical',
    from_state: 'PENDING',
    state: 'FIRING',
    reason: 'condition',
    value: 0.071,
    threshold: 0.05,
    duration_s: null,
    acked_by: null,
    acked_at: null,
    note: null,
    deliveries: [],
    deeplink: 'http://argos.example/apps/1/dashboard?alert=ev-1',
    ...overrides,
  };
}

function renderBanner(props: Parameters<typeof AlertBanner>[0]) {
  return render(
    <I18n>
      <MemoryRouter>
        <AlertBanner {...props} />
      </MemoryRouter>
    </I18n>,
  );
}

describe('AlertBanner (C-226 (8))', () => {
  it('summarises the alert= event with its unit and links to the history', () => {
    renderBanner({ eventId: 'ev-1', event: alertEvent(), loading: false });
    const banner = screen.getByTestId('alert-banner');
    expect(banner).toHaveAttribute('data-state', 'ready');
    expect(banner).toHaveAttribute('data-event-id', 'ev-1');
    expect(screen.getByTestId('alert-banner-rule')).toHaveTextContent('Error rate');
    expect(screen.getByTestId('alert-banner-target')).toHaveTextContent('order-1');
    expect(banner).toHaveTextContent('Instance');
    expect(screen.getByTestId('alert-banner-value')).toHaveTextContent('7.1% / 5%');
    expect(screen.getByTestId('alert-state')).toHaveAttribute('data-state', 'FIRING');
    expect(screen.getByTestId('alert-banner-history')).toHaveAttribute(
      'href',
      '/alerts?alert=ev-1',
    );
  });

  it('names the application for an application target', () => {
    renderBanner({
      eventId: 'ev-1',
      event: alertEvent({ target: { type: 'app', id: null, name: null } }),
      loading: false,
    });
    expect(screen.getByTestId('alert-banner-target')).toHaveTextContent('order-api');
  });

  it('says when the event is loading, failed or not in the range', () => {
    const view = renderBanner({ eventId: 'ev-9', event: null, loading: true });
    expect(screen.getByTestId('alert-banner')).toHaveAttribute('data-state', 'loading');
    view.unmount();
    const failed = renderBanner({ eventId: 'ev-9', event: null, loading: false, failed: true });
    expect(screen.getByTestId('alert-banner')).toHaveAttribute('data-state', 'error');
    failed.unmount();
    renderBanner({ eventId: 'ev-9', event: null, loading: false });
    expect(screen.getByTestId('alert-banner')).toHaveAttribute('data-state', 'missing');
    expect(screen.getByTestId('alert-banner')).toHaveTextContent(
      'This alert has no state transition in the selected time range.',
    );
  });

  it('notes a truncated marker list', () => {
    render(<AlertMarkersTruncated count={500} />, { wrapper: I18n });
    expect(screen.getByTestId('alert-markers-truncated')).toHaveTextContent('newest 500');
  });

  it('T-UI-005 renders rule and target names as text', () => {
    const payload = XSS_PAYLOADS[0] ?? '<img src=x onerror=window.__xss=1>';
    const { container } = renderBanner({
      eventId: 'ev-1',
      event: alertEvent({
        rule: { id: 'r', name: untrusted(payload), metric: 'error_rate', unit: 'ratio' },
        target: { type: 'service', id: '1', name: untrusted(payload) },
      }),
      loading: false,
    });
    expect(screen.getByTestId('alert-banner-rule')).toHaveTextContent(payload);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
