// SPDX-License-Identifier: Apache-2.0
//
// All-application firing alerts panel (05 §2.10 "전 앱 발생 중 알림 요약 패널", docs/15 §9.1).
// It lists the active alerts of the alert engine (WO-25, ADR-0010 C-212 (2)): `GET /overview/alerts`
// without a state filter, FIRING and ACKED (critical first), the same list `alerts_firing_total`
// counts, with the counts by severity. `alerts_firing_total === null` (a server without the alert
// engine) says "not available", never "no alerts", and requests nothing (T-UI-007 null ≠ empty). The panel links to the alert history filtered to firing alerts (`/alerts?state=
// firing`, global keys carried; an ACKED row opens `state=acked`); each row is the shared
// AlertSummaryRow (ADR-0010 C-227 (4): the rule name keeps its width), names through SafeText.
//
// The overview never scrolls at 1366x768 (05 §2.10-6, ADR-0010 C-234 (5)): the panel draws at most
// FIRING_PANEL_ROWS_MAX rows and only as many as fit above the bottom of the page's scroll box
// (#argos-main, else the window); the rest is the "+N more" link to the alert history.
import { useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { errorInfo } from '../../api/errors';
import { useFiringAlertsQuery } from '../../api/overview';
import { AlertSummaryRow } from '../../components/AlertSummaryRow';
import { ErrorState } from '../../components/ErrorState';

import { FIRING_PANEL_ROWS_MAX, firingAlerts } from './firingAlerts';

export interface FiringPanelProps {
  /** `alerts_firing_total` of the overview: null = a server without the alert engine. */
  readonly total: number | null;
  readonly env: string | undefined;
  /**
   * The alert history link, optionally to one alert event and filtered to its state (an ACKED
   * row opens the acknowledged alerts, ADR-0010 C-226 (8)).
   */
  readonly historyHref: (alertId: string | null, state?: string | null) => string;
}

/** The lowest pixel the side column may reach without a page scroll (viewport coordinates). */
function bottomLimit(panel: HTMLElement): number {
  const box = panel.closest('#argos-main');
  if (box === null) {
    return window.innerHeight;
  }
  const rect = box.getBoundingClientRect();
  const padding = Number.parseFloat(getComputedStyle(box).paddingBottom) || 0;
  return Math.min(rect.bottom, window.innerHeight) - padding;
}

/**
 * How many of `candidates` rows fit: starts at all of them and drops one row per layout pass while
 * the side column (or the panel) ends below bottomLimit. A layout without size (no layout engine)
 * keeps every row. Starts over when the rows or the box size change.
 */
function useFittingRows(panelRef: RefObject<HTMLElement | null>, candidates: number, key: string) {
  const [fit, setFit] = useState(candidates);
  const [epoch, setEpoch] = useState(0);
  // Start over (all candidate rows) when the rows or the box change: state adjusted while
  // rendering, the React pattern for state that follows props.
  const resetKey = `${String(candidates)}|${key}|${String(epoch)}`;
  const [measuredFor, setMeasuredFor] = useState(resetKey);
  if (measuredFor !== resetKey) {
    setMeasuredFor(resetKey);
    setFit(candidates);
  }
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (panel === null || fit <= 0) {
      return;
    }
    const column = panel.closest('.overview-page__side') ?? panel;
    const bottom = column.getBoundingClientRect().bottom;
    const limit = bottomLimit(panel);
    if (bottom > 0 && limit > 0 && bottom > limit + 0.5) {
      setFit((n) => Math.max(0, n - 1));
    }
  }, [fit, panelRef]);
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (panel === null || typeof ResizeObserver === 'undefined') {
      return undefined;
    }
    const box = panel.closest('#argos-main') ?? document.documentElement;
    let first = true;
    const observer = new ResizeObserver(() => {
      if (first) {
        first = false;
        return;
      }
      setEpoch((e) => e + 1);
    });
    observer.observe(box);
    return () => {
      observer.disconnect();
    };
  }, [panelRef]);
  return Math.min(fit, candidates);
}

export function FiringPanel({ total, env, historyHref }: FiringPanelProps) {
  const { t } = useTranslation(['common', 'overview']);
  const panelRef = useRef<HTMLElement>(null);
  const available = total !== null;
  const query = useFiringAlertsQuery(env, available);
  const parsed = available && query.data !== undefined ? firingAlerts(query.data) : null;
  const state = !available
    ? 'unavailable'
    : parsed === null
      ? query.isError
        ? 'error'
        : 'loading'
      : parsed.rows.length + parsed.invalid === 0
        ? 'empty'
        : 'list';
  const candidates = parsed?.rows.slice(0, FIRING_PANEL_ROWS_MAX) ?? [];
  const fitting = useFittingRows(
    panelRef,
    candidates.length,
    candidates.map((row) => row.id ?? '').join(','),
  );
  const shown = candidates.slice(0, fitting);
  const more = parsed === null ? 0 : parsed.rows.length - shown.length + parsed.invalid;

  return (
    <section
      ref={panelRef}
      className="overview-panel overview-firing-panel"
      aria-labelledby="overview-firing-title"
      data-testid="overview-firing-panel"
      data-state={state}
      data-total={total === null ? 'null' : String(total)}
      data-rows-shown={state === 'list' ? String(shown.length) : undefined}
    >
      <h2 id="overview-firing-title" className="overview-panel__title">
        {t('overview:panel.firing.title')}
      </h2>
      {state === 'unavailable' ? (
        <p className="overview-panel__note" data-testid="overview-firing-unavailable">
          {t('overview:panel.firing.unavailable')}
        </p>
      ) : state === 'loading' ? (
        <p className="overview-panel__note">{t('overview:panel.firing.loading')}</p>
      ) : state === 'error' ? (
        <ErrorState
          compact
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="overview-firing-error"
        />
      ) : state === 'empty' ? (
        <p className="overview-panel__note" data-testid="overview-firing-empty">
          {t('overview:panel.firing.none')}
        </p>
      ) : (
        <>
          <p className="overview-panel__summary" data-testid="overview-firing-summary">
            {t('overview:panel.firing.summary', {
              critical: parsed?.critical ?? 0,
              warn: parsed?.warn ?? 0,
            })}
          </p>
          <ul className="overview-firing-panel__list" data-testid="overview-firing-list">
            {shown.map((row, index) => (
              <li
                key={row.id ?? `row-${String(index)}`}
                className="overview-firing-panel__row"
                data-testid="overview-firing-row"
                data-alert-id={row.id ?? undefined}
                data-severity={row.severity ?? undefined}
              >
                <AlertSummaryRow href={historyHref(row.id, row.state)} alert={row} />
              </li>
            ))}
            {more > 0 ? (
              <li className="overview-panel__note">
                <Link
                  to={historyHref(null)}
                  className="overview-panel__link"
                  data-testid="overview-firing-more"
                >
                  {t('overview:panel.firing.more', { count: more })}
                </Link>
              </li>
            ) : null}
          </ul>
        </>
      )}
      <Link
        to={historyHref(null)}
        className="overview-panel__link"
        data-testid="overview-firing-history"
      >
        {t('overview:panel.firing.all')}
      </Link>
    </section>
  );
}
