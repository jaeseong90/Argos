// SPDX-License-Identifier: Apache-2.0
//
// The transaction timeline (docs/15 §8.4, 05 §2.2-3, DSN-15-11): a 1,000-step profile keeps a bounded
// number of DOM rows (virtualized, no SVG or canvas), the transaction row shows the uninstrumented
// time as grey segments and an icon at every STACK capture, a step with children shows its self-time
// gaps, folded runs start collapsed and open with Right / Enter, Enter on a step selects it for the
// side panel, and a SUMMARY step is left out of the scale and has no bar (ADR-0010 C-141). The 1,000-step render time is recorded as a reference value (jsdom, not a browser).
// The summary's uninstrumented time equals the grey time of the transaction row also for
// overlapping, out-of-range and orphaned top-level steps (docs/15 §8.3), and a folded run of STACK
// captures is drawn as capture icons without a span or time total (docs/15 §8.4, DSN-15-12).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'N-14' }, { type: 'req', description: 'DSN-15-11' }
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { withI18n } from '../../components/testing/withI18n';
import { VIRTUAL_ROWS_OVERSCAN, WATERFALL_ROW_HEIGHT } from '../../components/waterfall';
import enTx from '../../locales/en/tx.json';

import {
  profileFixture,
  sqlStepFixture,
  stepFixture,
  transactionDetailFixture,
} from './data/testing/fixtures';
import type { TransactionDetail, TransactionStep } from './data/transactionApi';
import { profileTotal } from './model/timeline';
import { profileTree, setExpanded } from './model/tree';
import { SummaryBar } from './SummaryBar';
import { TreeTab } from './tabs/TreeTab';
import { TimelineView } from './TimelineView';

const wrapper = withI18n('en');

function Harness({
  detail,
  onSelect,
}: {
  readonly detail: TransactionDetail;
  readonly onSelect?: (step: TransactionStep | null) => void;
}) {
  const [roots] = useState(() => profileTree(detail.profile.steps));
  const [toggled, setToggled] = useState<ReadonlySet<string>>(new Set());
  return (
    <TimelineView
      detail={detail}
      roots={roots}
      toggled={toggled}
      onToggle={(node, expanded) => {
        setToggled((current) => setExpanded(current, node, expanded));
      }}
      onSelect={onSelect}
      label="Timeline"
    />
  );
}

/** 1,000 distinct METHOD steps (no folding) one after another. */
function thousandSteps(): TransactionDetail {
  const steps = Array.from({ length: 1000 }, (_, i) =>
    stepFixture({
      seq: i + 1,
      type: 'METHOD',
      start_offset_ms: i * 2,
      elapsed_ms: 2,
      name: untrusted(`com.shop.Step.m${String(i)}`),
      name_id: String(10_000 + i),
    }),
  );
  return transactionDetailFixture({
    elapsed_ms: 2000,
    profile: profileFixture({ steps, total_steps: 1000, self_time_ms: 0, self_time_ratio: 0 }),
  });
}

beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value(this: HTMLElement, options: ScrollToOptions) {
      this.scrollTop = options.top ?? 0;
      this.dispatchEvent(new Event('scroll'));
    },
  });
});

afterEach(() => {
  Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo');
});

describe('TimelineView virtualization (DSN-15-11, docs/15 §14)', () => {
  it('a 1,000-step transaction keeps at most viewport + overscan rows in the DOM', ({
    annotate,
  }) => {
    const detail = thousandSteps();
    const started = performance.now();
    render(<Harness detail={detail} />, { wrapper });
    const elapsed = performance.now() - started;
    const grid = screen.getByRole('treegrid', { name: 'Timeline' });
    // The transaction row plus 1,000 steps.
    expect(grid).toHaveAttribute('aria-rowcount', '1001');
    const rows = screen.getAllByTestId('virtual-row');
    expect(rows.length).toBeLessThanOrEqual(480 / WATERFALL_ROW_HEIGHT + 2 * VIRTUAL_ROWS_OVERSCAN);
    expect(document.querySelectorAll('svg circle, canvas')).toHaveLength(0);
    const viewport = grid.parentElement as HTMLElement;
    act(() => {
      viewport.scrollTop = 900 * WATERFALL_ROW_HEIGHT;
      viewport.dispatchEvent(new Event('scroll'));
    });
    const indices = screen
      .getAllByTestId('virtual-row')
      .map((r) => Number(r.getAttribute('data-row-index')));
    expect(indices[0]).toBe(900 - VIRTUAL_ROWS_OVERSCAN);
    expect(indices.length).toBeLessThanOrEqual(
      480 / WATERFALL_ROW_HEIGHT + 2 * VIRTUAL_ROWS_OVERSCAN,
    );
    void annotate(
      `1,000-step first render ${elapsed.toFixed(1)} ms (jsdom, reference only, INVALID as a measurement)`,
    );
  });
});

describe('TimelineView rows (05 §2.2-3)', () => {
  const detail = transactionDetailFixture({
    elapsed_ms: 100,
    profile: profileFixture({
      steps: [
        stepFixture({
          seq: 1,
          type: 'METHOD',
          start_offset_ms: 10,
          elapsed_ms: 60,
          name: untrusted('com.shop.Order.save'),
          name_id: '1',
        }),
        sqlStepFixture(2, { parent_seq: 1, start_offset_ms: 20, elapsed_ms: 10 }),
        sqlStepFixture(3, { parent_seq: 1, start_offset_ms: 30, elapsed_ms: 10 }),
        sqlStepFixture(4, { parent_seq: 1, start_offset_ms: 40, elapsed_ms: 10 }),
        stepFixture({
          seq: 5,
          type: 'STACK',
          start_offset_ms: 50,
          frames: [untrusted('a.B.c(B.java)')],
        }),
      ],
      self_time_ms: 40,
      self_time_ratio: 0.4,
    }),
  });

  it('the transaction row has grey uninstrumented segments and a STACK icon; steps show self gaps', () => {
    render(<Harness detail={detail} />, { wrapper });
    const root = screen.getByTestId('transaction-timeline-root');
    // [0,10) and [70,100) are not covered by the top-level step 10..70 (STACK is not work).
    expect(
      root.querySelectorAll('[data-testid="waterfall-segment"][data-kind="self"]'),
    ).toHaveLength(2);
    expect(
      root.querySelector('[data-testid="waterfall-marker"][data-kind="stack"]'),
    ).not.toBeNull();
    const rows = screen.getAllByTestId('waterfall-row');
    // METHOD 10..70 with SQL 20..50: gaps [10,20) and [50,70).
    expect(rows[0]?.querySelectorAll('[data-testid="waterfall-segment"]')).toHaveLength(2);
    // The STACK step is a marker row (no bar).
    const stackRow = rows.find((r) => r.querySelector('[data-type="stack"]') !== null);
    expect(stackRow?.querySelector('[data-testid="waterfall-bar"]')).toBeNull();
  });

  it('a SUMMARY step (ADR-0010 C-141) is left out of the scale and drawn without a bar, with its count and total', () => {
    const summarized = transactionDetailFixture({
      elapsed_ms: 1000,
      flags: { ...transactionDetailFixture().flags, summarized: true },
      profile: profileFixture({
        steps: [
          sqlStepFixture(1, { start_offset_ms: 100, elapsed_ms: 400 }),
          // start_offset 0, elapsed = the total of the 7 summarized SQL steps (longer than the tx).
          stepFixture({
            seq: 2,
            type: 'SUMMARY',
            start_offset_ms: 0,
            elapsed_ms: 1500,
            repeat: 7,
            name_id: '15837492011223344556',
            sql: untrusted('SELECT * FROM orders WHERE id = ?'),
            summary: { step_type: 'SQL', count: 7, total_ms: 1500, max_ms: 300, failures: 0 },
          }),
        ],
        self_time_ms: 600,
        self_time_ratio: 0.6,
      }),
    });
    expect(profileTotal(summarized)).toBe(1000);
    render(<Harness detail={summarized} />, { wrapper });
    // The scale is the response time: the transaction bar spans all of it.
    const rootBar = screen
      .getByTestId('transaction-timeline-root')
      .querySelector<HTMLElement>('[data-testid="waterfall-bar"]');
    expect(rootBar?.style.width).toBe('100%');
    const sqlRow = screen
      .getAllByTestId('waterfall-row')
      .find((r) => r.querySelector('[data-type="sql"]') !== null);
    expect(sqlRow?.querySelector<HTMLElement>('[data-testid="waterfall-bar"]')?.style.width).toBe(
      '40%',
    );
    const values = screen.getByTestId('transaction-summary-step');
    const summaryRow = values.closest('[data-testid="waterfall-row"]') as HTMLElement;
    expect(summaryRow).toHaveAttribute('data-variant', 'none');
    expect(summaryRow.querySelector('[data-testid="waterfall-bar"]')).toBeNull();
    expect(summaryRow.querySelector('[data-testid="waterfall-segment"]')).toBeNull();
    expect(values).toHaveAttribute('data-count', '7');
    expect(values).toHaveAttribute('data-total-ms', '1500');
    expect(values).toHaveTextContent(`× 7`);
    expect(values).toHaveTextContent(`${enTx.tree.colTotal} 1.5s`);
    expect(values).toHaveTextContent(`${enTx.tree.max} 300ms`);
  });

  it('the folded run starts collapsed, opens with Right and Enter selects a step', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<Harness detail={detail} onSelect={onSelect} />, { wrapper });
    const group = screen.getByTestId('transaction-group');
    expect(group).toHaveAttribute('data-count', '3');
    const groupRow = group.closest('[role="row"]') as HTMLElement;
    expect(groupRow).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getAllByTestId('virtual-row')).toHaveLength(4);
    groupRow.focus();
    await user.keyboard('{ArrowRight}');
    expect(groupRow).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByTestId('virtual-row')).toHaveLength(7);
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0]?.[0]).toMatchObject({ seq: 2, type: 'SQL' });
  });
});

/** The server's self_time_ms rule (TransactionDetailService): elapsed − Σ parent_seq 0 work steps. */
function serverSelfTime(elapsed: number, steps: readonly TransactionStep[]): number {
  const sum = steps
    .filter((s) => s.parent_seq === 0 && !['STACK', 'ERROR', 'SUMMARY'].includes(s.type))
    .reduce((total, s) => total + s.elapsed_ms, 0);
  return Math.max(0, elapsed - sum);
}

/** Length in ms of the grey self segments of a row (their CSS width is a share of the scale). */
function selfSegmentMs(row: HTMLElement, totalMs: number): number {
  return [
    ...row.querySelectorAll<HTMLElement>('[data-testid="waterfall-segment"][data-kind="self"]'),
  ].reduce((sum, segment) => sum + (Number.parseFloat(segment.style.width) / 100) * totalMs, 0);
}

describe('uninstrumented time: the summary value is the grey time of the transaction row (docs/15 §8.3)', () => {
  const method = (
    seq: number,
    start: number,
    elapsed: number,
    extra: Partial<TransactionStep> = {},
  ) =>
    stepFixture({
      seq,
      type: 'METHOD',
      start_offset_ms: start,
      elapsed_ms: elapsed,
      name: untrusted(`com.shop.M.m${String(seq)}`),
      name_id: String(seq),
      ...extra,
    });

  it.each([
    {
      name: 'overlapping SQL 0-600 and ASYNC 100-700 at the top level',
      steps: [
        sqlStepFixture(1, { start_offset_ms: 0, elapsed_ms: 600 }),
        method(2, 100, 600, { type: 'ASYNC' }),
      ],
      ms: 300,
    },
    {
      name: 'a top-level step 900-1200 ending after the transaction',
      steps: [method(1, 0, 700), method(2, 900, 300, { type: 'HTTP', name: untrusted('pay') })],
      ms: 200,
    },
    {
      name: 'a step under a missing parent (parent_seq 5)',
      steps: [method(1, 0, 500), method(7, 600, 300, { parent_seq: 5 })],
      ms: 200,
    },
    {
      name: 'disjoint top-level steps (server value and timeline agree)',
      steps: [method(1, 0, 400), method(2, 500, 300)],
      ms: 300,
    },
  ])('$name', ({ steps, ms }) => {
    const server = serverSelfTime(1000, steps);
    const detail = transactionDetailFixture({
      elapsed_ms: 1000,
      profile: profileFixture({ steps, self_time_ms: server, self_time_ratio: server / 1000 }),
    });
    render(
      <>
        <SummaryBar detail={detail} />
        <Harness detail={detail} />
      </>,
      { wrapper },
    );
    const grey = selfSegmentMs(
      screen.getByTestId('transaction-timeline-root'),
      profileTotal(detail),
    );
    expect(grey).toBeCloseTo(ms, 6);
    const value = screen.getByTestId('transaction-uninstrumented');
    expect(Number(value.getAttribute('data-ms'))).toBeCloseTo(grey, 6);
    // The 20 % highlight of the summary follows the same value (200 ms of 1,000 ms is highlighted).
    expect(value).toHaveAttribute('data-highlight', String(Math.round(grey) / 1000 >= 0.2));
    if (server === ms) {
      expect(value).toHaveAttribute('data-source', 'server');
      expect(value).not.toHaveAttribute('data-server-ms');
    } else {
      expect(value).toHaveAttribute('data-source', 'client');
      expect(value).toHaveAttribute('data-server-ms', String(server));
      expect(screen.getByTestId('transaction-uninstrumented-server')).toBeInTheDocument();
    }
  });

  it('with no top-level work step the whole response time is grey, as the summary says', () => {
    const steps = [
      stepFixture({ seq: 1, type: 'STACK', start_offset_ms: 100, frames: [untrusted('a.B.c()')] }),
    ];
    const detail = transactionDetailFixture({
      elapsed_ms: 1000,
      profile: profileFixture({ steps, self_time_ms: 1000, self_time_ratio: 1 }),
    });
    render(
      <>
        <SummaryBar detail={detail} />
        <Harness detail={detail} />
      </>,
      { wrapper },
    );
    expect(selfSegmentMs(screen.getByTestId('transaction-timeline-root'), 1000)).toBeCloseTo(
      1000,
      6,
    );
    expect(screen.getByTestId('transaction-uninstrumented')).toHaveAttribute('data-ms', '1000');
    expect(screen.getByTestId('transaction-uninstrumented')).toHaveAttribute(
      'data-source',
      'server',
    );
  });
});

describe('a folded run of STACK captures (DSN-15-12) is drawn as captures, not as a span', () => {
  const detail = transactionDetailFixture({
    elapsed_ms: 10_000,
    profile: profileFixture({
      steps: [
        stepFixture({
          seq: 1,
          type: 'METHOD',
          start_offset_ms: 0,
          elapsed_ms: 9000,
          name: untrusted('com.shop.Batch.run'),
          name_id: '1',
        }),
        ...[1000, 4000, 7000].map((offset, i) =>
          stepFixture({
            seq: i + 2,
            parent_seq: 1,
            type: 'STACK',
            start_offset_ms: offset,
            frames: [untrusted('java.net.Socket.read(Socket.java)')],
          }),
        ),
      ],
      self_time_ms: 1000,
      self_time_ratio: 0.1,
    }),
  });

  it('the group row has no bar and a capture icon at each member offset; it shows the count, no time', () => {
    render(<Harness detail={detail} />, { wrapper });
    const group = screen.getByTestId('transaction-group');
    expect(group).toHaveAttribute('data-count', '3');
    const row = group.closest('[data-testid="waterfall-row"]') as HTMLElement;
    expect(row.querySelector('[data-testid="waterfall-bar"]')).toBeNull();
    const markers = [
      ...row.querySelectorAll<HTMLElement>('[data-testid="waterfall-marker"][data-kind="stack"]'),
    ];
    expect(markers.map((m) => m.style.left)).toEqual(['10%', '40%', '70%']);
    expect(screen.getByTestId('transaction-group-points')).toHaveTextContent('× 3');
    expect(row.textContent).not.toMatch(/\d\s?ms\b/);
  });

  it('the call tree shows the run with its count and no time total', () => {
    const roots = profileTree(detail.profile.steps);
    render(
      <TreeTab
        detail={detail}
        roots={roots}
        toggled={new Set()}
        onToggle={vi.fn()}
        selectedSeq={null}
        onSelect={vi.fn()}
      />,
      { wrapper },
    );
    const row = screen
      .getByTestId('transaction-group')
      .closest('[data-testid="transaction-tree-row"]') as HTMLElement;
    expect(row.querySelector('[data-testid="transaction-tree-no-time"]')).not.toBeNull();
    expect(row.textContent).not.toMatch(/\d\s?ms\b/);
  });
});

describe('a folded run of ERROR records (DSN-15-12) is drawn as records, not as a span', () => {
  const detail = transactionDetailFixture({
    elapsed_ms: 10_000,
    error: true,
    profile: profileFixture({
      steps: [
        stepFixture({
          seq: 1,
          type: 'METHOD',
          start_offset_ms: 0,
          elapsed_ms: 9000,
          name: untrusted('com.shop.Batch.run'),
          name_id: '1',
        }),
        ...[2000, 5000, 8000].map((offset, i) =>
          stepFixture({
            seq: i + 2,
            parent_seq: 1,
            type: 'ERROR',
            start_offset_ms: offset,
            name: untrusted('java.io.IOException'),
            name_id: '9',
            frames: [untrusted('java.net.Socket.read(Socket.java)')],
            error: true,
          }),
        ),
      ],
      self_time_ms: 1000,
      self_time_ratio: 0.1,
    }),
  });

  it('the group row has no bar and an error icon at each member offset, with the count only', () => {
    render(<Harness detail={detail} />, { wrapper });
    const group = screen.getByTestId('transaction-group');
    expect(group).toHaveAttribute('data-count', '3');
    const row = group.closest('[data-testid="waterfall-row"]') as HTMLElement;
    expect(row.querySelector('[data-testid="waterfall-bar"]')).toBeNull();
    const markers = [
      ...row.querySelectorAll<HTMLElement>('[data-testid="waterfall-marker"][data-kind="error"]'),
    ];
    expect(markers.map((m) => m.style.left)).toEqual(['20%', '50%', '80%']);
    expect(
      row.querySelectorAll('[data-testid="waterfall-marker"][data-kind="stack"]'),
    ).toHaveLength(0);
    expect(screen.getAllByRole('img', { name: enTx.timeline.errorMarker })).toHaveLength(3);
    expect(screen.getByTestId('transaction-group-points')).toHaveTextContent('× 3');
    expect(row.textContent).not.toMatch(/\d\s?ms\b/);
  });
});
