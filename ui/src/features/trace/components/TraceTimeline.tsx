// SPDX-License-Identifier: Apache-2.0
//
// The trace tree (left) and timeline (right) of docs/15 §9.5 / 05 §2.8 in one DOM virtual list
// (DSN-15-11, WAI-ARIA treegrid): one row per transaction (app (env) · service, instance, version,
// status, error, elapsed), the caller's HTTP step drawn as its own row right above each callee
// with the grey network / wait parts (D-05), external calls to uninstrumented systems and the
// missing-node placeholders with the server's reason. Critical-path rows have a bold outline.
//
// Keys: ↑↓ Home End PageUp PageDown move, → ← expand / collapse, Enter opens: a transaction its
// detail (with the ts hint), a caller step or an external call the caller's detail on the ext tab,
// a "not collected yet" placeholder queries again. The mouse targets inside a row (caret, app
// link, query again) are out of the tab order; the node panel below the list offers the same
// actions to the keyboard for the focused row.
import { useMemo, useState, type MouseEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { safeHref } from '../../../components/safeHref';
import { SafeText } from '../../../components/SafeText';
import {
  TimeScale,
  VirtualRows,
  WATERFALL_INDENT_PX,
  WaterfallRow,
  type WaterfallSegment,
} from '../../../components/waterfall';
import { formatDuration } from '../../../utils/format';
import {
  flattenTraceTree,
  rowInterval,
  waitIntervals,
  type CallRow,
  type ExternalRow,
  type MissingRow,
  type TraceRow,
  type TraceTree,
  type TxRow,
} from '../model/traceTree';

import { MissingLabel } from './labels';
import { TraceNodePanel } from './TraceNodePanel';
import { TraceRowTooltip } from './TraceRowTooltip';

/** Width of the label column (tree side) in px: name, instance, version, status, time. */
export const TRACE_LABEL_WIDTH = 640;

export interface TraceLinks {
  /** The detail route of a transaction with the ts hint (and an optional tab). */
  readonly detail: (appId: number, txId: string, ts: string, tab?: 'ext') => string;
  /** The dashboard of an application for the trace's time range. */
  readonly dashboard: (appId: number) => string;
}

export interface TraceTimelineProps {
  readonly tree: TraceTree;
  readonly links: TraceLinks;
  readonly onNavigate: (href: string) => void;
  /** "Query again" of the not-collected-yet placeholders (refetch). */
  readonly onQueryAgain: () => void;
  readonly fetching: boolean;
}

/** Stops a click inside a row from activating the row; plain clicks navigate in the app. */
function inRowLink(href: string, onNavigate: (href: string) => void) {
  return (event: MouseEvent<HTMLAnchorElement>): void => {
    event.stopPropagation();
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    onNavigate(href);
  };
}

function Caret({
  row,
  onToggle,
}: {
  readonly row: TxRow | MissingRow;
  readonly onToggle: (key: string) => void;
}) {
  const { t } = useTranslation(['common', 'trace']);
  if (row.expanded === undefined) {
    return <span className="trace-caret trace-caret--leaf" aria-hidden="true" />;
  }
  return (
    <button
      type="button"
      className="trace-caret"
      tabIndex={-1}
      aria-label={row.expanded ? t('trace:row.collapse') : t('trace:row.expand')}
      aria-expanded={row.expanded}
      data-testid="trace-row-toggle"
      onClick={(event) => {
        event.stopPropagation();
        onToggle(row.key);
      }}
    ></button>
  );
}

/** The fixed columns after the name: instance, version, status (+ error), time. */
function Columns({
  instance,
  version,
  status,
  elapsed,
}: {
  readonly instance?: ReactNode;
  readonly version?: ReactNode;
  readonly status?: ReactNode;
  readonly elapsed?: ReactNode;
}) {
  return (
    <span className="trace-cols">
      <span className="trace-col trace-col--instance">{instance}</span>
      <span className="trace-col trace-col--version">{version}</span>
      <span className="trace-col trace-col--status">{status}</span>
      <span className="trace-col trace-col--elapsed">{elapsed}</span>
    </span>
  );
}

function relative(tree: TraceTree, row: TraceRow): { startMs: number; elapsedMs: number } {
  const interval = rowInterval(row);
  return interval === null
    ? { startMs: 0, elapsedMs: 0 }
    : { startMs: interval.startMs - tree.originMs, elapsedMs: interval.elapsedMs };
}

function TxCells({
  row,
  tree,
  links,
  onNavigate,
  onToggle,
}: {
  readonly row: TxRow;
  readonly tree: TraceTree;
  readonly links: TraceLinks;
  readonly onNavigate: (href: string) => void;
  readonly onToggle: (key: string) => void;
}) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const { item } = row;
  const node = item.node;
  const dashboard = links.dashboard(node.app_id);
  const { startMs, elapsedMs } = relative(tree, row);
  return (
    <WaterfallRow
      depth={row.level - 1}
      icon={<Caret row={row} onToggle={onToggle} />}
      name={
        <span
          className="trace-node"
          data-testid="trace-node"
          data-kind="tx"
          data-tx-id={node.tx_id}
          data-app-id={node.app_id}
          data-critical={item.critical ? 'true' : 'false'}
          data-root={item.isRoot ? 'true' : 'false'}
          data-error={node.error ? 'true' : 'false'}
          data-profile={item.profileState}
        >
          {item.critical ? (
            <span
              className="trace-icon trace-icon--critical"
              role="img"
              aria-label={t('trace:row.critical')}
              data-testid="trace-node-critical"
            ></span>
          ) : null}
          <a
            className="trace-node__app"
            href={safeHref(dashboard)}
            tabIndex={-1}
            onClick={inRowLink(dashboard, onNavigate)}
            data-testid="trace-node-app"
          >
            <SafeText value={node.app} />
          </a>
          <span className="trace-node__env trace-env">
            <SafeText value={node.env} data-testid="trace-node-env" />
          </span>
          <span className="trace-sep" aria-hidden="true" />
          <SafeText
            value={node.service}
            className="trace-node__service"
            data-testid="trace-node-service"
          />
          {item.profileState === 'sampled_out' || item.profileState === 'lost' ? (
            <span
              className="trace-badge trace-badge--muted"
              title={
                item.profileState === 'lost'
                  ? t('common:component.partial.profileLost')
                  : t('common:component.partial.profileSampledOut')
              }
              data-testid="trace-node-profile"
            >
              {item.profileState === 'lost'
                ? t('trace:row.profileLost')
                : t('trace:row.profileSampledOut')}
            </span>
          ) : null}
          {item.callerAbsent ? (
            <span className="trace-badge trace-badge--warn" data-testid="trace-node-caller-absent">
              {t('trace:row.callerAbsent')}
            </span>
          ) : null}
        </span>
      }
      trailing={
        <Columns
          instance={<SafeText value={node.instance} data-testid="trace-node-instance" />}
          version={<SafeText value={node.version} data-testid="trace-node-version" />}
          status={
            <>
              <span data-testid="trace-node-status">{node.status}</span>
              {node.error ? (
                <span className="trace-badge trace-badge--error" data-testid="trace-node-error">
                  {t('trace:row.error')}
                </span>
              ) : null}
            </>
          }
          elapsed={
            <span data-testid="trace-node-elapsed" data-ms={node.elapsed_ms}>
              {formatDuration(node.elapsed_ms, locale)}
            </span>
          }
        />
      }
      startMs={startMs}
      elapsedMs={elapsedMs}
      totalMs={tree.totalMs}
      tone="tx"
      emphasis={item.critical ? 'critical' : undefined}
      error={node.error}
      data-testid="trace-row-tx"
    />
  );
}

function CallCells({ row, tree }: { readonly row: CallRow; readonly tree: TraceTree }) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const { gap, item } = row;
  const { startMs, elapsedMs } = relative(tree, row);
  const segments: WaterfallSegment[] = waitIntervals(item).map((w) => ({
    startMs: w.startMs - tree.originMs,
    elapsedMs: w.elapsedMs,
    kind: 'wait',
  }));
  return (
    <WaterfallRow
      depth={row.level - 1}
      icon={<span className="trace-glyph trace-glyph--call" aria-hidden="true" />}
      name={
        <span
          className="trace-node trace-node--call"
          data-testid="trace-call"
          data-callee-tx-id={item.node.tx_id}
          data-caller-ms={gap.callerStepMs}
          data-callee-ms={gap.calleeMs}
          data-wait-ms={gap.waitMs ?? undefined}
          data-clock-offset={gap.clockOffset ? 'true' : 'false'}
          data-critical={row.critical ? 'true' : 'false'}
        >
          <span className="trace-node__label">{t('trace:row.call')}</span>
          <SafeText value={item.node.app} className="trace-node__service" />
          {gap.clockOffset ? (
            <span className="trace-badge trace-badge--warn" data-testid="trace-call-clock-offset">
              <span className="trace-glyph trace-glyph--warn" aria-hidden="true" />
              {t('trace:row.clockOffset', {
                caller: formatDuration(gap.callerStepMs, locale),
                callee: formatDuration(gap.calleeMs, locale),
              })}
            </span>
          ) : (
            <span className="trace-call__wait" data-testid="trace-call-wait">
              {t('trace:row.wait', { value: formatDuration(gap.waitMs ?? 0, locale) })}
            </span>
          )}
        </span>
      }
      trailing={
        <Columns
          elapsed={
            <span data-testid="trace-call-elapsed" data-ms={gap.callerStepMs}>
              {formatDuration(gap.callerStepMs, locale)}
            </span>
          }
        />
      }
      startMs={startMs}
      elapsedMs={elapsedMs}
      totalMs={tree.totalMs}
      tone="http"
      segments={segments}
      emphasis={row.critical ? 'critical' : undefined}
      data-testid="trace-row-call"
    />
  );
}

function ExternalCells({ row, tree }: { readonly row: ExternalRow; readonly tree: TraceTree }) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const external = row.item.external;
  const { startMs, elapsedMs } = relative(tree, row);
  return (
    <WaterfallRow
      depth={row.level - 1}
      icon={<span className="trace-glyph trace-glyph--external" aria-hidden="true" />}
      name={
        <span
          className="trace-node trace-node--external"
          data-testid="trace-external"
          data-caller-tx-id={external.caller_tx_id}
          data-error={external.error ? 'true' : 'false'}
        >
          <span className="trace-badge trace-badge--external">{t('trace:row.external')}</span>
          <SafeText value={external.host} mono data-testid="trace-external-host" />
          <SafeText
            value={external.path_pattern}
            mono
            className="trace-node__service"
            data-testid="trace-external-path"
          />
        </span>
      }
      trailing={
        <Columns
          status={
            <>
              <span data-testid="trace-external-status">
                {external.status ?? t('trace:row.noResponse')}
              </span>
              {external.error ? (
                <span className="trace-badge trace-badge--error" data-testid="trace-external-error">
                  {t('trace:row.error')}
                </span>
              ) : null}
            </>
          }
          elapsed={
            <span data-testid="trace-external-elapsed" data-ms={external.elapsed_ms}>
              {formatDuration(external.elapsed_ms, locale)}
            </span>
          }
        />
      }
      startMs={startMs}
      elapsedMs={elapsedMs}
      totalMs={tree.totalMs}
      tone="external"
      dashed
      error={external.error}
      data-testid="trace-row-external"
    />
  );
}

function MissingCells({
  row,
  onToggle,
  onQueryAgain,
  fetching,
}: {
  readonly row: MissingRow;
  readonly onToggle: (key: string) => void;
  readonly onQueryAgain: () => void;
  readonly fetching: boolean;
}) {
  const { t } = useTranslation(['common', 'trace']);
  const { missing } = row.item;
  const notYet = missing.reason === 'not_yet_collected';
  return (
    <div className="waterfall-row" data-testid="trace-row-missing" data-depth={row.level - 1}>
      <div
        role="gridcell"
        className="waterfall-row__label"
        style={{ paddingLeft: `${String(4 + (row.level - 1) * WATERFALL_INDENT_PX)}px` }}
      >
        <span className="waterfall-row__type">
          <Caret row={row} onToggle={onToggle} />
        </span>
        <span
          className="waterfall-row__name trace-node trace-node--missing"
          data-testid="trace-missing"
          data-reason={missing.reason}
          data-role={row.item.role}
          data-tx-id={missing.tx_id}
        >
          <span
            className={`trace-glyph ${notYet ? 'trace-glyph--pending' : 'trace-glyph--unknown'}`}
            aria-hidden="true"
          />
          <span className="trace-node__label">
            <MissingLabel row={row} />
          </span>
          {notYet ? (
            <button
              type="button"
              className="trace-inline-button"
              tabIndex={-1}
              disabled={fetching}
              data-testid="trace-missing-query-again"
              onClick={(event) => {
                event.stopPropagation();
                onQueryAgain();
              }}
            >
              {t('trace:row.queryAgain')}
            </button>
          ) : null}
        </span>
      </div>
      <div role="gridcell" className="waterfall-row__track trace-missing__track">
        <span className="trace-missing__note">{t('trace:row.noTime')}</span>
      </div>
    </div>
  );
}

/** The transaction a row opens on Enter or click, or null. */
export function rowTarget(row: TraceRow, links: TraceLinks): string | null {
  switch (row.kind) {
    case 'tx':
      return links.detail(row.item.node.app_id, row.item.node.tx_id, row.item.node.ts);
    case 'call': {
      const caller = row.item.caller;
      return caller === null ? null : links.detail(caller.app_id, caller.tx_id, caller.ts, 'ext');
    }
    case 'external': {
      const caller = row.item.caller;
      return caller === null ? null : links.detail(caller.app_id, caller.tx_id, caller.ts, 'ext');
    }
    case 'missing':
      return null;
  }
}

export function TraceTimeline({
  tree,
  links,
  onNavigate,
  onQueryAgain,
  fetching,
}: TraceTimelineProps) {
  const { t } = useTranslation(['common', 'trace']);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [focusedKey, setFocusedKey] = useState<string | null>(null);
  const rows = useMemo(() => flattenTraceTree(tree, collapsed), [tree, collapsed]);
  const found = focusedKey === null ? -1 : rows.findIndex((row) => row.key === focusedKey);
  const focusedIndex = found >= 0 ? found : null;
  const rootIndex = rows.findIndex((row) => row.kind === 'tx' && row.item.isRoot);
  const panelRow = rows[focusedIndex ?? (rootIndex >= 0 ? rootIndex : 0)];

  const toggle = (key: string, expanded?: boolean): void => {
    setCollapsed((current) => {
      const next = new Set(current);
      const open = expanded ?? next.has(key);
      if (open) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const activate = (row: TraceRow): void => {
    if (row.kind === 'missing') {
      if (row.item.missing.reason === 'not_yet_collected' && !fetching) {
        onQueryAgain();
      }
      return;
    }
    const target = rowTarget(row, links);
    if (target !== null) {
      onNavigate(target);
    }
  };

  return (
    <div className="trace-timeline" data-testid="trace-timeline">
      <VirtualRows<TraceRow>
        items={rows}
        getKey={(row) => row.key}
        label={t('trace:list.label')}
        labelWidth={TRACE_LABEL_WIDTH}
        rowTestId="trace-row"
        data-testid="trace-rows"
        focusedIndex={focusedIndex}
        onFocusedIndexChange={(index) => {
          setFocusedKey(rows[index]?.key ?? null);
        }}
        getMeta={(row) => ({
          level: row.level,
          expanded: row.kind === 'tx' || row.kind === 'missing' ? row.expanded : undefined,
          selected: panelRow !== undefined && row.key === panelRow.key,
        })}
        onActivate={activate}
        onExpandedChange={(row, _index, expanded) => {
          toggle(row.key, expanded);
        }}
        renderTooltip={(row) => <TraceRowTooltip row={row} tree={tree} />}
        header={
          <TimeScale
            totalMs={tree.totalMs}
            label={
              <span className="trace-cols trace-cols--header" data-testid="trace-list-header">
                <span className="trace-col trace-col--name">{t('trace:list.column.node')}</span>
                <span className="trace-col trace-col--instance">
                  {t('trace:list.column.instance')}
                </span>
                <span className="trace-col trace-col--version">
                  {t('trace:list.column.version')}
                </span>
                <span className="trace-col trace-col--status">{t('trace:list.column.status')}</span>
                <span className="trace-col trace-col--elapsed">
                  {t('trace:list.column.elapsed')}
                </span>
              </span>
            }
          />
        }
        renderRow={(row) => {
          switch (row.kind) {
            case 'tx':
              return (
                <TxCells
                  row={row}
                  tree={tree}
                  links={links}
                  onNavigate={onNavigate}
                  onToggle={toggle}
                />
              );
            case 'call':
              return <CallCells row={row} tree={tree} />;
            case 'external':
              return <ExternalCells row={row} tree={tree} />;
            case 'missing':
              return (
                <MissingCells
                  row={row}
                  onToggle={toggle}
                  onQueryAgain={onQueryAgain}
                  fetching={fetching}
                />
              );
          }
        }}
      />
      {panelRow === undefined ? null : (
        <TraceNodePanel
          row={panelRow}
          tree={tree}
          links={links}
          onNavigate={onNavigate}
          onQueryAgain={onQueryAgain}
          fetching={fetching}
        />
      )}
    </div>
  );
}
