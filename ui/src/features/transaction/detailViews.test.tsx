// SPDX-License-Identifier: Apache-2.0
//
// Parts of the transaction detail on typed fixtures (ADR-0010 C-207 (5): categories the E2E seed
// cannot produce yet): the profile banner for sampled_out / lost / truncated / summary mode (D-07,
// T-ACC-07 display), bind parameters as the server sent them with the masked_by_role notice (C-13,
// SEC-3A: never more than sent), the SQL tab's wait/execution split and statistics links, the
// external tab's failure badges (TIMEOUT / CONNECT_FAIL / 5xx before WO-14), the error tab's
// frames and group link, the STACK view's common and stuck frames, and XSS payloads in SQL,
// parameters, URLs, headers, exception and frames rendered as text (T-UI-005).
// Requirements (test/README.md §5): { type: 'req', description: 'D-07' }, { type: 'req', description: 'SEC-3A' }, { type: 'req', description: 'SEC-60' }, { type: 'req', description: 'F-U02' }
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { withI18n } from '../../components/testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS } from '../../components/testing/xssPayloads';
import enTx from '../../locales/en/tx.json';
import { defaultState, TxDetailQ } from '../../state/urlState';

import {
  profileFixture,
  sqlStepFixture,
  stepFixture,
  transactionDetailFixture,
} from './data/testing/fixtures';
import type { TransactionDetail, TransactionStep } from './data/transactionApi';
import { detailLinks, linkContext } from './model/links';
import { ParamsView } from './ParamsView';
import { ProfileStatusBanner } from './ProfileStatusBanner';
import { ErrorTab } from './tabs/ErrorTab';
import { ExtTab } from './tabs/ExtTab';
import { HeadersTab } from './tabs/HeadersTab';
import { SqlTab } from './tabs/SqlTab';
import { StackTab } from './tabs/StackTab';

const wrapper = withI18n('en');

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
});

function linksOf(detail: TransactionDetail) {
  return detailLinks(detail, linkContext(detail, defaultState(TxDetailQ), null, ''));
}

describe('ProfileStatusBanner (D-07, T-ACC-07 display, C-154)', () => {
  it('sampled_out: profile not captured by the sampling policy', () => {
    render(
      <ProfileStatusBanner
        detail={transactionDetailFixture({
          profile: profileFixture({ state: 'sampled_out', steps: [] }),
        })}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-profile-notice-sampled-out')).toHaveTextContent(
      enTx.profile.sampledOut,
    );
    expect(screen.getByTestId('transaction-profile-banner')).toHaveAttribute(
      'data-state',
      'sampled_out',
    );
  });

  it('lost: profile captured but dropped (typed fixture: no LOST profile in the seed yet)', () => {
    render(
      <ProfileStatusBanner
        detail={transactionDetailFixture({ profile: profileFixture({ state: 'lost', steps: [] }) })}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-profile-notice-lost')).toHaveTextContent(
      enTx.profile.lost,
    );
  });

  it('lost past the retention: the retention reason alone, the server state kept', () => {
    render(
      <ProfileStatusBanner
        detail={transactionDetailFixture({
          profile: profileFixture({ state: 'lost', steps: [], retention_exceeded: true }),
        })}
      />,
      { wrapper },
    );
    const banner = screen.getByTestId('transaction-profile-banner');
    expect(banner).toHaveAttribute('data-state', 'lost');
    expect(banner).toHaveClass('tx-banner--warning');
    expect(screen.getByTestId('transaction-profile-notice-retention-exceeded')).toHaveTextContent(
      enTx.profile.retentionExceeded,
    );
    expect(screen.queryByTestId('transaction-profile-notice-lost')).not.toBeInTheDocument();
  });

  it('step_truncated is a partial banner with the shown and total counts; summary mode is said', () => {
    render(
      <ProfileStatusBanner
        detail={transactionDetailFixture({
          profile: profileFixture({ step_truncated: true, total_steps: 5000 }),
          flags: { ...transactionDetailFixture().flags, summarized: true },
        })}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-profile-notice-step-truncated')).toHaveTextContent(
      '2 shown, 5,000 happened',
    );
    expect(screen.getByTestId('transaction-profile-notice-summarized')).toHaveTextContent(
      enTx.profile.summarized,
    );
  });

  it('steps that may still arrive offer a refresh; a complete profile shows nothing', async () => {
    const user = userEvent.setup();
    const refresh = vi.fn();
    const { unmount } = render(
      <ProfileStatusBanner
        detail={transactionDetailFixture({
          profile: profileFixture({ steps: [], steps_pending: true }),
        })}
        onRefresh={refresh}
      />,
      { wrapper },
    );
    await user.click(screen.getByTestId('transaction-profile-refresh'));
    expect(refresh).toHaveBeenCalledTimes(1);
    unmount();
    render(<ProfileStatusBanner detail={transactionDetailFixture()} />, { wrapper });
    expect(screen.queryByTestId('transaction-profile-banner')).not.toBeInTheDocument();
  });
});

describe('bind parameters (C-13, SEC-3A)', () => {
  it('masked_by_role shows the server text and says the role lacks pii.view', () => {
    const step = sqlStepFixture(1, {
      params: untrusted('[String(11), Long]'),
      params_mode: 'masked_by_role',
    });
    render(<ParamsView step={step} />, { wrapper });
    expect(screen.getByTestId('transaction-params-masked-by-role')).toHaveTextContent(
      enTx.params.maskedByRoleNotice,
    );
    expect(screen.getByTestId('transaction-params-mode')).toHaveAttribute(
      'data-mode',
      'masked_by_role',
    );
    expect(screen.getByTestId('transaction-params-value').textContent).toContain(
      '[String(11), Long]',
    );
  });

  it('masked shows types only, exactly as sent; no notice', () => {
    render(<ParamsView step={sqlStepFixture(1)} />, { wrapper });
    expect(screen.getByTestId('transaction-params-mode')).toHaveTextContent(
      enTx.params.mode.masked,
    );
    expect(screen.getByTestId('transaction-params-value').textContent).toContain('[Long]');
    expect(screen.queryByTestId('transaction-params-masked-by-role')).not.toBeInTheDocument();
  });

  it('an unknown mode is labelled as such and the text stays as sent', () => {
    render(
      <ParamsView step={sqlStepFixture(1, { params_mode: 'future', params: untrusted('x') })} />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-params-mode')).toHaveAttribute('data-mode', 'unknown');
  });
});

describe('SQL tab (05 §2.2-5)', () => {
  it('separates connection wait and execution totals, lists pools and links SQL statistics', () => {
    const detail = transactionDetailFixture({
      sql: { count: 3, time_ms: 120, wait_ms: 30 },
      profile: profileFixture({
        steps: [
          stepFixture({
            seq: 1,
            type: 'DBC',
            elapsed_ms: 30,
            name: untrusted('HikariPool-1'),
            target: untrusted('db1'),
          }),
          sqlStepFixture(2, { params_mode: 'masked_by_role', params: untrusted('[String(4)]') }),
        ],
      }),
    });
    render(
      <SqlTab detail={detail} links={linksOf(detail)} onNavigate={vi.fn()} onSelect={vi.fn()} />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-sql-exec-total')).toHaveTextContent('120ms');
    expect(screen.getByTestId('transaction-sql-wait-total')).toHaveTextContent('30ms');
    expect(
      within(screen.getByTestId('transaction-sql-pools')).getByText('HikariPool-1'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('transaction-sql-masked-by-role')).toHaveTextContent(
      enTx.params.maskedByRoleNotice,
    );
    const link = screen.getByTestId('transaction-sql-stats-link');
    expect(link.getAttribute('href')).toMatch(
      /^\/apps\/3\/stats\/sql\/15837492011223344556\?from=/,
    );
  });

  it('a row opens the side panel by its seq', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const detail = transactionDetailFixture();
    render(
      <SqlTab detail={detail} links={linksOf(detail)} onNavigate={vi.fn()} onSelect={onSelect} />,
      { wrapper },
    );
    const rows = within(screen.getByTestId('transaction-sql')).getAllByTestId('data-table-row');
    await user.click(rows[rows.length - 1] as HTMLElement);
    expect(onSelect).toHaveBeenCalledWith(2);
  });
});

describe('external call tab (05 §2.2-6; failure classes before WO-14 on typed fixtures)', () => {
  it('shows TIMEOUT, CONNECT_FAIL and 5xx badges, host statistics and the trace link', () => {
    const call = (seq: number, extra: Partial<TransactionStep>) =>
      stepFixture({
        ...extra,
        seq,
        type: 'HTTP',
        name: untrusted('pay.internal'),
        name_id: '77',
        url: untrusted('/pay'),
      });
    const detail = transactionDetailFixture({
      ext: { count: 3, time_ms: 300 },
      profile: profileFixture({
        steps: [
          call(1, {
            start_offset_ms: 0,
            elapsed_ms: 100,
            failure: 'timeout',
            status: 1,
            error: true,
          }),
          call(2, {
            start_offset_ms: 10,
            elapsed_ms: 100,
            failure: 'connect_fail',
            status: 2,
            error: true,
          }),
          call(3, { start_offset_ms: 20, elapsed_ms: 100, status: 502, error: true }),
        ],
      }),
    });
    render(
      <ExtTab detail={detail} links={linksOf(detail)} onNavigate={vi.fn()} onSelect={vi.fn()} />,
      { wrapper },
    );
    const kinds = screen
      .getAllByTestId('transaction-fail-kind')
      .map((b) => [b.getAttribute('data-kind'), b.textContent]);
    expect(kinds).toEqual([
      ['timeout', 'TIMEOUT'],
      ['connect_fail', 'CONNECT_FAIL'],
      ['http_5xx', '5xx'],
    ]);
    expect(screen.getAllByTestId('transaction-ext-host-link')[0]?.getAttribute('href')).toMatch(
      /^\/apps\/3\/stats\/external\/77\?from=/,
    );
    expect(screen.getByTestId('transaction-ext-trace-link').getAttribute('href')).toMatch(
      /^\/traces\/4bf92f3577b34da6a3ce929d0e0e4736\?/,
    );
  });
});

describe('error tab (05 §2.2-7)', () => {
  it('shows the class, says the message is not in the detail, lists frames and links the group', () => {
    const detail = transactionDetailFixture({
      error: true,
      error_class: untrusted('java.sql.SQLTimeoutException'),
      error_group_id: '9223372036854775809',
      profile: profileFixture({
        steps: [
          stepFixture({
            seq: 1,
            type: 'ERROR',
            name: untrusted('java.sql.SQLTimeoutException'),
            frames: [
              untrusted('com.shop.OrderRepo.find(OrderRepo.java)'),
              untrusted('java.lang.Thread.run(Thread.java)'),
            ],
          }),
        ],
      }),
    });
    render(
      <ErrorTab detail={detail} links={linksOf(detail)} onNavigate={vi.fn()} appPackages={[]} />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-error-class')).toHaveTextContent(
      'java.sql.SQLTimeoutException',
    );
    expect(screen.getByTestId('transaction-error-message')).toHaveTextContent('—');
    expect(screen.getByText(enTx.error.messageNote)).toBeInTheDocument();
    expect(screen.getAllByTestId('transaction-frame')).toHaveLength(2);
    // No app_packages in the API yet (C-207 (3)): no frame is bold and the tab says why.
    expect(
      screen.getAllByTestId('transaction-frame').filter((f) => f.hasAttribute('data-app')),
    ).toHaveLength(0);
    expect(screen.getByTestId('transaction-error-app-frames-note')).toBeInTheDocument();
    expect(screen.getByTestId('transaction-error-group-link').getAttribute('href')).toMatch(
      /^\/apps\/3\/stats\/errors\/9223372036854775809\?from=/,
    );
  });

  it('a transaction without error says so', () => {
    const detail = transactionDetailFixture();
    render(
      <ErrorTab detail={detail} links={linksOf(detail)} onNavigate={vi.fn()} appPackages={[]} />,
      { wrapper },
    );
    expect(screen.getByTestId('transaction-error-empty')).toBeInTheDocument();
  });
});

describe('STACK view (05 §2.2-8, docs/15 §8.5)', () => {
  it('puts captures side by side with common frames highlighted and the stuck point marked', () => {
    const frames = (top: string) =>
      [top, 'com.shop.Pay.call(Pay.java)', 'java.lang.Thread.run(Thread.java)'].map(untrusted);
    const detail = transactionDetailFixture({
      profile: profileFixture({
        steps: [
          stepFixture({
            seq: 1,
            type: 'STACK',
            start_offset_ms: 1000,
            frames: frames('sun.nio.ch.Net.poll(Net.java)'),
          }),
          stepFixture({
            seq: 2,
            type: 'STACK',
            start_offset_ms: 4000,
            frames: frames('java.net.Socket.read(Socket.java)'),
          }),
        ],
      }),
    });
    render(<StackTab detail={detail} appPackages={[]} />, { wrapper });
    const columns = screen.getAllByTestId('transaction-stack-column');
    expect(columns).toHaveLength(2);
    const first = within(columns[0] as HTMLElement).getAllByTestId('transaction-frame');
    expect(first.map((f) => f.getAttribute('data-common'))).toEqual([null, 'true', 'true']);
    expect(first[1]).toHaveAttribute('data-stuck', 'true');
    expect(first[1]).toHaveTextContent(enTx.stack.stuckHere);
  });

  it('no captures: explains when the agent captures stacks', () => {
    render(<StackTab detail={transactionDetailFixture()} appPackages={[]} />, { wrapper });
    expect(screen.getByTestId('transaction-stack-empty')).toHaveTextContent(enTx.stack.empty);
  });
});

describe('T-UI-005: collected strings of the tabs render as text', () => {
  it.each(XSS_PAYLOADS)(
    'payload %j in SQL, parameters, host, path, exception, frames and headers',
    (payload) => {
      const value = untrusted(payload);
      const detail = transactionDetailFixture({
        error: true,
        error_class: value,
        request_id: value,
        headers: { [payload]: value },
        profile: profileFixture({
          steps: [
            sqlStepFixture(1, { sql: value, params: value, params_mode: 'partial', target: value }),
            stepFixture({
              seq: 2,
              type: 'HTTP',
              name: value,
              url: value,
              name_id: '5',
              start_offset_ms: 60,
              elapsed_ms: 5,
            }),
            stepFixture({ seq: 3, type: 'ERROR', name: value, frames: [value] }),
            stepFixture({ seq: 4, type: 'STACK', frames: [value] }),
            stepFixture({ seq: 5, type: 'DBC', name: value, target: value }),
          ],
        }),
      });
      const links = linksOf(detail);
      const { container } = render(
        <>
          <SqlTab detail={detail} links={links} onNavigate={vi.fn()} onSelect={vi.fn()} />
          <ExtTab detail={detail} links={links} onNavigate={vi.fn()} onSelect={vi.fn()} />
          <ErrorTab detail={detail} links={links} onNavigate={vi.fn()} appPackages={[]} />
          <StackTab detail={detail} appPackages={[]} />
          <HeadersTab detail={detail} />
          <ParamsView step={detail.profile.steps[0] ?? sqlStepFixture(1)} />
        </>,
        { wrapper },
      );
      expect(findInjectedMarkup(container)).toEqual([]);
      expect((window as { __xss?: unknown }).__xss).toBeUndefined();
      expect(container.textContent).toContain(payload);
    },
  );
});
