// SPDX-License-Identifier: Apache-2.0
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { EmptyState } from './EmptyState';
import { errorKind, ErrorState } from './ErrorState';
import { LoadingState, SLOW_LOADING_MS } from './LoadingState';
import { PartialBanner } from './PartialBanner';
import { withI18n } from './testing/withI18n';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'D-19' };

const wrapper = withI18n('en');

afterEach(() => {
  vi.useRealTimers();
});

describe('EmptyState (docs/15 §6.7, 05 §2.0-8)', () => {
  it('states the fact, the next action and offers buttons and the excluded-requests link', async () => {
    const user = userEvent.setup();
    const onWiden = vi.fn();
    const onClear = vi.fn();
    const onExcluded = vi.fn();
    render(
      <EmptyState
        title="No transactions in the selected range."
        onWidenRange={onWiden}
        onClearFilters={onClear}
        excluded={{ count: 1234, onOpen: onExcluded }}
      />,
      { wrapper },
    );
    const state = screen.getByTestId('empty-state');
    expect(state).toHaveTextContent('No transactions in the selected range.');
    expect(state).toHaveTextContent('Widen the time range or check the filters and the kind.');
    await user.click(screen.getByTestId('empty-state-widen'));
    await user.click(screen.getByTestId('empty-state-clear-filters'));
    expect(screen.getByTestId('empty-state-excluded')).toHaveTextContent(
      '1,234 requests were excluded in this range',
    );
    await user.click(screen.getByTestId('empty-state-excluded'));
    expect([onWiden, onClear, onExcluded].map((fn) => fn.mock.calls.length)).toEqual([1, 1, 1]);
  });

  it('uses the Korean wording and hides the excluded link at 0', () => {
    render(<EmptyState excluded={{ count: 0, onOpen: vi.fn() }} />, { wrapper: withI18n('ko') });
    expect(screen.getByTestId('empty-state')).toHaveTextContent('선택 기간에 데이터가 없습니다.');
    expect(screen.getByTestId('empty-state')).toHaveTextContent(
      '기간을 넓히거나 필터·종류를 확인하세요.',
    );
    expect(screen.queryByTestId('empty-state-excluded')).toBeNull();
  });
});

describe('ErrorState (docs/15 §6.7)', () => {
  it('maps status and code to the message kind (C-132 codes)', () => {
    expect(errorKind({})).toBe('network');
    expect(errorKind({ status: 504, code: 'query_timeout' })).toBe('timeout');
    expect(errorKind({ status: 429, code: 'rate_limited' })).toBe('rateLimited');
    expect(errorKind({ status: 503, code: 'storage_unavailable' })).toBe('unavailable');
    expect(errorKind({ status: 503, code: 'starting' })).toBe('starting');
    expect(errorKind({ status: 403, code: 'forbidden' })).toBe('forbidden');
    expect(errorKind({ status: 404, code: 'not_found' })).toBe('notFound');
    expect(errorKind({ status: 400, code: 'range_invalid' })).toBe('rangeInvalid');
    expect(errorKind({ status: 400, code: 'limit_exceeded' })).toBe('limitExceeded');
    expect(errorKind({ status: 422, code: 'filter_invalid' })).toBe('filterInvalid');
    expect(errorKind({ status: 400, code: 'unsupported_sort' })).toBe('unsupportedSort');
    expect(errorKind({ status: 400, code: 'invalid_request' })).toBe('badRequest');
    expect(errorKind({ status: 500, code: 'internal' })).toBe('server');
  });

  it('shows the request id, the status and a retry button', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(
      <ErrorState error={{ status: 500, code: 'internal', requestId: 'r-42' }} onRetry={onRetry} />,
      { wrapper },
    );
    expect(screen.getByRole('alert')).toHaveTextContent('report the request ID');
    expect(screen.getByTestId('error-state-status')).toHaveTextContent('Status 500');
    expect(screen.getByTestId('error-state-request-id')).toHaveTextContent('Request ID r-42');
    await user.click(screen.getByTestId('error-state-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('asks for a role on 403 without a retry button', () => {
    render(
      <ErrorState error={{ status: 403, code: 'forbidden', requestId: 'r-1' }} onRetry={vi.fn()} />,
      { wrapper },
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'You do not have permission to view this. Ask an administrator for a role.',
    );
    expect(screen.queryByTestId('error-state-retry')).toBeNull();
  });

  it('waits for Retry-After on 429 and never retries by itself', () => {
    vi.useFakeTimers();
    const onRetry = vi.fn();
    render(
      <ErrorState
        error={{ status: 429, code: 'rate_limited', retryAfterSeconds: 3 }}
        onRetry={onRetry}
      />,
      { wrapper },
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Too many requests. Try again in 3 s.');
    expect(screen.getByTestId('error-state-retry')).toBeDisabled();
    expect(screen.getByTestId('error-state-retry')).toHaveTextContent('Retry in 3 s');
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByTestId('error-state-retry')).toHaveTextContent('Retry in 2 s');
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByTestId('error-state-retry')).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent('You can try again now.');
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('asks to narrow the range on 504 query_timeout', async () => {
    const user = userEvent.setup();
    const onNarrow = vi.fn();
    render(<ErrorState error={{ status: 504, code: 'query_timeout' }} onNarrowRange={onNarrow} />, {
      wrapper: withI18n('ko'),
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      '조회 시간이 너무 깁니다. 기간을 좁혀 다시 시도하세요.',
    );
    await user.click(screen.getByTestId('error-state-narrow'));
    expect(onNarrow).toHaveBeenCalledTimes(1);
  });
});

describe('LoadingState (docs/15 §6.7)', () => {
  it('shows a skeleton and the narrow-the-range hint after 2 seconds', () => {
    vi.useFakeTimers();
    render(<LoadingState variant="table" rows={3} />, { wrapper });
    const state = screen.getByTestId('loading-state');
    expect(state).toHaveAttribute('aria-busy', 'true');
    expect(state.querySelectorAll('.skeleton__bar')).toHaveLength(3);
    expect(screen.queryByTestId('loading-state-slow')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(SLOW_LOADING_MS - 1);
    });
    expect(screen.queryByTestId('loading-state-slow')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByTestId('loading-state-slow')).toHaveTextContent(
      'Narrow the time range to make it faster.',
    );
  });
});

describe('PartialBanner (docs/15 §6.7, D-06, D-07)', () => {
  it('lists sampling, truncation, rollup source, retention and profile reasons', () => {
    render(
      <PartialBanner
        meta={{
          sampling: { applied: true },
          shown: 100000,
          total: 342118,
          truncated: true,
          source: '1m',
          retention_exceeded: true,
        }}
        profileState="sampled_out"
      />,
      { wrapper },
    );
    expect(screen.getByTestId('partial-sampling')).toHaveTextContent('Showing 100,000 of 342,118');
    expect(screen.getByTestId('partial-truncated')).toHaveTextContent(
      'Only the first 100,000 results are shown.',
    );
    expect(screen.getByTestId('partial-source')).toHaveTextContent('1-minute aggregates');
    expect(screen.getByTestId('partial-retention')).toHaveTextContent(
      'Raw data retention period exceeded',
    );
    expect(screen.getByTestId('partial-profile')).toHaveTextContent(
      'Profile not captured (sampling policy)',
    );
  });

  it('renders nothing for a complete raw result and the lost-profile reason in Korean', () => {
    const { container, rerender } = render(
      <PartialBanner meta={{ source: 'raw', truncated: false }} />,
      { wrapper },
    );
    expect(container).toBeEmptyDOMElement();
    rerender(<PartialBanner meta={{ source: '1h' }} profileState="lost" />);
    expect(screen.getByTestId('partial-banner')).toBeInTheDocument();
    const { container: ko } = render(
      <PartialBanner meta={{ source: '1m' }} profileState="lost" />,
      { wrapper: withI18n('ko') },
    );
    expect(ko).toHaveTextContent('1분 집계 데이터');
    expect(ko).toHaveTextContent('프로파일 유실(드롭)');
  });
});
