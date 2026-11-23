// SPDX-License-Identifier: Apache-2.0
//
// The panel under the trace list for the focused row (the root until a row is focused): every
// field of the node label in full (05 §2.8-1), the caller step and callee durations with the
// network / wait part or the clock-offset note (D-05), and the actions the row offers to the mouse,
// reachable by keyboard here: open the transaction detail (with the ts hint), open the
// application's dashboard for the same time range (05 §2.8-4), query again for a node that is not
// collected yet (05 §2.8-3). Collected strings through SafeText; links are internal paths.
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { formatDateTimeWithOffset, formatDuration } from '../../../utils/format';
import { MISSING_DASH } from '../../../utils/missing';
import { rowInterval, type TraceRow, type TraceTree } from '../model/traceTree';

import { MissingLabel, useProfileText } from './labels';
import type { TraceLinks } from './TraceTimeline';

export interface TraceNodePanelProps {
  readonly row: TraceRow;
  readonly tree: TraceTree;
  readonly links: TraceLinks;
  readonly onNavigate: (href: string) => void;
  readonly onQueryAgain: () => void;
  readonly fetching: boolean;
}

function Field({
  label,
  children,
  testId,
}: {
  readonly label: string;
  readonly children: ReactNode;
  readonly testId?: string;
}) {
  return (
    <div className="trace-panel__field">
      <dt>{label}</dt>
      <dd data-testid={testId}>{children}</dd>
    </div>
  );
}

export function TraceNodePanel({
  row,
  tree,
  links,
  onNavigate,
  onQueryAgain,
  fetching,
}: TraceNodePanelProps) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const profileText = useProfileText();
  const interval = rowInterval(row);

  let fields: ReactNode;
  let actions: ReactNode = null;
  switch (row.kind) {
    case 'tx': {
      const node = row.item.node;
      const start = Date.parse(node.ts);
      fields = (
        <>
          <Field label={t('trace:node.app')} testId="trace-panel-app">
            <SafeText value={node.app} maxLines={2} />
          </Field>
          <Field label={t('trace:node.env')} testId="trace-panel-env">
            <SafeText value={node.env} />
          </Field>
          <Field label={t('trace:node.service')} testId="trace-panel-service">
            <SafeText value={node.service} maxLines={3} />
          </Field>
          <Field label={t('trace:node.instance')} testId="trace-panel-instance">
            <SafeText value={node.instance} maxLines={2} />
          </Field>
          <Field label={t('trace:node.version')} testId="trace-panel-version">
            <SafeText value={node.version} />
          </Field>
          <Field label={t('trace:node.status')} testId="trace-panel-status">
            {node.status}
          </Field>
          <Field label={t('trace:node.error')} testId="trace-panel-error">
            {node.error ? t('trace:node.yes') : t('trace:node.no')}
          </Field>
          <Field label={t('trace:node.elapsed')} testId="trace-panel-elapsed">
            {formatDuration(node.elapsed_ms, locale)}
          </Field>
          <Field label={t('trace:node.start')} testId="trace-panel-start">
            {Number.isFinite(start) ? formatDateTimeWithOffset(start, locale) : node.ts}
          </Field>
          <Field label={t('trace:node.offset')}>
            {interval === null
              ? MISSING_DASH
              : `+${formatDuration(interval.startMs - tree.originMs, locale)}`}
          </Field>
          <Field label={t('trace:node.profile')} testId="trace-panel-profile">
            {profileText(row.item.profileState)}
          </Field>
          <Field label={t('trace:node.txId')} testId="trace-panel-tx-id">
            <SafeText value={node.tx_id} mono />
          </Field>
        </>
      );
      actions = (
        <>
          <SafeLink
            href={links.detail(node.app_id, node.tx_id, node.ts)}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="trace-panel-open-detail"
          >
            {t('trace:node.openDetail')}
          </SafeLink>
          <SafeLink
            href={links.dashboard(node.app_id)}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="trace-panel-open-dashboard"
          >
            {t('trace:node.openDashboard')}
          </SafeLink>
        </>
      );
      break;
    }
    case 'call': {
      const { gap, item } = row;
      fields = (
        <>
          <Field label={t('trace:node.callerStep')} testId="trace-panel-caller-step">
            {formatDuration(gap.callerStepMs, locale)}
          </Field>
          <Field label={t('trace:node.callee')} testId="trace-panel-callee">
            {formatDuration(gap.calleeMs, locale)}{' '}
            <span className="trace-env">
              <SafeText value={item.node.app} />
            </span>
          </Field>
          <Field label={t('trace:node.wait')} testId="trace-panel-wait">
            {gap.waitMs === null
              ? t('trace:node.clockOffset')
              : `${formatDuration(gap.waitMs, locale)} (${
                  gap.waitSource === 'server'
                    ? t('trace:node.waitServer')
                    : t('trace:node.waitComputed')
                })`}
          </Field>
          <Field label={t('trace:node.caller')}>
            <SafeText value={item.caller?.app ?? null} />
            <span className="trace-sep" aria-hidden="true" />
            <SafeText value={item.caller?.service ?? null} />
          </Field>
        </>
      );
      if (item.caller !== null) {
        actions = (
          <SafeLink
            href={links.detail(item.caller.app_id, item.caller.tx_id, item.caller.ts, 'ext')}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="trace-panel-open-detail"
          >
            {t('trace:node.openDetail')}
          </SafeLink>
        );
      }
      break;
    }
    case 'external': {
      const { external, caller } = row.item;
      fields = (
        <>
          <Field label={t('trace:node.host')} testId="trace-panel-host">
            <SafeText value={external.host} mono maxLines={2} />
          </Field>
          <Field label={t('trace:node.path')} testId="trace-panel-path">
            <SafeText value={external.path_pattern} mono maxLines={3} />
          </Field>
          <Field label={t('trace:node.status')} testId="trace-panel-status">
            {external.status ?? t('trace:row.noResponse')}
          </Field>
          <Field label={t('trace:node.error')} testId="trace-panel-error">
            {external.error ? t('trace:node.yes') : t('trace:node.no')}
          </Field>
          <Field label={t('trace:node.elapsed')} testId="trace-panel-elapsed">
            {formatDuration(external.elapsed_ms, locale)}
          </Field>
          <Field label={t('trace:node.caller')}>
            <SafeText value={caller?.app ?? null} />
            <span className="trace-sep" aria-hidden="true" />
            <SafeText value={caller?.service ?? null} />
          </Field>
        </>
      );
      if (caller !== null) {
        actions = (
          <SafeLink
            href={links.detail(caller.app_id, caller.tx_id, caller.ts, 'ext')}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="trace-panel-open-detail"
          >
            {t('trace:node.openDetail')}
          </SafeLink>
        );
      }
      break;
    }
    case 'missing': {
      const { missing } = row.item;
      fields = (
        <>
          <Field label={t('trace:node.reason')} testId="trace-panel-reason">
            <MissingLabel row={row} />
          </Field>
          <Field label={t('trace:node.txId')} testId="trace-panel-tx-id">
            <SafeText value={missing.tx_id} mono />
          </Field>
        </>
      );
      if (missing.reason === 'not_yet_collected') {
        actions = (
          <button
            type="button"
            className="control-button"
            disabled={fetching}
            onClick={onQueryAgain}
            data-testid="trace-panel-query-again"
          >
            {t('trace:row.queryAgain')}
          </button>
        );
      }
      break;
    }
  }

  return (
    <section
      className="trace-panel"
      aria-label={t('trace:node.label')}
      data-testid="trace-node-panel"
      data-row-key={row.key}
      data-kind={row.kind}
    >
      <dl className="trace-panel__fields">{fields}</dl>
      {interval?.estimated === true ? (
        <p className="trace-panel__note">{t('trace:node.estimated')}</p>
      ) : null}
      {actions === null ? null : <div className="trace-panel__actions">{actions}</div>}
    </section>
  );
}
