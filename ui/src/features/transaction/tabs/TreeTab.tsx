// SPDX-License-Identifier: Apache-2.0
//
// The call tree tab (05 §2.2-4, docs/15 §8.2, DSN-15-12): the steps as a tree with runs of three or
// more equal consecutive siblings folded into one row (count, executions, total, average, maximum;
// collapsed by default, expandable), batch chunk repetitions folded by the same rule, and summary
// mode said when the agent folded later steps itself (C-69). Each row shows its executions, total
// time, self time and share of the response time. A DOM virtual list (DSN-15-11) with the treegrid
// keys: Up/Down/Home/End/PageUp/PageDown move, Right/Left expand and collapse, Enter opens a step.
import { useTranslation } from 'react-i18next';

import { ValueText } from '../../../components/ValueText';
import { shareOf, VirtualRows } from '../../../components/waterfall';
import type { TransactionDetail, TransactionStep } from '../data/transactionApi';
import { isPointEventType, nodeSelfTime } from '../model/selfTime';
import { visibleRows, type TreeNode, type TreeRow } from '../model/tree';
import { StepName, StepTypeBadge } from '../parts';

export interface TreeTabProps {
  readonly detail: TransactionDetail;
  readonly roots: readonly TreeNode[];
  readonly toggled: ReadonlySet<string>;
  readonly onToggle: (node: TreeNode, expanded: boolean) => void;
  readonly selectedSeq: number | null;
  readonly onSelect: (step: TransactionStep) => void;
}

/** Total time of a row: a group's sum, a step's elapsed. */
function rowTotal(node: TreeNode): number {
  return node.kind === 'group' ? node.sumMs : node.elapsedMs;
}

/** Executions of a row: a group's executions, a step's repeat. */
function rowExecutions(node: TreeNode): number {
  return node.kind === 'group' ? node.executions : Math.max(1, node.step.repeat);
}

export function TreeTab({ detail, roots, toggled, onToggle, selectedSeq, onSelect }: TreeTabProps) {
  const { t } = useTranslation(['common', 'tx']);
  const rows = visibleRows(roots, toggled);
  const header = (
    <div className="tx-tree-row tx-tree-row--head" aria-hidden="true">
      <span>{t('tx:tree.colName')}</span>
      <span>{t('tx:tree.colExecutions')}</span>
      <span>{t('tx:tree.colTotal')}</span>
      <span>{t('tx:tree.colSelf')}</span>
      <span>{t('tx:tree.colShare')}</span>
    </div>
  );
  const renderRow = (row: TreeRow) => {
    const node = row.node;
    const total = rowTotal(node);
    const toggleClass = row.hasChildren
      ? row.expanded
        ? ' tx-tree-row__name--open'
        : ' tx-tree-row__name--closed'
      : '';
    return (
      <div
        className={`tx-tree-row${node.kind === 'group' ? ' tx-tree-row--group' : ''}`}
        data-testid="transaction-tree-row"
        data-kind={node.kind}
        data-depth={row.depth}
      >
        <span
          role="gridcell"
          className={`tx-tree-row__name${toggleClass}`}
          style={{ paddingLeft: `${String(4 + row.depth * 14)}px` }}
        >
          <StepTypeBadge type={node.kind === 'group' ? node.type : node.step.type} />
          {node.kind === 'group' ? (
            <span
              className="tx-group-name"
              data-testid="transaction-group"
              data-count={node.count}
              data-sum={node.sumMs}
            >
              <span className="tx-group-name__count">
                {t('tx:tree.groupCount', { count: node.count })}
              </span>
              <StepName step={node.first} />
            </span>
          ) : (
            <span className="tx-tree-row__label">
              <StepName step={node.step} />
            </span>
          )}
        </span>
        <span role="gridcell" className="tx-num">
          <ValueText value={rowExecutions(node)} unit="count" />
        </span>
        {node.kind === 'group' && isPointEventType(node.type) ? (
          // A run of point events (STACK captures, ERROR records) has no time: the count only.
          <>
            <span role="gridcell" className="tx-num" data-testid="transaction-tree-no-time" />
            <span role="gridcell" className="tx-num" />
            <span role="gridcell" className="tx-num" />
          </>
        ) : (
          <>
            <span role="gridcell" className="tx-num">
              <ValueText value={total} unit="ms" />
            </span>
            <span role="gridcell" className="tx-num">
              <ValueText value={node.children.length > 0 ? nodeSelfTime(node) : total} unit="ms" />
            </span>
            <span role="gridcell" className="tx-num">
              <ValueText value={shareOf(total, detail.elapsed_ms)} unit="ratio" />
            </span>
          </>
        )}
      </div>
    );
  };
  return (
    <div className="tx-tree" data-testid="transaction-tree-tab">
      {detail.flags.summarized ? (
        <p className="tx-note" data-testid="transaction-tree-summarized">
          {t('tx:tree.summarized')}
        </p>
      ) : null}
      <VirtualRows
        items={rows}
        getKey={(row) => row.node.key}
        label={t('tx:tree.label')}
        header={header}
        renderRow={renderRow}
        getMeta={(row) => ({
          level: row.depth + 1,
          expanded: row.hasChildren ? row.expanded : undefined,
          selected: row.node.kind === 'step' && row.node.step.seq === selectedSeq,
        })}
        onActivate={(row) => {
          if (row.node.kind === 'group') {
            onToggle(row.node, !row.expanded);
          } else {
            onSelect(row.node.step);
          }
        }}
        onExpandedChange={(row, _index, expanded) => {
          onToggle(row.node, expanded);
        }}
        data-testid="transaction-tree"
      />
    </div>
  );
}
