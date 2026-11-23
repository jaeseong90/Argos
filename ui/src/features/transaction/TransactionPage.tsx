// SPDX-License-Identifier: Apache-2.0
//
// Transaction detail /apps/:appId/transactions/:txId (docs/15 §8, §9.4, 05 §2.2): the profile
// status, the summary of every docs/05 §2.2 item, the position among the same service's requests
// with the comparison to a normal request, and the tabs timeline, tree, sql, ext, error, stack and
// headers with a side panel for the selected step; the actions go back to the list, to the previous
// and next transaction of that list, to the caller, the trace and the logs, export the server's JSON
// and copy the canonical link. States: loading skeleton, not found with the ADR-0007 C-50 guidance
// (open it from the list or with a time hint, plus the XLog of that time), any other error through
// ErrorState (403, 504, ...). A transaction of another application moves to that application's route
// (C-166). Data only through the generated client (data/); collected strings through SafeText,
// CodeBlock and SafeLink (SEC-60, SEC-61).
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router';

import { useAppPackages } from '../../api/apps';
import { EmptyState, type StateAction } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { SafeText } from '../../components/SafeText';
import { useAppId } from '../../routes/navigation';
import {
  buildUrl,
  defaultState,
  fitsWriteLimit,
  TxDetailQ,
  type TxDetailState,
  type UrlStateOf,
} from '../../state/urlState';
import { useUrlState, type SetOptions } from '../../state/useUrlState';
import { txIdToEpochMs } from '../../utils/ids';

import { CompareView } from './CompareView';
import { parseBack, type BackLink } from './data/listContext';
import type { TransactionDetail } from './data/transactionApi';
import { hasSteps } from './data/transactionModel';
import {
  useTransaction,
  useTransactionBaseline,
  type TransactionState,
} from './data/useTransaction';
import { DetailActions } from './DetailActions';
import { DetailTabs, type DetailTab } from './DetailTabs';
import { errorSteps, extRows, sqlRows } from './model/calls';
import { detailLinks, linkContext, transactionPath, xlogHref, xlogWindowHref } from './model/links';
import { nodeSelfTime } from './model/selfTime';
import { stackCaptures } from './model/stacks';
import { allStepNodes, profileTree, setExpanded, type TreeNode } from './model/tree';
import { PercentilePosition } from './PercentilePosition';
import { ProfileStatusBanner } from './ProfileStatusBanner';
import { StepPanel } from './StepPanel';
import { SummaryBar } from './SummaryBar';
import { ErrorTab } from './tabs/ErrorTab';
import { ExtTab } from './tabs/ExtTab';
import { HeadersTab } from './tabs/HeadersTab';
import { SqlTab } from './tabs/SqlTab';
import { StackTab } from './tabs/StackTab';
import { TreeTab } from './tabs/TreeTab';
import { TimelineView } from './TimelineView';
import type { Neighbour } from './useAdjacent';
import { useAdjacent } from './useAdjacent';

import './transaction.css';

/** The not-found text of a state (C-50: list or ts hint, unless the link already had a hint). */
function notFoundKey(
  state: TransactionState,
): 'tx:notFound.invalidId' | 'tx:notFound.clockSkew' | 'tx:notFound.hinted' | 'tx:notFound.other' {
  if (state.status === 'invalid') {
    return 'tx:notFound.invalidId';
  }
  if (state.status === 'not_found' && state.reason === 'clock_skew_possible') {
    return state.hinted ? 'tx:notFound.hinted' : 'tx:notFound.clockSkew';
  }
  return 'tx:notFound.other';
}

/** The URL of a neighbour in the list (C-206): same `back`, tab and range; within 2 KB. */
export function neighbourUrl(
  appId: number,
  neighbour: Neighbour,
  url: UrlStateOf<typeof TxDetailQ>,
): string {
  const path = transactionPath(appId, neighbour.txId);
  const state: TxDetailState = { ...url, ts: neighbour.ts ?? undefined, compare: undefined };
  for (const candidate of [
    state,
    { ...state, tab: 'timeline' as const },
    { ...defaultState(TxDetailQ), ts: state.ts },
  ]) {
    const href = buildUrl(path, TxDetailQ, candidate);
    if (fitsWriteLimit(href)) {
      return href;
    }
  }
  return path;
}

interface DetailViewProps {
  readonly detail: TransactionDetail;
  readonly url: TxDetailState;
  readonly set: (partial: Partial<TxDetailState>, options?: SetOptions) => boolean;
  readonly back: BackLink | null;
  readonly fetching: boolean;
  readonly refetch: () => void;
}

/** A loaded transaction (keyed by tx id: selection and expansion start fresh per transaction). */
function DetailView({ detail, url, set, back, fetching, refetch }: DetailViewProps) {
  const { t } = useTranslation(['common', 'tx']);
  const navigate = useNavigate();
  const location = useLocation();
  const [roots] = useState<TreeNode[]>(() => profileTree(detail.profile.steps));
  const [toggled, setToggled] = useState<ReadonlySet<string>>(() => new Set());
  const [selectedSeq, setSelectedSeq] = useState<number | null>(null);
  const baseline = useTransactionBaseline(detail.tx_id, detail.ts);
  const adjacent = useAdjacent(back, detail.tx_id);
  const links = detailLinks(detail, linkContext(detail, url, back, location.search));
  // Application frames in bold (docs/15 §8.5): the packages an administrator set for the app.
  const appPackages = useAppPackages(detail.app.app_id);
  const onNavigate = (path: string): void => {
    void navigate(path);
  };
  const onToggle = (node: TreeNode, expanded: boolean): void => {
    setToggled((current) => setExpanded(current, node, expanded));
  };
  const tab = url.tab;
  const steps = detail.profile.steps;
  const selected = selectedSeq === null ? undefined : steps.find((s) => s.seq === selectedSeq);
  const selectedNode =
    selected === undefined
      ? undefined
      : allStepNodes(roots).find((n) => n.step.seq === selected.seq);
  const compareTs =
    baseline.state.status === 'success' &&
    baseline.state.baseline.sample_normal_tx_id === url.compare
      ? baseline.state.baseline.sample_normal_ts
      : null;
  const sqlCount = sqlRows(steps).length;
  const extCount = extRows(steps).length;
  const stackCount = stackCaptures(steps).length;
  const errorCount = errorSteps(steps).length;
  const marked = new Set<DetailTab>(detail.error ? ['error'] : []);

  let content;
  switch (tab) {
    case 'timeline':
      content = hasSteps(detail.profile) ? (
        <TimelineView
          detail={detail}
          roots={roots}
          toggled={toggled}
          onToggle={onToggle}
          selectedSeq={selectedSeq}
          onSelect={(step) => {
            setSelectedSeq(step === null ? null : step.seq);
          }}
          label={t('tx:timeline.label')}
          labelWidth={selected === undefined ? 360 : 280}
        />
      ) : (
        <EmptyState
          compact
          data-testid="transaction-timeline-empty"
          title={t('tx:timeline.emptyTitle')}
          description={t('tx:tabs.noProfile')}
        />
      );
      break;
    case 'tree':
      content = hasSteps(detail.profile) ? (
        <TreeTab
          detail={detail}
          roots={roots}
          toggled={toggled}
          onToggle={onToggle}
          selectedSeq={selectedSeq}
          onSelect={(step) => {
            setSelectedSeq(step.seq);
          }}
        />
      ) : (
        <EmptyState
          compact
          data-testid="transaction-tree-empty"
          title={t('tx:tree.emptyTitle')}
          description={t('tx:tabs.noProfile')}
        />
      );
      break;
    case 'sql':
      content = (
        <SqlTab detail={detail} links={links} onNavigate={onNavigate} onSelect={setSelectedSeq} />
      );
      break;
    case 'ext':
      content = (
        <ExtTab detail={detail} links={links} onNavigate={onNavigate} onSelect={setSelectedSeq} />
      );
      break;
    case 'error':
      content = (
        <ErrorTab detail={detail} links={links} onNavigate={onNavigate} appPackages={appPackages} />
      );
      break;
    case 'stack':
      content = <StackTab detail={detail} appPackages={appPackages} />;
      break;
    case 'headers':
      content = <HeadersTab detail={detail} />;
      break;
  }
  const panelTabs: readonly DetailTab[] = ['timeline', 'tree', 'sql', 'ext'];
  const showPanel = selected !== undefined && panelTabs.includes(tab);

  return (
    <>
      <header className="tx-page__header">
        <h1 className="tx-page__title">
          <span>{t('tx:page.title')}</span>
          <SafeText value={detail.service} data-testid="transaction-service" />
        </h1>
        <p className="tx-page__id">
          <span>{t('tx:page.id')}</span>{' '}
          <SafeText value={detail.tx_id} mono data-testid="transaction-id" />
        </p>
      </header>
      <DetailActions
        detail={detail}
        tab={tab}
        back={back}
        adjacent={adjacent}
        links={links}
        onNavigate={onNavigate}
        onAdjacent={(neighbour) => {
          const appId = back?.xlog?.appId ?? detail.app.app_id;
          void navigate(neighbourUrl(appId, neighbour, url));
        }}
      />
      <ProfileStatusBanner detail={detail} onRefresh={refetch} refreshing={fetching} />
      <SummaryBar
        detail={detail}
        onShowHeaders={() => {
          set({ tab: 'headers' }, { replace: true });
        }}
      />
      <PercentilePosition
        detail={detail}
        state={baseline.state}
        onRetry={baseline.refetch}
        comparing={url.compare !== undefined}
        onCompare={(id) => {
          set({ compare: id });
        }}
      />
      {url.compare === undefined ? null : (
        <CompareView
          key={url.compare}
          detail={detail}
          compareId={url.compare}
          compareTs={compareTs}
          onNavigate={onNavigate}
          onClose={() => {
            set({ compare: undefined });
          }}
        />
      )}
      <DetailTabs
        active={tab}
        onChange={(next) => {
          set({ tab: next }, { replace: true });
        }}
        counts={{
          sql: sqlCount,
          ext: extCount,
          stack: stackCount,
          error: errorCount,
          headers: Object.keys(detail.headers).length,
        }}
        marked={marked}
      >
        <div className={`tx-body${showPanel ? ' tx-body--panel' : ''}`}>
          <div className="tx-body__main">{content}</div>
          {showPanel ? (
            <StepPanel
              detail={detail}
              step={selected}
              selfMs={
                selectedNode !== undefined && selectedNode.children.length > 0
                  ? nodeSelfTime(selectedNode)
                  : null
              }
              links={links}
              onNavigate={onNavigate}
              onClose={() => {
                setSelectedSeq(null);
              }}
            />
          ) : null}
        </div>
      </DetailTabs>
    </>
  );
}

export function TransactionPage() {
  const { t } = useTranslation(['common', 'tx']);
  const navigate = useNavigate();
  const { txId = '' } = useParams();
  const appId = useAppId();
  const { state: url, set } = useUrlState(TxDetailQ);
  const { state, refetch, fetching } = useTransaction(txId, url.ts);

  // The id belongs to another application: show it under its own app (canonical route, C-166).
  if (state.status === 'success' && appId !== null && state.detail.app.app_id !== appId) {
    const path = transactionPath(state.detail.app.app_id, txId);
    return <Navigate to={buildUrl(path, TxDetailQ, url)} replace />;
  }

  const back = parseBack(url.back);
  let body;
  switch (state.status) {
    case 'pending':
      body = <LoadingState variant="block" data-testid="loading-state" />;
      break;
    case 'invalid':
    case 'not_found': {
      const actions: StateAction[] = [];
      if (back !== null) {
        actions.push({
          label: t('tx:notFound.backToList'),
          onClick: () => {
            void navigate(back.href);
          },
          'data-testid': 'transaction-back-to-list',
        });
      }
      if (appId !== null) {
        // The XLog around the hint, or around the time in the id (C-50: open it from the list).
        const center = url.ts ?? (state.status === 'not_found' ? txIdToEpochMs(txId) : null);
        if (center !== null) {
          actions.push({
            label: t('tx:notFound.openXlogWindow'),
            onClick: () => {
              void navigate(xlogWindowHref(appId, center, url));
            },
            'data-testid': 'transaction-not-found-xlog',
          });
        }
        if (back === null) {
          actions.push({
            label: t('tx:notFound.openXlog'),
            onClick: () => {
              void navigate(xlogHref(appId, url));
            },
            'data-testid': 'transaction-open-xlog',
          });
        }
      }
      body = (
        <EmptyState
          data-testid="transaction-not-found"
          title={t('tx:notFound.title')}
          description={t(notFoundKey(state))}
          actions={actions}
        />
      );
      break;
    }
    case 'error':
      body = (
        <ErrorState error={state.info} onRetry={refetch} data-testid="transaction-error-state" />
      );
      break;
    case 'success':
      body = (
        <DetailView
          key={state.detail.tx_id}
          detail={state.detail}
          url={url}
          set={set}
          back={back}
          fetching={fetching}
          refetch={refetch}
        />
      );
      break;
  }

  return (
    <section
      className="tx-page"
      data-testid="transaction-page"
      data-state={state.status}
      data-tx-id={txId}
    >
      {state.status === 'success' ? null : (
        <header className="tx-page__header">
          <h1 className="tx-page__title">
            <span>{t('tx:page.title')}</span>{' '}
            <SafeText value={txId} mono data-testid="transaction-id" />
          </h1>
        </header>
      )}
      {body}
    </section>
  );
}
