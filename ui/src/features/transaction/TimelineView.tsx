// SPDX-License-Identifier: Apache-2.0
//
// The transaction timeline (docs/15 §8.4, 05 §2.2-3, DSN-15-11): a DOM virtual list of 24 px rows
// (no SVG or canvas), the transaction first and then the folded call tree, each row a bar at
// start / total and elapsed / total with a minimum width, indented by depth, coloured by step type
// with a type badge, grey self-time segments between children, a camera icon at every STACK
// capture, and a tooltip with time, share and detail. Folded runs (DSN-15-12) are collapsed rows
// that expand with Right / Enter; a step row opens the side panel. A SUMMARY step (summary mode,
// ADR-0010 C-141) is no interval of the time axis: its row has no bar (variant `none`), the kind
// and count are in its name, and the count, total and longest of the summarized steps follow it.
// The compare view reuses it with a shared scale.
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../components/SafeText';
import { ValueText } from '../../components/ValueText';
import {
  shareOf,
  stepTone,
  TimeScale,
  VirtualRows,
  WaterfallRow,
  type WaterfallMarker,
  type WaterfallSegment,
} from '../../components/waterfall';

import type { TransactionDetail, TransactionStep } from './data/transactionApi';
import { isPointEventType, nodeSelfTime, type Interval } from './model/selfTime';
import { isSummaryStep, profileTotal, timelineItems, type TimelineItem } from './model/timeline';
import type { TreeNode } from './model/tree';
import { StepName, StepTypeBadge } from './parts';

export interface TimelineViewProps {
  readonly detail: TransactionDetail;
  readonly roots: readonly TreeNode[];
  readonly toggled: ReadonlySet<string>;
  readonly onToggle: (node: TreeNode, expanded: boolean) => void;
  /** Scale length; default the profile's own (compare view: the longer of the two). */
  readonly totalMs?: number;
  readonly selectedSeq?: number | null;
  /** A step row was activated (Enter or click); null for the transaction row. */
  readonly onSelect?: (step: TransactionStep | null) => void;
  /** Accessible name of the list. */
  readonly label: string;
  readonly maxHeight?: number;
  readonly labelWidth?: number;
  readonly 'data-testid'?: string;
}

function selfSegments(gaps: readonly Interval[]): WaterfallSegment[] {
  return gaps.map((gap) => ({ startMs: gap.start, elapsedMs: gap.end - gap.start, kind: 'self' }));
}

export function TimelineView({
  detail,
  roots,
  toggled,
  onToggle,
  totalMs,
  selectedSeq = null,
  onSelect,
  label,
  maxHeight,
  labelWidth,
  'data-testid': testId = 'transaction-timeline',
}: TimelineViewProps) {
  const { t } = useTranslation(['common', 'tx']);
  const items = timelineItems(detail, roots, toggled);
  const total = totalMs ?? profileTotal(detail);
  const stackLabel = t('tx:timeline.stackMarker');
  const errorLabel = t('tx:timeline.errorMarker');

  const renderRow = (item: TimelineItem) => {
    if (item.kind === 'root') {
      const markers: WaterfallMarker[] = item.stackOffsets.map((offsetMs) => ({
        offsetMs,
        kind: 'stack',
        label: stackLabel,
      }));
      return (
        <WaterfallRow
          depth={0}
          icon={<span className="tx-type tx-type--tx">{t('tx:timeline.transactionBadge')}</span>}
          name={<SafeText value={detail.service} />}
          trailing={<ValueText value={item.elapsedMs} unit="ms" />}
          startMs={0}
          elapsedMs={item.elapsedMs}
          totalMs={total}
          tone="tx"
          segments={selfSegments(item.gaps)}
          markers={markers}
          error={detail.error}
          data-testid="transaction-timeline-root"
        />
      );
    }
    const { node, depth } = item.row;
    if (node.kind === 'group') {
      // A run of point events (STACK captures, ERROR records: elapsed 0) is no interval: no bar from
      // the first to the last member and no time total, only the number of members, and an icon at
      // every member (a camera per capture, docs/15 §8.4; an error cross per record).
      const point = isPointEventType(node.type);
      const stackRun = node.type === 'STACK';
      const errorRun = node.type === 'ERROR';
      const markerKind = errorRun ? 'error' : 'stack';
      const markerLabel = errorRun ? errorLabel : stackLabel;
      return (
        <WaterfallRow
          depth={depth + 1}
          icon={<StepTypeBadge type={node.type} />}
          name={
            <span className="tx-group-name" data-testid="transaction-group" data-count={node.count}>
              {point ? null : (
                <span className="tx-group-name__count">
                  {t('tx:tree.groupCount', { count: node.count })}
                </span>
              )}
              <StepName step={node.first} />
            </span>
          }
          trailing={
            point ? (
              <span data-testid="transaction-group-points" data-count={node.count}>
                {t('tx:tree.groupCount', { count: node.count })}
              </span>
            ) : (
              <ValueText value={node.sumMs} unit="ms" />
            )
          }
          startMs={node.startMs}
          elapsedMs={point ? 0 : node.elapsedMs}
          totalMs={total}
          tone={stepTone(node.type)}
          variant={isSummaryStep(node) ? 'none' : stackRun || errorRun ? 'marker' : 'bar'}
          markerLabel={markerLabel}
          markerKind={markerKind}
          markers={
            stackRun || errorRun
              ? node.children.slice(1).map((member): WaterfallMarker => ({
                  offsetMs: member.step.start_offset_ms,
                  kind: markerKind,
                  label: markerLabel,
                }))
              : undefined
          }
          error={node.errors > 0}
        />
      );
    }
    const step = node.step;
    const isStack = step.type.toUpperCase() === 'STACK';
    if (isSummaryStep(step)) {
      // The summary's count, total and longest (proto/README.md profile_step SUMMARY row); the step
      // columns repeat_count / elapsed / fetch carry them when the summary object is absent.
      const count = step.summary?.count ?? step.repeat;
      return (
        <WaterfallRow
          depth={depth + 1}
          icon={<StepTypeBadge type={step.type} />}
          name={<StepName step={step} />}
          trailing={
            <span
              className="tx-pair"
              data-testid="transaction-summary-step"
              data-count={count}
              data-total-ms={step.summary?.total_ms ?? step.elapsed_ms}
              data-max-ms={step.summary?.max_ms ?? ''}
            >
              <span>{t('tx:panel.repeatValue', { count })}</span>
              <span>
                {t('tx:tree.colTotal')}{' '}
                <ValueText value={step.summary?.total_ms ?? step.elapsed_ms} unit="ms" />
              </span>
              <span>
                {t('tx:tree.max')} <ValueText value={step.summary?.max_ms ?? null} unit="ms" />
              </span>
            </span>
          }
          startMs={step.start_offset_ms}
          elapsedMs={step.elapsed_ms}
          totalMs={total}
          tone={stepTone(step.type)}
          variant="none"
          error={step.error}
        />
      );
    }
    return (
      <WaterfallRow
        depth={depth + 1}
        icon={<StepTypeBadge type={step.type} />}
        name={<StepName step={step} />}
        trailing={
          isStack ? undefined : (
            <span className="tx-pair">
              {step.repeat > 1 ? (
                <span>{t('tx:panel.repeatValue', { count: step.repeat })}</span>
              ) : null}
              <ValueText value={step.elapsed_ms} unit="ms" />
            </span>
          )
        }
        startMs={step.start_offset_ms}
        elapsedMs={step.elapsed_ms}
        totalMs={total}
        tone={stepTone(step.type)}
        variant={isStack ? 'marker' : 'bar'}
        markerLabel={stackLabel}
        segments={selfSegments(item.gaps)}
        error={step.error}
      />
    );
  };

  const renderTooltip = (item: TimelineItem) => {
    if (item.kind === 'root') {
      return (
        <span className="tx-tip">
          <span>{t('tx:timeline.transactionBadge')}</span>
          <ValueText value={item.elapsedMs} unit="ms" />
          {item.gaps.length > 0 ? (
            <span>
              {t('tx:timeline.uninstrumentedTip')}{' '}
              <ValueText value={item.gaps.reduce((s, g) => s + (g.end - g.start), 0)} unit="ms" />
            </span>
          ) : null}
        </span>
      );
    }
    const node = item.row.node;
    if (node.kind === 'step' && isSummaryStep(node.step)) {
      // No start on the time axis: the summarized steps' count, total and longest only.
      const summary = node.step.summary;
      return (
        <span className="tx-tip" data-testid="transaction-timeline-tip">
          <span>
            {t('tx:panel.summaryCount')}{' '}
            <ValueText value={summary?.count ?? node.step.repeat} unit="count" />
          </span>
          <span>
            {t('tx:panel.summaryTotal')}{' '}
            <ValueText value={summary?.total_ms ?? node.step.elapsed_ms} unit="ms" />
          </span>
          <span>
            {t('tx:panel.summaryMax')} <ValueText value={summary?.max_ms ?? null} unit="ms" />
          </span>
        </span>
      );
    }
    const self = node.children.length > 0 ? nodeSelfTime(node) : null;
    if (node.kind === 'group' && isPointEventType(node.type)) {
      // A run of point events: where it starts and how many, no time.
      return (
        <span className="tx-tip" data-testid="transaction-timeline-tip">
          <span>
            {t('tx:timeline.startTip')} <ValueText value={node.startMs} unit="ms" />
          </span>
          <span>{t('tx:tree.groupTip', { count: node.count })}</span>
        </span>
      );
    }
    return (
      <span className="tx-tip" data-testid="transaction-timeline-tip">
        <span>
          {t('tx:timeline.startTip')} <ValueText value={node.startMs} unit="ms" />
        </span>
        <span>
          {t('tx:timeline.elapsedTip')}{' '}
          <ValueText value={node.kind === 'group' ? node.sumMs : node.elapsedMs} unit="ms" /> (
          <ValueText
            value={shareOf(node.kind === 'group' ? node.sumMs : node.elapsedMs, detail.elapsed_ms)}
            unit="ratio"
          />
          )
        </span>
        {node.kind === 'group' ? (
          <span>
            {t('tx:tree.groupTip', { count: node.count })} {t('tx:tree.avg')}{' '}
            <ValueText value={node.avgMs} unit="ms" /> {t('tx:tree.max')}{' '}
            <ValueText value={node.maxMs} unit="ms" />
          </span>
        ) : null}
        {self === null ? null : (
          <span>
            {t('tx:timeline.selfTip')} <ValueText value={self} unit="ms" />
          </span>
        )}
      </span>
    );
  };

  return (
    <VirtualRows
      items={items}
      getKey={(item) => item.key}
      label={label}
      header={<TimeScale totalMs={total} />}
      renderRow={renderRow}
      renderTooltip={renderTooltip}
      getMeta={(item) => {
        if (item.kind === 'root') {
          return { level: 1 };
        }
        const { row } = item;
        return {
          level: row.depth + 2,
          expanded: row.hasChildren ? row.expanded : undefined,
          selected: row.node.kind === 'step' && row.node.step.seq === selectedSeq,
        };
      }}
      onActivate={(item) => {
        if (item.kind === 'root') {
          onSelect?.(null);
          return;
        }
        const node = item.row.node;
        if (node.kind === 'group') {
          onToggle(node, !item.row.expanded);
        } else {
          onSelect?.(node.step);
        }
      }}
      onExpandedChange={(item, _index, expanded) => {
        if (item.kind === 'node') {
          onToggle(item.row.node, expanded);
        }
      }}
      maxHeight={maxHeight}
      labelWidth={labelWidth}
      data-testid={testId}
    />
  );
}
