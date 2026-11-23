// SPDX-License-Identifier: Apache-2.0
//
// The top summary of the transaction detail (05 §2.2 first bullet, judged against its full
// enumeration, ADR-0010 C-207 (4)): every field present with the API value; CPU time and allocated
// memory "Not measurable" when measured=false (virtual threads, T-UI-007 / T-ACC-23 display) and
// never 0; null values the dash; collection modes of user id and remote IP shown; the request URL
// (HTTP method + url, docs/14 §4.6) next to the service; collected strings with the XSS seed
// payloads rendered as text (T-UI-005, SEC-60).
// Requirements (test/README.md §5): { type: 'req', description: 'D-23' }, { type: 'req', description: 'SEC-60' }, { type: 'req', description: 'F-U02' }
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { withI18n } from '../../components/testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS } from '../../components/testing/xssPayloads';
import enCommon from '../../locales/en/common.json';
import enTx from '../../locales/en/tx.json';

import { profileFixture, stepFixture, transactionDetailFixture } from './data/testing/fixtures';
import type { TransactionDetail } from './data/transactionApi';
import { SummaryBar } from './SummaryBar';

const wrapper = withI18n('en');

function renderSummary(detail: TransactionDetail) {
  return render(<SummaryBar detail={detail} />, { wrapper });
}

function field(name: string): HTMLElement {
  return screen.getByTestId(`transaction-field-${name}`);
}

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
});

/** The docs/05 §2.2 first-bullet items, in order, and the field that shows each. */
const REQUIRED_FIELDS = [
  'service',
  'elapsed',
  'status',
  'time',
  'instance',
  'env',
  'version',
  'kind',
  'thread',
  'cpu',
  'alloc',
  'sql',
  'sql-wait',
  'ext',
  'error',
  'trace-id',
  'user-id',
  'remote-ip',
  'headers',
  'request-id',
  'caller',
] as const;

describe('SummaryBar (05 §2.2-1)', () => {
  it('shows every item of the 05 §2.2 enumeration with the API values', () => {
    const detail = transactionDetailFixture({
      http_status: 500,
      error: true,
      error_class: untrusted('java.sql.SQLTimeoutException'),
      user_id: untrusted('user-42'),
      user_id_mode: 'plain',
      caller: {
        app: untrusted('gateway'),
        app_id: 1,
        instance_id: 4,
        service: untrusted('POST /checkout'),
        ts: '2026-11-14T00:00:00.900Z',
        tx_id: '7312345678900000002',
      },
      sql: { count: 23, time_ms: 1650, wait_ms: 1200 },
      ext: { count: 2, time_ms: 90 },
    });
    renderSummary(detail);
    for (const name of REQUIRED_FIELDS) {
      expect(field(name), name).toBeInTheDocument();
    }
    expect(field('service')).toHaveTextContent('GET /api/orders/{id}');
    // The request URL as stored (docs/14 §4.6 http_method + url), next to the service.
    expect(screen.getByTestId('transaction-http-method')).toHaveTextContent('GET');
    expect(screen.getByTestId('transaction-url-value')).toHaveTextContent('/api/orders/123');
    expect(screen.getByTestId('transaction-elapsed-value')).toHaveTextContent('100ms');
    expect(screen.getByTestId('transaction-status-value')).toHaveTextContent('500');
    expect(screen.getByTestId('transaction-time-value')).toHaveAttribute('data-ts', detail.ts);
    expect(field('instance')).toHaveTextContent('web-01:8080');
    expect(field('env')).toHaveTextContent('prod');
    expect(field('version')).toHaveTextContent('1.4.2');
    expect(screen.getByTestId('transaction-kind-value')).toHaveTextContent(enCommon.term.kind.http);
    expect(field('thread')).toHaveTextContent('http-nio-8080-exec-7');
    expect(screen.getByTestId('transaction-cpu-value')).toHaveTextContent('12ms');
    expect(screen.getByTestId('transaction-alloc-value')).toHaveTextContent('4 MiB');
    expect(screen.getByTestId('transaction-sql-count')).toHaveAttribute('data-value', '23');
    expect(screen.getByTestId('transaction-sql-time')).toHaveTextContent('1.65s');
    expect(screen.getByTestId('transaction-sql-wait')).toHaveTextContent('1.2s');
    expect(screen.getByTestId('transaction-ext-count')).toHaveAttribute('data-value', '2');
    expect(screen.getByTestId('transaction-ext-time')).toHaveTextContent('90ms');
    expect(screen.getByTestId('transaction-error-value')).toHaveAttribute('data-error', 'true');
    expect(field('error')).toHaveTextContent('java.sql.SQLTimeoutException');
    expect(screen.getByTestId('transaction-trace-id')).toHaveTextContent(detail.trace_id ?? '');
    expect(screen.getByTestId('transaction-user-id')).toHaveTextContent('user-42');
    expect(screen.getByTestId('transaction-remote-ip')).toHaveTextContent('10.0.1.0');
    expect(screen.getByTestId('transaction-ip-mode')).toHaveTextContent(enTx.summary.ipMode.masked);
    expect(screen.getByTestId('transaction-header-names')).toHaveAttribute('data-count', '1');
    expect(field('headers')).toHaveTextContent('X-Request-Id');
    expect(screen.getByTestId('transaction-request-id-value')).toHaveTextContent('req-7f3a');
    expect(screen.getByTestId('transaction-caller-app')).toHaveTextContent('gateway');
    expect(screen.getByTestId('transaction-caller-service')).toHaveTextContent('POST /checkout');
    expect(screen.getByTestId('transaction-caller-instance')).toHaveTextContent('Instance #4');
  });

  it('T-UI-007 / T-ACC-23: virtual thread CPU and allocation are "Not measurable", never 0', () => {
    renderSummary(
      transactionDetailFixture({
        cpu_time_ms: null,
        alloc_bytes: null,
        measured: { cpu: false, alloc: false },
        flags: { ...transactionDetailFixture().flags, virtual_thread: true },
      }),
    );
    for (const id of ['transaction-cpu-value', 'transaction-alloc-value']) {
      const value = screen.getByTestId(id);
      expect(value).toHaveTextContent(enCommon.component.missing.notMeasurable);
      expect(value).toHaveAttribute('data-missing', 'notMeasurable');
      expect(value.textContent).not.toMatch(/\b0\b/);
    }
    expect(screen.getByTestId('transaction-flag-virtualThread')).toBeInTheDocument();
  });

  it('T-UI-007: a measured 0 is a value and a null without a flag is the dash', () => {
    renderSummary(
      transactionDetailFixture({
        cpu_time_ms: 0,
        alloc_bytes: null,
        measured: { cpu: true, alloc: true },
      }),
    );
    expect(screen.getByTestId('transaction-cpu-value')).toHaveTextContent('0ms');
    expect(screen.getByTestId('transaction-cpu-value')).not.toHaveAttribute('data-missing');
    expect(screen.getByTestId('transaction-alloc-value')).toHaveTextContent('—');
    expect(screen.getByTestId('transaction-alloc-value')).toHaveAttribute('data-missing', 'noData');
  });

  it('a URL the server did not send is the dash; an empty HTTP method (BATCH) is not shown', () => {
    renderSummary(
      transactionDetailFixture({ kind: 'BATCH', url: null, http_method: untrusted('') }),
    );
    expect(field('url')).toBeInTheDocument();
    expect(screen.getByTestId('transaction-url-value')).toHaveTextContent('—');
    expect(screen.getByTestId('transaction-url-value')).toHaveClass('safe-text--missing');
    expect(screen.queryByTestId('transaction-http-method')).not.toBeInTheDocument();
  });

  it('collection modes: user id off is "Not collected", the caller is explained when absent', () => {
    renderSummary(
      transactionDetailFixture({
        user_id: null,
        user_id_mode: 'off',
        remote_ip: null,
        ip_mode: 'off',
      }),
    );
    expect(field('user-id')).toHaveTextContent(enTx.summary.notCollected);
    expect(screen.getByTestId('transaction-user-id-mode')).toHaveTextContent(
      enTx.summary.userIdMode.off,
    );
    expect(field('remote-ip')).toHaveTextContent(enTx.summary.notCollected);
    expect(field('caller')).toHaveTextContent(enTx.summary.callerNone);
    expect(screen.getByTestId('transaction-status-value')).toHaveTextContent('200');
  });

  it('highlights the uninstrumented time from 20 % (server value) and not below', () => {
    // One top-level step, so the server's value and the timeline's are the same number.
    const method = (elapsed: number) => [
      stepFixture({ seq: 1, type: 'METHOD', start_offset_ms: 0, elapsed_ms: elapsed }),
    ];
    const { unmount } = renderSummary(
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({ steps: method(800), self_time_ms: 200, self_time_ratio: 0.2 }),
      }),
    );
    expect(screen.getByTestId('transaction-uninstrumented')).toHaveAttribute(
      'data-highlight',
      'true',
    );
    expect(field('uninstrumented')).toHaveClass('tx-field--emphasis');
    unmount();
    renderSummary(
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({ steps: method(850), self_time_ms: 150, self_time_ratio: 0.15 }),
      }),
    );
    expect(screen.getByTestId('transaction-uninstrumented')).toHaveAttribute(
      'data-highlight',
      'false',
    );
    expect(screen.getByTestId('transaction-uninstrumented')).toHaveAttribute(
      'data-source',
      'server',
    );
    expect(screen.getByTestId('transaction-uninstrumented')).not.toHaveAttribute('data-server-ms');
    expect(screen.queryByTestId('transaction-uninstrumented-server')).not.toBeInTheDocument();
  });

  it('a server value that differs from the timeline is named next to the timeline value', () => {
    // Overlapping asynchronous root steps: the timeline's union leaves 300 ms, the server's plain
    // sum of the top-level steps (1,200 ms) leaves 0.
    renderSummary(
      transactionDetailFixture({
        elapsed_ms: 1000,
        profile: profileFixture({
          steps: [
            stepFixture({ seq: 1, type: 'METHOD', start_offset_ms: 0, elapsed_ms: 600 }),
            stepFixture({ seq: 2, type: 'ASYNC', start_offset_ms: 100, elapsed_ms: 600 }),
          ],
          self_time_ms: 0,
          self_time_ratio: 0,
        }),
      }),
    );
    const value = screen.getByTestId('transaction-uninstrumented');
    expect(value).toHaveAttribute('data-ms', '300');
    expect(value).toHaveAttribute('data-source', 'client');
    expect(value).toHaveAttribute('data-server-ms', '0');
    expect(value).toHaveAttribute('data-highlight', 'true');
    const note = screen.getByTestId('transaction-uninstrumented-server');
    expect(note).toHaveTextContent(enTx.summary.uninstrumentedServerDiffers);
    expect(note).toHaveTextContent('0ms');
  });

  it.each(XSS_PAYLOADS)(
    'T-UI-005 renders the payload %j in every collected field as text',
    (payload) => {
      const value = untrusted(payload);
      const { container } = renderSummary(
        transactionDetailFixture({
          service: value,
          url: value,
          http_method: value,
          thread: value,
          version: value,
          user_id: value,
          user_id_mode: 'plain',
          request_id: value,
          error: true,
          error_class: value,
          app: { app_id: 3, name: value, env: value },
          instance: { instance_id: 12, name: value },
          headers: { 'X-Seed': value },
          caller: {
            app: value,
            app_id: 1,
            instance_id: null,
            service: value,
            ts: null,
            tx_id: null,
          },
          profile: profileFixture({
            steps: [stepFixture({ seq: 1, type: 'METHOD', name: value })],
          }),
        }),
      );
      expect(findInjectedMarkup(container)).toEqual([]);
      expect((window as { __xss?: unknown }).__xss).toBeUndefined();
      for (const name of [
        'service',
        'url',
        'thread',
        'version',
        'user-id',
        'request-id',
        'error',
        'instance',
        'env',
      ]) {
        expect(field(name).textContent, name).toContain(payload);
      }
      // The request URL (the field the XSS seed of the E2E stack carries) is the payload as text.
      expect(screen.getByTestId('transaction-url-value').textContent).toBe(payload);
      expect(screen.getByTestId('transaction-http-method').textContent).toBe(payload);
    },
  );
});
