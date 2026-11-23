// SPDX-License-Identifier: Apache-2.0
//
// The 7-day rule preview (05 §2.4-2, docs/15 §9.9): one request 500 ms after the last change of
// the rule (fake timers), the count, longest firing and firing times of the answer, the chart, the
// "not available" state for a metric outside the rollups (catalog: no request; server: 400
// metric/preview_unsupported) and the 429 retry hint with Retry-After and a manual retry.
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import type { AlertRuleRequest } from '../../../api/alertRules';
import { installDomStubs } from '../../../components/testing/dom';
import { withI18n } from '../../../components/testing/withI18n';

import { PREVIEW } from './testing/fixtures';

// uPlot reads matchMedia when it is imported: install the jsdom stand-ins first.
await vi.hoisted(async () => {
  (await import('../../../charts/testing/uplotDom')).installUplotDom();
});
const { PREVIEW_DEBOUNCE_MS, RulePreview } = await import('./RulePreview');

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U09' };

const PREVIEW_ROUTE = 'POST /api/v1/alerts/rules/preview';

let stub: ApiStub;

function rule(warn: number): AlertRuleRequest {
  return {
    name: 'Preview',
    target: { type: 'app', app_id: 1, ids: [] },
    metric: 'error_rate',
    window: '5m',
    for: '5m',
    condition: { operator: '>', warn },
  };
}

/** Lets the stubbed fetch and the state updates after it run. */
async function flush(): Promise<void> {
  await act(async () => {
    for (let i = 0; i < 10; i += 1) {
      await Promise.resolve();
    }
  });
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  installDomStubs();
  vi.useFakeTimers();
  stub = installApiStub();
  stub.on(PREVIEW_ROUTE, () => envelope(PREVIEW));
});

afterEach(() => {
  vi.useRealTimers();
  uninstallApiStub();
});

describe('rule preview (05 §2.4-2)', () => {
  it('sends one request 500 ms after the last of several quick changes', async () => {
    const view = render(
      <RulePreview
        request={rule(0.01)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
      { wrapper: withI18n() },
    );
    await advance(300);
    view.rerender(
      <RulePreview
        request={rule(0.02)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
    );
    await advance(300);
    view.rerender(
      <RulePreview
        request={rule(0.03)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
    );
    await advance(PREVIEW_DEBOUNCE_MS - 1);
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(0);
    await advance(1);
    await flush();
    const calls = stub.calls(PREVIEW_ROUTE);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.body).toEqual({ rule: rule(0.03), days: 7 });
    await flush();
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'ready');
    expect(screen.getByTestId('alert-rule-preview-count')).toHaveAttribute('data-value', '2');
    expect(screen.getByTestId('alert-rule-preview-count')).toHaveTextContent('2');
    expect(screen.getByTestId('alert-rule-preview-max-duration')).toHaveTextContent('15m');
    expect(screen.getAllByTestId('alert-rule-preview-firing')).toHaveLength(2);
    expect(screen.getAllByTestId('alert-rule-preview-firing')[0]).toHaveTextContent('peak 8%');
    expect(screen.getByTestId('alert-rule-preview-chart')).toBeInTheDocument();
  });

  it('does not ask again for the same rule (a re-render with an equal request)', async () => {
    const view = render(
      <RulePreview
        request={rule(0.01)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
      { wrapper: withI18n() },
    );
    await advance(PREVIEW_DEBOUNCE_MS);
    await flush();
    view.rerender(
      <RulePreview
        request={rule(0.01)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
    );
    await advance(PREVIEW_DEBOUNCE_MS * 2);
    await flush();
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(1);
  });

  it('says the preview is not available for a metric outside the rollups without asking', async () => {
    render(<RulePreview request={rule(1)} unsupported unit="state" metricLabel="Instance down" />, {
      wrapper: withI18n(),
    });
    await advance(PREVIEW_DEBOUNCE_MS * 2);
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(0);
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'unsupported');
    expect(screen.getByTestId('alert-rule-preview-unsupported')).toHaveTextContent(
      'The preview is not available for this metric.',
    );
  });

  it('maps the server 400 metric/preview_unsupported to the same state', async () => {
    stub.on(PREVIEW_ROUTE, () =>
      errorBody(400, 'invalid_request', {
        details: [{ field: 'metric', reason: 'preview_unsupported' }],
      }),
    );
    render(
      <RulePreview request={rule(1)} unsupported={false} unit="count" metricLabel="Active" />,
      { wrapper: withI18n() },
    );
    await advance(PREVIEW_DEBOUNCE_MS);
    await flush();
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'unsupported');
  });

  it('shows the 429 retry hint with Retry-After and retries only on request (C-176)', async () => {
    stub.on(PREVIEW_ROUTE, () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '12' } }),
    );
    render(
      <RulePreview
        request={rule(0.05)}
        unsupported={false}
        unit="ratio"
        metricLabel="Error rate"
      />,
      { wrapper: withI18n() },
    );
    await advance(PREVIEW_DEBOUNCE_MS);
    await flush();
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'rateLimited');
    expect(screen.getByTestId('alert-rule-preview-rate-limited')).toHaveTextContent('12 s');
    await advance(PREVIEW_DEBOUNCE_MS * 4);
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(1);
    stub.on(PREVIEW_ROUTE, () => envelope(PREVIEW));
    act(() => {
      screen.getByTestId('alert-rule-preview-retry').click();
    });
    await advance(PREVIEW_DEBOUNCE_MS);
    await flush();
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(2);
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'ready');
  });

  it('waits for a valid rule (no request while the evaluated fields are invalid)', async () => {
    render(
      <RulePreview request={null} unsupported={false} unit="ratio" metricLabel="Error rate" />,
      {
        wrapper: withI18n(),
      },
    );
    await advance(PREVIEW_DEBOUNCE_MS * 2);
    expect(stub.calls(PREVIEW_ROUTE)).toHaveLength(0);
    expect(screen.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'invalid');
  });
});
